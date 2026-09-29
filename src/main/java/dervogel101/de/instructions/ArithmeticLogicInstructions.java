package dervogel101.de.instructions;

import dervogel101.de.CPU8086;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.CPURegister;

final class ArithmeticLogicInstructions {
    private final CPU8086 cpu;
    private final CPURegister registers;
    private final CPUMemory memory;
    private final StackControlFlowInstructions controlFlow;

    ArithmeticLogicInstructions(CPU8086 cpu, CPURegister registers, CPUMemory memory,
                                StackControlFlowInstructions controlFlow) {
        this.cpu = cpu;
        this.registers = registers;
        this.memory = memory;
        this.controlFlow = controlFlow;
    }

    static boolean handles(int opcode) {
        if ((opcode <= 0x3D && (opcode & 7) <= 5) || (opcode >= 0x40 && opcode <= 0x4F)
                || (opcode >= 0xD0 && opcode <= 0xD5)) return true;
        return switch (opcode) {
            case 0x27, 0x2F, 0x37, 0x3F, 0x80, 0x81, 0x83, 0x84, 0x85,
                    0x98, 0x99, 0xA8, 0xA9, 0xF6, 0xF7, 0xFE -> true;
            default -> false;
        };
    }

    void execute(int opcode, int segmentOverride, int cs, int ip) {
        if (opcode <= 0x3D && (opcode & 7) <= 5) {
            binaryOpcode(opcode, segmentOverride);
            return;
        }
        if (opcode >= 0x40 && opcode <= 0x4F) {
            int code = opcode & 7;
            Operands.setReg16(registers, code,
                    incDec(Operands.reg16(registers, code), 16, opcode >= 0x48));
            return;
        }
        switch (opcode) {
            case 0x80, 0x81, 0x83 -> {
                Operands operand = operand(segmentOverride);
                int bits = opcode == 0x80 ? 8 : 16;
                int immediate = opcode == 0x81 ? cpu.fetch16()
                        : opcode == 0x83 ? (byte) cpu.fetch8() : cpu.fetch8();
                int result = operate(operand.reg, read(operand, bits), immediate, bits);
                if (operand.reg != 7) write(operand, bits, result);
            }
            case 0x84, 0x85 -> {
                Operands operand = operand(segmentOverride);
                int bits = opcode == 0x84 ? 8 : 16;
                operate(4, read(operand, bits), register(operand.reg, bits), bits);
            }
            case 0xA8, 0xA9 -> {
                int bits = opcode == 0xA8 ? 8 : 16;
                operate(4, register(0, bits), bits == 8 ? cpu.fetch8() : cpu.fetch16(), bits);
            }
            case 0xFE -> {
                Operands operand = operand(segmentOverride);
                if (operand.reg > 1) throw CPUInstructions.unsupported(opcode, cs, ip);
                write(operand, 8, incDec(operand.read8(), 8, operand.reg == 1));
            }
            case 0xF6, 0xF7 -> groupThree(opcode, segmentOverride, cs, ip);
            case 0xD0, 0xD1, 0xD2, 0xD3 -> shiftRotate(opcode, segmentOverride, cs, ip);
            case 0x27, 0x2F -> decimalAdjust(opcode == 0x2F);
            case 0x37, 0x3F -> asciiAdjust(opcode == 0x3F);
            case 0xD4 -> aam();
            case 0xD5 -> aad();
            case 0x98 -> registers.setAx((byte) Operands.reg8(registers, 0));
            case 0x99 -> registers.setDx((short) registers.getAx() < 0 ? 0xFFFF : 0);
            default -> throw CPUInstructions.unsupported(opcode, cs, ip);
        }
    }

    void executeGroupFive(Operands operand) {
        operand.write16(incDec(operand.read16(), 16, operand.reg == 1));
    }

    private void binaryOpcode(int opcode, int segmentOverride) {
        int operation = (opcode >>> 3) & 7;
        int form = opcode & 7;
        int bits = (form & 1) == 0 ? 8 : 16;
        if (form >= 4) {
            int result = operate(operation, register(0, bits),
                    bits == 8 ? cpu.fetch8() : cpu.fetch16(), bits);
            if (operation != 7) setRegister(0, bits, result);
            return;
        }
        Operands operand = operand(segmentOverride);
        boolean registerDestination = (form & 2) != 0;
        int left = registerDestination ? register(operand.reg, bits) : read(operand, bits);
        int right = registerDestination ? read(operand, bits) : register(operand.reg, bits);
        int result = operate(operation, left, right, bits);
        if (operation != 7) {
            if (registerDestination) setRegister(operand.reg, bits, result);
            else write(operand, bits, result);
        }
    }

    void compare(int left, int right, int bits) {
        operate(7, left, right, bits);
    }

