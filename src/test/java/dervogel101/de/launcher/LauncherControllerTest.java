package dervogel101.de.launcher;

import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class LauncherControllerTest {
    @Test
    void terminalOutputAppearsInLogSnapshotsAndSurvivesCpuReset() throws Exception {
        Path file = romFile("terminal", new byte[CPUMemory.ROM_SIZE]);
        BlockingQueue<LauncherController.Snapshot> snapshots = new LinkedBlockingQueue<>();
        try (LauncherController controller = new LauncherController(snapshots::add, error -> fail(error))) {
            assertTrue(LauncherController.writableIo(0x8130));
            assertTrue(LauncherController.writableIo(0x8150));
            assertFalse(LauncherController.writableIo(0x8151));
            controller.loadRom(file);
            await(snapshots, s -> s.romName().startsWith("terminal"));
            controller.writeIo(0x8130, 0x80); // Windows-1252 euro sign.
            controller.writeIo(0x8131, 0x0A);
            controller.writeIo(0x8150, 1);
            await(snapshots, s -> s.terminalLog().equals("€\n"));
            controller.reset();
            var reset = await(snapshots, s -> s.status().equals("CPU reset"));
            assertEquals("€\n", reset.terminalLog());
            controller.powerCycle();
            var cycled = await(snapshots, s -> s.status().equals("Power cycled"));
            assertEquals("", cycled.terminalLog());
        }
    }

    @Test
    void breakpointNavigationAndResetKeepLauncherResponsive() throws Exception {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xEA; rom[0x3FFF1] = 0; rom[0x3FFF2] = 0;
        rom[0x3FFF3] = 0; rom[0x3FFF4] = (byte) 0xC0;
        rom[0] = (byte) 0xCC;                    // INT3
        rom[1] = (byte) 0xE8; rom[2] = 3; rom[3] = 0; // CALL 0007
        rom[4] = (byte) 0xF4;                    // HLT
        rom[5] = (byte) 0x90; rom[6] = (byte) 0x90;
        rom[7] = (byte) 0xC3;                    // RET
        Path file = romFile("program", rom);
        BlockingQueue<LauncherController.Snapshot> snapshots = new LinkedBlockingQueue<>();
        try (LauncherController controller = new LauncherController(snapshots::add, error -> fail(error))) {
            controller.loadRom(file);
            await(snapshots, s -> s.romName().startsWith("program"));
            controller.run();
            var breakpoint = await(snapshots, s -> s.status().equals("INT 3 breakpoint"));
            assertEquals(0xC000, breakpoint.code().getFirst().cs());
            assertEquals(0, breakpoint.code().getFirst().ip());
            controller.step();
            var handler = await(snapshots, s -> s.totalInstructions() == 2 && s.status().equals("Paused"));
            assertEquals(0, handler.code().getFirst().cs());
            controller.setPc(0xC000, 1);
            await(snapshots, s -> s.status().equals("PC set"));
            controller.runTo(0xC000, 4);
            var atCursor = await(snapshots, s -> s.status().equals("Cursor reached"));
            assertEquals(4, atCursor.code().getFirst().ip());
            controller.step();
            var halted = await(snapshots, s -> s.status().equals("Paused (HLT)"));
            assertEquals(5, halted.code().getFirst().ip());
            controller.setPc(0xC000, 1);
            await(snapshots, s -> s.status().equals("PC set"));
            controller.stepOver();
            var over = await(snapshots, s -> s.status().equals("Step over complete"));
            assertEquals(4, over.code().getFirst().ip());
            controller.skipInstruction();
            var skipped = await(snapshots, s -> s.status().startsWith("Skipped"));
            assertEquals(5, skipped.code().getFirst().ip());
            controller.reset();
            var reset = await(snapshots, s -> s.status().equals("CPU reset"));
            assertEquals(0xFFFF, reset.code().getFirst().cs());
            controller.memoryBase(0xA0000);
            controller.writeMemory(0xA0000, 0x5A);
            await(snapshots, s -> s.memoryBase() == 0xA0000 && Byte.toUnsignedInt(s.memory()[0]) == 0x5A);
            controller.reset();
            var persisted = await(snapshots, s -> s.status().equals("CPU reset") && s.memoryBase() == 0xA0000);
            assertEquals(0x5A, Byte.toUnsignedInt(persisted.memory()[0]));
            controller.powerCycle();
            await(snapshots, s -> s.status().equals("Power cycled"));
            controller.memoryBase(0xA0000);
            var clean = await(snapshots, s -> s.status().equals("Power cycled") && s.memoryBase() == 0xA0000);
            assertEquals(0, Byte.toUnsignedInt(clean.memory()[0]));
        }
    }

    @Test
    void cpuThrowIsReportedAndPowerCycleRecovers() throws Exception {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = 0x62;
        Path file = romFile("bad", rom);
        BlockingQueue<LauncherController.Snapshot> snapshots = new LinkedBlockingQueue<>();
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        try (LauncherController controller = new LauncherController(snapshots::add, errors::add)) {
            controller.loadRom(file);
            await(snapshots, s -> s.romName().startsWith("bad"));
            controller.step();
            var fault = await(snapshots, s -> s.faulted());
            assertTrue(fault.status().startsWith("Error at FFFF:0000"));
            String report = errors.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(report);
            assertTrue(report.contains("Unsupported opcode 62"));
            controller.run();
            controller.reset();
            var reset = await(snapshots, s -> s.status().equals("CPU reset"));
            assertFalse(reset.faulted());
            controller.step();
            await(snapshots, s -> s.faulted());
            controller.powerCycle();
            var recovered = await(snapshots, s -> s.status().equals("Power cycled"));
            assertFalse(recovered.faulted());
            controller.skipInstruction();
            var skipped = await(snapshots, s -> s.status().startsWith("Skipped"));
            assertEquals(1, skipped.code().getFirst().ip());
        }
    }

    @Test
    void mappedPortEditsAndHostInputsAppearInSnapshots() throws Exception {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        Path file = romFile("io", rom);
        BlockingQueue<LauncherController.Snapshot> snapshots = new LinkedBlockingQueue<>();
        try (LauncherController controller = new LauncherController(snapshots::add, error -> fail(error))) {
            controller.loadRom(file);
            await(snapshots, s -> s.romName().startsWith("io"));
            controller.writeIo(1, 7);
            await(snapshots, s -> Byte.toUnsignedInt(s.io()[1]) == 7);
            controller.redstone(0, 5);
            await(snapshots, s -> Byte.toUnsignedInt(s.io()[0]) == 5);
            controller.ioBase(0x0180);
            controller.signClick(0, true);
            var clicked = await(snapshots, s -> s.ioBase() == 0x0180 && Byte.toUnsignedInt(s.io()[1]) == 3);
            assertEquals(3, Byte.toUnsignedInt(clicked.io()[1]));
        }
    }

    @Test
    void deviceControlsAndAddressSelectionAppearInSnapshots() throws Exception {
        Path file = romFile("devices", new byte[CPUMemory.ROM_SIZE]);
        BlockingQueue<LauncherController.Snapshot> snapshots = new LinkedBlockingQueue<>();
        try (LauncherController controller = new LauncherController(snapshots::add, error -> fail(error))) {
            controller.loadRom(file);
            await(snapshots, s -> s.romName().startsWith("devices"));
            controller.selectChannel(127);
            controller.redstone(127, 12);
            controller.sendIrq(0x007F);
            var redstone = await(snapshots, s -> s.devices().channel() == 127 &&
                    Byte.toUnsignedInt(s.devices().redstone()[0]) == 12 &&
                    (s.devices().irq()[2] & 2) != 0);
            assertEquals(0x007F, Byte.toUnsignedInt(redstone.devices().irq()[0]));

            controller.selectSign(1);
            controller.writeSignLine(1, 0, "HI");
            var sign = await(snapshots, s -> s.devices().signIndex() == 1 && s.devices().sign()[1] == 'H');
            assertEquals("HI", DeviceViewPanel.signText(sign.devices().sign(), 0));
            assertThrows(IllegalArgumentException.class, () -> controller.writeSignLine(1, 0, "non-ASCII ä"));

            controller.selectTimer(5);
            controller.writeTimer(5, 0x1234, 0x35);
            controller.timerAutomatic(5, false);
            var timer = await(snapshots, s -> s.devices().timerIndex() == 5 &&
                    !s.devices().timerAutomatic() && Byte.toUnsignedInt(s.devices().timer()[2]) == 0x34);
            assertEquals(0x12, Byte.toUnsignedInt(timer.devices().timer()[3]));
            assertEquals(0x35, Byte.toUnsignedInt(timer.devices().timer()[4]));
            assertEquals(0x0302, DeviceViewPanel.timerId(5));

            controller.memoryBase(0x9FFF0);
            controller.ioBase(0xFF00);
            controller.codeAddress(0xC000, 0x1234);
            var navigated = await(snapshots, s -> s.memoryBase() == 0x9FFF0 &&
                    s.ioBase() == 0xFF00 && !s.code().isEmpty() && s.code().getFirst().ip() == 0x1234);
            assertEquals(0xC000, navigated.code().getFirst().cs());

            controller.reset();
            var reset = await(snapshots, s -> s.status().equals("CPU reset") && s.devices().timerIndex() == 5);
            assertFalse(reset.devices().timerAutomatic());
            controller.powerCycle();
            var cycled = await(snapshots, s -> s.status().equals("Power cycled"));
            assertTrue(cycled.devices().timerAutomatic());
        }
    }

    private static LauncherController.Snapshot await(BlockingQueue<LauncherController.Snapshot> queue,
                                                      Predicate<LauncherController.Snapshot> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var value = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (value != null && condition.test(value)) return value;
        }
        fail("Timed out waiting for launcher state");
        return null;
    }

    private static Path romFile(String name, byte[] rom) throws Exception {
        Path directory = Path.of("build", "launcher-test-roms");
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, name, ".bin");
        Files.write(file, rom);
        return file;
    }
}
