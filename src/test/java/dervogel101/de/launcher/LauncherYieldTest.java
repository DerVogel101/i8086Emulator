package dervogel101.de.launcher;

import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class LauncherYieldTest {
    @TempDir Path directory;

    @Test void manualStepsCountYieldAndDisplayTheNextInstruction() throws Exception {
        var snapshots = new LinkedBlockingQueue<LauncherController.Snapshot>();
        var errors = new LinkedBlockingQueue<String>();
        try (var controller = new LauncherController(snapshots::add, errors::add)) {
            controller.loadRom(rom());
            await(snapshots, s -> s.romName().equals("yield.rom"));
            controller.step();
            var first = await(snapshots, s -> s.totalInstructions() == 1);
            assertEquals(2, first.code().getFirst().ip());
            assertFalse(first.running());
            controller.step();
            await(snapshots, s -> s.totalInstructions() == 2);
            controller.step();
            var tick = await(snapshots, s -> s.totalInstructions() == 3);
            assertEquals(5, tick.code().getFirst().ip());
            assertFalse(tick.registers().isBlank());
            assertTrue(errors.isEmpty());
        }
    }

    @Test void tickYieldSkipsLaterBatchesAndRunningViewsKeepThePausedSnapshot() throws Exception {
        var snapshots = new LinkedBlockingQueue<LauncherController.Snapshot>();
        var errors = new LinkedBlockingQueue<String>();
        try (var controller = new LauncherController(snapshots::add, errors::add)) {
            controller.loadRom(rom());
            var initial = await(snapshots, s -> s.romName().equals("yield.rom"));
            controller.configure(20, 1, 10);
            controller.run();
            var yielded = await(snapshots, s -> s.running() && s.totalInstructions() == 3);
            assertEquals(initial.registers(), yielded.registers());
            assertEquals(initial.code().getFirst().ip(), yielded.code().getFirst().ip());
            assertEquals(initial.code().stream().map(Disassembler8086.Instruction::assembly).toList(),
                    yielded.code().stream().map(Disassembler8086.Instruction::assembly).toList());
            assertArrayEquals(initial.memory(), yielded.memory());
            long observationEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
            while (System.nanoTime() < observationEnd) {
                var value = snapshots.poll(20, TimeUnit.MILLISECONDS);
                if (value != null) assertEquals(3, value.totalInstructions(), "Tick yield cannot run the next batch");
            }
            snapshots.clear();
            controller.pauseAndWait();
            var paused = await(snapshots, s -> !s.running());
            assertEquals(3, paused.totalInstructions());
            assertEquals(5, paused.code().getFirst().ip());
            assertTrue(errors.isEmpty());
        }
    }

    private Path rom() throws Exception {
        byte[] image = new byte[CPUMemory.ROM_SIZE];
        byte[] program = {0x0F, 0, 0x40, 0x0F, 1, 0x40, (byte) 0xF4};
        System.arraycopy(program, 0, image, 0x3FFF0, program.length);
        Path path = directory.resolve("yield.rom");
        Files.write(path, image);
        return path;
    }

    private static LauncherController.Snapshot await(BlockingQueue<LauncherController.Snapshot> values,
                                                    Predicate<LauncherController.Snapshot> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var value = values.poll(100, TimeUnit.MILLISECONDS);
            if (value != null && condition.test(value)) return value;
        }
        throw new AssertionError("Timed out waiting for yield state");
    }
}
