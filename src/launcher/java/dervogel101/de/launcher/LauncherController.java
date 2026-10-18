package dervogel101.de.launcher;

import dervogel101.de.CPU8086;
import dervogel101.de.i86.runtime.ShardSpeed;
import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.i86.runtime.HardwareProfile;
import dervogel101.de.i86.runtime.PunchcardDeck;
import dervogel101.de.i86.runtime.PunchcardDevice;
import dervogel101.de.i86.runtime.RedstoneIrqMode;
import dervogel101.de.state.SharedMemoryGroups;
import java.nio.charset.Charset;
import dervogel101.de.i86.runtime.TickBudget;
import dervogel101.de.i86.runtime.VirtualGameClock;
import dervogel101.de.CpuHostBridge;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.CPURegister;
import dervogel101.de.state.IoStorageDevice;
import dervogel101.de.state.SharedMemoryRegion;

import javax.swing.SwingUtilities;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

/** Owns the CPU thread. Swing only receives copied state. */
final class LauncherController implements DebuggerBackend {
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "8086-launcher-cpu");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<Snapshot> onSnapshot;
    private final Consumer<String> onError;
    private CPU8086 cpu;
    private SharedMemoryRegion sharedMemory;
    private SharedMemoryGroups groups;
    private long mainframeId;
    private String memoryId = "default";
    private boolean powered, psuHigh, sharedSelectionRequired, needsColdStart;
    private boolean shardPresent = true;
    private int shardTier = 3;
    private HardwareProfile hardware = HardwareProfile.complete();
    private final LongSupplier worldTime;
    private final VirtualGameClock ownClock;
    private final TickBudget tickBudget = new TickBudget();
    private ScheduledFuture<?> tickTask, batchTask;
    private long pacingGeneration, pacingTick, tickStart, tickDuration;
    private ShardSpeed tickSpeed;
    private Snapshot lastPausedSnapshot;
    private byte[] savedNvram = new byte[CPUMemory.NVRAM_SIZE];
    private Path nvramPath;
    private boolean nvramNew;
    private IoStorageDevice storage;
    private byte[] savedStorage;
    private Path storagePath;
    private boolean storageNew;
    private boolean testHigh;
    private byte[] rom;
    private Path romPath;
    private boolean romUnavailable;
    private BasicFileAttributes checkedRomAttributes;
    private boolean checkedRomMatches;
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
    private int batchesPerTick = 10;
    private int ticksPerSecond = 20;
    private int memoryBase = 0xFFF00;
    private int ioBase;
    private int codeCs = 0xFFFF, codeIp;
    private boolean followPc = true;
    private int selectedChannel, selectedSign, selectedTimer, selectedKeyboard;
    private int selectedPunchcard;
    private final PunchcardDevice[] punchcardDevices = new PunchcardDevice[32];
    private final java.util.Map<java.util.UUID,String> savedPunchcards = new java.util.HashMap<>();
    private long gameTime;
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
        ownClock = worldTime == null ? new VirtualGameClock(20) : null;
        this.worldTime = worldTime == null ? ownClock::ticks : worldTime;
        worker.setRemoveOnCancelPolicy(true);
        cpu = new CPU8086(sharedMemory);
        worker.scheduleWithFixedDelay(this::publish, 0, 200, TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(() -> {
            if (!powered || running || !hardware.has(Hardware.PUNCHCARD)) return;
            long time = this.worldTime.getAsLong(); cpu.hostBridge().submitWorldTime(time,time);
            cpu.hostBridge().servicePunchcardTick();
        },50,50,TimeUnit.MILLISECONDS);
    }

    LauncherController(Consumer<Snapshot> onSnapshot, Consumer<String> onError,
                       SharedMemoryGroups groups, long mainframeId, LongSupplier worldTime) {
        this(onSnapshot, onError, groups.region("default"), worldTime);
        this.groups = groups;
        this.mainframeId = mainframeId;
    }

    public void loadRom(Path path) {
        worker.execute(() -> {
            try {
                requirePowerOff();
                byte[] image = Files.readAllBytes(path);
                if (image.length != CPUMemory.ROM_SIZE) throw new IllegalArgumentException("ROM must be exactly 256 KiB");
                CPU8086 next = newCpu();
                next.hostBridge().loadRom(image);
                checkedRomAttributes = null;
            rom = image;
                romPath = path.toAbsolutePath(); romUnavailable = false;
                cpu = next;
                romName = path.getFileName().toString();
                resetView();
                needsColdStart = false;
                status = "Paused at reset vector";
                if (psuHigh && ensurePowered()) start(-1, 0, false);
            } catch (Throwable error) { report(error); }
            publish();
        });
    }

    public void configure(int instructions, int ticks) {
        configure(instructions, ticks, Math.min(10, instructions));
    }

    public void configure(int instructions, int ticks, int batches) {
        new ShardSpeed(instructions, batches);
        if (ticks < 1 || ticks > 1000) throw new IllegalArgumentException("1–1000 ticks/s required");
        worker.execute(() -> {
            instructionsPerTick = instructions;
            batchesPerTick = batches;
            ticksPerSecond = ticks;
            if (ownClock != null) ownClock.setRate(ticks);
            publish();
        });
    }

    public void run() { worker.execute(() -> start(-1, 0, false)); }
    public void runTo(int cs, int ip) { worker.execute(() -> start(cs, ip, false)); }
    public void stepOver() {
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
        psuHigh = true;
        if (!ensurePowered()) { publish(); return; }
        targetCs = cs;
        targetIp = ip;
        stepOver = over;
        bypassInt3Once = status.equals("INT 3 breakpoint");
        stopRequested = false;
        running = true;
        status = over ? "Stepping over" : cs >= 0 ? "Running to cursor" : "Running";
        updateWorldTime();
        pacingGeneration++;
        scheduleTick(0);
        publish();
    }

    public void pause() {
        stopRequested = true;
        worker.execute(() -> { pauseInternal(); stopRequested = false; publish(); });
    }
    private void pauseInternal() {
        cancelPacing();
        running = false;
        targetCs = -1;
        stepOver = false;
        status = faulted ? "Error" : cpu.isHalted() ? "Paused (HLT)" : "Paused";
    }

    public void step() { worker.execute(this::stepInternal); }
    private void stepInternal() {
        checkRomAvailability();
        if (rom == null || faulted) return;
        pauseInternal();
        psuHigh = true;
        if (!ensurePowered()) { publish(); return; }
        updateWorldTime();
        cpu.beforeBatch();
        executeOne();
        if (!faulted && cpu.isHalted()) status = "Paused (HLT)";
        publish();
    }

    public void skipInstruction() {
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

    public void setPc(int cs, int ip) {
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

    public void reset() {
        worker.execute(() -> {
            if (rom == null) return;
            pauseInternal();
            try { cpu.reset(); faulted = false; status = "CPU reset"; }
            catch (Throwable error) { report(error); }
            publish();
        });
    }

    public void powerCycle() {
        worker.execute(() -> {
            checkRomAvailability();
            if (rom == null || romUnavailable) return;
            boolean restart = running;
            powerOffInternal();
            CPU8086 next = newCpu();
            next.hostBridge().loadRom(rom);
            cpu = next;
            needsColdStart = false;
            resetView();
            if (psuHigh) ensurePowered();
            status = "Power cycled";
            if (restart && psuHigh) start(-1, 0, false);
            publish();
        });
    }

    private CPU8086 newCpu() {
        CPU8086 next = new CPU8086(sharedMemory);
        next.configureHardware(hardware);
        if (cpu != null) nvramNew |= cpu.hostBridge().snapshotNvramIfDirty() != null;
        next.hostBridge().loadNvram(cpu == null ? savedNvram : cpu.memory().snapshotNvram().bytes());
        if (storage != null) next.hostBridge().submitStorageDevice(storage);
        if (cpu != null) {
            for (int i = 0; i < 128; i++) {
                next.hostBridge().submitRedstoneIrqMode(i, cpu.hostBridge().redstoneIrqMode(i));
                next.hostBridge().submitSignIrqSides(i, cpu.hostBridge().signIrqSides(i));
                next.hostBridge().submitRedstoneInput(i, cpu.io().read8(i * 2), false);
            }
            for (int i = 0; i < 6; i++) next.hostBridge().submitTimerAutomatic(i, cpu.hostBridge().timerAutomatic(i));
        }
        next.hostBridge().submitTestPin(testHigh);
        long ticks = worldTime.getAsLong();
        next.hostBridge().submitWorldTime(ticks, ticks);
        next.beforeTick();
        next.beforeBatch();
        return next;
    }

    public void pauseAndWait() {
        stopRequested = true;
        call(() -> {
            pauseInternal();
            stopRequested = false;
            cpu.beforeInstruction();
            publish();
            return null;
        });
    }

    public ImageState inspectImages() {
        return call(this::imageState);
    }

    public void openNvram(Path path) {
        byte[] image = readImage(path, CPUMemory.NVRAM_SIZE);
        call(() -> {
            requirePowerOff();
            pauseInternal();
            cpu.hostBridge().loadNvram(image);
            savedNvram = image;
            nvramPath = path;
            nvramNew = false;
            publish();
            return null;
        });
    }

    public void newNvram() {
        call(() -> {
            requirePowerOff();
            pauseInternal();
            savedNvram = new byte[CPUMemory.NVRAM_SIZE];
            cpu.hostBridge().loadNvram(savedNvram);
            nvramPath = null;
            nvramNew = true;
            publish();
            return null;
        });
    }

    public void saveNvram(Path path) {
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

    public void discardNvram() {
        call(() -> {
            pauseInternal();
            cpu.hostBridge().loadNvram(savedNvram);
            nvramNew = false;
            publish();
            return null;
        });
    }

    public void openStorage(Path path) {
        byte[] image = readImage(path, IoStorageDevice.SIZE);
        call(() -> {
            requirePowerOff();
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

    public void newStorage() {
        call(() -> {
            requirePowerOff();
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

    public void saveStorage(Path path) {
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

    public void discardStorage() {
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

    public void disconnectStorage() {
        call(() -> {
            requirePowerOff();
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

    public void powerCycleWithSharedMemory(SharedMemoryRegion nextRegion) {
        if (groups != null) throw new IllegalStateException("Managed CPUs use named shared-memory groups");
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
                storage != null && (storageNew || storage.snapshotIfDirty() != null), storage != null && hardware.has(Hardware.DISK));
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
        selectedChannel = selectedSign = selectedTimer = selectedKeyboard = 0;
        gameTime = rateInstructions = measuredRate = totalInstructions = 0;
        lastSubmittedGameTime = Long.MIN_VALUE;
        rateStart = System.nanoTime();
    }

    public void nmi() { worker.execute(() -> {
        cpu.hostBridge().submitNmiPulse();
        if (!running) cpu.beforeInstruction();
        publish();
    }); }

    public void testPin(boolean high) { worker.execute(() -> {
        testHigh = high;
        cpu.hostBridge().submitTestPin(high);
        if (!running) cpu.beforeInstruction();
        publish();
    }); }

    public void redstone(int channel, int strength) { worker.execute(() -> command(() -> {
        cpu.hostBridge().submitRedstoneInput(channel, strength);
        if (!running) cpu.beforeInstruction();
    })); }

    public void signClick(int sign, boolean front) { worker.execute(() -> command(() -> {
        cpu.hostBridge().submitSignClick(sign, front ? CpuHostBridge.SignSide.FRONT : CpuHostBridge.SignSide.BACK);
        if (!running) cpu.beforeInstruction();
    })); }

    public void selectChannel(int channel) { worker.execute(() -> command(() -> {
        if (channel < 0 || channel >= 128) throw new IllegalArgumentException("Redstone channel: " + channel);
        selectedChannel = channel;
    })); }

    public void selectSign(int sign) { worker.execute(() -> command(() -> {
        if (sign < 0 || sign >= 128) throw new IllegalArgumentException("Sign index: " + sign);
        selectedSign = sign;
    })); }

    public void selectPunchcard(int index) {
        if (index < 0 || index >= 32) throw new IllegalArgumentException("Punchcard slot");
        call(() -> { selectedPunchcard = index; publish(); return null; });
    }
    public void punchcardCommand(int index,int operation,String hex) { punchcardCommand(index,operation,hex,0); }
    public void punchcardCommand(int index,int operation,String hex,int metadata) {
        call(() -> {
            if (running || !powered) throw new IllegalStateException("Pause a powered CPU before editing punchcard I/O");
            punchcardDevice(index);
            if (operation <= 0 || operation > 255 || Integer.bitCount(operation) != 1 || metadata < 0 || metadata > 1)
                throw new IllegalArgumentException("Invalid punchcard command or metadata selector");
            byte[] bytes = operation == PunchcardDevice.WRITE || operation == PunchcardDevice.WRITE_NAME ? java.util.HexFormat.of().parseHex(hex.replace(" ","")) : new byte[0];
            if ((operation == PunchcardDevice.WRITE || operation == PunchcardDevice.WRITE_NAME) && bytes.length != 32) throw new IllegalArgumentException("Enter exactly 32 hexadecimal bytes");
            int base = 0x8200+index*64;
            cpu.io().write8(base+0x2c,metadata);
            for (int i=0;i<bytes.length;i++) cpu.io().write8(base+i,bytes[i]&255);
            cpu.io().write8(base+32,operation); cpu.beforeInstruction(); publish(); return null;
        });
    }
    private PunchcardDevice punchcardDevice(int index) {
        if (index < 0 || index >= 32) throw new IllegalArgumentException("Punchcard slot");
        if (punchcardDevices[index] == null) punchcardDevices[index] = new PunchcardDevice(index >= 16);
        return punchcardDevices[index];
    }
    public PunchcardDeck punchcardMedia(int index, boolean output) {
        return call(() -> output ? punchcardDevice(index).output() : punchcardDevice(index).active());
    }
    public void insertPunchcard(int index, PunchcardDeck deck) {
        call(() -> {
            for (var device : punchcardDevices) if (device != null) for (var existing : new PunchcardDeck[]{device.active(),device.output()})
                if (existing != null && existing.id().equals(deck.id()) && (!existing.sealed() || !deck.sealed() || !existing.hash().equals(deck.hash())))
                    throw new IllegalStateException("Deck UUID is already active or has conflicting contents");
            if (!punchcardDevice(index).insert(deck)) throw new IllegalStateException("Transport occupied or incompatible deck");
            if (deck.sealed()) savedPunchcards.put(deck.id(),deck.hash());
            connectPunchcards(powered); publish(); return null;
        });
    }
    public void collectPunchcard(int index) { call(() -> { punchcardDevice(index).collect(); publish(); return null; }); }
    public boolean punchcardDirty(int index, boolean output) {
        return call(() -> { var deck = output ? punchcardDevice(index).output() : punchcardDevice(index).active();
            return deck != null && !deck.blankMedia() && !deck.hash().equals(savedPunchcards.get(deck.id())); });
    }
    public void savePunchcard(int index, boolean output, Path path) {
        call(() -> {
            var deck = output ? punchcardDevice(index).output() : punchcardDevice(index).active();
            if (deck == null) throw new IllegalStateException("No deck");
            deck.seal().save(path); savedPunchcards.put(deck.id(),deck.hash()); return null;
        });
    }
    public void discardPunchcardChanges(int index, boolean output) {
        call(() -> { var deck = output ? punchcardDevice(index).output() : punchcardDevice(index).active();
            if (deck != null) savedPunchcards.put(deck.id(),deck.hash()); return null; });
    }
    private void connectPunchcards(boolean connected) {
        for (int i=0;i<32;i++) cpu.hostBridge().submitPunchcardConnection(i>=16,i&15,connected ? punchcardDevice(i) : null);
    }
    private void writePunchcards(DataOutputStream out) throws IOException {
        var decks = new java.util.LinkedHashMap<java.util.UUID,PunchcardDeck>();
        for (int i=0;i<32;i++) for (var deck : new PunchcardDeck[]{punchcardDevice(i).active(),punchcardDevice(i).output()}) if (deck != null) {
            var previous = decks.putIfAbsent(deck.id(),deck);
            if (previous != null && !previous.hash().equals(deck.hash())) throw new IOException("Conflicting deck UUID");
        }
        out.writeInt(decks.size());
        for (var deck : decks.values()) { deck.write(out); out.writeUTF(savedPunchcards.getOrDefault(deck.id(),"")); }
        for (int i=0;i<32;i++) punchcardDevice(i).writeState(out);
    }
    private void readPunchcards(DataInputStream in) throws IOException {
        int count = in.readInt(); if (count < 0 || count > 64) throw new IOException("Invalid media count");
        var decks = new java.util.HashMap<java.util.UUID,PunchcardDeck>(); savedPunchcards.clear();
        for (int i=0;i<count;i++) {
            var deck = PunchcardDeck.read(in); String hash = in.readUTF();
            if (decks.putIfAbsent(deck.id(),deck) != null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid saved media");
            if (!hash.isEmpty()) savedPunchcards.put(deck.id(),hash);
        }
        var owners = new java.util.HashSet<java.util.UUID>();
        for (int i=0;i<32;i++) {
            var device = PunchcardDevice.readState(in,decks::get);
            if (device.writer != (i>=16) || device.active() != null && device.writer && !owners.add(device.active().id())) throw new IOException("Invalid transport ownership");
            punchcardDevices[i] = device;
        }
    }
    public void selectKeyboard(int slot) {
        worker.execute(() -> command(() -> {
            if (slot < 0 || slot >= 8) throw new IllegalArgumentException("Keyboard slot: " + slot);
            selectedKeyboard = slot;
        }));
    }
    public void keyboardInput(int slot, int value) {
        worker.execute(() -> command(() -> {
            if (slot < 0 || slot >= 8 || !dervogel101.de.i86.runtime.KeyboardInput.allowedByte(value))
                throw new IllegalArgumentException("Invalid keyboard input");
            if (!powered || !hardware.has(Hardware.KEYBOARD)) throw new IllegalStateException("Keyboard controller is offline");
            if (!cpu.hostBridge().submitKeyboardInput(slot, value)) throw new IllegalStateException("Keyboard FIFO is full");
            if (!running) cpu.beforeInstruction();
        }));
    }
    public void selectTimer(int timer) { worker.execute(() -> command(() -> {
        if (timer < 0 || timer >= 6) throw new IllegalArgumentException("Timer index: " + timer);
        selectedTimer = timer;
    })); }

    public void sendIrq(int deviceId) { worker.execute(() -> command(() -> {
        cpu.hostBridge().submitIrq(deviceId);
        if (!running) cpu.beforeInstruction();
    })); }

    public void timerAutomatic(int timer, boolean automatic) { worker.execute(() -> command(() -> {
        cpu.hostBridge().submitTimerAutomatic(timer, automatic);
        if (!running) cpu.beforeInstruction();
    })); }

    public void writeTimer(int timer, int reload, int control) { worker.execute(() -> command(() -> {
        if (timer < 0 || timer >= 6 || reload < 0 || reload > 0xFFFF || (control & ~0x37) != 0)
            throw new IllegalArgumentException("Timer setting out of range");
        int base = 0x8100 + timer * 8;
        cpu.io().write8(base + 2, reload & 0xFF);
        cpu.io().write8(base + 3, reload >>> 8);
        cpu.io().write8(base + 4, control);
        if (!running) cpu.beforeInstruction();
    })); }

    public void resetTimer(int timer) { worker.execute(() -> command(() -> {
        if (timer < 0 || timer >= 6) throw new IllegalArgumentException("Timer index: " + timer);
        cpu.io().write8(0x8100 + timer * 8 + 4, 8);
        if (!running) cpu.beforeInstruction();
    })); }

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    public void writeSignLine(int sign, int line, String text) {
        if (sign < 0 || sign >= 128 || line < 0 || line >= 8 || text.length() > 15
                || !WINDOWS_1252.newEncoder().canEncode(text))
            throw new IllegalArgumentException("Sign text requires at most 15 Windows-1252 characters");
        byte[] bytes = text.getBytes(WINDOWS_1252);
        worker.execute(() -> command(() -> {
            int base = 0x0100 + sign * 0x100 + line * 16 + 1;
            for (int i = 0; i < 15; i++) cpu.io().write8(base + i, i < bytes.length ? bytes[i] : 0);
        }));
    }

    public void memoryBase(int address) { worker.execute(() -> { memoryBase = address & 0xFFFFF; publish(); }); }
    public void ioBase(int port) { worker.execute(() -> { ioBase = port & 0xFFFF; publish(); }); }
    public void codeAddress(int cs, int ip) { worker.execute(() -> {
        codeCs = cs & 0xFFFF; codeIp = ip & 0xFFFF; followPc = false; publish();
    }); }
    public void followPc() { worker.execute(() -> { followPc = true; publish(); }); }

    public void writeMemory(int address, int value) { worker.execute(() -> command(() -> {
        if (!writableMemory(address)) throw new IllegalArgumentException("Read-only memory address");
        cpu.memory().write8(address >>> 4, address & 15, value);
    })); }

    public void writeIo(int port, int value) { worker.execute(() -> command(() -> {
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
        if (port >= 0x8200 && port <= 0x89ff) return (port & 63) <= 0x20 || (port & 63) == 0x22;
        if (port < 0 || port > 0xFFFF) return false;
        if (port < 0x100) return (port & 1) == 1;
        if (port <= 0x80FF) return (port & 0xFF) < 0x80 || (port & 0xFF) == 0x81;
        if (port <= 0x812F) { int offset = (port - 0x8100) & 7; return offset >= 2 && offset <= 4; }
        if (port <= 0x8150) return true;
        if (port >= 0x8160 && port <= 0x8164) return true;
        if (port >= 0x8170 && port <= 0x817f) return (port & 1) != 0;
        return port == 0xFF02;
    }

    private void cancelPacing() {
        pacingGeneration++;
        tickBudget.stop();
        if (tickTask != null) tickTask.cancel(false);
        if (batchTask != null) batchTask.cancel(false);
        tickTask = batchTask = null;
    }

    private void scheduleTick(long delayNanos) {
        long generation = pacingGeneration;
        tickTask = worker.schedule(() -> tick(generation), delayNanos, TimeUnit.NANOSECONDS);
    }

    private void tick(long generation) {
        checkRomAvailability();
        if (!running || generation != pacingGeneration) return;
        if (stopRequested) {
            pauseInternal();
            publish();
            return;
        }
        tickStart = System.nanoTime();
        tickDuration = 1_000_000_000L / ticksPerSecond;
        tickSpeed = new ShardSpeed(instructionsPerTick, batchesPerTick);
        tickBudget.begin(tickStart, tickDuration, tickSpeed);
        long tick = ++pacingTick;
        updateWorldTime();
        scheduleTick(tickDuration);
        runBatch(generation, tick);
    }

    private void runBatch(long generation, long tick) {
        if (!running || generation != pacingGeneration || tick != pacingTick) return;
        TickBudget.Batch batch = tickBudget.take(System.nanoTime());
        if (batch == null) return;
        cpu.beforeBatch();
        boolean tickYield = false;
        for (int remaining = batch.steps(); running && !stopRequested
                && remaining > 0 && tickBudget.valid(batch, System.nanoTime()); remaining--) {
            CPURegister r = cpu.registers();
            if (targetCs >= 0 && r.getCs() == targetCs && r.getIp() == targetIp) {
                boolean over = stepOver;
                pauseInternal();
                status = over ? "Step over complete" : "Cursor reached";
                break;
            }
            if (cpu.memory().read8(r.getCs(), r.getIp()) == 0xCC && !bypassInt3Once && !cpu.isHalted()) {
                pauseInternal();
                status = "INT 3 breakpoint";
                break;
            }
            bypassInt3Once = false;
            CPU8086.StepOutcome result = executeOne();
            if (faulted) break;
            if (result != CPU8086.StepOutcome.IDLE) {
                status = stepOver ? "Stepping over" : targetCs >= 0 ? "Running to cursor" : "Running";
            }
            if (result == CPU8086.StepOutcome.YIELD_BATCH || result == CPU8086.StepOutcome.YIELD_TICK) {
                tickYield = result == CPU8086.StepOutcome.YIELD_TICK;
                tickBudget.yielded(batch, tickYield ? TickBudget.Outcome.YIELD_TICK : TickBudget.Outcome.YIELD_BATCH);
                break;
            }
            if (result == CPU8086.StepOutcome.IDLE) {
                if (targetCs >= 0) {
                    pauseInternal();
                    status = "Temporary run stopped at HLT/WAIT";
                    break;
                }
                status = cpu.isHalted() ? "Waiting (HLT)" : "Waiting";
            }
        }
        if (stopRequested) pauseInternal();
        publish();
        if (!running || tickYield || generation != pacingGeneration || tick != pacingTick) return;
        long now = System.nanoTime();
        long elapsed = now - tickStart;
        if (elapsed >= tickDuration) return;
        int next = Math.max(batch.index() + 1,
                (int) (elapsed * tickSpeed.batchesPerTick() / tickDuration));
        if (next < tickSpeed.batchesPerTick()) {
            long when = tickStart + tickDuration * next / tickSpeed.batchesPerTick();
            batchTask = worker.schedule(() -> runBatch(generation, tick), Math.max(0, when - now),
                    TimeUnit.NANOSECONDS);
        }
    }

    private void updateWorldTime() {
        gameTime = worldTime.getAsLong();
        if (gameTime != lastSubmittedGameTime) {
            cpu.hostBridge().submitWorldTime(gameTime, gameTime);
        cpu.beforeTick();
            lastSubmittedGameTime = gameTime;
        }
    }

    private CPU8086.StepOutcome executeOne() {
        CPURegister r = cpu.registers();
        int cs = r.getCs(), ip = r.getIp();
        try {
            CPU8086.StepOutcome result = cpu.step();
            if (result == CPU8086.StepOutcome.INSTRUCTION || result == CPU8086.StepOutcome.YIELD_BATCH
                    || result == CPU8086.StepOutcome.YIELD_TICK) { rateInstructions++; totalInstructions++; }
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
        cpu.hostBridge().reportHostError(error.toString() + "\n");
        StringWriter output = new StringWriter();
        error.printStackTrace(new PrintWriter(output));
        SwingUtilities.invokeLater(() -> onError.accept(output.toString()));
    }

    private void publish() {
        checkRomAvailability();
        try {
            long now = System.nanoTime(), elapsed = now - rateStart;
            if (elapsed >= 1_000_000_000L) {
                measuredRate = rateInstructions * 1_000_000_000L / elapsed;
                rateInstructions = 0;
                rateStart = now;
            }
            if (running && lastPausedSnapshot != null) {
                Snapshot previous = lastPausedSnapshot;
                Snapshot snapshot = new Snapshot(romName, status, previous.registers(), measuredRate, totalInstructions,
                        previous.memoryBase(), previous.memory(), previous.ioBase(), previous.io(), previous.code(),
                        true, faulted, previous.devices().withKeyboard(selectedKeyboard, cpu.hostBridge().keyboardSnapshot(selectedKeyboard)).withPunchcard(selectedPunchcard,cpu.hostBridge().punchcardSnapshot(selectedPunchcard),cpu.hostBridge().punchcardName(selectedPunchcard)), cpu.hostBridge().terminalText(), imageState(), testHigh, machineState());
                SwingUtilities.invokeLater(() -> onSnapshot.accept(snapshot));
                return;
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
                redstone, sign, timer, irq, storagePorts, cpu.hostBridge().timerAutomatic(selectedTimer), cpu.hostBridge().redstoneIrqMode(selectedChannel),
                    cpu.hostBridge().signIrqSides(selectedSign), selectedKeyboard, cpu.hostBridge().keyboardSnapshot(selectedKeyboard)).withPunchcard(selectedPunchcard,cpu.hostBridge().punchcardSnapshot(selectedPunchcard),cpu.hostBridge().punchcardName(selectedPunchcard));
            List<Disassembler8086.Instruction> code = new ArrayList<>();
            if (rom != null) for (int i = 0; i < 25; i++) {
                var ins = Disassembler8086.decode(cpu.memory(), cs, ip);
                code.add(ins);
                ip = ins.nextIp();
            }
        Snapshot snapshot = new Snapshot(romName, status, registerText(r), measuredRate, totalInstructions,
                memoryBase, memory, ioBase, io, List.copyOf(code), running, faulted, devices,
                cpu.hostBridge().terminalText(), imageState(), testHigh, machineState());
            lastPausedSnapshot = snapshot;
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

    @Override public synchronized void close() {
        if (worker.isShutdown()) return;
        stopRequested = true;
        worker.execute(this::powerOffInternal);
        worker.shutdown();
    }

    private MachineState machineState() {
        return new MachineState(powered, psuHigh, hardware, shardTier, memoryId,
                new ShardSpeed(instructionsPerTick, batchesPerTick));
    }

    public MachineState inspectMachine() { return call(this::machineState); }

    private void requirePowerOff() {
        if (powered) throw new IllegalStateException("Power off the mainframe before changing installed hardware or images");
    }

    private void powerOffInternal() {
        pauseInternal();
        if (powered) {
            needsColdStart = true;
            if (groups != null) groups.powerOff(mainframeId);
        }
        powered = false;
        connectKeyboards(false);
    }

    private boolean ensurePowered() {
        if (powered) return true;
        if (rom == null || romUnavailable || !shardPresent || sharedSelectionRequired) {
            status = "Powered off: " + (rom == null ? "no ROM" : romUnavailable ? "ROM missing or invalid" : !shardPresent ? "no speed shard" : "select a shared-memory ID");
            return false;
        }
        if (groups != null && hardware.has(Hardware.SHARED_MEMORY)) sharedMemory = groups.powerOn(mainframeId, memoryId);
        if (needsColdStart) {
            cpu = newCpu(); cpu.hostBridge().loadRom(rom); resetView(); needsColdStart = false;
        }
        powered = true;
        connectKeyboards(true);
        return true;
    }
    private void connectKeyboards(boolean connected) {
        connectPunchcards(connected);
        for (int slot = 0; slot < 8; slot++)
            cpu.hostBridge().submitKeyboardConnection(slot, connected && hardware.has(Hardware.KEYBOARD));
    }

    public void powerSupply(boolean high) {
        call(() -> {
            psuHigh = high;
            if (!high) { powerOffInternal(); status = "Powered off"; }
            else if (!powered && ensurePowered()) start(-1, 0, false);
            publish(); return null;
        });
    }

    public void configureMachine(HardwareProfile profile, boolean crystal, String sharedId) {
        configureMachine(profile, crystal ? 3 : 0, sharedId);
    }

    public void configureMachine(HardwareProfile profile, int tier, String sharedId) {
        if (tier < 0 || tier > 3) throw new IllegalArgumentException("Speed shard tier must be 0 to 3");
        String normalized = SharedMemoryGroups.normalize(sharedId);
        profile.validationError().ifPresent(error -> { throw new IllegalArgumentException(error); });
        call(() -> {
            requirePowerOff();
            hardware = profile; shardTier = tier; shardPresent = tier != 0; memoryId = normalized; sharedSelectionRequired = false;
            if (groups != null) sharedMemory = groups.region(memoryId);
            needsColdStart = true;
            cpu.configureHardware(hardware);
            if (psuHigh && ensurePowered()) start(-1, 0, false);
            publish(); return null;
        });
    }

    public void sharedRegionDeleted(String id) {
        call(() -> {
            if (memoryId.equals(SharedMemoryGroups.normalize(id)) && hardware.has(Hardware.SHARED_MEMORY)) {
                powerOffInternal(); sharedSelectionRequired = true;
                status = "Powered off: shared-memory region deleted; select an ID";
            }
            publish(); return null;
        });
    }

    public void redstoneIrq(int channel, RedstoneIrqMode mode) {
        worker.execute(() -> command(() -> { cpu.hostBridge().submitRedstoneIrqMode(channel, mode); cpu.beforeInstruction(); }));
    }

    public void signIrq(int sign, int sides) {
        worker.execute(() -> command(() -> { cpu.hostBridge().submitSignIrqSides(sign, sides); cpu.beforeInstruction(); }));
    }

    public boolean powerOffForCycle() {
        return call(() -> { boolean restart = running; powerOffInternal(); return restart; });
    }

    public void powerOnAfterCycle(boolean restart) {
        call(() -> {
            if (psuHigh) ensurePowered();
            status = "Power cycled";
            if (restart && psuHigh) start(-1, 0, false);
            publish(); return null;
        });
    }

    public byte[] saveSessionState(boolean resume) {
        return call(() -> {
            if (running) throw new IllegalStateException("Suspend all CPUs before saving a session");
            updateWorldTime();
            var buffer = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(buffer)) {
                out.writeInt(3); out.writeBoolean(rom != null); out.writeUTF(romName);
                out.writeUTF(romPath == null ? "" : romPath.toString()); out.writeBoolean(romUnavailable);
                out.writeBoolean(powered); out.writeBoolean(psuHigh); out.writeBoolean(resume);
                out.writeBoolean(faulted); out.writeShort(faultCs); out.writeShort(faultIp); out.writeUTF(status);
                out.writeByte(shardTier); out.writeUTF(memoryId); out.writeBoolean(sharedSelectionRequired);
                out.writeBoolean(needsColdStart); out.writeBoolean(testHigh);
                out.writeInt(instructionsPerTick); out.writeInt(batchesPerTick); out.writeInt(ticksPerSecond);
                out.writeLong(totalInstructions); out.writeBoolean(nvramNew); out.writeBoolean(storageNew);
                out.writeUTF(nvramPath == null ? "" : nvramPath.toString());
                out.writeUTF(storagePath == null ? "" : storagePath.toString());
                out.write(savedNvram); out.writeBoolean(savedStorage != null);
                if (savedStorage != null) out.write(savedStorage);
                byte[] core = cpu.saveState(); out.writeInt(core.length); out.write(core); writePunchcards(out);
            }
            return buffer.toByteArray();
        });
    }

    public boolean restoreSessionState(byte[] saved) {
        return call(() -> {
            requirePowerOff();
            if (saved.length > 8 * 1024 * 1024) throw new IOException("Launcher CPU state exceeds 8 MiB");
            try (var in = new DataInputStream(new ByteArrayInputStream(saved))) {
                int version = in.readInt();
                if (version < 1 || version > 3) throw new IOException("Unsupported launcher CPU state format");
                boolean hasRom = in.readBoolean(); romName = in.readUTF();
                String romFile = in.readUTF(); romPath = romFile.isEmpty() ? null : Path.of(romFile);
                romUnavailable = in.readBoolean();
                if (hasRom && romPath == null) throw new IOException("Missing ROM reference");
                boolean savedPowered = in.readBoolean(); psuHigh = in.readBoolean(); boolean resume = in.readBoolean();
                faulted = in.readBoolean(); faultCs = in.readUnsignedShort(); faultIp = in.readUnsignedShort(); status = in.readUTF();
                shardTier = version == 1 ? (in.readBoolean() ? 3 : 0) : in.readUnsignedByte();
                if (shardTier > 3) throw new IOException("Invalid speed shard tier");
                shardPresent = shardTier != 0; memoryId = SharedMemoryGroups.normalize(in.readUTF()); sharedSelectionRequired = in.readBoolean();
                needsColdStart = in.readBoolean(); testHigh = in.readBoolean();
                instructionsPerTick = in.readInt(); batchesPerTick = in.readInt(); ticksPerSecond = in.readInt();
                new ShardSpeed(instructionsPerTick, batchesPerTick);
                if (ticksPerSecond < 1 || ticksPerSecond > 1000) throw new IOException("Invalid tick rate");
                totalInstructions = in.readLong(); nvramNew = in.readBoolean(); storageNew = in.readBoolean();
                String nvramFile = in.readUTF(), storageFile = in.readUTF();
                nvramPath = nvramFile.isEmpty() ? null : Path.of(nvramFile);
                storagePath = storageFile.isEmpty() ? null : Path.of(storageFile);
                savedNvram = new byte[CPUMemory.NVRAM_SIZE]; in.readFully(savedNvram);
                savedStorage = in.readBoolean() ? new byte[IoStorageDevice.SIZE] : null;
                if (savedStorage != null) in.readFully(savedStorage);
                int length = in.readInt();
                if (length < 1 || length > 2 * 1024 * 1024) throw new IOException("Invalid core-state length");
                byte[] core = new byte[length]; in.readFully(core);
                if (version >= 3) readPunchcards(in); else { Arrays.fill(punchcardDevices,null); savedPunchcards.clear(); }
                if (in.read() != -1 || totalInstructions < 0) throw new IOException("Invalid launcher CPU state");
                sharedMemory = groups == null ? sharedMemory : groups.region(memoryId);
                cpu = CPU8086.restoreState(core, sharedMemory); hardware = cpu.hardware();
                rom = hasRom ? cpu.memory().romImage() : null;
                checkedRomAttributes = null; storage = cpu.io().storageDevice();
                if (savedPowered && (rom == null || romUnavailable || !psuHigh || !shardPresent || sharedSelectionRequired)
                        || resume && (!savedPowered || faulted)) throw new IOException("Inconsistent power state");
                if (storage != null && savedStorage == null) throw new IOException("Missing storage baseline");
                powered = savedPowered;
                connectKeyboards(powered);
                if (powered && groups != null && hardware.has(Hardware.SHARED_MEMORY)) groups.powerOn(mainframeId, memoryId);
                running = false; lastPausedSnapshot = null; lastSubmittedGameTime = Long.MIN_VALUE;
                followPc = true; publish(); return resume;
            }
        });
    }

    public boolean suspendForSession() {
        return call(() -> { boolean resume = running; pauseInternal(); return resume; });
    }

    private void checkRomAvailability() {
        if (rom == null || romPath == null) return;
        boolean usable;
        try {
            var attributes = Files.readAttributes(romPath, BasicFileAttributes.class);
            usable = attributes.isRegularFile() && attributes.size() == CPUMemory.ROM_SIZE;
            if (usable) {
                if (checkedRomAttributes == null
                        || !attributes.lastModifiedTime().equals(checkedRomAttributes.lastModifiedTime())
                        || !attributes.creationTime().equals(checkedRomAttributes.creationTime())
                        || !Objects.equals(attributes.fileKey(), checkedRomAttributes.fileKey())) {
                    try (var input = Files.newInputStream(romPath)) {
                        checkedRomMatches = Arrays.equals(rom, input.readNBytes(CPUMemory.ROM_SIZE + 1));
                    }
                    checkedRomAttributes = attributes;
                }
                usable = checkedRomMatches;
            }
        }
        catch (IOException error) { usable = false; }
        if (!usable && (powered || !romUnavailable)) {
            powerOffInternal(); romUnavailable = true;
            status = "Powered off: ROM missing or invalid — " + romPath;
            report(new IllegalStateException(status));
        } else if (usable && romUnavailable) {
            romUnavailable = false;
            if (psuHigh && ensurePowered()) start(-1, 0, false);
        }
    }
}
