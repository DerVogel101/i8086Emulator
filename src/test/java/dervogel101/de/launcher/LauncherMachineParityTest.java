package dervogel101.de.launcher;

import dervogel101.de.i86.runtime.*;
import dervogel101.de.state.*;
import java.nio.file.*;
import java.util.EnumMap;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LauncherMachineParityTest {
    @Test void installedTierAndItsPacingSurviveSessionRestore() throws Exception {
        var updates = new LinkedBlockingQueue<LauncherController.Snapshot>();
        byte[] state;
        try (var cpu = new LauncherController(updates::add, error -> fail(error))) {
            cpu.loadRom(rom());
            await(updates, s -> s.romName().startsWith("parity"));
            cpu.configure(1_000, 20, 10);
            cpu.configureMachine(HardwareProfile.complete(), 1, "tier_test");
            assertEquals(1, cpu.inspectMachine().shardTier());
            assertEquals(ShardSpeed.TIER_1, cpu.inspectMachine().speed());
            state = cpu.saveSessionState(false);
        }
        try (var restored = new LauncherController(snapshot -> {}, error -> fail(error))) {
            restored.restoreSessionState(state);
            assertEquals(1, restored.inspectMachine().shardTier());
            assertEquals(ShardSpeed.TIER_1, restored.inspectMachine().speed());
            restored.configureMachine(HardwareProfile.complete(), 0, "tier_test");
            assertFalse(restored.inspectMachine().shardPresent());
        }
        var media = new java.io.ByteArrayOutputStream();
        var mediaOut = new java.io.DataOutputStream(media); mediaOut.writeInt(0);
        for (int i=0;i<32;i++) new PunchcardDevice(i>=16).writeState(mediaOut);
        byte[] oldFormat = java.util.Arrays.copyOf(state,state.length-media.size()); oldFormat[3] = 1;
        try (var legacy = new LauncherController(snapshot -> {}, error -> fail(error))) {
            legacy.restoreSessionState(oldFormat);
            assertEquals(3, legacy.inspectMachine().shardTier());
            assertEquals(ShardSpeed.TIER_1, legacy.inspectMachine().speed());
        }
    }

    @Test void shardAndPowerOffInstallationRulesAreEnforced() throws Exception {
        var updates = new LinkedBlockingQueue<LauncherController.Snapshot>();
        try (var cpu = new LauncherController(updates::add, error -> fail(error))) {
            cpu.loadRom(rom());
            await(updates, s -> s.romName().startsWith("parity"));
            cpu.configureMachine(HardwareProfile.complete(), false, "test");
            cpu.run();
            await(updates, s -> s.status().contains("no speed shard") && !s.machine().powered());
            cpu.configureMachine(HardwareProfile.complete(), true, "test");
            await(updates, s -> s.running() && s.machine().powered());
            cpu.pauseAndWait();
            assertThrows(IllegalStateException.class, () -> cpu.configureMachine(HardwareProfile.complete(), true, "other"));
            assertThrows(IllegalStateException.class, cpu::newStorage);
            cpu.powerSupply(false);
            assertFalse(cpu.inspectMachine().powered());
            cpu.newStorage();
            assertTrue(cpu.inspectImages().storageConnected());
        }
    }

    @Test void pausedPoweredMembersRetainSharedBytesUntilLastPowerOff() throws Exception {
        var groups = new SharedMemoryGroups();
        var firstUpdates = new LinkedBlockingQueue<LauncherController.Snapshot>();
        var secondUpdates = new LinkedBlockingQueue<LauncherController.Snapshot>();
        try (var first = new LauncherController(firstUpdates::add, error -> fail(error), groups, 1, () -> 0);
             var second = new LauncherController(secondUpdates::add, error -> fail(error), groups, 2, () -> 0)) {
            first.configureMachine(HardwareProfile.complete(), true, "TEST");
            second.configureMachine(HardwareProfile.complete(), true, "test");
            first.loadRom(rom()); second.loadRom(rom());
            await(firstUpdates, s -> s.romName().startsWith("parity"));
            await(secondUpdates, s -> s.romName().startsWith("parity"));
            first.step(); second.step();
            await(firstUpdates, s -> s.totalInstructions() == 1);
            await(secondUpdates, s -> s.totalInstructions() == 1);
            groups.region("test").write8(0, 42);
            first.powerSupply(false);
            assertEquals(42, groups.region("test").read8(0));
            second.powerSupply(false);
            assertEquals(0, groups.region("test").read8(0));
        }
    }

    @Test void deletionRequiresExplicitReselectionEvenWithPsuHigh() throws Exception {
        var groups = new SharedMemoryGroups();
        var updates = new LinkedBlockingQueue<LauncherController.Snapshot>();
        try (var cpu = new LauncherController(updates::add, error -> fail(error), groups, 1, () -> 0)) {
            cpu.loadRom(rom());
            await(updates, s -> s.romName().startsWith("parity"));
            cpu.step();
            await(updates, s -> s.totalInstructions() == 1);
            groups.delete("default");
            cpu.sharedRegionDeleted("DEFAULT");
            assertFalse(cpu.inspectMachine().powered());
            cpu.powerSupply(true);
            assertFalse(cpu.inspectMachine().powered());
            cpu.configureMachine(HardwareProfile.complete(), true, "new");
            assertTrue(cpu.inspectMachine().powered());
        }
    }

    @Test void windows1252SignEditsAndNvramSurviveRequiredBoundaries() throws Exception {
        assertTrue(DeviceViewPanel.signFont().canDisplay('ä'));
        var updates = new LinkedBlockingQueue<LauncherController.Snapshot>();
        try (var cpu = new LauncherController(updates::add, error -> fail(error))) {
            cpu.loadRom(rom());
            await(updates, s -> s.romName().startsWith("parity"));
            cpu.writeSignLine(0, 0, "€ä");
            var sign = await(updates, s -> (s.devices().sign()[1] & 255) == 0x80);
            assertEquals(0xE4, sign.devices().sign()[2] & 255);
            assertEquals("€ä", DeviceViewPanel.signText(sign.devices().sign(), 0));
            assertThrows(IllegalArgumentException.class, () -> cpu.writeSignLine(0, 0, "😀"));
            cpu.step();
            await(updates, s -> s.machine().powered());
            cpu.writeMemory(0xA0000, 0x5A);
            cpu.testPin(true);
            cpu.pauseAndWait();
            cpu.powerCycle();
            var cycled = await(updates, s -> s.status().equals("Power cycled"));
            assertTrue(cycled.testHigh());
            assertTrue(cycled.images().nvramDirty());
            cpu.memoryBase(0xA0000);
            await(updates, s -> s.memoryBase() == 0xA0000 && (s.memory()[0] & 255) == 0x5A);
        }
    }

    private static Path rom() throws Exception {
        byte[] bytes = new byte[CPUMemory.ROM_SIZE];
        bytes[0x3FFF0] = 0x0F; bytes[0x3FFF1] = 1;
        bytes[0x3FFF2] = (byte) 0xEB; bytes[0x3FFF3] = (byte) 0xFC;
        Path file = Files.createTempFile("parity", ".rom");
        Files.write(file, bytes); file.toFile().deleteOnExit(); return file;
    }

    private static LauncherController.Snapshot await(BlockingQueue<LauncherController.Snapshot> queue,
                                                     Predicate<LauncherController.Snapshot> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var snapshot = queue.poll(100, TimeUnit.MILLISECONDS);
            if (snapshot != null && condition.test(snapshot)) return snapshot;
        }
        throw new AssertionError("No matching launcher snapshot");
    }
}
