package dervogel101.de.launcher;

import dervogel101.de.state.*;
import dervogel101.de.i86.runtime.ShardSpeed;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LauncherSessionTest {
    @TempDir Path directory;

    @Test void tierSettingsRoundTripAndOlderSessionContainersUseDefaults() throws Exception {
        Path file = directory.resolve("tiers.i86session");
        var custom = List.of(new ShardSpeed(1500, 7), ShardSpeed.TIER_2, ShardSpeed.TIER_3);
        LauncherSession.save(file, new LauncherSession.State(100, 20, 2, Map.of(),
                List.of(new LauncherSession.Cpu(1, new byte[]{1})), custom));
        assertEquals(custom, LauncherSession.load(file).shards());
        try (var out = new java.io.DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(0x49383657); out.writeInt(1); out.writeLong(100); out.writeInt(20); out.writeInt(2);
            out.writeInt(0); out.writeInt(1); out.writeInt(1); out.writeInt(1); out.writeByte(1);
        }
        assertEquals(List.of(ShardSpeed.TIER_1, ShardSpeed.TIER_2, ShardSpeed.TIER_3), LauncherSession.load(file).shards());
    }

    @Test void sessionRoundTripPreservesExecutionAndPoweredSharedMembership() throws Exception {
        var groups = new SharedMemoryGroups();
        var updates = new LinkedBlockingQueue<LauncherController.Snapshot>();
        Path rom = rom();
        Path file = directory.resolve("world.i86session");
        try (var cpu = new LauncherController(updates::add, error -> fail(error), groups, 7, () -> 100)) {
            cpu.loadRom(rom);
            await(updates, s -> s.romName().equals("session.rom"));
            cpu.step();
            await(updates, s -> s.totalInstructions() == 1);
            cpu.writeMemory(0x100, 0x42);
            cpu.writeMemory(0xA0000, 0x5A);
            cpu.writeMemory(0xB0000, 0x69);
            cpu.pauseAndWait();
            byte[] saved = cpu.saveSessionState(false);
            LauncherSession.save(file, new LauncherSession.State(100, 20, 12, groups.snapshot(), List.of(new LauncherSession.Cpu(7, saved))));
        }
        var world = LauncherSession.load(file);
        assertEquals(100, world.ticks()); assertEquals(12, world.nextCpuId());
        var restoredGroups = new SharedMemoryGroups(); restoredGroups.restore(world.regions());
        var restoredUpdates = new LinkedBlockingQueue<LauncherController.Snapshot>();
        try (var restored = new LauncherController(restoredUpdates::add, error -> fail(error), restoredGroups, 7, () -> 100)) {
            assertFalse(restored.restoreSessionState(world.cpus().getFirst().state()));
            var snapshot = await(restoredUpdates, s -> s.totalInstructions() == 1);
            assertTrue(snapshot.machine().powered()); assertFalse(snapshot.running());
            assertEquals(2, snapshot.code().getFirst().ip());
            assertTrue(snapshot.images().nvramDirty());
            restored.memoryBase(0x100);
            await(restoredUpdates, s -> s.memoryBase() == 0x100 && (s.memory()[0] & 255) == 0x42);
            assertEquals(0x69, restoredGroups.region("default").read8(0));
            restored.step();
            await(restoredUpdates, s -> s.totalInstructions() == 2 && s.code().getFirst().ip() == 0);
            restored.powerSupply(false);
            assertEquals(0, restoredGroups.region("default").read8(0));
        }
    }

    @Test void failedSessionSavePreservesExistingFileAndInvalidFilesAreRejected() throws Exception {
        Path file = directory.resolve("keep.i86session"); Files.writeString(file, "keep");
        var bad = new LauncherSession.State(0, 20, 1, Map.of(), List.of(new LauncherSession.Cpu(1, new byte[]{1})));
        assertThrows(IOException.class, () -> LauncherSession.save(file, bad));
        assertEquals("keep", Files.readString(file));
        assertThrows(IOException.class, () -> LauncherSession.load(file));
        try (var stream = Files.list(directory)) { assertEquals(1, stream.count()); }
    }

    @Test void missingRomPowersOffAndReportsTheReference() throws Exception {
        var updates = new LinkedBlockingQueue<LauncherController.Snapshot>();
        var errors = new LinkedBlockingQueue<String>();
        Path rom = rom();
        try (var cpu = new LauncherController(updates::add, errors::add)) {
            cpu.loadRom(rom);
            await(updates, s -> s.romName().equals("session.rom"));
            cpu.run();
            await(updates, s -> s.running());
            Files.delete(rom);
            var stopped = await(updates, s -> s.status().contains("ROM missing or invalid"));
            assertFalse(stopped.machine().powered()); assertFalse(stopped.running());
            assertTrue(stopped.terminalLog().contains("ROM missing or invalid"));
            assertNotNull(errors.poll(1, TimeUnit.SECONDS));
        }
    }

    @Test void changedRomPowersOffUntilTheOriginalImageReturns() throws Exception {
        var updates = new LinkedBlockingQueue<LauncherController.Snapshot>();
        var errors = new LinkedBlockingQueue<String>();
        Path rom = rom();
        byte[] original = Files.readAllBytes(rom);
        long timestamp = Files.getLastModifiedTime(rom).toMillis();
        try (var cpu = new LauncherController(updates::add, errors::add)) {
            cpu.loadRom(rom);
            await(updates, s -> s.romName().equals("session.rom"));
            cpu.run();
            await(updates, LauncherController.Snapshot::running);
            byte[] changed = original.clone(); changed[0] = 1;
            Files.write(rom, changed);
            Files.setLastModifiedTime(rom, java.nio.file.attribute.FileTime.fromMillis(timestamp + 2_000));
            var stopped = await(updates, s -> s.status().contains("ROM missing or invalid"));
            assertFalse(stopped.machine().powered());
            assertFalse(stopped.running());
            assertTrue(stopped.terminalLog().contains("ROM missing or invalid"));
            assertNotNull(errors.poll(1, TimeUnit.SECONDS));
            cpu.powerSupply(true);
            assertFalse(cpu.inspectMachine().powered());
            Files.write(rom, original);
            Files.setLastModifiedTime(rom, java.nio.file.attribute.FileTime.fromMillis(timestamp + 4_000));
            await(updates, s -> s.running() && s.machine().powered());
        }
    }

    @Test void restoredSessionRejectsAChangedRomWithTheSameSize() throws Exception {
        Path rom = rom();
        var updates = new LinkedBlockingQueue<LauncherController.Snapshot>();
        byte[] saved;
        try (var cpu = new LauncherController(updates::add, error -> fail(error))) {
            cpu.loadRom(rom);
            await(updates, s -> s.romName().equals("session.rom"));
            cpu.step();
            await(updates, s -> s.totalInstructions() == 1);
            saved = cpu.saveSessionState(false);
        }
        byte[] changed = Files.readAllBytes(rom); changed[0] = 1;
        Files.write(rom, changed);
        var errors = new LinkedBlockingQueue<String>();
        try (var restored = new LauncherController(updates::add, errors::add)) {
            assertFalse(restored.restoreSessionState(saved));
            assertFalse(restored.inspectMachine().powered());
            assertNotNull(errors.poll(1, TimeUnit.SECONDS));
        }
    }

    private Path rom() throws Exception {
        byte[] image = new byte[CPUMemory.ROM_SIZE];
        image[0x3FFF0] = 0x0F; image[0x3FFF1] = 1;
        image[0x3FFF2] = (byte) 0xEB; image[0x3FFF3] = (byte) 0xFC;
        Path path = directory.resolve("session.rom"); Files.write(path, image); return path;
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
