package dervogel101.de;

import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StringInstructionsTest {
    @Test
    void movsCopiesBytesAndWordsWithSourceOverrideAndDirection() {
        CPU8086 bytes = boot(0x3E, 0x36, 0xA4);
        bytes.registers().setSi(0xFFFF);
        bytes.registers().setDi(0xFFFF);
        bytes.registers().setCx(7);
        bytes.registers().setFlags(0x0001);
        bytes.memory().write8(0x1000, 0xFFFF, 0x11);
        bytes.memory().write8(0x3000, 0xFFFF, 0x22);
        bytes.step();
        assertEquals(0x22, bytes.memory().read8(0x2000, 0xFFFF));
        assertEquals(0, bytes.registers().getSi());
        assertEquals(0, bytes.registers().getDi());
        assertEquals(7, bytes.registers().getCx());
        assertEquals(0x0001, bytes.registers().getFlags());
        assertEquals(3, bytes.registers().getIp());

        CPU8086 words = boot(0xA5);
        words.registers().setDF(true);
        words.memory().write16(0x1000, 0, 0xBEEF);
        int flags = words.registers().getFlags();
        words.step();
        assertEquals(0xBEEF, words.memory().read16(0x2000, 0));
        assertEquals(0xFFFE, words.registers().getSi());
        assertEquals(0xFFFE, words.registers().getDi());
        assertEquals(flags, words.registers().getFlags());
    }

    @Test
    void cmpsSubtractsSourceMinusDestinationForBothWidths() {
        CPU8086 bytes = boot(0x36, 0xA6);
        bytes.registers().setSi(0x10);
        bytes.registers().setDi(0x20);
        bytes.memory().write8(0x1000, 0x10, 0x99);
        bytes.memory().write8(0x3000, 0x10, 0x10);
        bytes.memory().write8(0x2000, 0x20, 0x20);
        bytes.step();
        assertTrue(bytes.registers().getCF());
        assertFalse(bytes.registers().getZF());
        assertEquals(0x11, bytes.registers().getSi());
        assertEquals(0x21, bytes.registers().getDi());

        CPU8086 words = boot(0xA7);
        words.registers().setDF(true);
        words.memory().write16(0x1000, 0, 0x1234);
        words.memory().write16(0x2000, 0, 0x1234);
        words.step();
        assertTrue(words.registers().getZF());
        assertFalse(words.registers().getCF());
        assertEquals(0xFFFE, words.registers().getSi());
        assertEquals(0xFFFE, words.registers().getDi());
    }

    @Test
    void stosAlwaysWritesEsAndOnlyMovesDi() {
        CPU8086 bytes = boot(0x36, 0xAA);
        bytes.registers().setAx(0xAB7C);
        bytes.registers().setSi(0x1234);
        bytes.registers().setDi(0xFFFF);
        bytes.registers().setFlags(0x0201);
        bytes.memory().write8(0x3000, 0xFFFF, 0x99);
        bytes.step();
        assertEquals(0x7C, bytes.memory().read8(0x2000, 0xFFFF));
        assertEquals(0x99, bytes.memory().read8(0x3000, 0xFFFF));
        assertEquals(0, bytes.registers().getDi());
        assertEquals(0x1234, bytes.registers().getSi());
        assertEquals(0x0201, bytes.registers().getFlags());

        CPU8086 words = boot(0xAB);
        words.registers().setAx(0x4321);
        words.registers().setDF(true);
        words.step();
        assertEquals(0x4321, words.memory().read16(0x2000, 0));
        assertEquals(0xFFFE, words.registers().getDi());
    }

    @Test
    void lodsPreservesAhForBytesAndLoadsWholeWord() {
        CPU8086 bytes = boot(0x36, 0xAC);
        bytes.registers().setAx(0xAB00);
        bytes.registers().setSi(0xFFFF);
        bytes.registers().setDi(0x1234);
        bytes.memory().write8(0x1000, 0xFFFF, 0x56);
        bytes.memory().write8(0x3000, 0xFFFF, 0x34);
        bytes.step();
        assertEquals(0xAB34, bytes.registers().getAx());
        assertEquals(0, bytes.registers().getSi());
        assertEquals(0x1234, bytes.registers().getDi());

        CPU8086 words = boot(0xAD);
        words.registers().setDF(true);
        words.memory().write16(0x1000, 0, 0xBEEF);
        words.step();
        assertEquals(0xBEEF, words.registers().getAx());
        assertEquals(0xFFFE, words.registers().getSi());
    }

    @Test
    void scasSubtractsEsDestinationFromAccumulator() {
        CPU8086 bytes = boot(0x36, 0xAE);
        bytes.registers().setAx(0xAB10);
        bytes.registers().setSi(0x1234);
        bytes.memory().write8(0x3000, 0, 0x10);
        bytes.memory().write8(0x2000, 0, 0x20);
        bytes.step();
        assertTrue(bytes.registers().getCF());
        assertFalse(bytes.registers().getZF());
        assertEquals(1, bytes.registers().getDi());
        assertEquals(0x1234, bytes.registers().getSi());

        CPU8086 words = boot(0xAF);
        words.registers().setAx(0x1234);
        words.registers().setDF(true);
        words.memory().write16(0x2000, 0, 0x1234);
        words.step();
        assertTrue(words.registers().getZF());
        assertEquals(0xFFFE, words.registers().getDi());
    }

    @Test
    void bothRepeatPrefixesRunMovsStosAndLodsToCompletionInOneStep() {
        for (int prefix : new int[]{0xF2, 0xF3}) {
            for (int opcode : new int[]{0xA4, 0xA5, 0xAA, 0xAB, 0xAC, 0xAD}) {
                CPU8086 cpu = boot(prefix, opcode);
                int width = (opcode & 1) + 1;
                cpu.registers().setAx(0x7A5C);
                cpu.registers().setSi(0x100);
                cpu.registers().setDi(0x200);
                cpu.registers().setCx(3);
                cpu.registers().setFlags(0x0041);
                for (int i = 0; i < 3; i++) write(cpu, 0x1000, 0x100 + i * width, width,
                        width == 1 ? 0x31 + i : 0x1122 + i);
                cpu.step();
                assertEquals(0, cpu.registers().getCx());
                assertEquals(2, cpu.registers().getIp());
                assertEquals(0x0041, cpu.registers().getFlags());
                int family = opcode & 0xFE;
                assertEquals(family == 0xAA ? 0x100 : 0x100 + 3 * width, cpu.registers().getSi());
                assertEquals(family == 0xAC ? 0x200 : 0x200 + 3 * width, cpu.registers().getDi());
                if (family == 0xA4 || family == 0xAA) {
                    assertEquals(family == 0xA4 ? (width == 1 ? 0x33 : 0x1124) :
                            (width == 1 ? 0x5C : 0x7A5C), read(cpu, 0x2000, 0x200 + 2 * width, width));
                } else {
                    assertEquals(width == 1 ? 0x7A33 : 0x1124, cpu.registers().getAx());
                }
            }
        }
    }

    @Test
    void repeAndRepneStopComparisonsAfterTheElementThatChangesZf() {
        for (int prefix : new int[]{0xF2, 0xF3}) {
            for (int opcode : new int[]{0xA6, 0xA7, 0xAE, 0xAF}) {
                CPU8086 cpu = boot(prefix, opcode);
                int width = (opcode & 1) + 1;
                int value = width == 1 ? 0x20 : 0x2020;
                int other = width == 1 ? 0x30 : 0x3030;
                cpu.registers().setAx(value);
                cpu.registers().setSi(0x100);
                cpu.registers().setDi(0x200);
                cpu.registers().setCx(3);
                for (int i = 0; i < 3; i++) {
                    write(cpu, 0x1000, 0x100 + i * width, width, value);
                    write(cpu, 0x2000, 0x200 + i * width, width,
                            i == 0 ? (prefix == 0xF3 ? value : other) : (prefix == 0xF3 ? other : value));
                }
                cpu.step();
                assertEquals(1, cpu.registers().getCx());
                assertEquals(0x200 + 2 * width, cpu.registers().getDi());
                assertEquals(opcode < 0xAE ? 0x100 + 2 * width : 0x100, cpu.registers().getSi());
                assertEquals(prefix == 0xF2, cpu.registers().getZF());
                assertEquals(prefix == 0xF3, cpu.registers().getCF());
                assertEquals(2, cpu.registers().getIp());
            }
        }

        CPU8086 equal = boot(0xF3, 0xA6);
        equal.registers().setCx(2);
        equal.memory().write8(0x1000, 0, 0x42);
        equal.memory().write8(0x1000, 1, 0x42);
        equal.memory().write8(0x2000, 0, 0x42);
        equal.memory().write8(0x2000, 1, 0x42);
        equal.step();
        assertEquals(0, equal.registers().getCx());
        assertTrue(equal.registers().getZF());

        CPU8086 unequal = boot(0xF2, 0xAE);
        unequal.registers().setCx(2);
        unequal.registers().setAx(0x10);
        unequal.memory().write8(0x2000, 0, 0x20);
        unequal.memory().write8(0x2000, 1, 0x20);
        unequal.step();
        assertEquals(0, unequal.registers().getCx());
        assertFalse(unequal.registers().getZF());
    }

    @Test
    void zeroCountSkipsEveryStringOpcodeWithoutChangingState() {
        for (int prefix : new int[]{0xF2, 0xF3}) {
            for (int opcode : new int[]{0xA4, 0xA5, 0xA6, 0xA7, 0xAA, 0xAB, 0xAC, 0xAD, 0xAE, 0xAF}) {
                CPU8086 cpu = boot(prefix, opcode);
                cpu.registers().setCx(0);
                cpu.registers().setSi(0x1111);
                cpu.registers().setDi(0x2222);
                cpu.registers().setAx(0xBEEF);
                cpu.registers().setFlags(0x0455);
                cpu.memory().write16(0x2000, 0x2222, 0x1234);
                cpu.step();
                assertEquals(0, cpu.registers().getCx());
                assertEquals(0x1111, cpu.registers().getSi());
                assertEquals(0x2222, cpu.registers().getDi());
                assertEquals(0xBEEF, cpu.registers().getAx());
                assertEquals(0x0455, cpu.registers().getFlags());
                assertEquals(0x1234, cpu.memory().read16(0x2000, 0x2222));
                assertEquals(2, cpu.registers().getIp());
            }
        }
    }

    @Test
    void mixedPrefixesWorkInEitherOrderAndTheLastOfEachKindWins() {
        for (int[] program : new int[][]{{0xF3, 0x36, 0xA4}, {0x36, 0xF3, 0xA4}}) {
            CPU8086 cpu = boot(program);
            cpu.registers().setCx(2);
            cpu.memory().write8(0x1000, 0, 0x11);
            cpu.memory().write8(0x3000, 0, 0x22);
            cpu.memory().write8(0x3000, 1, 0x33);
            cpu.step();
            assertEquals(0x22, cpu.memory().read8(0x2000, 0));
            assertEquals(0x33, cpu.memory().read8(0x2000, 1));
            assertEquals(0, cpu.registers().getCx());
            assertEquals(3, cpu.registers().getIp());
        }

        CPU8086 segment = boot(0x36, 0x3E, 0xA4);
        segment.memory().write8(0x1000, 0, 0x44);
        segment.memory().write8(0x3000, 0, 0x55);
        segment.step();
        assertEquals(0x44, segment.memory().read8(0x2000, 0));

        for (int[] program : new int[][]{{0xF2, 0xF3, 0xA6}, {0xF3, 0xF2, 0xA6}}) {
            CPU8086 cpu = boot(program);
            cpu.registers().setCx(3);
            cpu.memory().write8(0x1000, 0, 0x10);
            cpu.memory().write8(0x1000, 1, 0x10);
            cpu.memory().write8(0x2000, 0, 0x10);
            cpu.memory().write8(0x2000, 1, 0x20);
            cpu.step();
            assertEquals(program[1] == 0xF3 ? 1 : 2, cpu.registers().getCx());
            assertEquals(3, cpu.registers().getIp());
        }
    }

    @Test
    void csAndEsCanOverrideStringSources() {
        int[] program = new int[0x11];
        program[0] = 0x2E;
        program[1] = 0xAC;
        program[0x10] = 0x6A;
        CPU8086 cs = boot(program);
        cs.registers().setSi(0x10);
        cs.step();
        assertEquals(0x6A, cs.registers().getAx() & 0xFF);

        CPU8086 es = boot(0x26, 0xA4);
        es.registers().setSi(0x10);
        es.registers().setDi(0x20);
        es.memory().write8(0x1000, 0x10, 0x11);
        es.memory().write8(0x2000, 0x10, 0x22);
        es.step();
        assertEquals(0x22, es.memory().read8(0x2000, 0x20));
    }

    @Test
    void repeatPrefixOnNonStringOpcodeFailsAtStartingAddress() {
        for (int[] program : new int[][]{{0xF3, 0xB0, 0x7F}, {0xF2, 0x36, 0xB0, 0x7F}}) {
            CPU8086 cpu = boot(program);
            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, cpu::step);
            assertTrue(error.getMessage().contains("C000:0000"));
        }
    }

    private static void write(CPU8086 cpu, int segment, int offset, int width, int value) {
        if (width == 1) cpu.memory().write8(segment, offset, value);
        else cpu.memory().write16(segment, offset, value);
    }

    private static int read(CPU8086 cpu, int segment, int offset, int width) {
        return width == 1 ? cpu.memory().read8(segment, offset) : cpu.memory().read16(segment, offset);
    }

    private static CPU8086 boot(int... program) {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xEA;
        rom[0x3FFF4] = (byte) 0xC0;
        for (int i = 0; i < program.length; i++) rom[i] = (byte) program[i];
        CPU8086 cpu = new CPU8086();
        cpu.hostBridge().loadRom(rom);
        cpu.step();
        cpu.registers().setDs(0x1000);
        cpu.registers().setEs(0x2000);
        cpu.registers().setSs(0x3000);
        return cpu;
    }
}
