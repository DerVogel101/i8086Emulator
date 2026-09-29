package dervogel101.de;

import dervogel101.de.state.CPUIO;
import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IoCpuControlInstructionsTest {
    @Test
    void immediatePortFormsUseAlOrAxAndHostInputs() {
        CPU8086 inByte = boot(0xE4, 0x00);
        inByte.registers().setAx(0xABCD);
        inByte.registers().setFlags(0x0A55);
        inByte.hostBridge().submitRedstoneInput(0, 9);
        inByte.step();
        assertEquals(0xAB09, inByte.registers().getAx());
        assertEquals(0x0A55, inByte.registers().getFlags());
        assertEquals(2, inByte.registers().getIp());

        CPU8086 outByteInWord = boot(0xE6, 0xFF, 0xE5, 0xFE);
        outByteInWord.registers().setAx(0xAB0B);
        outByteInWord.registers().setFlags(0x0A55);
        outByteInWord.step();
        assertEquals(11, outByteInWord.hostBridge().pollChangedOutputs().redstone().get(127));
        outByteInWord.hostBridge().submitRedstoneInput(127, 6);
        outByteInWord.step();
        assertEquals(0x0B06, outByteInWord.registers().getAx());
        assertEquals(0x0A55, outByteInWord.registers().getFlags());
        assertEquals(4, outByteInWord.registers().getIp());

        CPU8086 outWord = boot(0xE7, 0xFF);
        outWord.registers().setAx(0x5A0C);
        outWord.registers().setFlags(0x0A55);
        outWord.step();
        CPUIO.OutputChanges changes = outWord.hostBridge().pollChangedOutputs();
        assertEquals(12, changes.redstone().get(127));
        assertEquals(0x5A, Byte.toUnsignedInt(changes.signs().get(0).bytes()[0]));
        assertEquals(0x5A0C, outWord.registers().getAx());
        assertEquals(0x0A55, outWord.registers().getFlags());
    }

    @Test
    void dxPortFormsUseFullPortAndWordReadsWrapAtFfff() {
        CPU8086 inByte = boot(0xEC);
        inByte.registers().setAx(0xABCD);
        inByte.registers().setDx(0xFE);
        inByte.hostBridge().submitRedstoneInput(127, 14);
        inByte.step();
        assertEquals(0xAB0E, inByte.registers().getAx());
        assertEquals(1, inByte.registers().getIp());

        CPU8086 inWord = boot(0xED);
        inWord.registers().setAx(0xFFFF);
        inWord.registers().setDx(0xFFFF);
        inWord.hostBridge().submitRedstoneInput(0, 12);
        inWord.step();
        assertEquals(0x0C00, inWord.registers().getAx());
        assertEquals(0xFFFF, inWord.registers().getDx());

        CPU8086 outByte = boot(0xEE);
        outByte.registers().setDx(1);
        outByte.registers().setAx(0xAB0D);
        outByte.step();
        assertEquals(13, outByte.hostBridge().pollChangedOutputs().redstone().get(0));
        assertEquals(0xAB0D, outByte.registers().getAx());

        CPU8086 outWord = boot(0xEF);
        outWord.registers().setDx(0x00FF);
        outWord.registers().setAx(0x7E09);
        outWord.registers().setFlags(0x0A55);
        outWord.step();
        CPUIO.OutputChanges changes = outWord.hostBridge().pollChangedOutputs();
        assertEquals(9, changes.redstone().get(127));
        assertEquals(0x7E, Byte.toUnsignedInt(changes.signs().get(0).bytes()[0]));
        assertEquals(0x0A55, outWord.registers().getFlags());
    }

    @Test
    void lahfAndSahfTransferOnlyTheDefinedStatusBits() {
        CPU8086 lahf = boot(0x9F);
        lahf.registers().setAx(0xAA7B);
        lahf.registers().setFlags(0x0AD5);
        lahf.step();
        assertEquals(0xD77B, lahf.registers().getAx());
        assertEquals(0x0AD5, lahf.registers().getFlags());

        CPU8086 sahfClear = boot(0x9E);
        sahfClear.registers().setAx(0x0001);
        sahfClear.registers().setFlags(0x0FD5);
        sahfClear.step();
        assertEquals(0x0F00, sahfClear.registers().getFlags());
        assertEquals(0x0001, sahfClear.registers().getAx());

        CPU8086 sahfSet = boot(0x9E);
        sahfSet.registers().setAx(0xFF34);
        sahfSet.step();
        assertEquals(0x00D5, sahfSet.registers().getFlags());
        assertEquals(0xFF34, sahfSet.registers().getAx());
    }

    @Test
    void flagControlsChangeOnlyTheirOwnFlag() {
        assertFlags(0xF5, 0x0A55, 0x0A54); // CMC clears a set carry.
        assertFlags(0xF5, 0x0A54, 0x0A55); // CMC sets a clear carry.
        assertFlags(0xF8, 0xFFFF, 0xFFFE); // CLC
        assertFlags(0xF9, 0, 0x0001);      // STC
        assertFlags(0xFA, 0xFFFF, 0xFDFF); // CLI
        assertFlags(0xFB, 0, 0x0200);      // STI
        assertFlags(0xFC, 0xFFFF, 0xFBFF); // CLD
        assertFlags(0xFD, 0, 0x0400);      // STD
    }

    @Test
    void waitAndExistingNopCompleteWhileHltStillStopsExecution() {
        CPU8086 cpu = boot(0x9B, 0x90, 0xF4, 0xB0, 0x42);
        cpu.registers().setAx(0x1234);
        cpu.registers().setFlags(0x0A55);
        cpu.step();
        assertEquals(1, cpu.registers().getIp());
        assertFalse(cpu.isHalted());
        cpu.step();
        assertEquals(2, cpu.registers().getIp());
        assertEquals(0x1234, cpu.registers().getAx());
        assertEquals(0x0A55, cpu.registers().getFlags());
        cpu.step();
        assertTrue(cpu.isHalted());
        cpu.step();
        assertEquals(3, cpu.registers().getIp());
        assertEquals(0x1234, cpu.registers().getAx());
    }

    @Test
    void everyEscapeOpcodeReportsUnsupportedInstruction() {
        for (int opcode = 0xD8; opcode <= 0xDF; opcode++) {
            CPU8086 cpu = boot(opcode, 0xC0);
            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, cpu::step);
            assertEquals("Unsupported opcode %02X at C000:0000".formatted(opcode), error.getMessage());
            assertEquals(1, cpu.registers().getIp()); // ModR/M was not consumed.
        }

        CPU8086 prefixed = boot(0x36, 0xDF, 0x86, 0x34, 0x12);
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, prefixed::step);
        assertEquals("Unsupported opcode DF at C000:0000", error.getMessage());
        assertEquals(2, prefixed.registers().getIp()); // Displacement was not consumed.
    }

    @Test
    void lockAcceptsSupportedMemoryReadModifyWriteForms() {
        int[] bases = {0x00, 0x08, 0x10, 0x18, 0x20, 0x28, 0x30};
        int[] expected = {0x06, 0x05, 0x06, 0x04, 0x01, 0x04, 0x04};
        for (int i = 0; i < bases.length; i++) {
            for (int width = 1; width <= 2; width++) {
                CPU8086 cpu = boot(0xF0, bases[i] + width - 1, 0x06, 0x00, 0x01);
                cpu.registers().setAx(0x0101);
                cpu.registers().setFlags(0);
                cpu.memory().write16(0x1000, 0x100, 0x0505);
                cpu.step();
                assertEquals(width == 1 ? 0x0500 | expected[i] : expected[i] * 0x0101,
                        cpu.memory().read16(0x1000, 0x100));
                assertEquals(5, cpu.registers().getIp());
            }
        }

        assertLockedMemory(0x80, 0x06, 2, 1, 7);    // ADD byte immediate
        assertLockedMemory(0x81, 0x2E, 2, 2, 3);    // SUB word immediate
        assertLockedMemory(0x83, 0x06, 0xFE, 2, 3); // ADD sign-extended immediate
        assertLockedMemory(0xFE, 0x06, -1, 1, 6);   // INC byte
        assertLockedMemory(0xFE, 0x0E, -1, 1, 4);   // DEC byte
        assertLockedMemory(0xFF, 0x06, -1, 2, 6);   // INC word
        assertLockedMemory(0xFF, 0x0E, -1, 2, 4);   // DEC word
        assertLockedMemory(0xF6, 0x16, -1, 1, 0xFA);   // NOT byte
        assertLockedMemory(0xF6, 0x1E, -1, 1, 0xFB);   // NEG byte
        assertLockedMemory(0xF7, 0x16, -1, 2, 0xFFFA); // NOT word
        assertLockedMemory(0xF7, 0x1E, -1, 2, 0xFFFB); // NEG word

        for (int opcode : new int[]{0x86, 0x87}) {
            CPU8086 cpu = boot(0xF0, opcode, 0x06, 0x00, 0x01);
            cpu.registers().setAx(3);
            cpu.memory().write16(0x1000, 0x100, 5);
            cpu.step();
            assertEquals(3, cpu.memory().read16(0x1000, 0x100));
            assertEquals(5, cpu.registers().getAx());
        }
    }

    @Test
    void lockAndSegmentOverrideWorkInEitherOrder() {
        for (int[] program : new int[][]{
                {0xF0, 0x36, 0x00, 0x06, 0x00, 0x01},
                {0x36, 0xF0, 0x00, 0x06, 0x00, 0x01}}) {
            CPU8086 cpu = boot(program);
            cpu.registers().setAx(1);
            cpu.memory().write8(0x1000, 0x100, 10);
            cpu.memory().write8(0x3000, 0x100, 20);
            cpu.step();
            assertEquals(10, cpu.memory().read8(0x1000, 0x100));
            assertEquals(21, cpu.memory().read8(0x3000, 0x100));
            assertEquals(6, cpu.registers().getIp());
        }
    }

    @Test
    void invalidLockCombinationsReportTheirStartingAddress() {
        int[][] programs = {
                {0xF0, 0x00, 0xC0},                    // register-only ADD
                {0xF0, 0x38, 0x06, 0x00, 0x01},       // CMP memory
                {0xF0, 0x80, 0x3E, 0x00, 0x01, 0},    // group CMP
                {0xF0, 0xF6, 0x06, 0x00, 0x01, 0},    // TEST memory
                {0xF0, 0xD0, 0x26, 0x00, 0x01},       // shift memory
                {0xF0, 0x88, 0x06, 0x00, 0x01},       // MOV memory
                {0xF0, 0xF3, 0xA4},                    // LOCK with REP
                {0xF0, 0xE6, 0x01},                    // OUT
                {0xF0, 0x90}                           // register-only NOP
        };
        for (int[] program : programs) {
            CPU8086 cpu = boot(program);
            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, cpu::step);
            assertTrue(error.getMessage().contains("C000:0000"));
        }
    }

    private static void assertFlags(int opcode, int initial, int expected) {
        CPU8086 cpu = boot(opcode);
        cpu.registers().setFlags(initial);
        cpu.step();
        assertEquals(expected, cpu.registers().getFlags());
    }

    private static void assertLockedMemory(int opcode, int modrm, int immediate, int width, int expected) {
        CPU8086 cpu;
        if (immediate < 0) cpu = boot(0xF0, opcode, modrm, 0x00, 0x01);
        else if (opcode == 0x81) cpu = boot(0xF0, opcode, modrm, 0x00, 0x01, immediate, 0);
        else cpu = boot(0xF0, opcode, modrm, 0x00, 0x01, immediate);
        if (width == 1) cpu.memory().write8(0x1000, 0x100, 5);
        else cpu.memory().write16(0x1000, 0x100, 5);
        cpu.step();
        assertEquals(expected, width == 1 ? cpu.memory().read8(0x1000, 0x100)
                : cpu.memory().read16(0x1000, 0x100));
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
        cpu.registers().setSs(0x3000);
        return cpu;
    }
}
