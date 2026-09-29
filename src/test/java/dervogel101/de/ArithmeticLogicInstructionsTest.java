package dervogel101.de;

import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArithmeticLogicInstructionsTest {
    @Test
    void accumulatorArithmeticAndLogicUpdateDefinedFlags() {
        // ADD, OR, ADC, SBB, AND, SUB, XOR, CMP: accumulator/immediate forms.
        int[][] cases = {
                {0x04, 0x7F, 0x01, 0, 0x80, 0, 1, 0, 1, 1, 0},
                {0x0C, 0x10, 0x03, 0, 0x13, 0, 0, 0, 0, 0, 0},
                {0x14, 0xFF, 0x00, 1, 0x00, 1, 0, 1, 0, 1, 1},
                {0x1C, 0x00, 0x00, 1, 0xFF, 1, 0, 0, 1, 1, 1},
                {0x24, 0xF0, 0x0F, 0, 0x00, 0, 0, 1, 0, 0, 1},
                {0x2C, 0x80, 0x01, 0, 0x7F, 0, 1, 0, 0, 1, 0},
                {0x34, 0x55, 0xFF, 0, 0xAA, 0, 0, 0, 1, 0, 1},
                {0x3C, 0x00, 0x01, 0, 0x00, 1, 0, 0, 1, 1, 1}
        };
        for (int[] c : cases) {
            CPU8086 cpu = boot(c[0], c[2]);
            cpu.registers().setAx(c[1]);
            cpu.registers().setCF(c[3] != 0);
            cpu.step();
            assertEquals(c[4], cpu.registers().getAx() & 0xFF, "opcode " + c[0]);
            assertEquals(c[5] != 0, cpu.registers().getCF(), "CF opcode " + c[0]);
            assertEquals(c[6] != 0, cpu.registers().getOF(), "OF opcode " + c[0]);
            assertEquals(c[7] != 0, cpu.registers().getZF(), "ZF opcode " + c[0]);
            assertEquals(c[8] != 0, cpu.registers().getSF(), "SF opcode " + c[0]);
            assertEquals(c[9] != 0, cpu.registers().getAF(), "AF opcode " + c[0]);
            assertEquals(c[10] != 0, cpu.registers().getPF(), "PF opcode " + c[0]);
        }
    }

    @Test
    void arithmeticCoversRegisterMemoryDirectionsAndImmediateWidths() {
        CPU8086 toMemory = boot(0x00, 0x07);
        toMemory.registers().setAx(1);
        toMemory.registers().setBx(0x0200);
        toMemory.memory().write8(0, 0x0200, 0xFF);
        toMemory.step();
        assertEquals(0, toMemory.memory().read8(0, 0x0200));
        assertTrue(toMemory.registers().getCF());
        assertTrue(toMemory.registers().getAF());
        assertTrue(toMemory.registers().getZF());
        assertTrue(toMemory.registers().getPF());

        CPU8086 toRegister = boot(0x03, 0x07);
        toRegister.registers().setAx(0x7FFF);
        toRegister.registers().setBx(0x0200);
        toRegister.memory().write16(0, 0x0200, 1);
        toRegister.step();
        assertEquals(0x8000, toRegister.registers().getAx());
        assertTrue(toRegister.registers().getOF());
        assertTrue(toRegister.registers().getPF()); // Parity uses the low byte.

        CPU8086 wordToRegister = boot(0x01, 0xC3); // ADD BX,AX
        wordToRegister.registers().setAx(1);
        wordToRegister.registers().setBx(2);
        wordToRegister.step();
        assertEquals(3, wordToRegister.registers().getBx());

        CPU8086 byteToRegister = boot(0x02, 0xC3); // ADD AL,BL
        byteToRegister.registers().setAx(1);
        byteToRegister.registers().setBx(2);
        byteToRegister.step();
        assertEquals(3, byteToRegister.registers().getAx());

        CPU8086 wordAccumulator = boot(0x05, 0x01, 0x00); // ADD AX,1
        wordAccumulator.registers().setAx(2);
        wordAccumulator.step();
        assertEquals(3, wordAccumulator.registers().getAx());

        CPU8086 signExtended = boot(0x83, 0xE8, 0xFF);
        signExtended.registers().setAx(0x7FFF);
        signExtended.step();
        assertEquals(0x8000, signExtended.registers().getAx());
        assertTrue(signExtended.registers().getOF());

        CPU8086 wordImmediate = boot(0x81, 0x07, 0x34, 0x12);
        wordImmediate.registers().setBx(0x0200);
        wordImmediate.memory().write16(0, 0x0200, 1);
        wordImmediate.step();
        assertEquals(0x1235, wordImmediate.memory().read16(0, 0x0200));

        CPU8086 byteImmediate = boot(0x80, 0x0F, 0x0F);
        byteImmediate.registers().setBx(0x0200);
        byteImmediate.memory().write8(0, 0x0200, 0x10);
        byteImmediate.step();
        assertEquals(0x1F, byteImmediate.memory().read8(0, 0x0200));

        CPU8086 overridden = boot(0x26, 0x00, 0x07);
        overridden.registers().setAx(1);
        overridden.registers().setBx(0x0200);
        overridden.registers().setEs(0x1000);
        overridden.memory().write8(0, 0x0200, 2);
        overridden.memory().write8(0x1000, 0x0200, 3);
        overridden.step();
        assertEquals(2, overridden.memory().read8(0, 0x0200));
        assertEquals(4, overridden.memory().read8(0x1000, 0x0200));
    }

    @Test
    void testFormsSetFlagsWithoutWritingOperands() {
        int[][] forms = {
                {0x84, 0xC3}, {0x85, 0xC3}, {0xA8, 0x0F},
                {0xA9, 0x0F, 0x00}, {0xF6, 0xC0, 0x0F}, {0xF7, 0xC0, 0x0F, 0x00}
        };
        for (int[] form : forms) {
            CPU8086 cpu = boot(form);
            cpu.registers().setAx(0xF0F0);
            cpu.registers().setBx(0x0F0F);
            cpu.registers().setFlags(0x0811); // OF, AF and CF initially set.
            cpu.step();
            assertEquals(0xF0F0, cpu.registers().getAx());
            assertTrue(cpu.registers().getZF());
            assertFalse(cpu.registers().getCF());
            assertFalse(cpu.registers().getOF());
            assertTrue(cpu.registers().getAF()); // Undefined for TEST: preserved.
        }
    }

    @Test
    void incAndDecPreserveCarryForRegistersAndMemory() {
        CPU8086 inc = boot(0x40);
        inc.registers().setAx(0x7FFF);
        inc.registers().setCF(true);
        inc.step();
        assertEquals(0x8000, inc.registers().getAx());
        assertTrue(inc.registers().getOF());
        assertTrue(inc.registers().getCF());

        CPU8086 dec = boot(0x48);
        dec.registers().setAx(0x8000);
        dec.registers().setCF(true);
        dec.step();
        assertEquals(0x7FFF, dec.registers().getAx());
        assertTrue(dec.registers().getOF());
        assertTrue(dec.registers().getCF());

        CPU8086 byteMemory = boot(0xFE, 0x07);
        byteMemory.registers().setBx(0x0200);
        byteMemory.registers().setCF(true);
        byteMemory.memory().write8(0, 0x0200, 0xFF);
        byteMemory.step();
        assertEquals(0, byteMemory.memory().read8(0, 0x0200));
        assertTrue(byteMemory.registers().getZF());
        assertTrue(byteMemory.registers().getCF());

        CPU8086 wordMemory = boot(0xFF, 0x0F);
        wordMemory.registers().setBx(0x0200);
        wordMemory.registers().setCF(true);
        wordMemory.step();
        assertEquals(0xFFFF, wordMemory.memory().read16(0, 0x0200));
        assertTrue(wordMemory.registers().getCF());
    }

    @Test
    void negAndNotUseTheirDistinctFlagRules() {
        CPU8086 negativeByte = boot(0xF6, 0xD8);
        negativeByte.registers().setAx(0x0080);
        negativeByte.step();
        assertEquals(0x80, negativeByte.registers().getAx() & 0xFF);
        assertTrue(negativeByte.registers().getCF());
        assertTrue(negativeByte.registers().getOF());

        CPU8086 negativeWord = boot(0xF7, 0xD8);
        negativeWord.registers().setAx(0x8000);
        negativeWord.step();
        assertEquals(0x8000, negativeWord.registers().getAx());
        assertTrue(negativeWord.registers().getOF());

        CPU8086 notByte = boot(0xF6, 0xD0);
        notByte.registers().setAx(0x120F);
        notByte.registers().setFlags(0x0AD5);
        notByte.step();
        assertEquals(0x12F0, notByte.registers().getAx());
        assertEquals(0x0AD5, notByte.registers().getFlags());

        CPU8086 notWord = boot(0xF7, 0xD0);
        notWord.registers().setAx(0x0F0F);
        notWord.registers().setFlags(0x0AD5);
        notWord.step();
        assertEquals(0xF0F0, notWord.registers().getAx());
        assertEquals(0x0AD5, notWord.registers().getFlags());
    }

    @Test
    void unaryAndShiftMemoryFormsHonorSegmentOverrides() {
        CPU8086 notMemory = boot(0x26, 0xF6, 0x17); // NOT byte ES:[BX]
        notMemory.registers().setBx(0x0200);
        notMemory.registers().setEs(0x1000);
        notMemory.registers().setFlags(0x0AD5);
        notMemory.memory().write8(0, 0x0200, 0xAA);
        notMemory.memory().write8(0x1000, 0x0200, 0x0F);
        notMemory.step();
        assertEquals(0xAA, notMemory.memory().read8(0, 0x0200));
        assertEquals(0xF0, notMemory.memory().read8(0x1000, 0x0200));
        assertEquals(0x0AD5, notMemory.registers().getFlags());

        CPU8086 shiftMemory = boot(0xD1, 0x27); // SHL word [BX],1
        shiftMemory.registers().setBx(0x0200);
        shiftMemory.memory().write16(0, 0x0200, 0x8001);
        shiftMemory.step();
        assertEquals(2, shiftMemory.memory().read16(0, 0x0200));
        assertTrue(shiftMemory.registers().getCF());
        assertTrue(shiftMemory.registers().getOF());
    }

    @Test
    void mulAndImulProduceBothHalvesAndReportOverflow() {
        CPU8086 unsignedByte = boot(0xF6, 0xE3); // MUL BL
        unsignedByte.registers().setAx(0x0010);
        unsignedByte.registers().setBx(0x0010);
        unsignedByte.step();
        assertEquals(0x0100, unsignedByte.registers().getAx());
        assertTrue(unsignedByte.registers().getCF());
        assertTrue(unsignedByte.registers().getOF());

        CPU8086 unsignedWord = boot(0xF7, 0xE3); // MUL BX
        unsignedWord.registers().setAx(0x1000);
        unsignedWord.registers().setBx(0x0010);
        unsignedWord.step();
        assertEquals(0, unsignedWord.registers().getAx());
        assertEquals(1, unsignedWord.registers().getDx());
        assertTrue(unsignedWord.registers().getCF());

        CPU8086 signedByte = boot(0xF6, 0xEB); // IMUL BL
        signedByte.registers().setAx(0x00FE);
        signedByte.registers().setBx(3);
        signedByte.step();
        assertEquals(0xFFFA, signedByte.registers().getAx());
        assertFalse(signedByte.registers().getCF());
        assertFalse(signedByte.registers().getOF());

        CPU8086 signedWord = boot(0xF7, 0xEB); // IMUL BX
        signedWord.registers().setAx(0x8000);
        signedWord.registers().setBx(2);
        signedWord.step();
        assertEquals(0, signedWord.registers().getAx());
        assertEquals(0xFFFF, signedWord.registers().getDx());
        assertTrue(signedWord.registers().getCF());
        assertTrue(signedWord.registers().getOF());
    }

    @Test
    void divAndIdivReturnQuotientAndRemainder() {
        CPU8086 unsignedByte = boot(0xF6, 0xF3); // DIV BL
        unsignedByte.registers().setAx(0x0123);
        unsignedByte.registers().setBx(0x0012);
        unsignedByte.registers().setFlags(0x0AD5);
        unsignedByte.step();
        assertEquals(0x0310, unsignedByte.registers().getAx());
        assertEquals(0x0AD5, unsignedByte.registers().getFlags());

        CPU8086 unsignedWord = boot(0xF7, 0xF3); // DIV BX
        unsignedWord.registers().setDx(1);
        unsignedWord.registers().setBx(0x0100);
        unsignedWord.step();
        assertEquals(0x0100, unsignedWord.registers().getAx());
        assertEquals(0, unsignedWord.registers().getDx());

        CPU8086 signedByte = boot(0xF6, 0xFB); // IDIV BL
        signedByte.registers().setAx(0xFF9C);
        signedByte.registers().setBx(0x00F9);
        signedByte.step();
        assertEquals(0xFE0E, signedByte.registers().getAx());

        CPU8086 signedWord = boot(0xF7, 0xFB); // IDIV BX
        signedWord.registers().setDx(0xFFFF);
        signedWord.registers().setAx(0xFF9C);
        signedWord.registers().setBx(7);
        signedWord.step();
        assertEquals(0xFFF2, signedWord.registers().getAx());
        assertEquals(0xFFFE, signedWord.registers().getDx());
    }

    @Test
    void divideErrorsAndAamZeroInvokeInterruptZeroWith8086ReturnIp() {
        int[][] faults = {
                {0xF6, 0xF3}, // DIV BL: divisor zero
                {0xF6, 0xF3}, // DIV BL: quotient overflow
                {0xF7, 0xFB}, // IDIV BX: signed quotient overflow
                {0xD4, 0x00}  // AAM base zero
        };
        for (int i = 0; i < faults.length; i++) {
            CPU8086 cpu = boot(faults[i]);
            cpu.memory().write16(0, 0, 0x0100);
            cpu.memory().write16(0, 2, 0xC000);
            cpu.registers().setSs(0x1000);
            cpu.registers().setSp(0x0200);
            cpu.registers().setFlags(0x0BD5);
            cpu.registers().setAx(i == 1 ? 0x0100 : i == 2 ? 0x8000 : 0x1234);
            cpu.registers().setBx(i == 1 || i == 2 ? 1 : 0);
            int originalAx = cpu.registers().getAx();
            cpu.step();
            assertEquals(0xC000, cpu.registers().getCs());
            assertEquals(0x0100, cpu.registers().getIp());
            assertEquals(0x01FA, cpu.registers().getSp());
            assertEquals(2, cpu.memory().read16(0x1000, 0x01FA));
            assertEquals(0xC000, cpu.memory().read16(0x1000, 0x01FC));
            assertEquals(0x0BD5, cpu.memory().read16(0x1000, 0x01FE));
            assertFalse(cpu.registers().getIF());
            assertFalse(cpu.registers().getTF());
            assertEquals(originalAx, cpu.registers().getAx());
            cpu.step();
            assertTrue(cpu.isHalted());
        }
    }

    @Test
    void eachRotateAndShiftHasIts8086OneBitResultAndFlags() {
        // ROL, ROR, RCL, RCR, SHL/SAL, SHR, SAR on AL = 0x81.
        int[][] cases = {
                {0xC0, 0x03, 1, 1}, {0xC8, 0xC0, 1, 0},
                {0xD0, 0x02, 1, 1}, {0xD8, 0x40, 1, 1},
                {0xE0, 0x02, 1, 1}, {0xE8, 0x40, 1, 1},
                {0xF8, 0xC0, 1, 0}
        };
        for (int[] c : cases) {
            CPU8086 cpu = boot(0xD0, c[0]);
            cpu.registers().setAx(0x0081);
            cpu.registers().setFlags(0x00C4); // CF=0, ZF=SF=PF=1.
            cpu.step();
            assertEquals(c[1], cpu.registers().getAx() & 0xFF, "ModR/M " + c[0]);
            assertEquals(c[2] != 0, cpu.registers().getCF());
            assertEquals(c[3] != 0, cpu.registers().getOF());
            assertEquals(c[0] < 0xE0, cpu.registers().getZF()); // Rotates preserve ZF.
        }

        CPU8086 word = boot(0xD1, 0xF8); // SAR AX,1
        word.registers().setAx(0x8001);
        word.step();
        assertEquals(0xC000, word.registers().getAx());
        assertTrue(word.registers().getCF());
        assertFalse(word.registers().getOF());
    }

    @Test
    void clCountsIncludeZeroAndAreNotMaskedToFiveBits() {
        CPU8086 zero = boot(0xD2, 0xE0); // SHL AL,CL
        zero.registers().setAx(0x0081);
        zero.registers().setCx(0);
        zero.registers().setFlags(0x08D5);
        zero.step();
        assertEquals(0x81, zero.registers().getAx());
        assertEquals(0x08D5, zero.registers().getFlags());

        CPU8086 thirtyTwo = boot(0xD2, 0xE0);
        thirtyTwo.registers().setAx(1);
        thirtyTwo.registers().setCx(32);
        thirtyTwo.registers().setCF(true);
        thirtyTwo.step();
        assertEquals(0, thirtyTwo.registers().getAx() & 0xFF);
        assertTrue(thirtyTwo.registers().getZF());
        assertTrue(thirtyTwo.registers().getCF()); // Undefined for overshift: preserved.

        CPU8086 throughCarry = boot(0xD2, 0xD0); // RCL AL,CL
        throughCarry.registers().setAx(0x0080);
        throughCarry.registers().setCx(2);
        throughCarry.registers().setOF(true);
        throughCarry.step();
        assertEquals(1, throughCarry.registers().getAx() & 0xFF);
        assertFalse(throughCarry.registers().getCF());
        assertTrue(throughCarry.registers().getOF()); // Undefined for count > 1: preserved.

        CPU8086 word = boot(0xD3, 0xE8); // SHR AX,CL
        word.registers().setAx(0x8001);
        word.registers().setCx(2);
        word.step();
        assertEquals(0x2000, word.registers().getAx());
        assertFalse(word.registers().getCF());
    }

    @Test
    void daaAndDasAdjustPackedDecimalAndDefinedFlags() {
        CPU8086 daa = boot(0x27);
        daa.registers().setAx(0x009A);
        daa.step();
        assertEquals(0, daa.registers().getAx() & 0xFF);
        assertTrue(daa.registers().getAF());
        assertTrue(daa.registers().getCF());
        assertTrue(daa.registers().getZF());

        CPU8086 das = boot(0x2F);
        das.registers().setAx(0x0010);
        das.registers().setAF(true);
        das.step();
        assertEquals(0x0A, das.registers().getAx() & 0xFF);
        assertTrue(das.registers().getAF());
        assertFalse(das.registers().getCF());
    }

    @Test
    void aaaAndAasAdjustUnpackedDecimal() {
        CPU8086 aaa = boot(0x37);
        aaa.registers().setAx(0x000A);
        aaa.step();
        assertEquals(0x0100, aaa.registers().getAx());
        assertTrue(aaa.registers().getAF());
        assertTrue(aaa.registers().getCF());

        CPU8086 aas = boot(0x3F);
        aas.registers().setAx(0x010A);
        aas.step();
        assertEquals(0x0004, aas.registers().getAx());
        assertTrue(aas.registers().getAF());
        assertTrue(aas.registers().getCF());
    }

    @Test
    void aamAndAadUseTheirEncodedBases() {
        int[][] aamCases = {{10, 0x0402}, {16, 0x020A}};
        for (int[] c : aamCases) {
            CPU8086 cpu = boot(0xD4, c[0]);
            cpu.registers().setAx(42);
            cpu.step();
            assertEquals(c[1], cpu.registers().getAx());
            assertFalse(cpu.registers().getZF());
        }
        int[][] aadCases = {{10, 0x0017}, {16, 0x0023}};
        for (int[] c : aadCases) {
            CPU8086 cpu = boot(0xD5, c[0]);
            cpu.registers().setAx(0x0203);
            cpu.step();
            assertEquals(c[1], cpu.registers().getAx());
        }
    }

    @Test
    void cbwAndCwdSignExtendWithoutChangingFlags() {
        CPU8086 cpu = boot(0x98, 0x99);
        cpu.registers().setAx(0x0080);
        cpu.registers().setFlags(0x0AD5);
        cpu.step();
        assertEquals(0xFF80, cpu.registers().getAx());
        cpu.step();
        assertEquals(0xFFFF, cpu.registers().getDx());
        assertEquals(0x0AD5, cpu.registers().getFlags());
    }

    @Test
    void invalidAndLaterCpuOpcodesRemainUnsupported() {
        int[][] invalid = {
                {0xF6, 0xC8}, // F6 /1 is not an instruction.
                {0xFE, 0xD0}, // FE /2 is invalid.
                {0xFF, 0xF8}, // FF /7 is invalid.
                {0xD0, 0xF0}, // Shift group /6 is invalid.
                {0x82, 0xC0, 0x01}, // Undocumented alias.
                {0xC0, 0xE0, 0x01}  // 80186 immediate-count shift.
        };
        for (int[] code : invalid) {
            CPU8086 cpu = boot(code);
            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, cpu::step);
            assertTrue(error.getMessage().contains("C000:0000"));
        }
    }

    private static CPU8086 boot(int... program) {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xEA;
        rom[0x3FFF4] = (byte) 0xC0;
        rom[0x0100] = (byte) 0xF4;
        for (int i = 0; i < program.length; i++) rom[i] = (byte) program[i];
        CPU8086 cpu = new CPU8086();
        cpu.hostBridge().loadRom(rom);
        cpu.step();
        return cpu;
    }
}
