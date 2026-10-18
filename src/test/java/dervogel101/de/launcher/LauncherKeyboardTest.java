package dervogel101.de.launcher;

import dervogel101.de.i86.runtime.*;
import org.junit.jupiter.api.Test;
import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.EnumMap;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static java.awt.event.KeyEvent.*;

class LauncherKeyboardTest {
    @Test void awtKeysUseStatelessLayoutsAndDeviceSpecialCodes() {
        var us = KeyboardInput.Layout.US; var de = KeyboardInput.Layout.DE;
        assertEquals('a', key(VK_A, 'a', 0, KEY_LOCATION_STANDARD, us, false));
        assertEquals('A', key(VK_A, 'a', 0, KEY_LOCATION_STANDARD, us, true));
        assertEquals('a', key(VK_A, 'A', SHIFT_DOWN_MASK, KEY_LOCATION_STANDARD, us, true));
        assertEquals('y', key(VK_Y, 'y', 0, KEY_LOCATION_STANDARD, de, false));
        assertEquals(0x80, key(VK_E, '€', CTRL_DOWN_MASK | ALT_DOWN_MASK | ALT_GRAPH_DOWN_MASK, KEY_LOCATION_STANDARD, de, false));
        assertEquals(0x80, SwingKeyboardInput.translate(new KeyEvent(new JPanel(), KEY_PRESSED, 0,
                CTRL_DOWN_MASK | ALT_DOWN_MASK, VK_E, '€'), de, false, true), "Right Alt remains AltGr when Windows reports Ctrl+Alt");
        assertEquals('^', key(VK_DEAD_CIRCUMFLEX, CHAR_UNDEFINED, 0, KEY_LOCATION_STANDARD, de, false));
        assertEquals('e', key(VK_E, 'ê', 0, KEY_LOCATION_STANDARD, de, false), "Dead accent must not compose the next letter");
        assertEquals(0xb4, key(VK_DEAD_ACUTE, CHAR_UNDEFINED, 0, KEY_LOCATION_STANDARD, de, false));
        assertEquals(-1, key(VK_ENTER, '\n', SHIFT_DOWN_MASK, KEY_LOCATION_STANDARD, us, false));
        assertEquals(-1, key(VK_A, 'a', CTRL_DOWN_MASK, KEY_LOCATION_STANDARD, us, false));
        assertEquals(0x81, key(VK_NUMPAD0, '0', 0, KEY_LOCATION_NUMPAD, us, false));
        assertEquals(0x81, key(VK_INSERT, CHAR_UNDEFINED, 0, KEY_LOCATION_NUMPAD, us, false));
        assertEquals(0x8b, key(VK_DELETE, CHAR_UNDEFINED, 0, KEY_LOCATION_NUMPAD, us, false));
        assertEquals(0x90, key(VK_ENTER, '\n', 0, KEY_LOCATION_NUMPAD, us, false));
        assertEquals(0x92, key(VK_LEFT, CHAR_UNDEFINED, 0, KEY_LOCATION_STANDARD, us, false));
        assertEquals('+', key(VK_EQUALS, '+', SHIFT_DOWN_MASK, KEY_LOCATION_STANDARD, us, false));
        assertEquals(-1, key(VK_UNDEFINED, '漢', 0, KEY_LOCATION_UNKNOWN, us, false));
        for (int digit = 0; digit <= 9; digit++) assertEquals(0x81 + digit,
                key(VK_NUMPAD0 + digit, (char)('0' + digit), 0, KEY_LOCATION_NUMPAD, us, false));
    }