    // Operation codes follow the /reg field: ADD, OR, ADC, SBB, AND, SUB, XOR, CMP.
    private int operate(int operation, int left, int right, int bits) {
        int mask = bits == 8 ? 0xFF : 0xFFFF;
        int sign = bits == 8 ? 0x80 : 0x8000;
        left &= mask;
        right &= mask;
        int result;
        if (operation == 0 || operation == 2) {
            int carry = operation == 2 && registers.getCF() ? 1 : 0;
            int full = left + right + carry;
            result = full & mask;
            registers.setCF(full > mask);
            registers.setOF(((~(left ^ right) & (left ^ result)) & sign) != 0);
            registers.setAF(((left ^ right ^ result) & 0x10) != 0);
        } else if (operation == 3 || operation == 5 || operation == 7) {
            int borrow = operation == 3 && registers.getCF() ? 1 : 0;
            result = (left - right - borrow) & mask;
            registers.setCF(left < right + borrow);
            registers.setOF((((left ^ right) & (left ^ result)) & sign) != 0);
            registers.setAF(((left ^ right ^ result) & 0x10) != 0);
        } else {
            result = switch (operation) {
                case 1 -> left | right;
                case 4 -> left & right;
                case 6 -> left ^ right;
                default -> throw new IllegalArgumentException("ALU operation: " + operation);
            };
            registers.setCF(false);
            registers.setOF(false);
        }
        setSignZeroParity(result, bits);
        return result;
    }

    private int incDec(int value, int bits, boolean decrement) {
        boolean carry = registers.getCF();
        int result = operate(decrement ? 5 : 0, value, 1, bits);
        registers.setCF(carry);
        return result;
    }

    private void groupThree(int opcode, int segmentOverride, int cs, int ip) {
        Operands operand = operand(segmentOverride);
        int bits = opcode == 0xF6 ? 8 : 16;
        switch (operand.reg) {
            case 0 -> operate(4, read(operand, bits), bits == 8 ? cpu.fetch8() : cpu.fetch16(), bits);
            case 1 -> throw CPUInstructions.unsupported(opcode, cs, ip);
            case 2 -> write(operand, bits, ~read(operand, bits));
            case 3 -> write(operand, bits, operate(5, 0, read(operand, bits), bits));
            case 4 -> multiply(read(operand, bits), bits, false);
            case 5 -> multiply(read(operand, bits), bits, true);
            case 6 -> divide(read(operand, bits), bits, false);
            case 7 -> divide(read(operand, bits), bits, true);
            default -> throw new AssertionError();
        }
    }

    private void multiply(int operand, int bits, boolean signed) {
        if (bits == 8) {
            int product = signed ? (byte) Operands.reg8(registers, 0) * (byte) operand
                    : Operands.reg8(registers, 0) * operand;
            registers.setAx(product);
            boolean overflow = signed ? product < -128 || product > 127 : (product & 0xFF00) != 0;
            registers.setCF(overflow);
            registers.setOF(overflow);
        } else {
            long product = signed ? (long) (short) registers.getAx() * (short) operand
                    : (long) registers.getAx() * operand;
            registers.setAx((int) product);
            registers.setDx((int) (product >>> 16));
            boolean overflow = signed ? product < -32768 || product > 32767 : (product >>> 16) != 0;
            registers.setCF(overflow);
            registers.setOF(overflow);
        }
    }

    private void divide(int operand, int bits, boolean signed) {
        long divisor = signed ? bits == 8 ? (byte) operand : (short) operand : operand;
        if (divisor == 0) {
            controlFlow.interrupt(0);
            return;
        }
        long dividend = bits == 8 ? signed ? (short) registers.getAx() : registers.getAx()
                : signed ? (int) ((registers.getDx() << 16) | registers.getAx())
                : ((long) registers.getDx() << 16) | registers.getAx();
        long quotient = dividend / divisor;
        long limit = bits == 8 ? 0xFF : 0xFFFF;
        if (signed ? quotient < -(limit + 1) / 2 || quotient > limit / 2
                : quotient > limit) {
            controlFlow.interrupt(0);
            return;
        }
        long remainder = dividend % divisor;
        if (bits == 8) registers.setAx(((int) remainder << 8) | ((int) quotient & 0xFF));
        else {
            registers.setAx((int) quotient);
            registers.setDx((int) remainder);
        }
    }

