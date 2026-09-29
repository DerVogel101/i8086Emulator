package dervogel101.de.instructions;

import dervogel101.de.CPU8086;
import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.CPURegister;

final class Operands {
    private final CPURegister registers;
    private final CPUMemory memory;
    private final int mod;
    private final int rm;
    private final int segment;
    private final int offset;
    final int reg;

    private Operands(CPURegister registers, CPUMemory memory, int mod, int reg, int rm,
                     int segment, int offset) {
        this.registers = registers;
        this.memory = memory;
        this.mod = mod;
        this.reg = reg;
        this.rm = rm;
        this.segment = segment;
        this.offset = offset;
    }

    static Operands decode(CPU8086 cpu, CPURegister registers, CPUMemory memory, int segmentOverride) {
        int modrm = cpu.fetch8();
        int mod = modrm >>> 6;
        int reg = (modrm >>> 3) & 7;
        int rm = modrm & 7;
        if (mod == 3) return new Operands(registers, memory, mod, reg, rm, 0, 0);

        int base = switch (rm) {
            case 0 -> registers.getBx() + registers.getSi();
            case 1 -> registers.getBx() + registers.getDi();
            case 2 -> registers.getBp() + registers.getSi();
            case 3 -> registers.getBp() + registers.getDi();
            case 4 -> registers.getSi();
            case 5 -> registers.getDi();
            case 6 -> mod == 0 ? 0 : registers.getBp();
            default -> registers.getBx();
        };
        int displacement = switch (mod) {
            case 0 -> rm == 6 ? cpu.fetch16() : 0;
            case 1 -> (byte) cpu.fetch8();
            default -> (short) cpu.fetch16();
        };
        boolean usesBp = rm == 2 || rm == 3 || (rm == 6 && mod != 0);
        int segment = segmentOverride >= 0 ? segmentOverride
                : usesBp ? registers.getSs() : registers.getDs();
        return new Operands(registers, memory, mod, reg, rm, segment, (base + displacement) & 0xFFFF);
    }

    boolean isMemory() {
        return mod != 3;
    }

    int rm() {
        return rm;
    }

    int segment() {
        return segment;
    }

    int offset() {
        return offset;
    }

    int read8() {
        return isMemory() ? memory.read8(segment, offset) : reg8(registers, rm);
    }

    int read16() {
        return isMemory() ? memory.read16(segment, offset) : reg16(registers, rm);
    }

    void write8(int value) {
        if (isMemory()) memory.write8(segment, offset, value);
        else setReg8(registers, rm, value);
    }

    void write16(int value) {
        if (isMemory()) memory.write16(segment, offset, value);
        else setReg16(registers, rm, value);
    }

    static int reg8(CPURegister r, int code) {
        return (reg16(r, code & 3) >>> ((code & 4) == 0 ? 0 : 8)) & 0xFF;
    }

    static void setReg8(CPURegister r, int code, int value) {
        int register = code & 3;
        int old = reg16(r, register);
        setReg16(r, register, (code & 4) == 0
                ? (old & 0xFF00) | (value & 0xFF)
                : (old & 0x00FF) | ((value & 0xFF) << 8));
    }

    static int reg16(CPURegister r, int code) {
        return switch (code) {
            case 0 -> r.getAx();
            case 1 -> r.getCx();
            case 2 -> r.getDx();
            case 3 -> r.getBx();
            case 4 -> r.getSp();
            case 5 -> r.getBp();
            case 6 -> r.getSi();
            case 7 -> r.getDi();
            default -> throw new IllegalArgumentException("Register: " + code);
        };
    }

    static void setReg16(CPURegister r, int code, int value) {
        switch (code) {
            case 0 -> r.setAx(value);
            case 1 -> r.setCx(value);
            case 2 -> r.setDx(value);
            case 3 -> r.setBx(value);
            case 4 -> r.setSp(value);
            case 5 -> r.setBp(value);
            case 6 -> r.setSi(value);
            case 7 -> r.setDi(value);
            default -> throw new IllegalArgumentException("Register: " + code);
        }
    }

    static int segmentRegister(CPURegister r, int code) {
        return switch (code) {
            case 0 -> r.getEs();
            case 1 -> r.getCs();
            case 2 -> r.getSs();
            case 3 -> r.getDs();
            default -> throw new IllegalArgumentException("Segment register: " + code);
        };
    }

    static void setSegmentRegister(CPURegister r, int code, int value) {
        switch (code) {
            case 0 -> r.setEs(value);
            case 2 -> r.setSs(value);
            case 3 -> r.setDs(value);
            default -> throw new IllegalArgumentException("Segment register: " + code);
        }
    }
}
