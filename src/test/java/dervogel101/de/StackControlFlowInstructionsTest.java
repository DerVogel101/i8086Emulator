package dervogel101.de;

import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StackControlFlowInstructionsTest {
    @Test
    void pushAndPopRegistersUseSsAndWrapSp() {
        CPU8086 push = boot(0x50); // PUSH AX
        push.registers().setAx(0xBEEF);
        push.registers().setSs(0x1000);
        push.registers().setSp(0x0200);
        push.step();
        assertEquals(0x01FE, push.registers().getSp());
        assertEquals(0xBEEF, push.memory().read16(0x1000, 0x01FE));

        CPU8086 pop = boot(0x5B); // POP BX
        pop.registers().setSs(0x1000);
        pop.registers().setSp(0x0200);
        pop.memory().write16(0x1000, 0x0200, 0xCAFE);
        pop.step();
        assertEquals(0xCAFE, pop.registers().getBx());
        assertEquals(0x0202, pop.registers().getSp());

        CPU8086 wrapped = boot(0x50, 0x58); // PUSH AX; POP AX
        wrapped.registers().setAx(0x1234);
        wrapped.registers().setSp(0);
        wrapped.step();
        assertEquals(0xFFFE, wrapped.registers().getSp());
        assertEquals(0x1234, wrapped.memory().read16(0, 0xFFFE));
        wrapped.registers().setAx(0);
        wrapped.step();
        assertEquals(0x1234, wrapped.registers().getAx());
        assertEquals(0, wrapped.registers().getSp());
    }

    @Test
    void pushSpStoresTheDecrementedValueAndPopSpUsesThePoppedValue() {
        for (int[] code : new int[][] {{0x54}, {0xFF, 0xF4}}) {
            CPU8086 cpu = boot(code);
            cpu.registers().setSs(0x1000);
            cpu.registers().setSp(0x0200);
            cpu.step();
            assertEquals(0x01FE, cpu.registers().getSp());
            assertEquals(0x01FE, cpu.memory().read16(0x1000, 0x01FE));
        }
        for (int[] code : new int[][] {{0x5C}, {0x8F, 0xC4}}) {
            CPU8086 cpu = boot(code);
            cpu.registers().setSs(0x1000);
            cpu.registers().setSp(0x0200);
            cpu.memory().write16(0x1000, 0x0200, 0x3456);
            cpu.step();
            assertEquals(0x3456, cpu.registers().getSp());
        }
    }

    @Test
    void segmentAndFlagPushPopFormsUseTheStack() {
        int[][] pushes = {{0x06, 0x1111}, {0x0E, 0xC000}, {0x16, 0x1000}, {0x1E, 0x2222}};
        for (int[] form : pushes) {
            CPU8086 cpu = boot(form[0]);
            cpu.registers().setSs(0x1000);
            cpu.registers().setSp(0x0200);
            cpu.registers().setEs(0x1111);
            cpu.registers().setDs(0x2222);
            cpu.step();
            assertEquals(form[1], cpu.memory().read16(0x1000, 0x01FE));
        }
        for (int opcode : new int[] {0x07, 0x17, 0x1F}) {
            CPU8086 cpu = boot(opcode);
            cpu.registers().setSs(0x1000);
            cpu.registers().setSp(0x0200);
            cpu.memory().write16(0x1000, 0x0200, 0x3456);
            cpu.step();
            assertEquals(0x0202, cpu.registers().getSp());
            assertEquals(0x3456, switch (opcode) {
                case 0x07 -> cpu.registers().getEs();
                case 0x17 -> cpu.registers().getSs();
                default -> cpu.registers().getDs();
            });
        }

        CPU8086 flags = boot(0x9C, 0x9D);
        flags.registers().setSs(0x1000);
        flags.registers().setSp(0x0200);
        flags.registers().setFlags(0x0BD5);
        flags.step();
        assertEquals(0x0BD5, flags.memory().read16(0x1000, 0x01FE));
        flags.registers().setFlags(0);
        flags.step();
        assertEquals(0x0BD5, flags.registers().getFlags());
        assertEquals(0x0200, flags.registers().getSp());
    }

    @Test
    void memoryPushAndPopOverrideOnlyTheOperandSegment() {
        CPU8086 cpu = boot(0x26, 0xFF, 0x37, 0x26, 0x8F, 0x07); // ES:[BX]
        cpu.registers().setSs(0x1000);
        cpu.registers().setEs(0x2000);
        cpu.registers().setSp(0x0200);
        cpu.registers().setBx(0x0300);
        cpu.memory().write16(0, 0x0300, 0x1111);
        cpu.memory().write16(0x2000, 0x0300, 0xBEEF);
        cpu.step();
        assertEquals(0xBEEF, cpu.memory().read16(0x1000, 0x01FE));
        assertEquals(0x1111, cpu.memory().read16(0, 0x0300));
        cpu.memory().write16(0x2000, 0x0300, 0);
        cpu.step();
        assertEquals(0xBEEF, cpu.memory().read16(0x2000, 0x0300));
        assertEquals(0x1111, cpu.memory().read16(0, 0x0300));
        assertEquals(0x0200, cpu.registers().getSp());
    }

    @Test
    void nearCallAndReturnUseTheNextIp() {
        CPU8086 cpu = boot(0xE8, 0x03, 0x00, 0xF4, 0, 0, 0xC3);
        cpu.registers().setSs(0x1000);
        cpu.registers().setSp(0x0200);
        cpu.step();
        assertEquals(6, cpu.registers().getIp());
        assertEquals(0x01FE, cpu.registers().getSp());
        assertEquals(3, cpu.memory().read16(0x1000, 0x01FE));
        cpu.step();
        assertEquals(3, cpu.registers().getIp());
        assertEquals(0x0200, cpu.registers().getSp());
        cpu.step();
        assertTrue(cpu.isHalted());
    }

    @Test
    void farCallAndReturnUseIpThenCsOnTheStack() {
        CPU8086 cpu = bootWithHandler(0xCB, 0x9A, 0x00, 0x01, 0x00, 0xC0, 0xF4);
        cpu.registers().setSs(0x1000);
        cpu.registers().setSp(0x0200);
        cpu.step();
        assertEquals(0xC000, cpu.registers().getCs());
        assertEquals(0x0100, cpu.registers().getIp());
        assertEquals(0x01FC, cpu.registers().getSp());
        assertEquals(5, cpu.memory().read16(0x1000, 0x01FC));
        assertEquals(0xC000, cpu.memory().read16(0x1000, 0x01FE));
        cpu.step();
        assertEquals(0xC000, cpu.registers().getCs());
        assertEquals(5, cpu.registers().getIp());
        assertEquals(0x0200, cpu.registers().getSp());
        cpu.step();
        assertTrue(cpu.isHalted());
    }

    @Test
    void nearAndFarReturnsApplyOptionalStackCleanup() {
        CPU8086 near = boot(0xC2, 0x04, 0x00);
        near.registers().setSs(0x1000);
        near.registers().setSp(0x0200);
        near.memory().write16(0x1000, 0x0200, 0x1234);
        near.step();
        assertEquals(0x1234, near.registers().getIp());
        assertEquals(0x0206, near.registers().getSp());

        CPU8086 far = boot(0xCA, 0x04, 0x00);
        far.registers().setSs(0x1000);
        far.registers().setSp(0x0200);
        far.memory().write16(0x1000, 0x0200, 0x3456);
        far.memory().write16(0x1000, 0x0202, 0x1234);
        far.step();
        assertEquals(0x3456, far.registers().getIp());
        assertEquals(0x1234, far.registers().getCs());
        assertEquals(0x0208, far.registers().getSp());
    }

    @Test
    void indirectNearAndFarCallsAndJumpsUseTheirOperands() {
        CPU8086 nearCall = bootWithHandler(0xC3, 0xFF, 0xD0); // CALL AX
        nearCall.registers().setAx(0x0100);
        nearCall.registers().setSs(0x1000);
        nearCall.registers().setSp(0x0200);
        nearCall.step();
        assertEquals(0x0100, nearCall.registers().getIp());
        assertEquals(2, nearCall.memory().read16(0x1000, 0x01FE));
        nearCall.step();
        assertEquals(2, nearCall.registers().getIp());

        CPU8086 farCall = bootWithHandler(0xCB, 0x26, 0xFF, 0x1E, 0x00, 0x03);
        farCall.registers().setSs(0x1000);
        farCall.registers().setEs(0x2000);
        farCall.registers().setSp(0x0200);
        farCall.memory().write16(0, 0x0300, 0x2222);
        farCall.memory().write16(0x2000, 0x0300, 0x0100);
        farCall.memory().write16(0x2000, 0x0302, 0xC000);
        farCall.step();
        assertEquals(0xC000, farCall.registers().getCs());
        assertEquals(0x0100, farCall.registers().getIp());
        assertEquals(5, farCall.memory().read16(0x1000, 0x01FC));
        farCall.step();
        assertEquals(5, farCall.registers().getIp());

        CPU8086 nearJump = boot(0xFF, 0xE0); // JMP AX
        nearJump.registers().setAx(0x0100);
        nearJump.step();
        assertEquals(0x0100, nearJump.registers().getIp());

        CPU8086 farJump = boot(0x26, 0xFF, 0x2E, 0x00, 0x03);
        farJump.registers().setEs(0x2000);
        farJump.memory().write16(0, 0x0300, 0x2222);
        farJump.memory().write16(0x2000, 0x0300, 0x0100);
        farJump.memory().write16(0x2000, 0x0302, 0xC000);
        farJump.step();
        assertEquals(0xC000, farJump.registers().getCs());
        assertEquals(0x0100, farJump.registers().getIp());

        CPU8086 arithmetic = boot(0xFF, 0xC0, 0xFF, 0xC8); // INC AX; DEC AX
        arithmetic.registers().setAx(7);
        arithmetic.step();
        assertEquals(8, arithmetic.registers().getAx());
        arithmetic.step();
        assertEquals(7, arithmetic.registers().getAx());
        assertEquals(4, arithmetic.registers().getIp());
    }

    @Test
    void shortAndNearRelativeJumpsUseSignedDisplacementsAndWrapIp() {
        CPU8086 shortBack = boot(0xEB, 0xFE); // JMP -2
        shortBack.step();
        assertEquals(0, shortBack.registers().getIp());

        CPU8086 nearBack = boot(0xE9, 0xFC, 0xFF); // JMP -4
        nearBack.step();
        assertEquals(0xFFFF, nearBack.registers().getIp());

        CPU8086 nearForward = boot(0xE9, 0x02, 0x00);
        nearForward.step();
        assertEquals(5, nearForward.registers().getIp());
    }

    @Test
    void allSixteenShortConditionalJumpsTakeAndSkipTheirBranches() {
        // Each row supplies flags that make Jcc true, then flags that make it false.
        int[][] flags = {
                {0x800, 0}, {0, 0x800}, {1, 0}, {0, 1},
                {0x40, 0}, {0, 0x40}, {1, 0}, {0, 1},
                {0x80, 0}, {0, 0x80}, {4, 0}, {0, 4},
                {0x80, 0}, {0, 0x80}, {0x40, 0}, {0, 0x40}
        };
        for (int condition = 0; condition < 16; condition++) {
            for (int outcome = 0; outcome < 2; outcome++) {
                CPU8086 cpu = boot(0x70 + condition, 2);
                cpu.registers().setFlags(flags[condition][outcome]);
                cpu.step();
                assertEquals(outcome == 0 ? 4 : 2, cpu.registers().getIp(),
                        "condition " + condition + ", outcome " + outcome);
            }
        }
    }

    @Test
    void loopVariantsDecrementCxOnlyWhereRequired() {
        int[][] cases = {
                {0xE0, 2, 0, 0, 1}, // LOOPNE taken
                {0xE0, 2, 0x40, 2, 1}, // LOOPNE blocked by ZF
                {0xE1, 2, 0x40, 0, 1}, // LOOPE taken
                {0xE1, 2, 0, 2, 1}, // LOOPE blocked by ZF
                {0xE2, 2, 0, 0, 1}, // LOOP taken
                {0xE2, 1, 0, 2, 0}, // LOOP stops at zero
                {0xE2, 0, 0, 0, 0xFFFF}, // LOOP wraps CX
                {0xE3, 0, 0, 0, 0}, // JCXZ taken without decrement
                {0xE3, 1, 0, 2, 1}  // JCXZ not taken
        };
        for (int[] c : cases) {
            CPU8086 cpu = boot(c[0], 0xFE);
            cpu.registers().setCx(c[1]);
            cpu.registers().setFlags(c[2]);
            cpu.step();
            assertEquals(c[3], cpu.registers().getIp(), "opcode " + c[0]);
            assertEquals(c[4], cpu.registers().getCx(), "CX opcode " + c[0]);
        }
    }

    @Test
    void int3IntAndIntoUseVectorsAndIretRestoresTheFrame() {
        int[][] instructions = {{0xCC}, {0xCD, 0x21}, {0xCE}};
        int[] vectors = {3, 0x21, 4};
        for (int i = 0; i < instructions.length; i++) {
            CPU8086 cpu = bootWithHandler(0xCF, instructions[i]);
            int vector = vectors[i];
            cpu.memory().write16(0, vector * 4, 0x0100);
            cpu.memory().write16(0, vector * 4 + 2, 0xC000);
            cpu.registers().setSs(0x1000);
            cpu.registers().setSp(0x0200);
            cpu.registers().setFlags(0x0BD5); // OF, IF and TF set.
            cpu.step();
            assertEquals(0xC000, cpu.registers().getCs());
            assertEquals(0x0100, cpu.registers().getIp());
            assertEquals(0x01FA, cpu.registers().getSp());
            assertEquals(instructions[i].length, cpu.memory().read16(0x1000, 0x01FA));
            assertEquals(0xC000, cpu.memory().read16(0x1000, 0x01FC));
            assertEquals(0x0BD5, cpu.memory().read16(0x1000, 0x01FE));
            assertFalse(cpu.registers().getIF());
            assertFalse(cpu.registers().getTF());
            cpu.step(); // IRET
            assertEquals(0xC000, cpu.registers().getCs());
            assertEquals(instructions[i].length, cpu.registers().getIp());
            assertEquals(0x0200, cpu.registers().getSp());
            assertEquals(0x0BD5, cpu.registers().getFlags());
        }
    }

    @Test
    void intoWithoutOverflowLeavesTheStackAndFlagsUntouched() {
        CPU8086 cpu = boot(0xCE);
        cpu.registers().setSs(0x1000);
        cpu.registers().setSp(0x0200);
        cpu.registers().setFlags(0x0315); // IF and TF set, OF clear.
        cpu.step();
        assertEquals(1, cpu.registers().getIp());
        assertEquals(0x0200, cpu.registers().getSp());
        assertEquals(0x0315, cpu.registers().getFlags());
    }

    @Test
    void interruptFrameAndIretWrapTheStackPointer() {
        CPU8086 cpu = bootWithHandler(0xCF, 0xCC);
        cpu.memory().write16(0, 3 * 4, 0x0100);
        cpu.memory().write16(0, 3 * 4 + 2, 0xC000);
        cpu.registers().setSp(0);
        cpu.registers().setFlags(0x0300);
        cpu.step();
        assertEquals(0xFFFA, cpu.registers().getSp());
        assertEquals(1, cpu.memory().read16(0, 0xFFFA));
        assertEquals(0xC000, cpu.memory().read16(0, 0xFFFC));
        assertEquals(0x0300, cpu.memory().read16(0, 0xFFFE));
        cpu.step();
        assertEquals(0, cpu.registers().getSp());
        assertEquals(0x0300, cpu.registers().getFlags());
    }

    @Test
    void invalidStackAndControlEncodingsReportTheirStart() {
        int[][] invalid = {
                {0x8F, 0xC8}, // POP /1
                {0xFF, 0xD8}, // Far CALL with register operand
                {0xFF, 0xE8}, // Far JMP with register operand
                {0xFF, 0xF8}, // Group FF /7
                {0x0F},       // Undocumented POP CS
                {0x68, 0, 0}, // 80186 PUSH immediate
                {0x60},       // 80186 PUSHA
                {0xC8, 0, 0, 0} // 80186 ENTER
        };
        for (int[] code : invalid) {
            CPU8086 cpu = boot(code);
            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, cpu::step);
            assertTrue(error.getMessage().contains("C000:0000"));
        }
    }

    private static CPU8086 boot(int... program) {
        return bootWithHandler(0xF4, program);
    }

    private static CPU8086 bootWithHandler(int handlerOpcode, int... program) {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xEA;
        rom[0x3FFF4] = (byte) 0xC0;
        rom[0x0100] = (byte) handlerOpcode;
        for (int i = 0; i < program.length; i++) rom[i] = (byte) program[i];
        CPU8086 cpu = new CPU8086();
        cpu.hostBridge().loadRom(rom);
        cpu.step();
        return cpu;
    }
}