    private void shiftRotate(int opcode, int segmentOverride, int cs, int ip) {
        Operands operand = operand(segmentOverride);
        if (operand.reg == 6) throw CPUInstructions.unsupported(opcode, cs, ip);
        int bits = (opcode & 1) == 0 ? 8 : 16;
        int count = (opcode & 2) == 0 ? 1 : Operands.reg8(registers, 1);
        if (count == 0) return;
        int mask = bits == 8 ? 0xFF : 0xFFFF;
        int sign = bits == 8 ? 0x80 : 0x8000;
        int value = read(operand, bits);
        boolean originalSign = (value & sign) != 0;
        boolean originalCarry = registers.getCF();
        for (int i = 0; i < count; i++) {
            boolean carry;
            switch (operand.reg) {
                case 0 -> { // ROL
                    carry = (value & sign) != 0;
                    value = ((value << 1) & mask) | (carry ? 1 : 0);
                }
                case 1 -> { // ROR
                    carry = (value & 1) != 0;
                    value = (value >>> 1) | (carry ? sign : 0);
                }
                case 2 -> { // RCL
                    carry = (value & sign) != 0;
                    value = ((value << 1) & mask) | (registers.getCF() ? 1 : 0);
                }
                case 3 -> { // RCR
                    carry = (value & 1) != 0;
                    value = (value >>> 1) | (registers.getCF() ? sign : 0);
                }
                case 4 -> { // SHL/SAL
                    carry = (value & sign) != 0;
                    value = (value << 1) & mask;
                }
                case 5 -> { // SHR
                    carry = (value & 1) != 0;
                    value >>>= 1;
                }
                case 7 -> { // SAR
                    carry = (value & 1) != 0;
                    value = (value >>> 1) | (value & sign);
                }
                default -> throw new AssertionError();
            }
            registers.setCF(carry);
        }
        write(operand, bits, value);
        if (operand.reg >= 4) setSignZeroParity(value, bits);
        if (operand.reg >= 4 && count >= bits) registers.setCF(originalCarry);
        if (count == 1) {
            boolean top = (value & sign) != 0;
            boolean next = (value & (sign >>> 1)) != 0;
            registers.setOF(switch (operand.reg) {
                case 0, 2, 4 -> top != registers.getCF();
                case 1, 3 -> top != next;
                case 5 -> originalSign;
                case 7 -> false;
                default -> throw new AssertionError();
            });
        }
    }

    private void decimalAdjust(boolean subtract) {
        int original = Operands.reg8(registers, 0);
        boolean oldCarry = registers.getCF();
        int value = original;
        if ((original & 0x0F) > 9 || registers.getAF()) {
            value = (value + (subtract ? -6 : 6)) & 0xFF;
            registers.setAF(true);
        } else registers.setAF(false);
        if (original > 0x99 || oldCarry) {
            value = (value + (subtract ? -0x60 : 0x60)) & 0xFF;
            registers.setCF(true);
        } else registers.setCF(false);
        Operands.setReg8(registers, 0, value);
        setSignZeroParity(value, 8);
    }

    private void asciiAdjust(boolean subtract) {
        int ax = registers.getAx();
        if ((ax & 0x0F) > 9 || registers.getAF()) {
            ax += subtract ? -0x106 : 0x106;
            registers.setAF(true);
            registers.setCF(true);
        } else {
            registers.setAF(false);
            registers.setCF(false);
        }
        registers.setAx((ax & 0xFF00) | (ax & 0x0F));
    }

    private void aam() {
        int base = cpu.fetch8();
        if (base == 0) {
            controlFlow.interrupt(0);
            return;
        }
        int al = Operands.reg8(registers, 0);
        registers.setAx(((al / base) << 8) | (al % base));
        setSignZeroParity(al % base, 8);
    }

    private void aad() {
        int base = cpu.fetch8();
        int value = (Operands.reg8(registers, 4) * base + Operands.reg8(registers, 0)) & 0xFF;
        registers.setAx(value);
        setSignZeroParity(value, 8);
    }

    private void setSignZeroParity(int result, int bits) {
        int mask = bits == 8 ? 0xFF : 0xFFFF;
        registers.setSF((result & (bits == 8 ? 0x80 : 0x8000)) != 0);
        registers.setZF((result & mask) == 0);
        registers.setPF((Integer.bitCount(result & 0xFF) & 1) == 0);
    }

    private Operands operand(int segmentOverride) {
        return Operands.decode(cpu, registers, memory, segmentOverride);
    }

    private int register(int code, int bits) {
        return bits == 8 ? Operands.reg8(registers, code) : Operands.reg16(registers, code);
    }

    private void setRegister(int code, int bits, int value) {
        if (bits == 8) Operands.setReg8(registers, code, value);
        else Operands.setReg16(registers, code, value);
    }

    private static int read(Operands operand, int bits) {
        return bits == 8 ? operand.read8() : operand.read16();
    }

    private static void write(Operands operand, int bits, int value) {
        if (bits == 8) operand.write8(value);
        else operand.write16(value);
    }
}
