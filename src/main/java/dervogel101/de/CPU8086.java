package dervogel101.de;

import dervogel101.de.state.CPUIO;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.SharedMemoryRegion;
import dervogel101.de.state.CPURegister;
import dervogel101.de.instructions.CPUInstructions;

import java.time.Clock;
import java.io.*;
import java.util.EnumMap;
import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.i86.runtime.HardwareProfile;

public class CPU8086 {
    public enum StepOutcome { INSTRUCTION, EVENT, IDLE, YIELD_BATCH, YIELD_TICK }
    private final CPURegister registers;
    private final CPUMemory memory;
    private final CPUIO io;
    private final CpuHostBridge hostBridge;
    private final CPUInstructions instructions;
    private HardwareProfile hardware = HardwareProfile.complete();
    private boolean halted;
    private final dervogel101.de.i86.runtime.CpuActivity activity = new dervogel101.de.i86.runtime.CpuActivity();
    private int irqDelay;

    public CPU8086() {
        this(Clock.systemDefaultZone(), new SharedMemoryRegion());
    }

    public CPU8086(SharedMemoryRegion sharedMemory) {
        this(Clock.systemDefaultZone(), sharedMemory);
    }

    CPU8086(Clock clock) {
        this(clock, new SharedMemoryRegion());
    }

    CPU8086(Clock clock, SharedMemoryRegion sharedMemory) {
        registers = new CPURegister();
        memory = new CPUMemory(sharedMemory);
        io = new CPUIO();
        memory.activity(activity); io.activity(activity);
        hostBridge = new CpuHostBridge(memory, io, clock);
        instructions = new CPUInstructions(this, registers, memory, io);
        resetCpu();
    }

    /** Host must change installed hardware only while powered off, on the CPU worker. */
    public void configureHardware(HardwareProfile profile) {
        if (profile.validationError().isPresent()) throw new IllegalArgumentException(profile.validationError().get());
        hardware = profile;
        memory.configureHardware(profile);
        io.configureHardware(profile);
        hostBridge.configureHardware(profile);
    }

    public HardwareProfile hardware() { return hardware; }
    public dervogel101.de.i86.runtime.CpuActivity activity() { return activity; }

    public CpuHostBridge hostBridge() {
        return hostBridge;
    }

    /** Worker-only manual reset, preserving externally held pin levels and device state. */
    public void reset() {
        beforeInstruction();
        hostBridge.consumeReset();
        activity.mark(Hardware.RESET, 2);
        resetCpu();
        activity.mark(Hardware.RESET, 4);
        activity.waiting(false);
        hostBridge.discardNmi();
    }

    /** Worker-only host tick boundary, including powered-on paused CPUs. */
    public void beforeTick() {
        hostBridge.beforeTick();
    }

    /** Worker-only boundary before a scheduled batch or manual debugger step. */
    public void beforeBatch() {
        hostBridge.beforeBatch();
    }

    public void beforeInstruction() {
        hostBridge.beforeInstruction();
    }

    public StepOutcome step() {
        activity.executing(true);
        try { return stepGuest(); }
        finally { activity.executing(false); }
    }

    private StepOutcome stepGuest() {
        beforeInstruction();
        if (hostBridge.consumeReset()) {
            activity.mark(Hardware.RESET, 2);
            resetCpu();
            activity.mark(Hardware.RESET, 4);
            activity.waiting(false);
            hostBridge.discardNmi();
            return StepOutcome.EVENT;
        }
        if (hostBridge.resetPinHigh()) return StepOutcome.IDLE;
        if (hostBridge.consumeNmi()) {
            halted = false;
            activity.mark(Hardware.NMI, 4); activity.waiting(false);
            instructions.enterInterrupt(2);
            return StepOutcome.EVENT;
        }
        if (hostBridge.irqPending() && registers.getIF() && irqDelay == 0) {
            halted = false;
            activity.mark(Hardware.INTERRUPTS, 2); activity.waiting(false);
            instructions.enterInterrupt(0x20);
            return StepOutcome.EVENT;
        }
        if (halted) return StepOutcome.IDLE;
        int cs = registers.getCs();
        int ip = registers.getIp();
        halted = instructions.execute(fetch8(), cs, ip, hostBridge.testPinHigh());
        activity.waiting(instructions.waitStalled());
        if (!instructions.waitStalled()) {
            if (instructions.interruptDelayRequested()) irqDelay = 1;
            else if (irqDelay > 0) irqDelay--;
        }
        return switch (instructions.yieldMode()) {
            case 0 -> StepOutcome.YIELD_BATCH;
            case 1 -> StepOutcome.YIELD_TICK;
            default -> instructions.waitStalled() ? StepOutcome.IDLE : StepOutcome.INSTRUCTION;
        };
    }

