package dervogel101.de;

import dervogel101.de.state.CPUIO;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.IoStorageDevice;
import dervogel101.de.state.SharedMemoryRegion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class CpuHostBridge {
    public enum SignSide { FRONT, BACK }

    private static final int TERMINAL_CAPACITY = 16_384;
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private final CPUMemory memory;
    private final CPUIO io;
    private final Clock clock;
    private final SharedMemoryRegion sharedMemory;
    private final char[] terminalLog = new char[TERMINAL_CAPACITY];
    private int terminalStart;
    private int terminalLength;
    private final ConcurrentLinkedQueue<Runnable> pendingInputs = new ConcurrentLinkedQueue<>();
    private final boolean[] automaticTimerIrqs = {true, true, true, true, true, true};
    private final CPUIO.IrqSink timerIrqs = (deviceId, count) -> {
        int timer = (deviceId >>> 8) == 2 ? deviceId & 0xFF : 3 + (deviceId & 0xFF);
        if (automaticTimerIrqs[timer]) enqueueRepeatedIrq(deviceId, count);
    };
    private final ArrayDeque<Integer> waitingIrqs = new ArrayDeque<>();
    private int activeIrq = -1;
    private boolean irqOverflow;
    private long lastGameTime;
    private long poweredOnGameTicks;
    private boolean hasGameTime;
    private long dayTime;
    private boolean testPinHigh;
    private boolean resetPinHigh;
    private boolean nmiPinHigh;
    private boolean resetPending;
    private boolean nmiPending;

    CpuHostBridge(CPUMemory memory, CPUIO io, Clock clock) {
        this.memory = memory;
        this.io = io;
        this.clock = clock;
        this.sharedMemory = memory.sharedMemory();
    }

    /** Pass this exact region to another CPU constructor to share its memory. */
    public SharedMemoryRegion sharedMemory() {
        return sharedMemory;
    }

    /** Connect a host-owned storage device, or pass null to disconnect it, at the next step boundary. */
    public void submitStorageDevice(IoStorageDevice device) {
        pendingInputs.add(() -> io.setStorageDevice(device));
    }

    public void submitWorldTime(long gameTime, long dayTime) {
        pendingInputs.add(() -> {
            if (hasGameTime && gameTime > lastGameTime) poweredOnGameTicks += gameTime - lastGameTime;
            lastGameTime = gameTime;
            hasGameTime = true;
            this.dayTime = dayTime;
        });
    }

    public void submitRedstoneInput(int channel, int strength) {
        if (channel < 0 || channel >= 128 || strength < 0 || strength > 15) {
            throw new IllegalArgumentException("Redstone channel or strength out of range");
        }
        pendingInputs.add(() -> io.setRedstoneInput(channel, strength));
    }

    public void submitSignClick(int sign, SignSide side) {
        if (sign < 0 || sign >= 128) throw new IllegalArgumentException("Sign index: " + sign);
        Objects.requireNonNull(side);
        pendingInputs.add(() -> io.clickSign(sign, side == SignSide.FRONT));
    }

    /** Queue a raw 16-bit device ID; its high byte is type and low byte is device index. */
    public void submitIrq(int deviceId) {
        if (deviceId < 0 || deviceId > 0xFFFF) throw new IllegalArgumentException("Device ID: " + deviceId);
        pendingInputs.add(() -> enqueueIrq(deviceId));
    }

    /** Device type occupies the high byte; the per-type device index occupies the low byte. */
    public void submitIrq(int deviceType, int deviceIndex) {
        if ((deviceType & ~0xFF) != 0 || (deviceIndex & ~0xFF) != 0)
            throw new IllegalArgumentException("Device type or index out of range");
        submitIrq((deviceType << 8) | deviceIndex);
    }

    /** Suppress or resume future expiry IRQs without changing the timer countdown or its control port. */
    public void submitTimerAutomatic(int timer, boolean automatic) {
        if (timer < 0 || timer >= automaticTimerIrqs.length) throw new IllegalArgumentException("Timer index: " + timer);
        pendingInputs.add(() -> automaticTimerIrqs[timer] = automatic);
    }

    /** Read on the CPU thread. */
    public boolean timerAutomatic(int timer) {
        if (timer < 0 || timer >= automaticTimerIrqs.length) throw new IllegalArgumentException("Timer index: " + timer);
        return automaticTimerIrqs[timer];
    }

    private void enqueueIrq(int deviceId) {
        if (activeIrq < 0) activeIrq = deviceId;
        else {
            if (waitingIrqs.size() == 255) {
                waitingIrqs.removeFirst();
                irqOverflow = true;
            }
            waitingIrqs.addLast(deviceId);
        }
    }

    private void enqueueRepeatedIrq(int deviceId, long count) {
        if (count > 256) irqOverflow = true;
        for (int i = 0; i < Math.min(count, 256); i++) enqueueIrq(deviceId);
    }

    public void submitTestPin(boolean high) {
        pendingInputs.add(() -> testPinHigh = high);
    }

    public void submitResetPin(boolean high) {
        pendingInputs.add(() -> {
            if (high && !resetPinHigh) resetPending = true;
            resetPinHigh = high;
        });
    }

    public void submitNmiPin(boolean high) {
        pendingInputs.add(() -> {
            if (high && !nmiPinHigh && !resetPinHigh) nmiPending = true;
            nmiPinHigh = high;
        });
    }

    boolean testPinHigh() {
        return testPinHigh;
    }

    boolean resetPinHigh() {
        return resetPinHigh;
    }

    boolean consumeReset() {
        boolean pending = resetPending;
        resetPending = false;
        return pending;
    }

    boolean consumeNmi() {
        boolean pending = nmiPending;
        nmiPending = false;
        return pending;
    }

    void discardNmi() {
        nmiPending = false;
    }

    boolean irqPending() {
        return activeIrq >= 0;
    }

    /** Called by the CPU thread once per step, including halted and waiting steps. */
    public void beforeInstruction() {
        int commands = io.consumeIrqCommands();
        if ((commands & 4) != 0) irqOverflow = false;
        if ((commands & 1) != 0 && activeIrq >= 0)
            activeIrq = waitingIrqs.isEmpty() ? -1 : waitingIrqs.removeFirst();
        Runnable input;
        while ((input = pendingInputs.poll()) != null) input.run();
        io.serviceStorageCommand();
        io.sendTerminalIfPending(this::appendTerminal);
        Instant now = clock.instant();
        io.advanceTimers(now.toEpochMilli(), poweredOnGameTicks, timerIrqs);
        io.setIrqStatus(activeIrq, irqOverflow);
        memory.updateClock(poweredOnGameTicks, dayTime, LocalDateTime.ofInstant(now, clock.getZone()));
    }

    private synchronized void appendTerminal(byte[] bytes) {
        byte[] nonzero = new byte[bytes.length];
        int count = 0;
        for (byte value : bytes) if (value != 0) nonzero[count++] = value;
        String message = new String(nonzero, 0, count, WINDOWS_1252);
        for (int i = 0; i < message.length(); i++) {
            terminalLog[(terminalStart + terminalLength) % TERMINAL_CAPACITY] = message.charAt(i);
            if (terminalLength < TERMINAL_CAPACITY) terminalLength++;
            else terminalStart = (terminalStart + 1) % TERMINAL_CAPACITY;
        }
    }

    /** A read-only snapshot of the newest terminal characters. */
    public synchronized String terminalText() {
        char[] text = new char[terminalLength];
        for (int i = 0; i < text.length; i++)
            text[i] = terminalLog[(terminalStart + i) % TERMINAL_CAPACITY];
        return new String(text);
    }

    /** Returns and clears changed outputs; safe to call from the host thread. */
    public CPUIO.OutputChanges pollChangedOutputs() {
        return io.drainChangedOutputs();
    }

    /** Returns null when NVRAM has not changed since the last acknowledged save. */
    public CPUMemory.NvramSnapshot snapshotNvramIfDirty() {
        return memory.snapshotNvramIfDirty();
    }

    /** Call only after the snapshot with this version was saved successfully. */
    public void acknowledgeNvramSaved(long version) {
        memory.acknowledgeNvramSaved(version);
    }

    /** Snapshot the connected storage; retain the device reference to save after disconnecting it. */
    public IoStorageDevice.Snapshot snapshotStorageIfDirty() {
        return io.snapshotStorageIfDirty();
    }

    /** Acknowledge a successfully persisted snapshot of the connected storage. */
    public void acknowledgeStorageSaved(long version) {
        io.acknowledgeStorageSaved(version);
    }

    /** Load the flat ROM image before starting CPU execution. */
    public void loadRom(byte[] image) {
        memory.loadRom(image);
    }

    /** Restore saved NVRAM before starting CPU execution. */
    public void loadNvram(byte[] image) {
        memory.loadNvram(image);
    }
}
