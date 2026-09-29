package dervogel101.de.instructions;

import dervogel101.de.CPU8086;
import dervogel101.de.state.CPUIO;
import dervogel101.de.state.CPURegister;

final class IoCpuControlInstructions {
    private final CPU8086 cpu;
    private final CPURegister registers;
    private final CPUIO io;

    IoCpuControlInstructions(CPU8086 cpu, CPURegister registers, CPUIO io) {
        this.cpu = cpu;
        this.registers = registers;
        this.io = io;
    }

    static boolean handles(int opcode) {
        if (opcode >= 0xE4 && opcode <= 0xE7
                || opcode >= 0xEC && opcode <= 0xEF) return true;
        return switch (opcode) {
            case 0x9B, 0x9E, 0x9F, 0xF4, 0xF5, 0xF8, 0xF9, 0xFA, 0xFB, 0xFC, 0xFD -> true;
            default -> false;
        };
    }

    /** Returns true only for HLT. */
    boolean execute(int opcode) {
        if (opcode >= 0xE4 && opcode <= 0xE7 || opcode >= 0xEC && opcode <= 0xEF) {
            int port = opcode >= 0xEC ? registers.getDx() : cpu.fetch8();
            boolean word = (opcode & 1) != 0;
            if ((opcode & 2) == 0) {
                int value = word ? io.read16(port) : io.read8(port);
                registers.setAx(word ? value : (registers.getAx() & 0xFF00) | value);
            } else {
                if (word) io.write16(port, registers.getAx());
                else io.write8(port, registers.getAx());
            }
            return false;
        }
        switch (opcode) {
            case 0x9B -> { } // No coprocessor busy signal.
            case 0x9E -> registers.setFlags((registers.getFlags() & ~0xD5)
                    | ((registers.getAx() >>> 8) & 0xD5));
            case 0x9F -> registers.setAx((registers.getAx() & 0x00FF)
                    | (((registers.getFlags() & 0xD5) | 0x02) << 8));
            case 0xF4 -> { return true; }
            case 0xF5 -> registers.setCF(!registers.getCF());
            case 0xF8 -> registers.setCF(false);
            case 0xF9 -> registers.setCF(true);
            case 0xFA -> registers.setIF(false);
            case 0xFB -> registers.setIF(true);
            case 0xFC -> registers.setDF(false);
            case 0xFD -> registers.setDF(true);
            default -> throw new IllegalArgumentException("CPU control opcode: " + opcode);
        }
        return false;
    }
}
