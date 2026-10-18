package dervogel101.de;

import dervogel101.de.i86.runtime.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.EnumMap;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KeyboardRomTest {
    @Test void realCLibraryPollsAndRoutesIrqsWithoutDuplicateReads() throws Exception {
        String path = System.getenv("KEYBOARD_TEST_ROM");
        assumeTrue(path != null, "Set KEYBOARD_TEST_ROM to run the real IA-16 C library");
        var cpu = new CPU8086();
        var hardware = new EnumMap<Hardware, Integer>(HardwareProfile.complete().counts());
        hardware.put(Hardware.GPU, 3); hardware.put(Hardware.KEYBOARD, 1);
        cpu.configureHardware(new HardwareProfile(hardware));
        cpu.hostBridge().loadRom(Files.readAllBytes(Path.of(path)));
        cpu.hostBridge().submitKeyboardConnection(0, true);
        cpu.hostBridge().submitKeyboardConnection(7, true);
        cpu.beforeTick(); cpu.beforeBatch();
        await(cpu, () -> word(cpu, 0) == 1);
        assertTrue(cpu.hostBridge().submitKeyboardInput(0, 'A'));
        await(cpu, () -> word(cpu, 8) == 3);
        assertEquals('A', word(cpu, 2));
        assertEquals(3, word(cpu, 8));
        cpu.beforeTick();
        cpu.memory().write16(0, 0x0512, 1);
        await(cpu, () -> word(cpu, 0) == 2);
        assertTrue(cpu.hostBridge().submitKeyboardInput(0, 0x92));
        assertTrue(cpu.hostBridge().submitKeyboardInput(0, 0x81));
        assertTrue(cpu.hostBridge().submitKeyboardInput(7, 'Z'));
        await(cpu, () -> word(cpu, 3) == 1 && word(cpu, 4) == 1);
        for (int i = 0; i < 2000; i++) cpu.step();
        assertEquals(1, word(cpu, 3), "Second byte must wait for a game tick");
        cpu.beforeTick();
        await(cpu, () -> word(cpu, 0) == 3);
        assertEquals(0, word(cpu, 1), "C registration/IF self-checks");
        assertEquals(0x92, word(cpu, 12)); assertEquals(0x81, word(cpu, 13));
        assertEquals('Z', word(cpu, 6));
    }
    private static int word(CPU8086 cpu, int index) { return cpu.memory().read16(0, 0x0500 + index * 2); }
    private static void await(CPU8086 cpu, BooleanSupplier condition) {
        for (int i = 0; i < 200_000 && !condition.getAsBoolean(); i++) cpu.step();
        assertTrue(condition.getAsBoolean(), "Keyboard ROM did not reach the expected state");
    }
}
