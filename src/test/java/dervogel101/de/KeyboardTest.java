package dervogel101.de;

import dervogel101.de.i86.runtime.*;
import dervogel101.de.state.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KeyboardTest {
    @Test void snapshotRetainsControllerButClearsTransientInput() throws Exception {
        var counts = new EnumMap<Hardware, Integer>(HardwareProfile.complete().counts());
        counts.put(Hardware.GPU, 3); counts.put(Hardware.KEYBOARD, 1);
        var cpu = new CPU8086();
        cpu.configureHardware(new HardwareProfile(counts));
        cpu.hostBridge().submitKeyboardConnection(0, true);
        assertTrue(cpu.hostBridge().submitKeyboardInput(0, 'A'));
        var restored = CPU8086.restoreState(cpu.saveState(), new SharedMemoryRegion());
        assertEquals(cpu.hardware(), restored.hardware());
        assertEquals(8, restored.hardware().keyboardCapacity());
        restored.hostBridge().submitKeyboardConnection(0, true);
        restored.beforeTick();
        assertEquals(0, restored.io().read16(0x8170));
        assertTrue(restored.hostBridge().submitKeyboardInput(0, 'B'));
        restored.beforeInstruction();
        assertEquals('B', restored.io().read8(0x8170));
    }

    @Test void cpuResetPreservesQueuedInputAndPendingAck() {
        var counts = new EnumMap<Hardware, Integer>(HardwareProfile.complete().counts());
        counts.put(Hardware.GPU, 3); counts.put(Hardware.KEYBOARD, 1);
        var cpu = new CPU8086(); cpu.configureHardware(new HardwareProfile(counts));
        var bridge = cpu.hostBridge(); var io = cpu.ioForTest();
        bridge.submitKeyboardConnection(0, true);
        assertTrue(bridge.submitKeyboardInput(0, 'A')); assertTrue(bridge.submitKeyboardInput(0, 'B'));
        bridge.beforeInstruction(); io.write8(0x8171, 1); bridge.beforeInstruction();
        cpu.reset();
        assertEquals('A', io.read8(0x8170)); assertEquals(3, io.read8(0x8171));
        cpu.beforeTick(); assertEquals('B', io.read8(0x8170));
    }
    @Test void fifoAckAndInterruptsAreSeparateAndTickPaced() {
        var io = new CPUIO();
        var bridge = new CpuHostBridge(new CPUMemory(), io, Clock.systemUTC());
        var profile = new HardwareProfile(Map.of(Hardware.KEYBOARD, 1, Hardware.INTERRUPTS, 1));
        io.configureHardware(profile); bridge.configureHardware(profile);
        bridge.submitKeyboardConnection(0, true);
        bridge.submitKeyboardConnection(7, true);
        for (int i = 1; i <= 8; i++) assertTrue(bridge.submitKeyboardInput(0, 64 + i));
        assertFalse(bridge.submitKeyboardInput(0, 99));
        assertEquals(0, io.read8(0x8171));
        assertTrue(bridge.submitKeyboardInput(7, 90));
        bridge.beforeInstruction();
        assertEquals(65, io.read8(0x8170)); assertEquals(2, io.read8(0x8171));
        assertEquals(90, io.read8(0x817e)); assertEquals(0x400, io.read16(0xff00));
        io.write8(0xff02, 1); bridge.beforeInstruction();
        assertEquals(0x407, io.read16(0xff00)); assertEquals(65, io.read8(0x8170));
        for (int expected = 65; expected <= 72; expected++) {
            io.write8(0x8171, 1); io.write8(0x8171, 1);
            bridge.beforeInstruction();
            assertEquals(expected, io.read8(0x8170)); assertEquals(3, io.read8(0x8171));
            bridge.beforeBatch(); bridge.beforeInstruction();
            assertEquals(expected, io.read8(0x8170));
            if (expected == 65) assertFalse(bridge.submitKeyboardInput(0, 99));
            bridge.beforeTick();
            assertEquals(expected == 72 ? 0 : expected + 1, io.read8(0x8170));
        }
        assertEquals(0, io.read8(0x8171));
        assertTrue(bridge.submitKeyboardInput(0, 99));
        bridge.submitKeyboardConnection(0, false);
        assertEquals(0, io.read8(0x8171)); assertEquals(90, io.read8(0x817e));
    }

    @Test void absentHardwareAndPollingAndDisconnect() {
        var io = new CPUIO(); var bridge = new CpuHostBridge(new CPUMemory(), io, Clock.systemUTC());
        bridge.submitKeyboardConnection(0, true);
        assertFalse(bridge.submitKeyboardInput(0, 65));
        io.write8(0x8171, 1); assertEquals(0, io.read8(0x8171));
        var profile = new HardwareProfile(Map.of(Hardware.KEYBOARD, 1));
        io.configureHardware(profile); bridge.configureHardware(profile);
        bridge.submitKeyboardConnection(0, true);
        io.write8(0x8171, 1); bridge.beforeInstruction(); assertEquals(0, io.read8(0x8171));
        assertTrue(bridge.submitKeyboardInput(0, 65)); bridge.beforeInstruction();
        assertEquals(65, io.read8(0x8170)); assertEquals(0, io.read8(0xff02));
        bridge.submitKeyboardConnection(0, false); bridge.submitKeyboardConnection(0, true);
        assertEquals(0, io.read16(0x8170));
        assertEquals(0, HardwareProfile.complete().keyboardCapacity());
    }

    @Test void statelessGermanAndUsMapsAndSpecialCodes() {
        var us = KeyboardInput.Layout.US; var de = KeyboardInput.Layout.DE;
        assertEquals('y', KeyboardInput.translate(us, 28, 0));
        assertEquals('z', KeyboardInput.translate(de, 28, 0));
        assertEquals('Y', KeyboardInput.translate(us, 28, 0x2000));
        assertEquals('y', KeyboardInput.translate(us, 28, 0x2001));
        assertEquals(0x80, KeyboardInput.translate(de, 8, 0x240));
        assertEquals('@', KeyboardInput.translate(de, 20, 0x240));
        assertEquals('^', KeyboardInput.translate(de, 53, 0));
        assertEquals('e', KeyboardInput.translate(de, 8, 0));
        assertEquals(0xb4, KeyboardInput.translate(de, 46, 0));
        assertEquals('+', KeyboardInput.translate(de, 48, 0));
        assertEquals('!', KeyboardInput.translate(de, 30, 1));
        assertEquals(')', KeyboardInput.translate(de, 38, 1));
        assertEquals(10, KeyboardInput.translate(us, 40, 0));
        for (int key : new int[]{40,43,42,73,76,79,80,81,82,75,78,74,77,98,103})
            assertEquals(-1, KeyboardInput.translate(de, key, 1));
        for (int key = 89; key <= 97; key++) assertEquals(0x82 + key - 89, KeyboardInput.translate(us, key, 0x1000));
        assertEquals(0x81, KeyboardInput.translate(us, 98, 0));
        for (int key = 84; key <= 88; key++) assertEquals(0x8c + key - 84, KeyboardInput.translate(us, key, 0));
        assertEquals(0x8b, KeyboardInput.translate(us, 99, 0));
        assertEquals(0x91, KeyboardInput.translate(us, 103, 0));
        for (int value = 0x81; value <= 0x9b; value++) assertTrue(KeyboardInput.allowedByte(value));
        assertFalse(KeyboardInput.allowedByte(0)); assertFalse(KeyboardInput.allowedByte(27));
        assertEquals(-1, KeyboardInput.translate(us, 4, 0x40));
        assertEquals(-1, KeyboardInput.translate(de, 4, 0x140));
    }
}
