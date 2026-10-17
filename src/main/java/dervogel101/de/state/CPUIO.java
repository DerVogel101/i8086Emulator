package dervogel101.de.state;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.i86.runtime.HardwareProfile;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class CPUIO {
    private static final int SIGN_FIRST = 0x0100;
    private static final int SIGN_LAST = 0x80FF;
    private static final int SIGN_COUNT = 128;
    private static final int TIMER_FIRST = 0x8100;
    private static final int TIMER_LAST = 0x812F;
    private static final int TERMINAL_FIRST = 0x8130;
    private static final int TERMINAL_CONTROL = 0x8150;
    private static final int STORAGE_FIRST = 0x8160;
    private static final int STORAGE_CONTROL = 0x8164;
    private static final int IRQ_FIRST = 0xFF00;
    private static final int[] REAL_RESOLUTIONS = {1, 5, 10, 50};
    private static final int[] GAME_RESOLUTIONS = {1, 5, 10, 20};

    private HardwareProfile hardware = HardwareProfile.complete();
    private dervogel101.de.i86.runtime.CpuActivity activity;
    public void activity(dervogel101.de.i86.runtime.CpuActivity value) { activity = value; }
    public dervogel101.de.i86.runtime.CpuActivity activity() { return activity; }
    private final byte[] ports = new byte[1 << 16];
    private final BitSet changedRedstone = new BitSet(128);
    private final BitSet changedSigns = new BitSet(SIGN_COUNT);
    private int irqId;
    private boolean irqPending;
    private boolean irqOverflow;
    private int irqCommands;
    private IoStorageDevice storageDevice;
    private int storageData;
    private int storageAddress;
    private int storageCommands;
    private final Timer[] timers = new Timer[6];
    private long lastRealMillis = Long.MIN_VALUE;
    private long lastGameTicks = Long.MIN_VALUE;

    public CPUIO() {
        for (int i = 0; i < timers.length; i++) timers[i] = new Timer();
    }

    public synchronized void configureHardware(HardwareProfile profile) {
        hardware = profile;
    }

    private boolean available(int port) {
        if (port < SIGN_FIRST) return (port >>> 1) < hardware.redstoneCapacity();
        if (port <= SIGN_LAST) return ((port - SIGN_FIRST) >>> 8) < hardware.signCapacity();
        if (port >= TIMER_FIRST && port <= TIMER_LAST) return hardware.has(Hardware.CLOCK);
        if (port >= TERMINAL_FIRST && port <= TERMINAL_CONTROL) return hardware.has(Hardware.TERMINAL);
        if (port >= STORAGE_FIRST && port <= STORAGE_CONTROL) return hardware.has(Hardware.DISK);
        if (port >= IRQ_FIRST) return hardware.has(Hardware.INTERRUPTS);
        return false;
    }

    public int readGuest8(int port) {
        int value = read8(port);
        if (activity != null && available(port & 0xffff)) activity.readIo(port & 0xffff);
        return value;
    }
    public int readGuest16(int port) { return readGuest8(port) | readGuest8(port + 1) << 8; }
    public void writeGuest8(int port, int value) {
        write8(port, value);
        if (activity != null && available(port & 0xffff)) activity.writeIo(port & 0xffff);
    }
    public void writeGuest16(int port, int value) { writeGuest8(port, value); writeGuest8(port + 1, value >>> 8); }

    public synchronized int read8(int port) {
        port &= 0xFFFF;
        if (!available(port)) return 0;
        if (port == IRQ_FIRST) return irqPending ? irqId & 0xFF : 0;
        if (port == IRQ_FIRST + 1) return irqPending ? irqId >>> 8 : 0;
        if (port == IRQ_FIRST + 2) return (irqPending ? 2 : 0) | (irqOverflow ? 4 : 0);
        if (port >= TIMER_FIRST && port <= TIMER_LAST) {
            Timer timer = timers[(port - TIMER_FIRST) >>> 3];
            return switch ((port - TIMER_FIRST) & 7) {
                case 0 -> timer.countdown & 0xFF;
                case 1 -> timer.countdown >>> 8;
                case 2 -> timer.wait & 0xFF;
                case 3 -> timer.wait >>> 8;
                case 4 -> timer.control;
                default -> 0;
            };
        }
        if (port >= TERMINAL_FIRST && port <= TERMINAL_CONTROL)
            return Byte.toUnsignedInt(ports[port]);
        if (port >= STORAGE_FIRST && port <= STORAGE_CONTROL) {
            return switch (port - STORAGE_FIRST) {
                case 0 -> storageData & 0xFF;
                case 1 -> storageData >>> 8;
                case 2 -> storageAddress & 0xFF;
                case 3 -> storageAddress >>> 8;
                default -> storageCommands | (storageDevice == null ? 0 : 4);
            };
        }
        if (port >= SIGN_FIRST && port <= SIGN_LAST && (port & 0xFF) >= 0x82) return 0;
        if (port > SIGN_LAST) return 0;
        return Byte.toUnsignedInt(ports[port]);
    }

    public synchronized int read16(int port) {
        return read8(port) | (read8(port + 1) << 8);
    }

    public synchronized void write8(int port, int value) {
        port &= 0xFFFF;
        if (!available(port)) return;
        if (port == IRQ_FIRST + 2) {
            irqCommands |= value & 5;
            return;
        }
        if (port >= TIMER_FIRST && port <= TIMER_LAST) {
            Timer timer = timers[(port - TIMER_FIRST) >>> 3];
            switch ((port - TIMER_FIRST) & 7) {
                case 2 -> timer.wait = (timer.wait & 0xFF00) | (value & 0xFF);
                case 3 -> timer.wait = (timer.wait & 0x00FF) | ((value & 0xFF) << 8);
                case 4 -> {
                    if ((value & 8) != 0) {
                        timer.wait = timer.countdown = timer.control = timer.remainder = 0;
                    } else {
                        int previous = timer.control;
                        timer.control = value & 0x37;
                        if ((previous & 1) == 0 && (timer.control & 1) != 0) timer.countdown = timer.wait;
                        if ((previous & 1) == 0 && (timer.control & 1) != 0
                                || (previous & 0x30) != (timer.control & 0x30)) timer.remainder = 0;
                    }
                }
                default -> { } // Countdown and reserved bytes are read-only.
            }
            return;
        }
        if (port >= TERMINAL_FIRST && port < TERMINAL_CONTROL) {
            ports[port] = (byte) value;
            return;
        }
        if (port == TERMINAL_CONTROL) {
            ports[port] |= (byte) (value & 1);
            return;
        }
        if (port >= STORAGE_FIRST && port <= STORAGE_CONTROL) {
            switch (port - STORAGE_FIRST) {
                case 0 -> storageData = (storageData & 0xFF00) | (value & 0xFF);
                case 1 -> storageData = (storageData & 0x00FF) | ((value & 0xFF) << 8);
                case 2 -> storageAddress = (storageAddress & 0xFF00) | (value & 0xFF);
                case 3 -> storageAddress = (storageAddress & 0x00FF) | ((value & 0xFF) << 8);
                case 4 -> storageCommands |= value & 3;
                default -> { }
            }
            return;
        }
        if (port < SIGN_FIRST) {
            if ((port & 1) != 0) {
                int output = value & 0x0F;
                if (Byte.toUnsignedInt(ports[port]) != output) {
                    ports[port] = (byte) output;
                    changedRedstone.set(port >>> 1);
                }
            }
        } else if (port <= SIGN_LAST) {
            int offset = port & 0xFF;
            if (offset < 0x80) {
                if (ports[port] != (byte) value) {
                    ports[port] = (byte) value;
                    changedSigns.set((port - SIGN_FIRST) >>> 8);
                }
            } else if (offset == 0x81) {
                // Zero bits acknowledge only the corresponding pending click flags.
                ports[port] &= (byte) (value & 0x07);
            }
        }
    }

    public synchronized void write16(int port, int value) {
        write8(port, value);
        write8(port + 1, value >>> 8);
    }

    /** CPU-thread snapshot; commands from OUT are applied at the next instruction boundary. */
    public synchronized int consumeIrqCommands() {
        int commands = irqCommands;
        irqCommands = 0;
        return commands;
    }

    public synchronized void setStorageDevice(IoStorageDevice device) {
        storageDevice = device;
    }

    /** Executes a pending command after host connection changes at the instruction boundary. */
    public synchronized void serviceStorageCommand() {
        int commands = storageCommands;
        storageCommands = 0;
        if (storageDevice == null || !hardware.has(Hardware.DISK)) return;
        if ((commands & 1) != 0) {
            storageData = storageDevice.readWord(storageAddress);
            if (activity != null) activity.mark(Hardware.DISK, 1);
        } else if ((commands & 2) != 0) {
            storageDevice.writeWord(storageAddress, storageData);
            if (activity != null) activity.mark(Hardware.DISK, 2);
        }
    }

    public synchronized IoStorageDevice.Snapshot snapshotStorageIfDirty() {
        return storageDevice == null ? null : storageDevice.snapshotIfDirty();
    }

    public synchronized void acknowledgeStorageSaved(long version) {
        if (storageDevice == null) throw new IllegalStateException("I/O storage is disconnected");
        storageDevice.acknowledgeSaved(version);
    }

    public synchronized void setIrqStatus(int activeId, boolean overflow) {
        irqPending = activeId >= 0;
        irqId = activeId;
        irqOverflow = overflow;
    }

    /** Copies a pending send before clearing the character field and send bit. */
    public synchronized void sendTerminalIfPending(Consumer<byte[]> receiver) {
        if ((ports[TERMINAL_CONTROL] & 1) == 0) return;
        receiver.accept(Arrays.copyOfRange(ports, TERMINAL_FIRST, TERMINAL_CONTROL));
        Arrays.fill(ports, TERMINAL_FIRST, TERMINAL_CONTROL + 1, (byte) 0);
    }

    @FunctionalInterface
    public interface IrqSink {
        void queue(int deviceId, long count);
    }

    /** Advance both timer groups; hosts normally use the separate tick/batch hooks. */
    public synchronized void advanceTimers(long realMillis, long gameTicks, IrqSink irqSink) {
        advanceRealTimers(realMillis, irqSink);
        advanceTickTimers(gameTicks, irqSink);
    }

    public synchronized void advanceRealTimers(long realMillis, IrqSink irqSink) {
        if (!hardware.has(Hardware.CLOCK)) return;
        long realElapsed = lastRealMillis == Long.MIN_VALUE || realMillis < lastRealMillis
                ? 0 : realMillis - lastRealMillis;
        lastRealMillis = realMillis;
        advanceTimers(0, 3, realElapsed, irqSink);
    }

    public synchronized void advanceTickTimers(long gameTicks, IrqSink irqSink) {
        if (!hardware.has(Hardware.CLOCK)) return;
        long gameElapsed = lastGameTicks == Long.MIN_VALUE || gameTicks < lastGameTicks
                ? 0 : gameTicks - lastGameTicks;
        lastGameTicks = gameTicks;
        advanceTimers(3, 6, gameElapsed, irqSink);
    }

    private void advanceTimers(int first, int end, long elapsed, IrqSink irqSink) {
        for (int i = first; i < end; i++) {
            Timer timer = timers[i];
            if ((timer.control & 1) == 0 || timer.countdown == 0) continue;
            int resolution = (i < 3 ? REAL_RESOLUTIONS : GAME_RESOLUTIONS)[(timer.control >>> 4) & 3];
            long units = elapsed / resolution;
            timer.remainder += (int) (elapsed % resolution);
            if (timer.remainder >= resolution) {
                timer.remainder -= resolution;
                units++;
            }
            if (units < timer.countdown) {
                timer.countdown -= (int) units;
                continue;
            }
            units -= timer.countdown;
            long expirations = 1;
            if ((timer.control & 2) != 0 && timer.wait != 0) {
                expirations += units / timer.wait;
                timer.countdown = timer.wait - (int) (units % timer.wait);
            } else {
                timer.countdown = 0;
                if ((timer.control & 2) == 0) timer.control &= ~1;
            }
            if (activity != null) activity.mark(Hardware.CLOCK, 4);
            if ((timer.control & 4) != 0) irqSink.queue(((i < 3 ? 2 : 3) << 8) | (i % 3), expirations);
        }
    }

    private static final class Timer {
        int countdown;
        int wait;
        int control;
        int remainder;
    }

    public synchronized void setRedstoneInput(int channel, int strength) {
        checkIndex(channel);
        if (strength < 0 || strength > 15) throw new IllegalArgumentException("Redstone strength: " + strength);
        if (channel < hardware.redstoneCapacity()) ports[channel * 2] = (byte) strength;
    }

    public synchronized void clickSign(int sign, boolean front) {
        checkIndex(sign);
        if (sign >= hardware.signCapacity()) return;
        ports[SIGN_FIRST + sign * 0x100 + 0x81] |= (byte) (front ? 0x03 : 0x05);
    }

    public synchronized OutputChanges drainChangedOutputs() {
        Map<Integer, Integer> redstone = new HashMap<>();
        for (int channel = changedRedstone.nextSetBit(0); channel >= 0; channel = changedRedstone.nextSetBit(channel + 1)) {
            redstone.put(channel, read8(channel * 2 + 1));
        }
        Map<Integer, SignSnapshot> signs = new HashMap<>();
        for (int sign = changedSigns.nextSetBit(0); sign >= 0; sign = changedSigns.nextSetBit(sign + 1)) {
            int base = SIGN_FIRST + sign * 0x100;
            signs.put(sign, new SignSnapshot(Arrays.copyOfRange(ports, base, base + 0x80)));
        }
        changedRedstone.clear();
        changedSigns.clear();
        return new OutputChanges(Map.copyOf(redstone), Map.copyOf(signs));
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= SIGN_COUNT) throw new IllegalArgumentException("Device index: " + index);
    }

    public record OutputChanges(Map<Integer, Integer> redstone, Map<Integer, SignSnapshot> signs) {}

    public record SignSnapshot(byte[] bytes) {
        public SignSnapshot {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public synchronized IoStorageDevice storageDevice() { return storageDevice; }

    public synchronized void writeState(DataOutput out) throws IOException {
        out.write(ports);
        for (int i = 0; i < 128; i++) { out.writeBoolean(changedRedstone.get(i)); out.writeBoolean(changedSigns.get(i)); }
        out.writeInt(irqId); out.writeBoolean(irqPending); out.writeBoolean(irqOverflow); out.writeInt(irqCommands);
        out.writeShort(storageData); out.writeShort(storageAddress); out.writeByte(storageCommands);
        out.writeBoolean(storageDevice != null);
        if (storageDevice != null) storageDevice.writeState(out);
        out.writeLong(lastRealMillis); out.writeLong(lastGameTicks);
        for (Timer timer : timers) {
            out.writeShort(timer.countdown); out.writeShort(timer.wait); out.writeByte(timer.control); out.writeByte(timer.remainder);
        }
    }

    public synchronized void readState(DataInput in) throws IOException {
        in.readFully(ports); changedRedstone.clear(); changedSigns.clear();
        for (int i = 0; i < 128; i++) { changedRedstone.set(i, in.readBoolean()); changedSigns.set(i, in.readBoolean()); }
        irqId = in.readInt(); irqPending = in.readBoolean(); irqOverflow = in.readBoolean(); irqCommands = in.readInt();
        storageData = in.readUnsignedShort(); storageAddress = in.readUnsignedShort(); storageCommands = in.readUnsignedByte();
        if (irqId < -1 || irqId > 0xFFFF || (irqCommands & ~5) != 0 || (storageCommands & ~3) != 0)
            throw new IOException("Invalid I/O state");
        storageDevice = in.readBoolean() ? new IoStorageDevice() : null;
        if (storageDevice != null) storageDevice.readState(in);
        lastRealMillis = in.readLong(); lastGameTicks = in.readLong();
        for (Timer timer : timers) {
            timer.countdown = in.readUnsignedShort(); timer.wait = in.readUnsignedShort();
            timer.control = in.readUnsignedByte(); timer.remainder = in.readUnsignedByte();
            if ((timer.control & ~0x37) != 0 || timer.remainder >= 50) throw new IOException("Invalid timer state");
        }
    }

}
