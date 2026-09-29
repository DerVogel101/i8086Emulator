package dervogel101.de;

import dervogel101.de.state.CPUIO;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.SharedMemoryRegion;
import dervogel101.de.state.CPURegister;
import dervogel101.de.instructions.CPUInstructions;

import java.time.Clock;

public class CPU8086 {
    public enum StepOutcome { INSTRUCTION, EVENT, IDLE }
    private final CPURegister registers;
    private final CPUMemory memory;
    private final CPUIO io;
    private final CpuHostBridge hostBridge;
    private final CPUInstructions instructions;
    private boolean halted;
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
        hostBridge = new CpuHostBridge(memory, io, clock);
        instructions = new CPUInstructions(this, registers, memory, io);
        resetCpu();
    }

    public CpuHostBridge hostBridge() {
        return hostBridge;
    }

    public void beforeInstruction() {
        hostBridge.beforeInstruction();
    }

    public StepOutcome step() {
        beforeInstruction();
        if (hostBridge.consumeReset()) {
            resetCpu();
            hostBridge.discardNmi();
            return StepOutcome.EVENT;
        }
        if (hostBridge.resetPinHigh()) return StepOutcome.IDLE;
        if (hostBridge.consumeNmi()) {
            halted = false;
            instructions.enterInterrupt(2);
            return StepOutcome.EVENT;
        }
        if (hostBridge.irqPending() && registers.getIF() && irqDelay == 0) {
            halted = false;
            instructions.enterInterrupt(0x20);
            return StepOutcome.EVENT;
        }
        if (halted) return StepOutcome.IDLE;
        int cs = registers.getCs();
        int ip = registers.getIp();
        halted = instructions.execute(fetch8(), cs, ip, hostBridge.testPinHigh());
        if (!instructions.waitStalled()) {
            if (instructions.interruptDelayRequested()) irqDelay = 1;
            else if (irqDelay > 0) irqDelay--;
        }
        return instructions.waitStalled() ? StepOutcome.IDLE : StepOutcome.INSTRUCTION;
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
}
