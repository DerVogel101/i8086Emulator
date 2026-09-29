package dervogel101.de.launcher;

import dervogel101.de.CPU8086;
import dervogel101.de.CpuHostBridge;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.CPURegister;
import dervogel101.de.state.IoStorageDevice;
import dervogel101.de.state.SharedMemoryRegion;

import javax.swing.SwingUtilities;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

/** Owns the CPU thread. Swing only receives copied state. */
final class LauncherController implements AutoCloseable {
    record Snapshot(String romName, String status, String registers, long instructionsPerSecond,
                    long totalInstructions,
                    int memoryBase, byte[] memory, int ioBase, byte[] io,
                    List<Disassembler8086.Instruction> code, boolean running, boolean faulted,
            DeviceSnapshot devices, String terminalLog, ImageState images, boolean testHigh) {}
    record DeviceSnapshot(int channel, int signIndex, int timerIndex, byte[] redstone,
                          byte[] sign, byte[] timer, byte[] irq, byte[] storage, boolean timerAutomatic) {}
    record ImageState(Path nvramPath, boolean nvramDirty, Path storagePath,
                      boolean storageDirty, boolean storageConnected) {}

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "8086-launcher-cpu");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<Snapshot> onSnapshot;
    private final Consumer<String> onError;
    private CPU8086 cpu;
    private SharedMemoryRegion sharedMemory;
    private final LongSupplier worldTime;
    private byte[] savedNvram = new byte[CPUMemory.NVRAM_SIZE];
    private Path nvramPath;
    private boolean nvramNew;
    private IoStorageDevice storage;
    private byte[] savedStorage;
    private Path storagePath;
    private boolean storageNew;
    private boolean testHigh;
    private byte[] rom;
    private String romName = "No ROM loaded";
    private String status = "Paused";
    private boolean running;
    private volatile boolean stopRequested;
    private boolean faulted;
    private int faultCs, faultIp;
    private boolean bypassInt3Once;
    private int targetCs = -1, targetIp;
    private boolean stepOver;
    private int instructionsPerTick = 10_000;
    private int ticksPerSecond = 20;
    private int memoryBase = 0xFFF00;
    private int ioBase;
    private int codeCs = 0xFFFF, codeIp;
    private boolean followPc = true;
    private int selectedChannel, selectedSign, selectedTimer;
    private long gameTime, gameNanos, lastTickNanos;
    private long lastSubmittedGameTime = Long.MIN_VALUE;
    private long rateInstructions, rateStart = System.nanoTime(), measuredRate;
    private long totalInstructions;

    LauncherController(Consumer<Snapshot> onSnapshot, Consumer<String> onError) {
        this(onSnapshot, onError, new SharedMemoryRegion(), null);
    }

    LauncherController(Consumer<Snapshot> onSnapshot, Consumer<String> onError,
                       SharedMemoryRegion sharedMemory, LongSupplier worldTime) {
        this.onSnapshot = onSnapshot;
        this.onError = onError;
        this.sharedMemory = sharedMemory;
        this.worldTime = worldTime;
        cpu = new CPU8086(sharedMemory);
        worker.scheduleWithFixedDelay(this::publish, 0, 200, TimeUnit.MILLISECONDS);
    }

    void loadRom(Path path) {
        worker.execute(() -> {
            pauseInternal();
            try {
                byte[] image = Files.readAllBytes(path);
                if (image.length != CPUMemory.ROM_SIZE) throw new IllegalArgumentException("ROM must be exactly 256 KiB");
                CPU8086 next = newCpu();
                next.hostBridge().loadRom(image);
                rom = image;
                cpu = next;
                romName = path.getFileName().toString();
                resetView();
                status = "Paused at reset vector";
            } catch (Throwable error) { report(error); }
            publish();
        });
    }

    void configure(int instructions, int ticks) {
        if (instructions < 1 || ticks < 1 || ticks > 1000) throw new IllegalArgumentException("Positive budget and 1–1000 ticks/s required");
        worker.execute(() -> { instructionsPerTick = instructions; ticksPerSecond = ticks; publish(); });
    }

    void run() { worker.execute(() -> start(-1, 0, false)); }
    void runTo(int cs, int ip) { worker.execute(() -> start(cs, ip, false)); }
    void stepOver() {
        worker.execute(() -> {
            if (faulted || rom == null) return;
            CPURegister r = cpu.registers();
            Disassembler8086.Instruction ins = Disassembler8086.decode(cpu.memory(), r.getCs(), r.getIp());
            if (ins.call()) start(r.getCs(), ins.nextIp(), true);
            else stepInternal();
        });
    }

    private void start(int cs, int ip, boolean over) {
        if (rom == null || faulted || running) return;
        targetCs = cs;
        targetIp = ip;
        stepOver = over;
        bypassInt3Once = status.equals("INT 3 breakpoint");
        stopRequested = false;
        running = true;
        status = over ? "Stepping over" : cs >= 0 ? "Running to cursor" : "Running";
        lastTickNanos = System.nanoTime();
        scheduleTick(0);
        publish();
    }

    void pause() {
        stopRequested = true;
        worker.execute(() -> { pauseInternal(); stopRequested = false; publish(); });
    }
    private void pauseInternal() {
        running = false;
        targetCs = -1;
        stepOver = false;
        status = faulted ? "Error" : cpu.isHalted() ? "Paused (HLT)" : "Paused";
    }

    void step() { worker.execute(this::stepInternal); }
    private void stepInternal() {
        if (rom == null || faulted) return;
        pauseInternal();
        updateWorldTime(System.nanoTime());
        executeOne();
        if (!faulted && cpu.isHalted()) status = "Paused (HLT)";
        publish();
    }

    void skipInstruction() {
        worker.execute(() -> {
            if (rom == null) return;
            pauseInternal();
            CPURegister r = cpu.registers();
            int cs = faulted ? faultCs : r.getCs();
            int ip = faulted ? faultIp : r.getIp();
            var ins = Disassembler8086.decode(cpu.memory(), cs, ip);
            cpu.jumpTo(cs, ins.nextIp());
            faulted = false;
            status = "Skipped " + ins.assembly();
            publish();
        });
    }

    void setPc(int cs, int ip) {
        worker.execute(() -> {
            if (rom == null) return;
            pauseInternal();
            cpu.jumpTo(cs, ip);
            faulted = false;
            followPc = true;
            status = "PC set";
            publish();
        });
    }

    void reset() {
        worker.execute(() -> {
            if (rom == null) return;
            pauseInternal();
            cpu.hostBridge().submitResetPin(true);
            cpu.hostBridge().submitResetPin(false);
            try { cpu.step(); faulted = false; status = "CPU reset"; }
            catch (Throwable error) { report(error); }
            publish();
        });
    }

    void powerCycle() {
        worker.execute(() -> {
            if (rom == null) return;
            pauseInternal();
            CPU8086 next = newCpu();
            next.hostBridge().loadRom(rom);
            cpu = next;
            resetView();
            status = "Power cycled";
            publish();
        });
    }

    private CPU8086 newCpu() {
        CPU8086 next = new CPU8086(sharedMemory);
        next.hostBridge().loadNvram(savedNvram);
        if (storage != null) {
            next.hostBridge().submitStorageDevice(storage);
            next.beforeInstruction();
        }
        return next;
    }

    void pauseAndWait() {
        stopRequested = true;
        call(() -> {
            pauseInternal();
            stopRequested = false;
            cpu.beforeInstruction();
            publish();
            return null;
        });
    }

    ImageState inspectImages() {
        return call(this::imageState);
    }

    void openNvram(Path path) {
        byte[] image = readImage(path, CPUMemory.NVRAM_SIZE);
        call(() -> {
            pauseInternal();
            cpu.hostBridge().loadNvram(image);
            savedNvram = image;
            nvramPath = path;
            nvramNew = false;
            publish();
            return null;
        });
    }

    void newNvram() {
        call(() -> {
            pauseInternal();
            savedNvram = new byte[CPUMemory.NVRAM_SIZE];
            cpu.hostBridge().loadNvram(savedNvram);
            nvramPath = null;
            nvramNew = true;
            publish();
            return null;
        });
    }

    void saveNvram(Path path) {
        call(() -> {
            CPUMemory.NvramSnapshot snapshot = cpu.memory().snapshotNvram();
            byte[] image = snapshot.bytes();
            writeImage(path, image);
            savedNvram = image;
            nvramPath = path;
            nvramNew = false;
            cpu.hostBridge().acknowledgeNvramSaved(snapshot.version());
            publish();
            return null;
        });
    }

    void discardNvram() {
        call(() -> {
            pauseInternal();
            cpu.hostBridge().loadNvram(savedNvram);
            nvramNew = false;
            publish();
            return null;
        });
    }

    void openStorage(Path path) {
        byte[] image = readImage(path, IoStorageDevice.SIZE);
        call(() -> {
            pauseInternal();
            cpu.beforeInstruction();
            storage = new IoStorageDevice();
            storage.load(image);
            savedStorage = image;
            storagePath = path;
            storageNew = false;
            connectStorage();
            publish();
            return null;
        });
    }

    void newStorage() {
        call(() -> {
            pauseInternal();
            cpu.beforeInstruction();
            storage = new IoStorageDevice();
            savedStorage = new byte[IoStorageDevice.SIZE];
            storagePath = null;
            storageNew = true;
            connectStorage();
            publish();
            return null;
        });
    }

    void saveStorage(Path path) {
        call(() -> {
            if (storage == null) throw new IllegalStateException("Storage is disconnected");
            IoStorageDevice.Snapshot snapshot = storage.snapshot();
            byte[] image = snapshot.bytes();
            writeImage(path, image);
            savedStorage = image;
            storagePath = path;
            storageNew = false;
            storage.acknowledgeSaved(snapshot.version());
            publish();
            return null;
        });
    }

    void discardStorage() {
        call(() -> {
            pauseInternal();
            cpu.beforeInstruction();
            if (storage != null) {
                storage = new IoStorageDevice();
                storage.load(savedStorage);
                connectStorage();
            }
            storageNew = false;
            publish();
            return null;
        });
    }

    void disconnectStorage() {
        call(() -> {
            pauseInternal();
            cpu.beforeInstruction();
            storage = null;
            savedStorage = null;
            storagePath = null;
            storageNew = false;
            connectStorage();
            publish();
            return null;
        });
    }

    void powerCycleWithSharedMemory(SharedMemoryRegion nextRegion) {
        call(() -> {
            pauseInternal();
            sharedMemory = nextRegion;
            cpu = newCpu();
            if (rom != null) cpu.hostBridge().loadRom(rom);
            resetView();
            status = "Power cycled";
            publish();
            return null;
        });
    }

    private void connectStorage() {
        cpu.hostBridge().submitStorageDevice(storage);
        cpu.beforeInstruction();
    }

    private ImageState imageState() {
        return new ImageState(nvramPath,
                nvramNew || cpu.hostBridge().snapshotNvramIfDirty() != null,
                storagePath,
                storage != null && (storageNew || storage.snapshotIfDirty() != null), storage != null);
    }

    private static byte[] readImage(Path path, int expectedSize) {
        try {
            if (Files.size(path) != expectedSize) throw new IllegalArgumentException("Image must be exactly " + expectedSize + " bytes");
            byte[] image = Files.readAllBytes(path);
            if (image.length != expectedSize) throw new IllegalArgumentException("Image must be exactly " + expectedSize + " bytes");
            return image;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void writeImage(Path path, byte[] image) {
        Path target = path.toAbsolutePath();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".i8086-", ".tmp");
            Files.write(temporary, image);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { }
            }
        }
    }

    private <T> T call(Callable<T> action) {
        try {
            Future<T> result = worker.submit(action);
            return result.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for CPU worker", error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(error.getCause());
        }
    }

    private void resetView() {
        faulted = false;
        followPc = true;
        codeCs = 0xFFFF; codeIp = 0;
        memoryBase = 0xFFF00;
        ioBase = 0;
        selectedChannel = selectedSign = selectedTimer = 0;
        gameTime = gameNanos = rateInstructions = measuredRate = totalInstructions = 0;
        lastSubmittedGameTime = Long.MIN_VALUE;
        testHigh = false;
        rateStart = System.nanoTime();
    }

    void nmi() { worker.execute(() -> {
        cpu.hostBridge().submitNmiPin(true);
        cpu.hostBridge().submitNmiPin(false);
        if (!running) cpu.beforeInstruction();
        publish();
    }); }

    void testPin(boolean high) { worker.execute(() -> {
        testHigh = high;
        cpu.hostBridge().submitTestPin(high);
        if (!running) cpu.beforeInstruction();
        publish();
    }); }

    void redstone(int channel, int strength) { worker.execute(() -> command(() -> {
        cpu.hostBridge().submitRedstoneInput(channel, strength);
        if (!running) cpu.beforeInstruction();
    })); }

    void signClick(int sign, boolean front) { worker.execute(() -> command(() -> {
        cpu.hostBridge().submitSignClick(sign, front ? CpuHostBridge.SignSide.FRONT : CpuHostBridge.SignSide.BACK);
        if (!running) cpu.beforeInstruction();
    })); }

    void selectChannel(int channel) { worker.execute(() -> command(() -> {
        if (channel < 0 || channel >= 128) throw new IllegalArgumentException("Redstone channel: " + channel);
        selectedChannel = channel;
    })); }

    void selectSign(int sign) { worker.execute(() -> command(() -> {
        if (sign < 0 || sign >= 128) throw new IllegalArgumentException("Sign index: " + sign);
        selectedSign = sign;
    })); }

    void selectTimer(int timer) { worker.execute(() -> command(() -> {
        if (timer < 0 || timer >= 6) throw new IllegalArgumentException("Timer index: " + timer);
        selectedTimer = timer;
    })); }

    void sendIrq(int deviceId) { worker.execute(() -> command(() -> {
        cpu.hostBridge().submitIrq(deviceId);
        if (!running) cpu.beforeInstruction();
    })); }

    void timerAutomatic(int timer, boolean automatic) { worker.execute(() -> command(() -> {
        cpu.hostBridge().submitTimerAutomatic(timer, automatic);
        if (!running) cpu.beforeInstruction();
    })); }

    void writeTimer(int timer, int reload, int control) { worker.execute(() -> command(() -> {
        if (timer < 0 || timer >= 6 || reload < 0 || reload > 0xFFFF || (control & ~0x37) != 0)
            throw new IllegalArgumentException("Timer setting out of range");
        int base = 0x8100 + timer * 8;
        cpu.io().write8(base + 2, reload & 0xFF);
        cpu.io().write8(base + 3, reload >>> 8);
        cpu.io().write8(base + 4, control);
        if (!running) cpu.beforeInstruction();
    })); }

    void resetTimer(int timer) { worker.execute(() -> command(() -> {
        if (timer < 0 || timer >= 6) throw new IllegalArgumentException("Timer index: " + timer);
        cpu.io().write8(0x8100 + timer * 8 + 4, 8);
        if (!running) cpu.beforeInstruction();
    })); }

    void writeSignLine(int sign, int line, String text) {
        if (sign < 0 || sign >= 128 || line < 0 || line >= 8 || text.length() > 15 ||
                !text.chars().allMatch(c -> c >= 0x20 && c <= 0x7E))
            throw new IllegalArgumentException("Sign text must be at most 15 printable ASCII characters.");
        worker.execute(() -> command(() -> {
            int base = 0x0100 + sign * 0x100 + line * 16 + 1;
            for (int i = 0; i < 15; i++) cpu.io().write8(base + i, i < text.length() ? text.charAt(i) : 0);
        }));
    }

    void memoryBase(int address) { worker.execute(() -> { memoryBase = address & 0xFFFFF; publish(); }); }
    void ioBase(int port) { worker.execute(() -> { ioBase = port & 0xFFFF; publish(); }); }
    void codeAddress(int cs, int ip) { worker.execute(() -> {
        codeCs = cs & 0xFFFF; codeIp = ip & 0xFFFF; followPc = false; publish();
    }); }
    void followPc() { worker.execute(() -> { followPc = true; publish(); }); }

    void writeMemory(int address, int value) { worker.execute(() -> command(() -> {
        if (!writableMemory(address)) throw new IllegalArgumentException("Read-only memory address");
        cpu.memory().write8(address >>> 4, address & 15, value);
    })); }

    void writeIo(int port, int value) { worker.execute(() -> command(() -> {
        if (!writableIo(port)) throw new IllegalArgumentException("Read-only I/O port");
        cpu.io().write8(port, value);
        if (!running) cpu.beforeInstruction();
    })); }

    private void command(Runnable command) {
        try { command.run(); }
        catch (Throwable error) { report(error); }
        publish();
    }

    static boolean writableMemory(int address) {
        return address >= 0 && address < 0x9FFF3 || address >= 0xA0000 && address < 0xC0000;
    }

    static boolean writableIo(int port) {
        if (port < 0 || port > 0xFFFF) return false;
        if (port < 0x100) return (port & 1) == 1;
        if (port <= 0x80FF) return (port & 0xFF) < 0x80 || (port & 0xFF) == 0x81;
        if (port <= 0x812F) { int offset = (port - 0x8100) & 7; return offset >= 2 && offset <= 4; }
        if (port <= 0x8150) return true;
        if (port >= 0x8160 && port <= 0x8164) return true;
        return port == 0xFF02;
    }

    private void scheduleTick(long delayNanos) {
        worker.schedule(this::tick, delayNanos, TimeUnit.NANOSECONDS);
    }

    private void tick() {
        if (!running) return;
        long begin = System.nanoTime();
        updateWorldTime(begin);
        int completed = 0, attempts = 0;
        while (running && !stopRequested && completed < instructionsPerTick && attempts++ < instructionsPerTick + 1024) {
            CPURegister r = cpu.registers();
            if (targetCs >= 0 && r.getCs() == targetCs && r.getIp() == targetIp) {
                boolean over = stepOver;
                pauseInternal(); status = over ? "Step over complete" : "Cursor reached"; break;
            }
            if (cpu.memory().read8(r.getCs(), r.getIp()) == 0xCC && !bypassInt3Once && !cpu.isHalted()) {
                pauseInternal(); status = "INT 3 breakpoint"; break;
            }
            bypassInt3Once = false;
            CPU8086.StepOutcome result = executeOne();
            if (faulted) break;
            if (result == CPU8086.StepOutcome.INSTRUCTION) completed++;
            if (result == CPU8086.StepOutcome.IDLE) {
                if (targetCs >= 0) { pauseInternal(); status = "Temporary run stopped at HLT/WAIT"; }
                else status = cpu.isHalted() ? "Waiting (HLT)" : "Waiting";
                break;
            }
        }
        if (stopRequested) pauseInternal();
        publish();
        if (running) scheduleTick(Math.max(0, 1_000_000_000L / ticksPerSecond - (System.nanoTime() - begin)));
    }

    private void updateWorldTime(long now) {
        if (worldTime != null) {
            gameTime = worldTime.getAsLong();
            if (gameTime != lastSubmittedGameTime) {
                cpu.hostBridge().submitWorldTime(gameTime, gameTime);
                lastSubmittedGameTime = gameTime;
            }
            return;
        }
        gameNanos += Math.max(0, now - lastTickNanos);
        lastTickNanos = now;
        long delta = gameNanos / 50_000_000L;
        gameNanos %= 50_000_000L;
        if (delta > 0) {
            gameTime += delta;
            cpu.hostBridge().submitWorldTime(gameTime, gameTime);
        }
    }

    private CPU8086.StepOutcome executeOne() {
        CPURegister r = cpu.registers();
        int cs = r.getCs(), ip = r.getIp();
        try {
            CPU8086.StepOutcome result = cpu.step();
            if (result == CPU8086.StepOutcome.INSTRUCTION) { rateInstructions++; totalInstructions++; }
            return result;
        } catch (Throwable error) {
            faulted = true;
            faultCs = cs;
            faultIp = ip;
            pauseInternal();
            status = "Error at %04X:%04X".formatted(cs, ip);
            report(error);
            return CPU8086.StepOutcome.IDLE;
        }
    }

    private void report(Throwable error) {
        StringWriter output = new StringWriter();
        error.printStackTrace(new PrintWriter(output));
        SwingUtilities.invokeLater(() -> onError.accept(output.toString()));
    }

    private void publish() {
        try {
            long now = System.nanoTime(), elapsed = now - rateStart;
            if (elapsed >= 1_000_000_000L) {
                measuredRate = rateInstructions * 1_000_000_000L / elapsed;
                rateInstructions = 0;
                rateStart = now;
            }
            CPURegister r = cpu.registers();
            int cs = followPc ? r.getCs() : codeCs, ip = followPc ? r.getIp() : codeIp;
            byte[] memory = new byte[256], io = new byte[256];
            for (int i = 0; i < 256; i++) {
                int address = (memoryBase + i) & 0xFFFFF;
                memory[i] = (byte) cpu.memory().read8(address >>> 4, address & 15);
                io[i] = (byte) cpu.io().read8((ioBase + i) & 0xFFFF);
            }
            byte[] redstone = readPorts(selectedChannel * 2, 2);
            byte[] sign = readPorts(0x0100 + selectedSign * 0x100, 0x82);
            byte[] timer = readPorts(0x8100 + selectedTimer * 8, 5);
        byte[] irq = readPorts(0xFF00, 3);
        byte[] storagePorts = readPorts(0x8160, 5);
        DeviceSnapshot devices = new DeviceSnapshot(selectedChannel, selectedSign, selectedTimer,
                redstone, sign, timer, irq, storagePorts, cpu.hostBridge().timerAutomatic(selectedTimer));
            List<Disassembler8086.Instruction> code = new ArrayList<>();
            if (rom != null) for (int i = 0; i < 25; i++) {
                var ins = Disassembler8086.decode(cpu.memory(), cs, ip);
                code.add(ins);
                ip = ins.nextIp();
            }
        Snapshot snapshot = new Snapshot(romName, status, registerText(r), measuredRate, totalInstructions,
                memoryBase, memory, ioBase, io, List.copyOf(code), running, faulted, devices,
                cpu.hostBridge().terminalText(), imageState(), testHigh);
            SwingUtilities.invokeLater(() -> onSnapshot.accept(snapshot));
        } catch (Throwable error) { report(error); }
    }

    private byte[] readPorts(int first, int count) {
        byte[] values = new byte[count];
        for (int i = 0; i < count; i++) values[i] = (byte) cpu.io().read8(first + i);
        return values;
    }

    private String registerText(CPURegister r) {
        return ("AX %04X  BX %04X  CX %04X  DX %04X    SP %04X  BP %04X  SI %04X  DI %04X\n" +
                "CS %04X  DS %04X  ES %04X  SS %04X    IP %04X  FLAGS %04X  PC %05X\n" +
                "CF %s PF %s AF %s ZF %s SF %s TF %s IF %s DF %s OF %s").formatted(
                r.getAx(), r.getBx(), r.getCx(), r.getDx(), r.getSp(), r.getBp(), r.getSi(), r.getDi(),
                r.getCs(), r.getDs(), r.getEs(), r.getSs(), r.getIp(), r.getFlags(),
                cpu.memory().physicalAddress(r.getCs(), r.getIp()),
                bit(r.getCF()), bit(r.getPF()), bit(r.getAF()), bit(r.getZF()), bit(r.getSF()),
                bit(r.getTF()), bit(r.getIF()), bit(r.getDF()), bit(r.getOF()));
    }

    private static int bit(boolean value) { return value ? 1 : 0; }

    @Override public void close() { worker.shutdownNow(); }
}
