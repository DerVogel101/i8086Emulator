package dervogel101.de.instructions;

import dervogel101.de.CPU8086;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.CPURegister;

final class StackControlFlowInstructions {
    private final CPU8086 cpu;
    private final CPURegister registers;
    private final CPUMemory memory;

    StackControlFlowInstructions(CPU8086 cpu, CPURegister registers, CPUMemory memory) {
        this.cpu = cpu;
        this.registers = registers;
        this.memory = memory;
    }

    boolean execute(int opcode, int segmentOverride, int cs, int ip) {
        if (opcode >= 0x50 && opcode <= 0x57) {
            int code = opcode & 7;
            push(code == 4 ? (registers.getSp() - 2) & 0xFFFF
                    : Operands.reg16(registers, code));
            return true;
        }
        if (opcode >= 0x58 && opcode <= 0x5F) {
            Operands.setReg16(registers, opcode & 7, pop());
            return true;
        }
        if (opcode >= 0x70 && opcode <= 0x7F) {
            int displacement = (byte) cpu.fetch8();
            if (condition(opcode & 0x0F)) jumpRelative(displacement);
            return true;
        }
        if (opcode >= 0xE0 && opcode <= 0xE3) {
            int displacement = (byte) cpu.fetch8();
            if (opcode == 0xE3) {
                if (registers.getCx() == 0) jumpRelative(displacement);
            } else {
                registers.setCx(registers.getCx() - 1);
                if (registers.getCx() != 0 && (opcode == 0xE2
                        || opcode == 0xE1 && registers.getZF()
                        || opcode == 0xE0 && !registers.getZF())) jumpRelative(displacement);
            }
            return true;
        }
        switch (opcode) {
            case 0x06, 0x0E, 0x16, 0x1E -> {
                int segment = switch (opcode) {
                    case 0x06 -> registers.getEs();
                    case 0x0E -> registers.getCs();
                    case 0x16 -> registers.getSs();
                    default -> registers.getDs();
                };
                push(segment);
            }
            case 0x07 -> registers.setEs(pop());
            case 0x17 -> registers.setSs(pop());
            case 0x1F -> registers.setDs(pop());
            case 0x8F -> {
                Operands operand = Operands.decode(cpu, registers, memory, segmentOverride);
                if (operand.reg != 0) throw CPUInstructions.unsupported(opcode, cs, ip);
                operand.write16(pop());
            }
            case 0x9A -> {
                int offset = cpu.fetch16();
                int segment = cpu.fetch16();
                callFar(segment, offset);
            }
            case 0x9C -> push(registers.getFlags());
            case 0x9D -> registers.setFlags(pop());
            case 0xC2, 0xC3 -> {
                int cleanup = opcode == 0xC2 ? cpu.fetch16() : 0;
                registers.setIp(pop());
                registers.setSp(registers.getSp() + cleanup);
            }
            case 0xCA, 0xCB -> {
                int cleanup = opcode == 0xCA ? cpu.fetch16() : 0;
                registers.setIp(pop());
                registers.setCs(pop());
                registers.setSp(registers.getSp() + cleanup);
            }
            case 0xCC -> interrupt(3);
            case 0xCD -> interrupt(cpu.fetch8());
            case 0xCE -> {
                if (registers.getOF()) interrupt(4);
            }
            case 0xCF -> {
                int offset = pop();
                int segment = pop();
                int flags = pop();
                registers.setIp(offset);
                registers.setCs(segment);
                registers.setFlags(flags);
            }
            case 0xE8 -> {
                int displacement = (short) cpu.fetch16();
                push(registers.getIp());
                jumpRelative(displacement);
            }
            case 0xE9 -> jumpRelative((short) cpu.fetch16());
            case 0xEA -> {
                int offset = cpu.fetch16();
                int segment = cpu.fetch16();
                registers.setCs(segment);
                registers.setIp(offset);
            }
            case 0xEB -> jumpRelative((byte) cpu.fetch8());
            default -> {
                return false;
            }
        }
        return true;
    }

    void executeGroupFive(Operands operand, int cs, int ip) {
        switch (operand.reg) {
            case 2 -> {
                int target = operand.read16();
                push(registers.getIp());
                registers.setIp(target);
            }
            case 3 -> {
                if (!operand.isMemory()) throw CPUInstructions.unsupported(0xFF, cs, ip);
                callFar(farSegment(operand), farOffset(operand));
            }
            case 4 -> registers.setIp(operand.read16());
            case 5 -> {
                if (!operand.isMemory()) throw CPUInstructions.unsupported(0xFF, cs, ip);
                int offset = farOffset(operand);
                int segment = farSegment(operand);
                registers.setCs(segment);
                registers.setIp(offset);
            }
            case 6 -> push(!operand.isMemory() && operand.rm() == 4
                    ? (registers.getSp() - 2) & 0xFFFF : operand.read16());
            default -> throw CPUInstructions.unsupported(0xFF, cs, ip);
        }
    }

    private void callFar(int segment, int offset) {
        push(registers.getCs());
        push(registers.getIp());
        registers.setCs(segment);
        registers.setIp(offset);
    }

    private int farOffset(Operands operand) {
        return memory.read16(operand.segment(), operand.offset());
    }

    private int farSegment(Operands operand) {
        return memory.read16(operand.segment(), (operand.offset() + 2) & 0xFFFF);
    }

    private void jumpRelative(int displacement) {
        registers.setIp(registers.getIp() + displacement);
    }

    private boolean condition(int code) {
        boolean carry = registers.getCF();
        boolean zero = registers.getZF();
        boolean sign = registers.getSF();
        boolean overflow = registers.getOF();
        return switch (code) {
            case 0 -> overflow;
            case 1 -> !overflow;
            case 2 -> carry;
            case 3 -> !carry;
            case 4 -> zero;
            case 5 -> !zero;
            case 6 -> carry || zero;
            case 7 -> !carry && !zero;
            case 8 -> sign;
            case 9 -> !sign;
            case 10 -> registers.getPF();
            case 11 -> !registers.getPF();
            case 12 -> sign != overflow;
            case 13 -> sign == overflow;
            case 14 -> zero || sign != overflow;
            case 15 -> !zero && sign == overflow;
            default -> throw new AssertionError();
        };
    }

    private int pop() {
        int value = memory.read16(registers.getSs(), registers.getSp());
        registers.setSp(registers.getSp() + 2);
        return value;
    }

    void interrupt(int vector) {
        push(registers.getFlags());
        push(registers.getCs());
        push(registers.getIp());
        registers.setIF(false);
        registers.setTF(false);
        registers.setIp(memory.read16(0, vector * 4));
        registers.setCs(memory.read16(0, vector * 4 + 2));
    }

    private void push(int value) {
        registers.setSp(registers.getSp() - 2);
        memory.write16(registers.getSs(), registers.getSp(), value);
    }
}