    @Test void virtualSlotsExposeBoundedFifosAndFollowPowerAndReset() throws Exception {
        var snapshots = new LinkedBlockingQueue<DebuggerBackend.Snapshot>();
        var errors = new LinkedBlockingQueue<String>();
        try (var controller = new LauncherController(snapshots::add, errors::add)) {
            controller.configureMachine(hardware(), 1, "keyboard_test");
            controller.loadRom(yieldRom()); controller.step(); controller.selectKeyboard(7); controller.pauseAndWait();
            await(snapshots, s -> s.devices().keyboardIndex() == 7 && s.devices().keyboard().connected());
            for (int i = 0; i < 8; i++) controller.keyboardInput(7, 'A' + i);
            var full = await(snapshots, s -> s.devices().keyboard().queue().length == 8);
            assertTrue(full.devices().keyboard().ready());
            byte[] copy = full.devices().keyboard().queue(); copy[0] = 0;
            assertEquals('A', full.devices().keyboard().queue()[0]);
            controller.keyboardInput(7, 'Z'); controller.pauseAndWait();
            assertTrue(errors.poll(5, TimeUnit.SECONDS).contains("full"));
            controller.writeIo(0x817f, 1);
            await(snapshots, s -> s.devices().keyboard().ackPending());
            controller.reset();
            var reset = await(snapshots, s -> s.status().equals("CPU reset"));
            assertEquals(8, reset.devices().keyboard().queue().length);
            controller.powerSupply(false);
            await(snapshots, s -> !s.machine().powered() && s.devices().keyboard().queue().length == 0);
            assertTrue(errors.isEmpty());
            assertTrue(LauncherController.writableIo(0x8171));
            assertFalse(LauncherController.writableIo(0x8170));
        }
    }

    @Test void runningLauncherCanDriveTheActualKeyboardEditorRom() throws Exception {
        String path = System.getenv("KEYBOARD_EDITOR_ROM");
        assumeTrue(path != null, "Set KEYBOARD_EDITOR_ROM to test the real editor in the launcher");
        var snapshots = new LinkedBlockingQueue<DebuggerBackend.Snapshot>();
        var errors = new LinkedBlockingQueue<String>();
        try (var controller = new LauncherController(snapshots::add, errors::add)) {
            controller.configureMachine(hardware(), 3, "keyboard_editor");
            controller.loadRom(Path.of(path)); controller.run();
            await(snapshots, s -> s.running() && s.devices().keyboard().connected());
            controller.keyboardInput(0, 'A'); controller.keyboardInput(0, 'B');
            controller.keyboardInput(0, 0x92); controller.keyboardInput(0, 8); controller.keyboardInput(0, 0x8b);
            await(snapshots, s -> s.running() && s.devices().keyboard().queue().length > 0);
            await(snapshots, s -> s.running() && s.devices().keyboard().queue().length == 0);
            controller.pauseAndWait(); controller.selectSign(1);
            var result = await(snapshots, s -> !s.running() && s.devices().signIndex() == 1 && s.devices().sign()[1] == 'B');
            assertEquals(0x90, Byte.toUnsignedInt(result.devices().sign()[0]), "Numpad decimal toggles underline");
            assertTrue(errors.isEmpty(), errors.toString());
        }
    }

    private static HardwareProfile hardware() {
        var counts = new EnumMap<Hardware, Integer>(HardwareProfile.complete().counts());
        counts.put(Hardware.KEYBOARD, 1); return new HardwareProfile(counts);
    }
    private static Path yieldRom() throws Exception {
        byte[] image = new byte[256 * 1024];
        image[image.length - 16] = 15; image[image.length - 15] = 1;
        image[image.length - 14] = (byte)0xeb; image[image.length - 13] = (byte)0xfc;
        Path path = Files.createTempFile("keyboard-launcher", ".bin"); Files.write(path, image); path.toFile().deleteOnExit(); return path;
    }
    private static int key(int code, char character, int modifiers, int location, KeyboardInput.Layout layout, boolean caps) {
        return SwingKeyboardInput.translate(new KeyEvent(new JPanel(), KEY_PRESSED, 0, modifiers, code, character, location), layout, caps, false);
    }
    private static DebuggerBackend.Snapshot await(BlockingQueue<DebuggerBackend.Snapshot> snapshots, Predicate<DebuggerBackend.Snapshot> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            var snapshot = snapshots.poll(100, TimeUnit.MILLISECONDS);
            if (snapshot != null && condition.test(snapshot)) return snapshot;
        }
        throw new AssertionError("No matching keyboard launcher snapshot");
    }
}