    private void resetCpu() {
        registers.setAx(0);
        registers.setBx(0);
        registers.setCx(0);
        registers.setDx(0);
        registers.setSp(0);
        registers.setBp(0);
        registers.setSi(0);
        registers.setDi(0);
        registers.setDs(0);
        registers.setEs(0);
        registers.setSs(0);
        registers.setCs(0xFFFF);
        registers.setIp(0);
        registers.setFlags(0);
        halted = false;
        irqDelay = 0;
    }

    public boolean isHalted() {
        return halted;
    }

    public CPURegister registers() {
        return registers;
    }

    public CPUMemory memory() {
        return memory;
    }

    CPUIO ioForTest() {
        return io;
    }

    public CPUIO io() {
        return io;
    }

    /** Debugger-only control; call on the CPU thread. */
    public void jumpTo(int cs, int ip) {
        registers.setCs(cs);
        registers.setIp(ip);
        halted = false;
    }

    public int fetch8() {
        int value = memory.read8(registers.getCs(), registers.getIp());
        registers.setIp((registers.getIp() + 1) & 0xFFFF);
        return value;
    }

    public int fetch16() {
        int lo = fetch8();
        int hi = fetch8();
        return lo | (hi << 8);
    }

    /** Call on the owning worker between steps. Shared regions are saved once by the host. */
    public byte[] saveState() throws IOException {
        beforeInstruction();
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(0x49383653); out.writeInt(3);
            for (Hardware device : Hardware.values()) out.writeByte(hardware.count(device));
            out.writeShort(registers.getAx());
            out.writeShort(registers.getBx());
            out.writeShort(registers.getCx());
            out.writeShort(registers.getDx());
            out.writeShort(registers.getSp());
            out.writeShort(registers.getBp());
            out.writeShort(registers.getSi());
            out.writeShort(registers.getDi());
            out.writeShort(registers.getCs());
            out.writeShort(registers.getDs());
            out.writeShort(registers.getSs());
            out.writeShort(registers.getEs());
            out.writeShort(registers.getIp());
            out.writeShort(registers.getFlags());
            out.writeBoolean(halted); out.writeByte(irqDelay);
            memory.writeState(out); io.writeState(out); hostBridge.writeState(out);
        }
        return buffer.toByteArray();
    }

    public static CPU8086 restoreState(byte[] state, SharedMemoryRegion sharedMemory) throws IOException {
        return restoreState(state, Clock.systemDefaultZone(), sharedMemory);
    }

    public static CPU8086 restoreState(byte[] state, Clock clock, SharedMemoryRegion sharedMemory) throws IOException {
        if (state.length > 2 * 1024 * 1024) throw new IOException("CPU state exceeds 2 MiB");
        try (var in = new DataInputStream(new ByteArrayInputStream(state))) {
            if (in.readInt() != 0x49383653) throw new IOException("Unsupported CPU state format");
            int version = in.readInt();
            if (version < 1 || version > 3) throw new IOException("Unsupported CPU state format");
            var counts = new EnumMap<Hardware, Integer>(Hardware.class);
            for (Hardware device : Hardware.values())
                if ((version >= 2 || device != Hardware.KEYBOARD) && (version >= 3 || device != Hardware.PUNCHCARD)) counts.put(device, in.readUnsignedByte());
            CPU8086 next = new CPU8086(clock, sharedMemory);
            next.configureHardware(new HardwareProfile(counts));
            next.registers.setAx(in.readUnsignedShort());
            next.registers.setBx(in.readUnsignedShort());
            next.registers.setCx(in.readUnsignedShort());
            next.registers.setDx(in.readUnsignedShort());
            next.registers.setSp(in.readUnsignedShort());
            next.registers.setBp(in.readUnsignedShort());
            next.registers.setSi(in.readUnsignedShort());
            next.registers.setDi(in.readUnsignedShort());
            next.registers.setCs(in.readUnsignedShort());
            next.registers.setDs(in.readUnsignedShort());
            next.registers.setSs(in.readUnsignedShort());
            next.registers.setEs(in.readUnsignedShort());
            next.registers.setIp(in.readUnsignedShort());
            next.registers.setFlags(in.readUnsignedShort());
            next.halted = in.readBoolean(); next.irqDelay = in.readUnsignedByte();
            if (next.irqDelay > 1) throw new IOException("Invalid interrupt shadow");
            next.memory.readState(in); next.io.readState(in); next.hostBridge.readState(in);
            if (in.read() != -1) throw new IOException("Trailing CPU state data");
            return next;
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid CPU state", error);
        }
    }
}
