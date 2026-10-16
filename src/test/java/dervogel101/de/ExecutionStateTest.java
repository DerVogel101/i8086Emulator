package dervogel101.de;

import dervogel101.de.state.*;
import dervogel101.de.i86.runtime.RedstoneIrqMode;
import java.io.IOException;
import java.time.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionStateTest {
    private static final Clock FIXED = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);

    @Test void roundTripPreservesExecutionDevicesDirtyWritesAndPendingPins() throws Exception {
        var region = new SharedMemoryRegion();
        var cpu = new CPU8086(FIXED, region);
        cpu.jumpTo(0, 0);
        cpu.memory().write8(0, 0, 0xF4);
        cpu.registers().setAx(0x1234);
        cpu.registers().setDs(0x5678);
        cpu.registers().setFlags(0x8D5);
        cpu.registers().setSp(0x4000);
        cpu.memory().write8(0xA000, 1, 0xAB);
        cpu.memory().write8(0xB000, 2, 0xCD);
        cpu.io().write8(1, 9);
        cpu.io().write8(0x101, 0x80);
        var disk = new IoStorageDevice();
        disk.writeWord(3, 0x9876);
        cpu.hostBridge().submitStorageDevice(disk);
        cpu.hostBridge().submitRedstoneIrqMode(4, RedstoneIrqMode.BOTH);
        cpu.hostBridge().submitSignIrqSides(5, 3);
        cpu.hostBridge().submitWorldTime(20, 6000);
        cpu.io().write8(0x8130, 0x80);
        cpu.io().write8(0x8150, 1);
        cpu.step();
        cpu.hostBridge().submitIrq(0x104);
        cpu.hostBridge().submitIrq(0x201);
        cpu.hostBridge().submitNmiPin(true);
        byte[] state = cpu.saveState();
        var restoredRegion = new SharedMemoryRegion();
        restoredRegion.load(region.snapshot());
        var restored = CPU8086.restoreState(state, FIXED, restoredRegion);
        assertTrue(restored.isHalted());
        assertEquals(1, restored.registers().getIp());
        assertEquals(0x1234, restored.registers().getAx());
        assertEquals(0x5678, restored.registers().getDs());
        assertEquals(cpu.registers().getFlags(), restored.registers().getFlags());
        assertEquals(0xAB, restored.memory().read8(0xA000, 1));
        assertEquals(0xCD, restored.memory().read8(0xB000, 2));
        assertNotNull(restored.hostBridge().snapshotNvramIfDirty());
        assertEquals(0x9876, restored.io().storageDevice().readWord(3));
        assertNotNull(restored.io().snapshotStorageIfDirty());
        assertEquals("€", restored.hostBridge().terminalText());
        assertEquals(RedstoneIrqMode.BOTH, restored.hostBridge().redstoneIrqMode(4));
        assertEquals(3, restored.hostBridge().signIrqSides(5));
        var changes = restored.hostBridge().pollChangedOutputs();
        assertEquals(9, changes.redstone().get(0));
        assertEquals((byte) 0x80, changes.signs().get(0).bytes()[1]);
        assertEquals(0x104, restored.io().read16(0xFF00));
        restored.io().write8(0xFF02, 1);
        restored.beforeInstruction();
        assertEquals(0x201, restored.io().read16(0xFF00));
        assertEquals(CPU8086.StepOutcome.EVENT, restored.step());
        assertFalse(restored.isHalted());
    }

    @Test void interruptShadowSurvivesWorldSave() throws Exception {
        var cpu = new CPU8086(FIXED);
        cpu.jumpTo(0, 0);
        cpu.memory().write8(0, 0, 0xFB);
        cpu.memory().write8(0, 1, 0x90);
        cpu.step();
        cpu.hostBridge().submitIrq(0x200);
        var restored = CPU8086.restoreState(cpu.saveState(), FIXED, new SharedMemoryRegion());
        assertEquals(CPU8086.StepOutcome.INSTRUCTION, restored.step());
        assertEquals(2, restored.registers().getIp());
        assertEquals(CPU8086.StepOutcome.EVENT, restored.step());
    }

    @Test void realAndWorldTimersExpireAcrossSuspension() throws Exception {
        var clock = new MutableClock();
        var cpu = new CPU8086(clock);
        cpu.hostBridge().submitWorldTime(100, 0);
        cpu.beforeTick();
        cpu.beforeBatch();
        for (int port : new int[]{0x8100, 0x8118}) {
            cpu.io().write16(port + 2, 5);
            cpu.io().write8(port + 4, 5);
        }
        byte[] state = cpu.saveState();
        clock.millis += 10;
        var restored = CPU8086.restoreState(state, clock, new SharedMemoryRegion());
        restored.hostBridge().submitWorldTime(110, 0);
        restored.beforeTick();
        restored.beforeBatch();
        assertEquals(0, restored.io().read16(0x8100));
        assertEquals(0, restored.io().read16(0x8118));
        assertEquals(0x300, restored.io().read16(0xFF00));
        restored.io().write8(0xFF02, 1);
        restored.beforeInstruction();
        assertEquals(0x200, restored.io().read16(0xFF00));
    }

    @Test void malformedSnapshotsFailExplicitly() throws Exception {
        byte[] state = new CPU8086(FIXED).saveState();
        assertThrows(IOException.class, () -> CPU8086.restoreState(new byte[2], new SharedMemoryRegion()));
        assertThrows(IOException.class, () -> CPU8086.restoreState(Arrays.copyOf(state, state.length - 1), new SharedMemoryRegion()));
        assertThrows(IOException.class, () -> CPU8086.restoreState(Arrays.copyOf(state, state.length + 1), new SharedMemoryRegion()));
        state[4] = 1;
        assertThrows(IOException.class, () -> CPU8086.restoreState(state, new SharedMemoryRegion()));
    }

    private static final class MutableClock extends Clock {
        long millis = 1000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }
}
