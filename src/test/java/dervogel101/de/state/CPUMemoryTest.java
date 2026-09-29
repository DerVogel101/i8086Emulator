package dervogel101.de.state;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CPUMemoryTest {
    @Test
    void regionsAndWordAccessesUseThePhysicalMap() {
        CPUMemory memory = new CPUMemory();
        assertEquals(0x12340, memory.physicalAddress(0x1234, 0));
        assertEquals(0, memory.physicalAddress(0xFFFF, 0x10));

        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0] = 0x12;
        rom[0x3FFF0] = (byte) 0xEA;
        rom[CPUMemory.ROM_SIZE - 1] = (byte) 0xAB;
        memory.loadRom(rom);
        assertEquals(0x12, memory.read8(0xC000, 0));
        assertEquals(0xEA, memory.read8(0xFFFF, 0));
        memory.write8(0xC000, 0, 0x77);
        assertEquals(0x12, memory.read8(0xC000, 0));

        memory.updateClock(0x12345, 48_000, LocalDateTime.of(2026, 9, 26, 13, 14, 15, 123_000_000));
        memory.write8(0x9000, 0xFFF2, 0xAB);
        assertEquals(0x7BAB, memory.read16(0x9000, 0xFFF2)); // RAM to millisecond clock
        assertEquals(123, memory.read16(0x9000, 0xFFF3));
        assertEquals(0x2345, memory.read16(0x9000, 0xFFF7));
        assertEquals(13, memory.read8(0x9000, 0xFFF9));
        assertEquals(25, memory.read8(0x9000, 0xFFFC));
        assertEquals(8, memory.read8(0x9000, 0xFFFD));
        assertEquals(2026, memory.read16(0x9000, 0xFFFE));

        memory.write16(0x9000, 0xFFFF, 0x7788); // Clock is read-only; next byte enters NVRAM
        assertEquals(0x77, memory.read8(0xA000, 0));
        memory.write16(0xA000, 0xFFFF, 0x6677); // NVRAM end enters shared memory
        assertEquals(0x6677, memory.read16(0xA000, 0xFFFF));
        memory.write16(0xB000, 0xFFFF, 0x2288); // Shared memory end enters read-only ROM
        assertEquals(0x1288, memory.read16(0xB000, 0xFFFF));
        memory.write16(0xFFFF, 0x000F, 0x34AB); // ROM end wraps to RAM start
        assertEquals(0x34AB, memory.read16(0xFFFF, 0x000F));
        assertEquals(0x34, memory.read8(0, 0));
        assertThrows(IllegalArgumentException.class, () -> memory.loadRom(new byte[1]));
    }

    @Test
    void nvramSnapshotStaysDirtyUntilItsSavedVersionIsAcknowledged() {
        CPUMemory memory = new CPUMemory();
        assertNull(memory.snapshotNvramIfDirty());
        memory.write8(0xA000, 0, 0); // No change
        assertNull(memory.snapshotNvramIfDirty());

        memory.write8(0xA000, 0, 0x55);
        CPUMemory.NvramSnapshot first = memory.snapshotNvramIfDirty();
        assertNotNull(first);
        assertEquals(0x10000, first.bytes().length);
        assertEquals(0x55, Byte.toUnsignedInt(first.bytes()[0]));
        first.bytes()[0] = 0; // Caller cannot mutate CPU storage or the saved snapshot
        assertEquals(0x55, Byte.toUnsignedInt(first.bytes()[0]));

        memory.write8(0xA000, 1, 0x66);
        memory.acknowledgeNvramSaved(first.version());
        CPUMemory.NvramSnapshot second = memory.snapshotNvramIfDirty();
        assertNotNull(second);
        assertEquals(0x66, Byte.toUnsignedInt(second.bytes()[1]));
        memory.acknowledgeNvramSaved(second.version());
        assertNull(memory.snapshotNvramIfDirty());
        assertThrows(IllegalArgumentException.class, () -> memory.acknowledgeNvramSaved(second.version() + 1));

        byte[] restored = new byte[CPUMemory.NVRAM_SIZE];
        restored[0] = 0x33;
        memory.loadNvram(restored);
        assertEquals(0x33, memory.read8(0xA000, 0));
        assertNull(memory.snapshotNvramIfDirty());
        assertThrows(IllegalArgumentException.class, () -> memory.loadNvram(new byte[0x20000]));
    }
}
