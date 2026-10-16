package dervogel101.de;

import dervogel101.de.state.CPUIO;
import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CpuTimerTest {
    private static final int REAL = 0x8100;
    private static final int GAME = 0x8118;

    @Test
    void manualTimerIrqModeKeepsCountdownAndOtherTimersRunning() {
        MutableClock clock = new MutableClock(0);
        CPUIO io = new CPUIO();
        CpuHostBridge bridge = new CpuHostBridge(new CPUMemory(), io, clock);
        for (int timer = 0; timer < 2; timer++) {
            int base = REAL + timer * 8;
            io.write16(base + 2, 2);
            io.write8(base + 4, 7); // enabled, periodic, expiry IRQ
        }
        refresh(bridge);
        bridge.submitTimerAutomatic(0, false);
        refresh(bridge);
        assertFalse(bridge.timerAutomatic(0));
        assertTrue(bridge.timerAutomatic(1));
        clock.advance(2);
        refresh(bridge);
        assertEquals(2, io.read16(REAL)); // manual mode still reloads
        assertEquals(0x0201, io.read16(0xFF00)); // timer 1 remains automatic

        bridge.submitIrq(2, 0); // manual pulse works independently of expiry
        refresh(bridge);
        io.write8(0xFF02, 1);
        refresh(bridge);
        assertEquals(0x0200, io.read16(0xFF00));
        io.write8(0xFF02, 1);
        refresh(bridge);
        assertEquals(0, io.read8(0xFF02) & 2);

        bridge.submitTimerAutomatic(0, true);
        refresh(bridge);
        clock.advance(2);
        refresh(bridge);
        assertEquals(0x0200, io.read16(0xFF00));
        assertThrows(IllegalArgumentException.class, () -> bridge.submitTimerAutomatic(6, false));
    }

    @Test
    void registersAreLittleEndianCountdownIsReadOnlyAndResetWins() {
        CPUIO io = new CPUIO();
        io.write16(REAL, 0xFFFF);
        assertEquals(0, io.read16(REAL));
        io.write16(REAL + 2, 0x1234);
        io.write8(REAL + 4, 0xF7); // Reserved bits ignored.
        assertEquals(0x1234, io.read16(REAL));
        assertEquals(0x1234, io.read16(REAL + 2));
        assertEquals(0x37, io.read8(REAL + 4));
        io.write16(REAL + 5, 0xFFFF);
        assertEquals(0, io.read16(REAL + 5));
        io.write8(REAL + 4, 0x3F); // Reset overrides enable and all other bits.
        assertEquals(0, io.read16(REAL));
        assertEquals(0, io.read16(REAL + 2));
        assertEquals(0, io.read8(REAL + 4));
        assertEquals(0, io.read8(0x8130));
        assertEquals(0, io.read8(0x8107));
    }

    @Test
    void romUsesWordAndByteIoForTimerRegisters() {
        CPU8086 cpu = boot(new MutableClock(0),
                0xBA, 0x02, 0x81, 0xB8, 0x02, 0x00, 0xEF,
                0xBA, 0x04, 0x81, 0xB0, 0x07, 0xEE,
                0xBA, 0x00, 0x81, 0xED,
                0xBA, 0x04, 0x81, 0xEC);
        for (int i = 0; i < 6; i++) step(cpu);
        assertEquals(2, cpu.ioForTest().read16(REAL));
        assertEquals(7, cpu.ioForTest().read8(REAL + 4));
        step(cpu);
        step(cpu);
        assertEquals(2, cpu.registers().getAx());
        step(cpu);
        step(cpu);
        assertEquals(7, cpu.registers().getAx());
    }

    @Test
    void everyResolutionCountsElapsedMillisecondsOrGameTicks() {
        int[] realUnits = {1, 5, 10, 50};
        int[] gameUnits = {1, 5, 10, 20};
        for (int resolution = 0; resolution < 4; resolution++) {
            assertResolution(REAL, resolution, realUnits[resolution], true);
            assertResolution(GAME, resolution, gameUnits[resolution], false);
        }
    }

    @Test
    void allSixTimerBlocksProduceTheirOwnTypeAndIndex() {
        CPUIO io = new CPUIO();
        List<Integer> ids = new ArrayList<>();
        for (int timer = 0; timer < 6; timer++) {
            int base = REAL + timer * 8;
            io.write16(base + 2, 1);
            io.write8(base + 4, 5);
        }
        io.advanceTimers(0, 0, (id, count) -> fail());
        io.advanceTimers(1, 1, (id, count) -> {
            assertEquals(1, count);
            ids.add(id);
        });
        assertEquals(List.of(0x0200, 0x0201, 0x0202, 0x0300, 0x0301, 0x0302), ids);
    }

    private static void assertResolution(int base, int resolution, int unit, boolean real) {
        CPUIO io = new CPUIO();
        io.write16(base + 2, 2);
        io.write8(base + 4, 1 | (resolution << 4));
        io.advanceTimers(0, 0, (id, count) -> fail("IRQ disabled"));
        io.advanceTimers(real ? unit - 1 : 0, real ? 0 : unit - 1,
                (id, count) -> fail("IRQ disabled"));
        assertEquals(2, io.read16(base));
        io.advanceTimers(real ? unit : 0, real ? 0 : unit,
                (id, count) -> fail("IRQ disabled"));
        assertEquals(1, io.read16(base));
        io.advanceTimers(real ? 2L * unit : 0, real ? 0 : 2L * unit,
                (id, count) -> fail("IRQ disabled"));
        assertEquals(0, io.read16(base));
        assertEquals(resolution << 4, io.read8(base + 4)); // One-shot clears enable.
    }

    @Test
    void disableFreezesCountdownWaitChangesOnReloadAndZeroWaitIsIdle() {
        CPUIO io = new CPUIO();
        io.write16(REAL + 2, 4);
        io.write8(REAL + 4, 3);
        io.advanceTimers(0, 0, (id, count) -> fail());
        io.advanceTimers(1, 0, (id, count) -> fail());
        assertEquals(3, io.read16(REAL));
        io.write16(REAL + 2, 6);
        assertEquals(3, io.read16(REAL));
        io.advanceTimers(4, 0, (id, count) -> fail());
        assertEquals(6, io.read16(REAL)); // New wait takes effect at the next reload.
        io.write8(REAL + 4, 2);
        io.advanceTimers(100, 0, (id, count) -> fail());
        assertEquals(6, io.read16(REAL));
        io.write8(REAL + 4, 3); // Re-enable loads the new wait.
        assertEquals(6, io.read16(REAL));
        io.advanceTimers(106, 0, (id, count) -> fail());
        assertEquals(6, io.read16(REAL)); // Periodic reload at zero.
        io.write8(REAL + 4, 0);
        assertEquals(6, io.read16(REAL)); // Off preserves countdown.
        io.write16(REAL + 2, 0);
        io.write8(REAL + 4, 7);
        io.advanceTimers(10_000, 0, (id, count) -> fail("Zero wait must not interrupt"));
        assertEquals(0, io.read16(REAL));
        assertEquals(7, io.read8(REAL + 4));
    }

    @Test
    void timerIrqsUseTypedIdsAndLargeJumpsSetQueueOverflow() {
        MutableClock clock = new MutableClock(0);
        CPUIO io = new CPUIO();
        CpuHostBridge bridge = new CpuHostBridge(new CPUMemory(), io, clock);
        assertThrows(IllegalArgumentException.class, () -> bridge.submitIrq(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> bridge.submitIrq(1, 256));
        bridge.submitIrq(1, 0);
        refresh(bridge);
        assertEquals(0x0100, io.read16(0xFF00));
        io.write16(REAL + 2, 2);
        io.write8(REAL + 4, 7);
        clock.advance(1_000);
        refresh(bridge);
        assertEquals(0x0100, io.read16(0xFF00)); // Active ID remains stable.
        assertEquals(6, io.read8(0xFF02)); // 500 expirations overflow the queue.
        assertEquals(2, io.read16(REAL));
        io.write8(0xFF02, 5); // ACK and clear, then a fresh expiry at this boundary.
        clock.advance(4);
        refresh(bridge);
        assertEquals(0x0200, io.read16(0xFF00));
        assertEquals(6, io.read8(0xFF02)); // Fresh overflow remains visible.
    }

    @Test
    void poweredOnTicksDriveMinecraftTimerAndClockMapAcrossReset() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-26T13:14:15.999Z").toEpochMilli());
        CPU8086 cpu = boot(clock, 0x90);
        CPUIO io = cpu.ioForTest();
        cpu.hostBridge().submitWorldTime(500, 48_000);
        refresh(cpu);
        assertEquals(0, cpu.memory().read16(0x9000, 0xFFF7));
        assertEquals(2, cpu.memory().read16(0x9000, 0xFFF5));
        assertEquals(999, cpu.memory().read16(0x9000, 0xFFF3));
        assertEquals(15, cpu.memory().read8(0x9000, 0xFFFB));
        io.write16(GAME + 2, 2);
        io.write8(GAME + 4, 7);
        cpu.hostBridge().submitWorldTime(501, 48_000);
        refresh(cpu);
        assertEquals(1, io.read16(GAME));
        cpu.hostBridge().submitWorldTime(503, 72_000);
        clock.advance(2);
        refresh(cpu);
        assertEquals(1, io.read16(GAME));
        assertEquals(0x0300, io.read16(0xFF00));
        assertEquals(3, cpu.memory().read16(0x9000, 0xFFF5));
        assertEquals(3, cpu.memory().read16(0x9000, 0xFFF7));
        assertEquals(1, cpu.memory().read16(0x9000, 0xFFF3));
        assertEquals(16, cpu.memory().read8(0x9000, 0xFFFB));
        cpu.hostBridge().submitResetPin(true);
        step(cpu);
        assertEquals(3, cpu.memory().read16(0x9000, 0xFFF7));
        assertEquals(1, io.read16(GAME));
        assertEquals(0x0300, io.read16(0xFF00));
        cpu.hostBridge().submitWorldTime(503 + 65_536, 72_000);
        refresh(cpu);
        assertEquals(3, cpu.memory().read16(0x9000, 0xFFF7)); // 16-bit view wraps.
        cpu.hostBridge().submitWorldTime(500, 72_000);
        refresh(cpu);
        assertEquals(3, cpu.memory().read16(0x9000, 0xFFF7)); // Backward game time cannot subtract uptime.
        cpu.hostBridge().submitWorldTime(505, 72_000);
        refresh(cpu);
        assertEquals(8, cpu.memory().read16(0x9000, 0xFFF7));
    }

    @Test
    void realTimerWakesHltAndClockGoingBackwardDoesNotTick() {
        MutableClock clock = new MutableClock(100);
        CPU8086 cpu = boot(clock, 0xF4, 0x90);
        cpu.memory().write16(0, 0x80, 0x0100);
        cpu.memory().write16(0, 0x82, 0xC000);
        cpu.registers().setSs(0x3000);
        cpu.registers().setSp(0x0100);
        cpu.registers().setIF(true);
        cpu.ioForTest().write16(REAL + 2, 2);
        cpu.ioForTest().write8(REAL + 4, 5); // Enabled, one-shot, IRQ enabled.
        step(cpu);
        assertTrue(cpu.isHalted());
        clock.advance(-10);
        step(cpu);
        assertTrue(cpu.isHalted());
        assertEquals(2, cpu.ioForTest().read16(REAL));
        clock.advance(2);
        step(cpu);
        assertFalse(cpu.isHalted());
        assertEquals(0x0100, cpu.registers().getIp());
        assertEquals(1, cpu.memory().read16(0x3000, 0x00FA));
        assertEquals(0x0200, cpu.ioForTest().read16(0xFF00));
    }

    private static CPU8086 boot(Clock clock, int... program) {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xEA;
        rom[0x3FFF4] = (byte) 0xC0;
        rom[0x0100] = (byte) 0xCF;
        for (int i = 0; i < program.length; i++) rom[i] = (byte) program[i];
        CPU8086 cpu = new CPU8086(clock);
        cpu.hostBridge().loadRom(rom);
        step(cpu);
        return cpu;
    }

    private static void refresh(CpuHostBridge bridge) {
        bridge.beforeTick();
        bridge.beforeBatch();
    }

    private static void refresh(CPU8086 cpu) {
        cpu.beforeTick();
        cpu.beforeBatch();
    }

    private static void step(CPU8086 cpu) {
        refresh(cpu);
        cpu.step();
    }

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) { this.millis = millis; }

        void advance(long delta) { millis += delta; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }
}
