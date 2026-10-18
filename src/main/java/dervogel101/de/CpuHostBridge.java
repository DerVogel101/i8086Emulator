package dervogel101.de;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import dervogel101.de.state.CPUIO;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.IoStorageDevice;
import dervogel101.de.state.SharedMemoryRegion;

import java.time.Clock;
import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.i86.runtime.HardwareProfile;
import dervogel101.de.i86.runtime.RedstoneIrqMode;
import java.util.Arrays;
import java.time.Instant;
import java.time.LocalDateTime;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class CpuHostBridge {
    public enum SignSide { FRONT, BACK }

    public void submitKeyboardConnection(int slot, boolean connected) { io.keyboards.connect(slot, connected); }
    public void submitPunchcardConnection(boolean writer, int slot, dervogel101.de.i86.runtime.PunchcardDevice device) { io.punchcards.connect(writer, slot, device); }
    public byte[] punchcardSnapshot(int index) {
        if (index < 0 || index >= 32) throw new IllegalArgumentException("Punchcard slot");
        return io.punchcards.snapshot(index);
    }
    public String punchcardName(int index) {
        if (index < 0 || index >= 32) throw new IllegalArgumentException("Punchcard slot");
        var device = io.punchcards.device(index>=16,index&15); var deck = device == null ? null : device.active();
        return deck == null ? "" : deck.label();
    }
    public boolean submitKeyboardInput(int slot, int value) { return io.keyboards.offer(slot, value); }
    public dervogel101.de.i86.runtime.KeyboardDevice.Snapshot keyboardSnapshot(int slot) { return io.keyboards.snapshot(slot); }

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
    private HardwareProfile hardware = HardwareProfile.complete();
    private final RedstoneIrqMode[] redstoneIrqs = new RedstoneIrqMode[128];
    private final int[] signIrqSides = new int[128];
    private long lastGameTime;
    private long poweredOnGameTicks;
    private long lastPunchcardTick = Long.MIN_VALUE;
    private boolean hasGameTime;
    private long dayTime;
    private boolean testPinHigh;
    private boolean resetPinHigh;
    private boolean nmiPinHigh;
    private boolean resetPending;
    private boolean nmiPending;
    private final java.util.concurrent.atomic.AtomicLong hostErrors = new java.util.concurrent.atomic.AtomicLong();
    public long hostErrors() { return hostErrors.get(); }

    CpuHostBridge(CPUMemory memory, CPUIO io, Clock clock) {
        Arrays.fill(redstoneIrqs, RedstoneIrqMode.DISABLED);
        this.memory = memory;
        this.io = io;
        if (io.activity() == null) io.activity(new dervogel101.de.i86.runtime.CpuActivity());
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
        submitRedstoneInput(channel, strength, true);
    }

    /** Reattachment samples input silently instead of replaying missed IRQs. */
    public void submitRedstoneInput(int channel, int strength, boolean notify) {
        if (channel < 0 || channel >= 128 || strength < 0 || strength > 15) {
            throw new IllegalArgumentException("Redstone channel or strength out of range");
        }
        pendingInputs.add(() -> {
            if (channel >= hardware.redstoneCapacity()) return;
            int previous = io.read8(channel * 2);
            io.setRedstoneInput(channel, strength);
            if (notify && redstoneIrqs[channel].triggers(previous, strength)) enqueueIrq(channel);
        });
    }

    public void submitSignClick(int sign, SignSide side) {
        if (sign < 0 || sign >= 128) throw new IllegalArgumentException("Sign index: " + sign);
        Objects.requireNonNull(side);
        pendingInputs.add(() -> {
            if (sign >= hardware.signCapacity()) return;
            io.clickSign(sign, side == SignSide.FRONT);
            if ((signIrqSides[sign] & (side == SignSide.FRONT ? 1 : 2)) != 0) enqueueIrq(0x100 | sign);
        });
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

    void configureHardware(HardwareProfile profile) {
        hardware = profile;
        if (!profile.has(Hardware.INTERRUPTS)) {
            activeIrq = -1;
            waitingIrqs.clear();
            irqOverflow = false;
        }
    }

    public void submitRedstoneIrqMode(int channel, RedstoneIrqMode mode) {
        if (channel < 0 || channel >= 128) throw new IllegalArgumentException("Redstone channel: " + channel);
        Objects.requireNonNull(mode);
        pendingInputs.add(() -> redstoneIrqs[channel] = mode);
    }

    public RedstoneIrqMode redstoneIrqMode(int channel) { return redstoneIrqs[channel]; }

    public void submitSignIrqSides(int sign, int sides) {
        if (sign < 0 || sign >= 128 || (sides & ~3) != 0) throw new IllegalArgumentException("Sign IRQ setting");
        pendingInputs.add(() -> signIrqSides[sign] = sides);
    }

    public int signIrqSides(int sign) { return signIrqSides[sign]; }

    private void enqueueIrq(int deviceId) {
        if (!hardware.has(Hardware.INTERRUPTS)) return;
        io.activity().mark(Hardware.INTERRUPTS, 1);
        if ((deviceId >>> 8) == 0) io.activity().redstoneIrq(deviceId & 255);
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
        submitResetPin(high, true);
    }

    /** A chunk reattachment resamples levels without synthesizing a missed edge. */
    public void submitResetPin(boolean high, boolean notify) {
        pendingInputs.add(() -> {
            if (notify && high && !resetPinHigh) resetPending = true;
            resetPinHigh = high;
        });
    }

    /** A manual NMI event leaves the external pin level intact. RESET still has priority. */
    public void submitNmiPulse() {
        pendingInputs.add(() -> { if (!resetPinHigh) { nmiPending = true; io.activity().mark(Hardware.NMI, 2); } });
    }

    public void submitNmiPin(boolean high) {
        submitNmiPin(high, true);
    }

    /** A chunk reattachment resamples levels without synthesizing a missed edge. */
    public void submitNmiPin(boolean high, boolean notify) {
        pendingInputs.add(() -> {
            if (notify && high && !nmiPinHigh && !resetPinHigh) { nmiPending = true; io.activity().mark(Hardware.NMI, 2); }
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

    /** Sample world time and advance game-tick timers once per host tick. */
    public void beforeTick() {
        beforeInstruction();
        io.keyboards.beforeTick(this::enqueueKeyboardIrq);
        advancePunchcardTick();
        io.setIrqStatus(activeIrq, irqOverflow);
        if (!hardware.has(Hardware.CLOCK)) return;
        io.advanceTickTimers(poweredOnGameTicks, timerIrqs);
        memory.updateTickClock(poweredOnGameTicks, dayTime);
        io.setIrqStatus(activeIrq, irqOverflow);
    }

    /** Sample real time and advance real-time timers once per execution batch. */
    public void beforeBatch() {
        beforeInstruction();
        if (!hardware.has(Hardware.CLOCK)) return;
        Instant now = clock.instant();
        io.advanceRealTimers(now.toEpochMilli(), timerIrqs);
        memory.updateRealtimeClock(LocalDateTime.ofInstant(now, clock.getZone()));
        io.setIrqStatus(activeIrq, irqOverflow);
    }
    /** Paused standalone debuggers still have a ticking mechanical transport. */
    public void servicePunchcardTick() {
        beforeInstruction(); advancePunchcardTick(); io.setIrqStatus(activeIrq,irqOverflow);
    }
    private void advancePunchcardTick() {
        if (!hasGameTime || poweredOnGameTicks != lastPunchcardTick) {
            lastPunchcardTick = poweredOnGameTicks; io.punchcards.beforeTick(this::enqueueIrq);
        }
    }

    /** Apply inputs and device commands between steps without sampling clocks. */
    public void beforeInstruction() {
        int commands = io.consumeIrqCommands();
        if ((commands & 4) != 0) irqOverflow = false;
        if ((commands & 1) != 0 && activeIrq >= 0) {
            io.activity().mark(Hardware.INTERRUPTS, 4);
            activeIrq = waitingIrqs.isEmpty() ? -1 : waitingIrqs.removeFirst();
        }
        Runnable input;
        while ((input = pendingInputs.poll()) != null) input.run();
        io.keyboards.beforeInstruction(this::enqueueKeyboardIrq);
        io.punchcards.beforeInstruction(this::enqueueIrq);
        io.serviceStorageCommand();
        io.sendTerminalIfPending(bytes -> { io.activity().mark(Hardware.TERMINAL, 1); appendTerminal(bytes); });
        io.setIrqStatus(activeIrq, irqOverflow);
    }

    private void enqueueKeyboardIrq(int id) {
        io.activity().mark(Hardware.KEYBOARD, 1);
        enqueueIrq(id);
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
    public void reportHostError(String message) {
        hostErrors.incrementAndGet();
        if (hardware.has(Hardware.TERMINAL)) appendTerminal(message.getBytes(WINDOWS_1252));
    }

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

    /** CPU worker only, after queued inputs have been applied at an instruction boundary. */
    void writeState(DataOutput out) throws IOException {
        out.writeInt(activeIrq); out.writeBoolean(irqOverflow); out.writeShort(waitingIrqs.size());
        for (int id : waitingIrqs) out.writeShort(id);
        for (boolean automatic : automaticTimerIrqs) out.writeBoolean(automatic);
        for (RedstoneIrqMode mode : redstoneIrqs) out.writeByte(mode.ordinal());
        for (int sides : signIrqSides) out.writeByte(sides);
        out.writeLong(lastGameTime); out.writeLong(poweredOnGameTicks); out.writeBoolean(hasGameTime); out.writeLong(dayTime);
        out.writeBoolean(testPinHigh); out.writeBoolean(resetPinHigh); out.writeBoolean(nmiPinHigh);
        out.writeBoolean(resetPending); out.writeBoolean(nmiPending); out.writeUTF(terminalText());
    }

    void readState(DataInput in) throws IOException {
        pendingInputs.clear(); waitingIrqs.clear();
        activeIrq = in.readInt(); irqOverflow = in.readBoolean(); int count = in.readUnsignedShort();
        if (activeIrq < -1 || activeIrq > 0xFFFF || count > 255 || (activeIrq < 0 && count != 0))
            throw new IOException("Invalid IRQ queue");
        for (int i = 0; i < count; i++) waitingIrqs.addLast(in.readUnsignedShort());
        for (int i = 0; i < automaticTimerIrqs.length; i++) automaticTimerIrqs[i] = in.readBoolean();
        for (int i = 0; i < redstoneIrqs.length; i++) {
            int mode = in.readUnsignedByte();
            if (mode >= RedstoneIrqMode.values().length) throw new IOException("Invalid redstone IRQ mode");
            redstoneIrqs[i] = RedstoneIrqMode.values()[mode];
        }
        for (int i = 0; i < signIrqSides.length; i++) {
            signIrqSides[i] = in.readUnsignedByte();
            if (signIrqSides[i] > 3) throw new IOException("Invalid sign IRQ sides");
        }
        lastGameTime = in.readLong(); poweredOnGameTicks = in.readLong(); hasGameTime = in.readBoolean(); dayTime = in.readLong();
        testPinHigh = in.readBoolean(); resetPinHigh = in.readBoolean(); nmiPinHigh = in.readBoolean();
        resetPending = in.readBoolean(); nmiPending = in.readBoolean(); String terminal = in.readUTF();
        if (terminal.length() > TERMINAL_CAPACITY) throw new IOException("Terminal log too large");
        synchronized (this) {
            terminalStart = 0; terminalLength = terminal.length(); terminal.getChars(0, terminalLength, terminalLog, 0);
        }
    }

}
