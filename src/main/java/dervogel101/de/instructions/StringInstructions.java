package dervogel101.de.instructions;

import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.CPURegister;

final class StringInstructions {
    private final CPURegister registers;
    private final CPUMemory memory;
    private final ArithmeticLogicInstructions arithmeticLogic;

    StringInstructions(CPURegister registers, CPUMemory memory, ArithmeticLogicInstructions arithmeticLogic) {
        this.registers = registers;
        this.memory = memory;
        this.arithmeticLogic = arithmeticLogic;
    }

    static boolean handles(int opcode) {
        return opcode >= 0xA4 && opcode <= 0xA7 || opcode >= 0xAA && opcode <= 0xAF;
    }

    void execute(int opcode, int segmentOverride, int repeatPrefix) {
        int family = opcode & 0xFE;
        int width = (opcode & 1) + 1;
        int sourceSegment = segmentOverride == -1 ? registers.getDs() : segmentOverride;
        int count = repeatPrefix == -1 ? 1 : registers.getCx();

        while (count > 0) {
            int si = registers.getSi();
            int di = registers.getDi();
            int accumulator = width == 1 ? registers.getAx() & 0xFF : registers.getAx();
            switch (family) {
                case 0xA4 -> write(registers.getEs(), di, width, read(sourceSegment, si, width));
                case 0xA6 -> arithmeticLogic.compare(read(sourceSegment, si, width),
                        read(registers.getEs(), di, width), width * 8);
                case 0xAA -> write(registers.getEs(), di, width, accumulator);
                case 0xAC -> {
                    int value = read(sourceSegment, si, width);
                    registers.setAx(width == 1 ? (registers.getAx() & 0xFF00) | value : value);
                }
                case 0xAE -> arithmeticLogic.compare(accumulator, read(registers.getEs(), di, width), width * 8);
                default -> throw new IllegalArgumentException("String opcode: " + opcode);
            }

            int delta = registers.getDF() ? -width : width;
            if (family == 0xA4 || family == 0xA6 || family == 0xAC) registers.setSi((si + delta) & 0xFFFF);
            if (family != 0xAC) registers.setDi((di + delta) & 0xFFFF);

            if (repeatPrefix == -1) break;
            registers.setCx(--count);
            if ((family == 0xA6 || family == 0xAE) && registers.getZF() != (repeatPrefix == 0xF3)) break;
        }
    }

    private int read(int segment, int offset, int width) {
        return width == 1 ? memory.read8(segment, offset) : memory.read16(segment, offset);
    }

    private void write(int segment, int offset, int width, int value) {
        if (width == 1) memory.write8(segment, offset, value);
        else memory.write16(segment, offset, value);
    }
}
