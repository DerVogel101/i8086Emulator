package dervogel101.de.instructions;

import dervogel101.de.CPU8086;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.CPURegister;

final class DataTransferInstructions {
    private final CPU8086 cpu;
    private final CPURegister registers;
    private final CPUMemory memory;

    DataTransferInstructions(CPU8086 cpu, CPURegister registers, CPUMemory memory) {
        this.cpu = cpu;
        this.registers = registers;
        this.memory = memory;
    }

    static boolean handles(int opcode) {
        if (opcode >= 0x90 && opcode <= 0x97 || opcode >= 0xA0 && opcode <= 0xA3
                || opcode >= 0xB0 && opcode <= 0xBF) return true;
        return switch (opcode) {
            case 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x8E,
                    0xC4, 0xC5, 0xC6, 0xC7, 0xD7 -> true;
            default -> false;
        };
    }

    void execute(int opcode, int segmentOverride, int cs, int ip) {
        if (opcode >= 0xB0 && opcode <= 0xB7) {
            Operands.setReg8(registers, opcode & 7, cpu.fetch8());
            return;
        }
        if (opcode >= 0xB8 && opcode <= 0xBF) {
            Operands.setReg16(registers, opcode & 7, cpu.fetch16());
            return;
        }
        if (opcode >= 0x90 && opcode <= 0x97) {
            int other = Operands.reg16(registers, opcode & 7);
            Operands.setReg16(registers, opcode & 7, registers.getAx());
            registers.setAx(other);
            return;
        }

        switch (opcode) {
            case 0x88, 0x89, 0x8A, 0x8B -> {
                Operands operand = operand(segmentOverride);
                boolean byteOperand = (opcode & 1) == 0;
                boolean registerDestination = (opcode & 2) != 0;
                if (byteOperand) {
                    if (registerDestination) Operands.setReg8(registers, operand.reg, operand.read8());
                    else operand.write8(Operands.reg8(registers, operand.reg));
                } else {
                    if (registerDestination) Operands.setReg16(registers, operand.reg, operand.read16());
                    else operand.write16(Operands.reg16(registers, operand.reg));
                }
            }
            case 0x8C, 0x8E -> {
                Operands operand = operand(segmentOverride);
                if (operand.reg > 3 || opcode == 0x8E && operand.reg == 1)
                    throw CPUInstructions.unsupported(opcode, cs, ip);
                if (opcode == 0x8C) operand.write16(Operands.segmentRegister(registers, operand.reg));
                else Operands.setSegmentRegister(registers, operand.reg, operand.read16());
            }
            case 0xA0, 0xA1, 0xA2, 0xA3 -> {
                int offset = cpu.fetch16();
                int segment = segmentOverride >= 0 ? segmentOverride : registers.getDs();
                switch (opcode) {
                    case 0xA0 -> Operands.setReg8(registers, 0, memory.read8(segment, offset));
                    case 0xA1 -> registers.setAx(memory.read16(segment, offset));
                    case 0xA2 -> memory.write8(segment, offset, Operands.reg8(registers, 0));
                    case 0xA3 -> memory.write16(segment, offset, registers.getAx());
                    default -> throw new AssertionError();
                }
            }
            case 0xC6, 0xC7 -> {
                Operands operand = operand(segmentOverride);
                if (operand.reg != 0) throw CPUInstructions.unsupported(opcode, cs, ip);
                if (opcode == 0xC6) operand.write8(cpu.fetch8());
                else operand.write16(cpu.fetch16());
            }
            case 0x86, 0x87 -> {
                Operands operand = operand(segmentOverride);
                if (opcode == 0x86) {
                    int fromMemory = operand.read8();
                    operand.write8(Operands.reg8(registers, operand.reg));
                    Operands.setReg8(registers, operand.reg, fromMemory);
                } else {
                    int fromMemory = operand.read16();
                    operand.write16(Operands.reg16(registers, operand.reg));
                    Operands.setReg16(registers, operand.reg, fromMemory);
                }
            }
            case 0x8D -> {
                Operands operand = operand(segmentOverride);
                if (!operand.isMemory()) throw CPUInstructions.unsupported(opcode, cs, ip);
                Operands.setReg16(registers, operand.reg, operand.offset());
            }
            case 0xC4, 0xC5 -> {
                Operands operand = operand(segmentOverride);
                if (!operand.isMemory()) throw CPUInstructions.unsupported(opcode, cs, ip);
                int offset = memory.read16(operand.segment(), operand.offset());
                int segment = memory.read16(operand.segment(), (operand.offset() + 2) & 0xFFFF);
                Operands.setReg16(registers, operand.reg, offset);
                if (opcode == 0xC4) registers.setEs(segment);
                else registers.setDs(segment);
            }
            case 0xD7 -> {
                int segment = segmentOverride >= 0 ? segmentOverride : registers.getDs();
                int offset = (registers.getBx() + Operands.reg8(registers, 0)) & 0xFFFF;
                Operands.setReg8(registers, 0, memory.read8(segment, offset));
            }
            default -> throw CPUInstructions.unsupported(opcode, cs, ip);
        }
    }

    private Operands operand(int segmentOverride) {
        return Operands.decode(cpu, registers, memory, segmentOverride);
    }
}
