package dervogel101.de;

import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataTransferInstructionsTest {
    @Test
    void movImmediateOpcodesUseThe8086RegisterOrder() {
        CPU8086 words = boot(
                0xB8, 0x01, 0x10, 0xB9, 0x02, 0x20, 0xBA, 0x03, 0x30, 0xBB, 0x04, 0x40,
                0xBC, 0x05, 0x50, 0xBD, 0x06, 0x60, 0xBE, 0x07, 0x70, 0xBF, 0x08, 0x80);
        steps(words, 8);
        assertEquals(0x1001, words.registers().getAx());
        assertEquals(0x2002, words.registers().getCx());
        assertEquals(0x3003, words.registers().getDx());
        assertEquals(0x4004, words.registers().getBx());
        assertEquals(0x5005, words.registers().getSp());
        assertEquals(0x6006, words.registers().getBp());
        assertEquals(0x7007, words.registers().getSi());
        assertEquals(0x8008, words.registers().getDi());

        CPU8086 bytes = boot(0xB0, 1, 0xB1, 2, 0xB2, 3, 0xB3, 4,
                0xB4, 5, 0xB5, 6, 0xB6, 7, 0xB7, 8);
        steps(bytes, 8);
        assertEquals(0x0501, bytes.registers().getAx());
        assertEquals(0x0602, bytes.registers().getCx());
        assertEquals(0x0703, bytes.registers().getDx());
        assertEquals(0x0804, bytes.registers().getBx());
    }

    @Test
    void movRegisterAndImmediateFormsKeepByteHalvesAndFlags() {
        CPU8086 cpu = boot(
                0xB0, 0x34, 0xB4, 0x12, 0xB9, 0x78, 0x56,
                0x88, 0xE3, 0x8A, 0xC4, 0x89, 0xC3, 0x8B, 0xD1,
                0xC6, 0xC4, 0x9A, 0xC7, 0xC2, 0xEF, 0xBE);
        cpu.registers().setFlags(0xA55A);
        steps(cpu, 9);
        assertEquals(0x9A12, cpu.registers().getAx());
        assertEquals(0x1212, cpu.registers().getBx());
        assertEquals(0x5678, cpu.registers().getCx());
        assertEquals(0xBEEF, cpu.registers().getDx());
        assertEquals(0xA55A, cpu.registers().getFlags());
    }

    @Test
    void movMemoryAndSegmentFormsUseCorrectSegments() {
        CPU8086 cpu = boot(
                0x88, 0x40, 0x05, 0x8A, 0x43, 0xFE, 0x26, 0x8A, 0x43, 0xFE,
                0x8B, 0x06, 0x34, 0x12, 0x89, 0x80, 0x00, 0x01,
                0xA0, 0x00, 0x20, 0xA1, 0x02, 0x20, 0xA2, 0x04, 0x20,
                0xA3, 0x06, 0x20, 0xC6, 0x06, 0x08, 0x20, 0x5A,
                0xC7, 0x06, 0x09, 0x20, 0x78, 0x56,
                0x8E, 0x06, 0x20, 0x20, 0xB8, 0x00, 0x01, 0x8E, 0xD8, 0x8C, 0xD8);
        cpu.registers().setDs(0);
        cpu.registers().setSs(0x1000);
        cpu.registers().setEs(0x2000);
        cpu.registers().setBx(0x0100);
        cpu.registers().setSi(0x0020);
        cpu.registers().setBp(0x0300);
        cpu.registers().setDi(0x0010);
        cpu.registers().setAx(0xCAFE);
        cpu.memory().write8(0x1000, 0x030E, 0x11);
        cpu.memory().write8(0x2000, 0x030E, 0x22);
        cpu.memory().write16(0, 0x1234, 0xBEEF);
        cpu.memory().write8(0, 0x2000, 0xAA);
        cpu.memory().write16(0, 0x2002, 0x3344);
        cpu.memory().write16(0, 0x2020, 0x4567);

        cpu.step();
        assertEquals(0xFE, cpu.memory().read8(0, 0x0125));
        cpu.step();
        assertEquals(0x11, cpu.registers().getAx() & 0xFF);
        cpu.step();
        assertEquals(0x22, cpu.registers().getAx() & 0xFF);
        steps(cpu, 2);
        assertEquals(0xBEEF, cpu.memory().read16(0, 0x0220));
        steps(cpu, 6);
        assertEquals(0x44, cpu.memory().read8(0, 0x2004));
        assertEquals(0x3344, cpu.memory().read16(0, 0x2006));
        assertEquals(0x5A, cpu.memory().read8(0, 0x2008));
        assertEquals(0x5678, cpu.memory().read16(0, 0x2009));
        steps(cpu, 4);
        assertEquals(0x4567, cpu.registers().getEs());
        assertEquals(0x0100, cpu.registers().getDs());
        assertEquals(0x0100, cpu.registers().getAx());
    }

    @Test
    void movSupportsEveryBaseIndexAddressAndOffsetWrap() {
        CPU8086 cpu = boot(0x8A, 0x00, 0x8A, 0x01, 0x8A, 0x02, 0x8A, 0x03,
                0x8A, 0x04, 0x8A, 0x05, 0x8A, 0x06, 0x30, 0x03, 0x8A, 0x07);
        cpu.registers().setDs(0);
        cpu.registers().setSs(0x1000);
        cpu.registers().setBx(0x0100);
        cpu.registers().setBp(0x0200);
        cpu.registers().setSi(0x0010);
        cpu.registers().setDi(0x0020);
        int[] offsets = {0x110, 0x120, 0x210, 0x220, 0x10, 0x20, 0x330, 0x100};
        for (int i = 0; i < offsets.length; i++)
            cpu.memory().write8(i == 2 || i == 3 ? 0x1000 : 0, offsets[i], i + 1);
        for (int i = 0; i < offsets.length; i++) {
            cpu.step();
            assertEquals(i + 1, cpu.registers().getAx() & 0xFF);
        }

        CPU8086 wrapped = boot(0x8A, 0x80, 0x01, 0x00);
        wrapped.registers().setBx(0xFFF0);
        wrapped.registers().setSi(0x0010);
        wrapped.memory().write8(0, 1, 0x5A);
        wrapped.step();
        assertEquals(0x5A, wrapped.registers().getAx() & 0xFF);
    }

    @Test
    void movUsesTheLastSegmentOverrideWithinOneStep() {
        CPU8086 cpu = boot(0x26, 0x36, 0xA0, 0x00, 0x10);
        cpu.registers().setEs(0x1000);
        cpu.registers().setSs(0x2000);
        cpu.memory().write8(0x1000, 0x1000, 0x11);
        cpu.memory().write8(0x2000, 0x1000, 0x22);
        cpu.step();
        assertEquals(0x22, cpu.registers().getAx() & 0xFF);
        assertEquals(5, cpu.registers().getIp());
    }

    @Test
    void xchgSwapsRegisterAndMemoryOperandsWithoutChangingFlags() {
        CPU8086 cpu = boot(0x86, 0x07, 0x86, 0xE0, 0x87, 0xD9, 0x93, 0x90,
                0x87, 0x1E, 0x00, 0x03);
        cpu.registers().setAx(0x1234);
        cpu.registers().setBx(0x0200);
        cpu.registers().setCx(0xBBBB);
        cpu.registers().setFlags(0xA55A);
        cpu.memory().write8(0, 0x0200, 0x77);
        cpu.memory().write16(0, 0x0300, 0xABCD);
        cpu.step();
        assertEquals(0x34, cpu.memory().read8(0, 0x0200));
        assertEquals(0x1277, cpu.registers().getAx());
        steps(cpu, 4);
        assertEquals(0xBBBB, cpu.registers().getAx());
        assertEquals(0x7712, cpu.registers().getBx());
        assertEquals(0x0200, cpu.registers().getCx());
        cpu.step();
        assertEquals(0xABCD, cpu.registers().getBx());
        assertEquals(0x7712, cpu.memory().read16(0, 0x0300));
        assertEquals(0xA55A, cpu.registers().getFlags());
    }

    @Test
    void leaComputesAddressWithoutReadingMemory() {
        CPU8086 cpu = boot(0x8D, 0x42, 0xFE, 0x8D, 0x97, 0x00, 0x01,
                0x8D, 0x06, 0x34, 0x12);
        cpu.registers().setBp(0x1000);
        cpu.registers().setSi(0x0010);
        cpu.registers().setBx(0xFF80);
        cpu.registers().setFlags(0xA55A);
        cpu.step();
        assertEquals(0x100E, cpu.registers().getAx());
        cpu.step();
        assertEquals(0x0080, cpu.registers().getDx());
        cpu.step();
        assertEquals(0x1234, cpu.registers().getAx());
        assertEquals(0xA55A, cpu.registers().getFlags());
    }

    @Test
    void lesLoadsOffsetAndEsFromMemory() {
        CPU8086 cpu = boot(0xC4, 0x16, 0x00, 0x30);
        cpu.registers().setFlags(0xA55A);
        cpu.memory().write16(0, 0x3000, 0x1111);
        cpu.memory().write16(0, 0x3002, 0x2222);
        cpu.step();
        assertEquals(0x1111, cpu.registers().getDx());
        assertEquals(0x2222, cpu.registers().getEs());
        assertEquals(0xA55A, cpu.registers().getFlags());
    }

    @Test
    void ldsUsesTheOverriddenSourceSegment() {
        CPU8086 cpu = boot(0x36, 0xC5, 0x1E, 0x08, 0x30);
        cpu.registers().setSs(0x1000);
        cpu.registers().setFlags(0xA55A);
        cpu.memory().write16(0x1000, 0x3008, 0x3333);
        cpu.memory().write16(0x1000, 0x300A, 0x4444);
        cpu.step();
        assertEquals(0x3333, cpu.registers().getBx());
        assertEquals(0x4444, cpu.registers().getDs());
        assertEquals(5, cpu.registers().getIp());
        assertEquals(0xA55A, cpu.registers().getFlags());
    }

    @Test
    void xlatUsesBxPlusAlWithSegmentOverrideAndOffsetWrap() {
        CPU8086 cpu = boot(0xD7, 0x26, 0xD7);
        cpu.registers().setBx(0xFFFE);
        cpu.registers().setAx(3);
        cpu.registers().setEs(0x1000);
        cpu.registers().setFlags(0xA55A);
        cpu.memory().write8(0, 1, 5);
        cpu.memory().write8(0x1000, 3, 9);
        cpu.step();
        assertEquals(5, cpu.registers().getAx() & 0xFF);
        cpu.step();
        assertEquals(9, cpu.registers().getAx() & 0xFF);
        assertEquals(0xA55A, cpu.registers().getFlags());
    }

    @Test
    void invalidDataTransferEncodingsFailAtTheirStartingAddress() {
        int[][] invalid = {
                {0x8E, 0xC8}, {0x8E, 0xE0}, {0xC6, 0xC8, 0x12},
                {0x8D, 0xC0}, {0xC4, 0xC0}, {0xC5, 0xC0}
        };
        for (int[] program : invalid) {
            CPU8086 cpu = boot(program);
            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, cpu::step);
            assertTrue(error.getMessage().contains("C000:0000"));
        }
    }

    private static CPU8086 boot(int... program) {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xEA;
        rom[0x3FFF4] = (byte) 0xC0;
        for (int i = 0; i < program.length; i++) rom[i] = (byte) program[i];
        CPU8086 cpu = new CPU8086();
        cpu.hostBridge().loadRom(rom);
        cpu.step();
        return cpu;
    }

    private static void steps(CPU8086 cpu, int count) {
        for (int i = 0; i < count; i++) cpu.step();
    }
}
