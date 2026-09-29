package dervogel101.de.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CPUIOTest {
    @Test
    void redstoneAndSignOutputsArePolledOnlyWhenChanged() {
        CPUIO io = new CPUIO();
        io.setRedstoneInput(127, 15);
        assertEquals(15, io.read8(0xFE));
        io.write8(0xFE, 0); // CPU cannot replace a host input
        assertEquals(15, io.read8(0xFE));
        io.write8(0xFF, 0x2A); // Output high nibble is ignored
        assertEquals(10, io.read8(0xFF));

        io.write8(0x8000, 0x71); // Sign 127, front line 1 format
        io.write8(0x8001, 'A');
        io.write8(0x8082, 0x77); // Unused sign slot
        io.write8(0x8151, 0x77); // Unassigned port
        assertEquals(0, io.read8(0x8082));
        assertEquals(0, io.read8(0x8151));

        CPUIO.OutputChanges changes = io.drainChangedOutputs();
        assertEquals(10, changes.redstone().get(127));
        assertEquals('A', Byte.toUnsignedInt(changes.signs().get(127).bytes()[1]));
        changes.signs().get(127).bytes()[1] = 0;
        assertEquals('A', Byte.toUnsignedInt(changes.signs().get(127).bytes()[1]));
        assertTrue(io.drainChangedOutputs().redstone().isEmpty());
        assertTrue(io.drainChangedOutputs().signs().isEmpty());

        io.setRedstoneInput(0, 12);
        io.write16(0xFFFF, 0x1234); // Unassigned low port; wrapped high port is read-only input
        assertEquals(12 << 8, io.read16(0xFFFF));
        assertEquals(12, io.read8(0));
    }

    @Test
    void outClearsOnlyTheClickBitsWrittenAsZero() {
        CPUIO io = new CPUIO();
        io.clickSign(0, true);
        io.clickSign(0, true); // Repeated front click coalesces
        io.clickSign(0, false);
        assertEquals(0x0700, io.read16(0x0180));

        io.write8(0x0180, 0); // Low byte does not acknowledge flags
        assertEquals(0x0700, io.read16(0x0180));
        io.write8(0x0181, 0x06); // Clear general click, keep both side bits
        assertEquals(0x0600, io.read16(0x0180));
        io.write16(0x0180, 0x0500); // Clear front only
        assertEquals(0x0400, io.read16(0x0180));
        io.write8(0x0181, 0);
        assertEquals(0, io.read16(0x0180));
    }
}
