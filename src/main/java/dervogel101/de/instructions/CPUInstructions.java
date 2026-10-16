package dervogel101.de.instructions;

import dervogel101.de.CPU8086;
import dervogel101.de.state.CPUIO;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.CPURegister;

public final class CPUInstructions {
    private final CPU8086 cpu;
    private final CPURegister registers;
    private final CPUMemory memory;
    private final DataTransferInstructions dataTransfer;
    private final ArithmeticLogicInstructions arithmeticLogic;
    private final StringInstructions strings;
    private final StackControlFlowInstructions stackControlFlow;
    private final IoCpuControlInstructions ioCpuControl;
    private boolean interruptDelayRequested;
    private boolean waitStalled;
    private int yieldMode = -1;

    public CPUInstructions(CPU8086 cpu, CPURegister registers, CPUMemory memory, CPUIO io) {
        this.cpu = cpu;
        this.registers = registers;
        this.memory = memory;
        dataTransfer = new DataTransferInstructions(cpu, registers, memory);
        stackControlFlow = new StackControlFlowInstructions(cpu, registers, memory);
        arithmeticLogic = new ArithmeticLogicInstructions(cpu, registers, memory, stackControlFlow);
        strings = new StringInstructions(registers, memory, arithmeticLogic);
        ioCpuControl = new IoCpuControlInstructions(cpu, registers, io);
    }

    /** Returns true when the instruction halts the CPU. */
    public boolean execute(int opcode, int cs, int ip, boolean testPinHigh) {
        interruptDelayRequested = false;
        waitStalled = false;
        yieldMode = -1;
        int segmentOverride = -1;
        int repeatPrefix = -1;
        boolean lockPrefix = false;
        for (int prefixes = 0; isSegmentPrefix(opcode) || isRepeatPrefix(opcode) || opcode == 0xF0; prefixes++) {
            if (prefixes == 0xFFFF) throw unsupported(opcode, cs, ip);
            if (opcode == 0xF0) lockPrefix = true;
            else if (isRepeatPrefix(opcode)) repeatPrefix = opcode;
            else segmentOverride = switch (opcode) {
                case 0x26 -> registers.getEs();
                case 0x2E -> registers.getCs();
                case 0x36 -> registers.getSs();
                default -> registers.getDs();
            };
            opcode = cpu.fetch8();
        }
        if (opcode == 0x0F) {
            if (segmentOverride != -1 || repeatPrefix != -1 || lockPrefix) throw unsupported(opcode, cs, ip);
            int mode = cpu.fetch8();
            if (mode > 1) {
                throw new UnsupportedOperationException(
                        "Unsupported YIELD mode %02X at %04X:%04X".formatted(mode, cs, ip));
            }
            yieldMode = mode;
            return false;
        }
        if (lockPrefix && (repeatPrefix != -1 || !lockable(opcode))) throw unsupported(opcode, cs, ip);
        if (StringInstructions.handles(opcode)) {
            strings.execute(opcode, segmentOverride, repeatPrefix);
            return false;
        }
        if (repeatPrefix != -1) throw unsupported(opcode, cs, ip);
        if (opcode == 0x9B && testPinHigh) {
            registers.setIp(ip);
            waitStalled = true;
            return false;
        }
        if (DataTransferInstructions.handles(opcode)) {
            boolean movSs = opcode == 0x8E && ((memory.read8(registers.getCs(), registers.getIp()) >>> 3) & 7) == 2;
            dataTransfer.execute(opcode, segmentOverride, cs, ip);
            interruptDelayRequested = movSs;
            return false;
        }
        if (opcode == 0xFF) {
            Operands operand = Operands.decode(cpu, registers, memory, segmentOverride);
            if (operand.reg <= 1) arithmeticLogic.executeGroupFive(operand);
            else stackControlFlow.executeGroupFive(operand, cs, ip);
            return false;
        }
        if (ArithmeticLogicInstructions.handles(opcode)) {
            arithmeticLogic.execute(opcode, segmentOverride, cs, ip);
            return false;
        }
        if (stackControlFlow.execute(opcode, segmentOverride, cs, ip)) {
            interruptDelayRequested = opcode == 0x17;
            return false;
        }
        if (IoCpuControlInstructions.handles(opcode)) {
            boolean halted = ioCpuControl.execute(opcode);
            interruptDelayRequested = opcode == 0xFB;
            return halted;
        }
        throw unsupported(opcode, cs, ip);
    }

    public void enterInterrupt(int vector) {
        stackControlFlow.interrupt(vector);
    }

    public boolean interruptDelayRequested() { return interruptDelayRequested; }

    public boolean waitStalled() { return waitStalled; }

    public int yieldMode() { return yieldMode; }

    private boolean lockable(int opcode) {
        int modrm = memory.read8(registers.getCs(), registers.getIp());
        if ((modrm & 0xC0) == 0xC0) return false;
        int extension = (modrm >>> 3) & 7;
        if (opcode == 0x86 || opcode == 0x87) return true;
        if (opcode == 0x80 || opcode == 0x81 || opcode == 0x83) return extension != 7;
        if (opcode == 0xFE || opcode == 0xFF) return extension <= 1;
        if (opcode == 0xF6 || opcode == 0xF7) return extension == 2 || extension == 3;
        return opcode < 0x40 && (opcode & 7) <= 1 && (opcode & 0x38) != 0x38;
    }

    static UnsupportedOperationException unsupported(int opcode, int cs, int ip) {
        return new UnsupportedOperationException(
                "Unsupported opcode %02X at %04X:%04X".formatted(opcode, cs, ip));
    }

    private static boolean isSegmentPrefix(int opcode) {
        return opcode == 0x26 || opcode == 0x2E || opcode == 0x36 || opcode == 0x3E;
    }

    private static boolean isRepeatPrefix(int opcode) {
        return opcode == 0xF2 || opcode == 0xF3;
    }
}
