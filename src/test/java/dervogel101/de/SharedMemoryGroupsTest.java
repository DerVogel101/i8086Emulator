package dervogel101.de;

import dervogel101.de.state.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedMemoryGroupsTest {
    @Test void lastPowerOffClearsBytesButPreservesNamedRegion() {
        var groups = new SharedMemoryGroups();
        var first = groups.powerOn(1, "Test_RAM");
        assertSame(first, groups.powerOn(2, "test_ram"));
        first.write16(0, 0x1234);
        groups.powerOff(1);
        assertEquals(0x1234, first.read16(0));
        groups.powerOff(2);
        assertEquals(0, first.read16(0));
        assertSame(first, groups.region("TEST_RAM"));
        assertTrue(groups.snapshot().containsKey("test_ram"));
    }

    @Test void sessionSuspensionRetainsBytesAndDeletionFindsPoweredMembers() {
        var groups = new SharedMemoryGroups();
        groups.powerOn(7, "a").write8(0, 42);
        var restored = new SharedMemoryGroups();
        restored.restore(groups.snapshot());
        assertEquals(42, restored.powerOn(7, "A").read8(0));
        assertEquals(java.util.Set.of(7L), restored.delete("a"));
        assertEquals(0, restored.region("a").read8(0));
        restored.powerOff(7);
    }

    @Test void invalidIdsAndCaseCollisionsAreRejected() {
        for (String id : new String[]{"", "a b", "ä", "a".repeat(33)})
            assertThrows(IllegalArgumentException.class, () -> SharedMemoryGroups.normalize(id));
        assertThrows(IllegalArgumentException.class, () -> new SharedMemoryGroups().restore(
            java.util.Map.of("A", new byte[SharedMemoryRegion.SIZE], "a", new byte[SharedMemoryRegion.SIZE])));
    }
}
