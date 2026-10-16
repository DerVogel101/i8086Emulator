package dervogel101.de.launcher;

import dervogel101.de.state.CPUMemory;
import dervogel101.de.state.IoStorageDevice;
import dervogel101.de.state.SharedMemoryRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class LauncherMultiCpuTest {
    private Path directory;

    @BeforeEach
    void createDirectory() throws Exception {
        Path parent = Files.createDirectories(Path.of("build", "launcher-test-tmp").toAbsolutePath());
        directory = Files.createTempDirectory(parent, "case-");
    }

    @AfterEach
    void removeDirectory() throws Exception {
        Path parent = Path.of("build", "launcher-test-tmp").toAbsolutePath().normalize();
        if (!directory.toAbsolutePath().normalize().startsWith(parent)) throw new IllegalStateException("Unexpected test directory");
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void separateWorkersRunRomsAgainstOneSharedRegionAndKeepPrivateMemory() throws Exception {
        SharedMemoryRegion region = new SharedMemoryRegion();
        BlockingQueue<LauncherController.Snapshot> firstSnapshots = new LinkedBlockingQueue<>();
        BlockingQueue<LauncherController.Snapshot> secondSnapshots = new LinkedBlockingQueue<>();
        try (LauncherController first = new LauncherController(firstSnapshots::add, error -> fail(error), region, () -> 50);
             LauncherController second = new LauncherController(secondSnapshots::add, error -> fail(error), region, () -> 50)) {
            first.loadRom(rom("first", 0, 0x11));
            second.loadRom(rom("second", 1, 0x22));
            await(firstSnapshots, s -> s.romName().equals("first.rom"));
            await(secondSnapshots, s -> s.romName().equals("second.rom"));
            first.run();
            second.run();
            await(firstSnapshots, s -> s.totalInstructions() > 5);
            await(secondSnapshots, s -> s.totalInstructions() > 5);
            first.pauseAndWait();
            second.pauseAndWait();
            assertEquals(0x11, region.read8(0));
            assertEquals(0x22, region.read8(1));

            first.writeMemory(0xA0000, 0x5A);
            first.pauseAndWait();
            first.powerSupply(false);
            first.newStorage();
            assertTrue(first.inspectImages().storageConnected());
            assertFalse(second.inspectImages().storageConnected());
            second.memoryBase(0xA0000);
            assertEquals(0, Byte.toUnsignedInt(await(secondSnapshots, s -> s.memoryBase() == 0xA0000).memory()[0]));
            first.powerCycle();
            await(firstSnapshots, s -> s.status().equals("Power cycled"));
            assertEquals(0x11, region.read8(0)); // A selected CPU power cycle keeps shared memory.

            SharedMemoryRegion replacement = new SharedMemoryRegion();
            first.powerCycleWithSharedMemory(replacement);
            second.powerCycleWithSharedMemory(replacement);
            assertEquals(0, replacement.read16(0));
            first.writeMemory(0xB0002, 0x33);
            first.pauseAndWait();
            second.memoryBase(0xB0000);
            assertEquals(0x33, Byte.toUnsignedInt(await(secondSnapshots,
                    s -> s.memoryBase() == 0xB0000 && Byte.toUnsignedInt(s.memory()[2]) == 0x33).memory()[2]));
        }
    }

    @Test
    void imageFilesRoundTripAndFailedSavesStayDirty() throws Exception {
        for (int port = 0x8160; port <= 0x8164; port++) assertTrue(LauncherController.writableIo(port));
        assertFalse(LauncherController.writableIo(0x815F));
        assertFalse(LauncherController.writableIo(0x8165));
        Path nvram = directory.resolve("cpu.nvram");
        Path storageFile = directory.resolve("cpu.storage");
        BlockingQueue<LauncherController.Snapshot> snapshots = new LinkedBlockingQueue<>();
        try (LauncherController cpu = new LauncherController(snapshots::add, error -> fail(error))) {
            cpu.newNvram();
            cpu.writeMemory(0xA0000, 0x5A);
            cpu.pauseAndWait();
            cpu.saveNvram(nvram);
            assertEquals(CPUMemory.NVRAM_SIZE, Files.size(nvram));
            assertEquals(0x5A, Byte.toUnsignedInt(Files.readAllBytes(nvram)[0]));
            cpu.writeMemory(0xA0000, 0x66);
            cpu.pauseAndWait();
            assertTrue(cpu.inspectImages().nvramDirty());
            assertThrows(RuntimeException.class, () -> cpu.saveNvram(directory.resolve("missing/file")));
            assertTrue(cpu.inspectImages().nvramDirty());
            cpu.discardNvram();
            assertFalse(cpu.inspectImages().nvramDirty());

            cpu.newStorage();
            cpu.writeIo(0x8160, 0x34);
            cpu.writeIo(0x8161, 0x12);
            cpu.writeIo(0x8162, 0xFF);
            cpu.writeIo(0x8163, 0xFF);
            cpu.writeIo(0x8164, 2);
            cpu.pauseAndWait();
            cpu.saveStorage(storageFile);
            assertEquals(IoStorageDevice.SIZE, Files.size(storageFile));
            byte[] image = Files.readAllBytes(storageFile);
            assertEquals(0x34, Byte.toUnsignedInt(image[image.length - 2]));
            assertEquals(0x12, Byte.toUnsignedInt(image[image.length - 1]));
            cpu.disconnectStorage();
            assertFalse(cpu.inspectImages().storageConnected());
            cpu.openStorage(storageFile);
            assertTrue(cpu.inspectImages().storageConnected());
            cpu.openNvram(nvram);
            assertFalse(cpu.inspectImages().nvramDirty());
            cpu.loadRom(rom("persist", 0, 0x11));
            await(snapshots, s -> s.romName().equals("persist.rom"));
            cpu.memoryBase(0xA0000);
            await(snapshots, s -> s.memoryBase() == 0xA0000
                    && Byte.toUnsignedInt(s.memory()[0]) == 0x5A && (s.devices().storage()[4] & 4) != 0);
            cpu.powerCycle();
            await(snapshots, s -> s.status().equals("Power cycled") && (s.devices().storage()[4] & 4) != 0);
            cpu.memoryBase(0xA0000);
            await(snapshots, s -> s.status().equals("Power cycled") && s.memoryBase() == 0xA0000
                    && Byte.toUnsignedInt(s.memory()[0]) == 0x5A);
            Path legacy = directory.resolve("legacy.nvram");
            Files.write(legacy, new byte[0x20000]);
            assertThrows(IllegalArgumentException.class, () -> cpu.openNvram(legacy));
            assertEquals(nvram, cpu.inspectImages().nvramPath());
        }
    }

    @Test
    void saveDiscardAndCancelDecisionsProtectImages() throws Exception {
        try (LauncherController cpu = new LauncherController(snapshot -> {}, error -> fail(error))) {
            cpu.newNvram();
            cpu.newStorage();
            assertFalse(ImageTransitions.resolve(List.of(cpu), true, true, new ImageTransitions.Prompt() {
                @Override public ImageTransitions.Decision decide(DebuggerBackend ignored, ImageTransitions.Kind kind) {
                    return kind == ImageTransitions.Kind.NVRAM
                            ? ImageTransitions.Decision.DISCARD : ImageTransitions.Decision.CANCEL;
                }
                @Override public Path choosePath(DebuggerBackend ignored, ImageTransitions.Kind kind) { return null; }
            }));
            assertTrue(cpu.inspectImages().nvramDirty());
            assertTrue(cpu.inspectImages().storageDirty());

            assertThrows(RuntimeException.class, () -> ImageTransitions.resolve(List.of(cpu), true, true,
                    new ImageTransitions.Prompt() {
                        @Override public ImageTransitions.Decision decide(DebuggerBackend ignored, ImageTransitions.Kind kind) {
                            return kind == ImageTransitions.Kind.NVRAM
                                    ? ImageTransitions.Decision.DISCARD : ImageTransitions.Decision.SAVE;
                        }
                        @Override public Path choosePath(DebuggerBackend ignored, ImageTransitions.Kind kind) {
                            return directory.resolve("missing/storage.img");
                        }
                    }));
            assertTrue(cpu.inspectImages().nvramDirty()); // Failed save did not apply the earlier discard.

            assertTrue(ImageTransitions.resolve(List.of(cpu), true, true, prompt(ImageTransitions.Decision.SAVE)));
            assertEquals(CPUMemory.NVRAM_SIZE, Files.size(directory.resolve("NVRAM.img")));
            assertEquals(IoStorageDevice.SIZE, Files.size(directory.resolve("STORAGE.img")));
            assertFalse(cpu.inspectImages().nvramDirty());
            assertFalse(cpu.inspectImages().storageDirty());
            cpu.writeMemory(0xA0000, 0x55);
            cpu.writeIo(0x8160, 0x99);
            cpu.writeIo(0x8164, 2);
            cpu.pauseAndWait();
            assertTrue(cpu.inspectImages().storageDirty());
            assertTrue(ImageTransitions.resolve(List.of(cpu), true, true, prompt(ImageTransitions.Decision.DISCARD)));
            assertFalse(cpu.inspectImages().nvramDirty());
            assertFalse(cpu.inspectImages().storageDirty());
        }
    }

    private ImageTransitions.Prompt prompt(ImageTransitions.Decision decision) {
        return new ImageTransitions.Prompt() {
            @Override public ImageTransitions.Decision decide(DebuggerBackend cpu, ImageTransitions.Kind kind) {
                return decision;
            }
            @Override public Path choosePath(DebuggerBackend cpu, ImageTransitions.Kind kind) {
                return directory.resolve(kind + ".img");
            }
        };
    }

    private Path rom(String name, int offset, int value) throws Exception {
        byte[] image = new byte[CPUMemory.ROM_SIZE];
        image[0x3FFF0] = (byte) 0xEA;
        image[0x3FFF4] = (byte) 0xC0;
        image[0] = (byte) 0xB8; image[1] = 0; image[2] = (byte) 0xB0; // MOV AX,B000
        image[3] = (byte) 0x8E; image[4] = (byte) 0xD8; // MOV DS,AX
        image[5] = (byte) 0xC6; image[6] = 0x06; image[7] = (byte) offset;
        image[8] = 0; image[9] = (byte) value; // MOV byte [offset],value
        image[10] = (byte) 0xEB; image[11] = (byte) 0xFE; // JMP $ forever
        Path path = directory.resolve(name + ".rom");
        Files.write(path, image);
        return path;
    }

    private static LauncherController.Snapshot await(BlockingQueue<LauncherController.Snapshot> queue,
                                                      Predicate<LauncherController.Snapshot> predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            LauncherController.Snapshot snapshot = queue.poll(100, TimeUnit.MILLISECONDS);
            if (snapshot != null && predicate.test(snapshot)) return snapshot;
        }
        throw new AssertionError("Expected launcher snapshot did not arrive");
    }
}
