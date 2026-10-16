package dervogel101.de;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YieldInstructionTest {
    @Test void yieldsPreserveStateAndAreNotHalts() {
        for (int mode = 0; mode <= 1; mode++) {
            var cpu = code(0x0F, mode, 0x90);
            var r = cpu.registers();
            r.setAx(0x1234); r.setBx(0x5678); r.setCx(0x9ABC); r.setDx(0xDEF0);
            r.setSp(0x4000); r.setBp(0x5000); r.setSi(0x6000); r.setDi(0x7000);
            r.setDs(0x1000); r.setEs(0x2000); r.setSs(0x3000); r.setFlags(0x8D5);
            int flags = r.getFlags();
            cpu.memory().write8(0, 0x1000, 0xAB);
            assertEquals(mode == 0 ? CPU8086.StepOutcome.YIELD_BATCH : CPU8086.StepOutcome.YIELD_TICK, cpu.step());
            assertEquals(2, r.getIp()); assertEquals(0, r.getCs());
            assertEquals(0x1234, r.getAx()); assertEquals(0x5678, r.getBx());
            assertEquals(0x9ABC, r.getCx()); assertEquals(0xDEF0, r.getDx());
            assertEquals(0x4000, r.getSp()); assertEquals(0x5000, r.getBp());
            assertEquals(0x6000, r.getSi()); assertEquals(0x7000, r.getDi());
            assertEquals(0x1000, r.getDs()); assertEquals(0x2000, r.getEs());
            assertEquals(0x3000, r.getSs()); assertEquals(flags, r.getFlags());
            assertEquals(0xAB, cpu.memory().read8(0, 0x1000)); assertFalse(cpu.isHalted());
            assertEquals(CPU8086.StepOutcome.INSTRUCTION, cpu.step());
            assertEquals(3, r.getIp());
        }
    }

    @Test void invalidModesAndPrefixesFailAtTheirStartingAddress() {
        for (int mode : new int[] {2, 11, 255}) {
            var cpu = code(0x0F, mode);
            var error = assertThrows(UnsupportedOperationException.class, cpu::step);
            assertTrue(error.getMessage().contains("YIELD"));
            assertTrue(error.getMessage().contains("0000:0000"));
        }
        for (int prefix : new int[] {0x26, 0x2E, 0x36, 0x3E, 0xF0, 0xF2, 0xF3}) {
            var cpu = code(prefix, 0x0F, 0);
            assertThrows(UnsupportedOperationException.class, cpu::step);
            assertEquals(2, cpu.registers().getIp(), "Invalid prefixes must not consume the mode");
        }
    }

    @Test void coprocessorOpcodesStillFailBeforeConsumingOperands() {
        for (int opcode = 0xD8; opcode <= 0xDF; opcode++) {
            var cpu = code(opcode, 0);
            assertThrows(UnsupportedOperationException.class, cpu::step);
            assertEquals(1, cpu.registers().getIp());
        }
    }

    @Test void yieldConsumesTheStiInterruptShadow() {
        var cpu = code(0xFB, 0x0F, 0, 0x90);
        cpu.memory().write16(0, 0x80, 0x100); cpu.memory().write16(0, 0x82, 0);
        assertEquals(CPU8086.StepOutcome.INSTRUCTION, cpu.step());
        cpu.hostBridge().submitIrq(0);
        assertEquals(CPU8086.StepOutcome.YIELD_BATCH, cpu.step());
        assertEquals(CPU8086.StepOutcome.EVENT, cpu.step());
        assertEquals(0x100, cpu.registers().getIp());
    }

    @Test void immediateFetchWrapsAtTheCodeSegmentBoundary() {
        var cpu = new CPU8086(); cpu.jumpTo(0, 0xFFFF);
        cpu.memory().write8(0, 0xFFFF, 0x0F); cpu.memory().write8(0, 0, 1);
        assertEquals(CPU8086.StepOutcome.YIELD_TICK, cpu.step());
        assertEquals(1, cpu.registers().getIp());
    }

    private static CPU8086 code(int... bytes) {
        var cpu = new CPU8086(); cpu.jumpTo(0, 0);
        for (int i = 0; i < bytes.length; i++) cpu.memory().write8(0, i, bytes[i]);
        return cpu;
    }
}
