package dervogel101.de.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CPURegisterTest {
    @Test
    void registersKeepOnlySixteenBits() {
        CPURegister registers = new CPURegister();
        registers.setAx(0x12345);
        registers.setSp(-1);
        registers.setCs(0x10000);
        registers.setIp(0x12345);

        assertEquals(0x2345, registers.getAx());
        assertEquals(0xFFFF, registers.getSp());
        assertEquals(0, registers.getCs());
        assertEquals(0x2345, registers.getIp());
    }

    @Test
    void flagsCanBeSetAndClearedIndependently() {
        CPURegister registers = new CPURegister();
        int expectedFlags = 0;
        for (CPURegisterFlagsEnum flag : CPURegisterFlagsEnum.values()) {
            expectedFlags |= flag.getMask();
            registers.setFlag(flag, true);
            assertTrue(registers.getFlag(flag));
            assertEquals(expectedFlags, registers.getFlags());
        }

        registers.setCF(false);
        assertFalse(registers.getCF());
        assertTrue(registers.getZF());
        assertEquals(expectedFlags & ~CPURegisterFlagsEnum.CF.getMask(), registers.getFlags());

        registers.setFlags(0x1FFFF);
        assertEquals(0xFFFF, registers.getFlags());
    }
}
