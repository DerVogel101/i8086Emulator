package dervogel101.de;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;

class ClockBoundaryTest {
    @Test void clocksAndTimersOnlyAdvanceAtTheirOwnHostBoundary() {
        var clock = new CountingClock();
        var cpu = new CPU8086(clock);
        cpu.jumpTo(0, 0);
        for (int i = 0; i < 1000; i++) cpu.memory().write8(0, i, 0x90);
        cpu.hostBridge().submitWorldTime(100, 48_000);
        cpu.beforeTick();
        assertEquals(0, clock.samples);
        assertEquals(2, cpu.memory().read16(0x9000, 0xFFF5));
        cpu.beforeBatch();
        assertEquals(1, clock.samples);
        assertEquals(100, cpu.memory().read16(0x9000, 0xFFF3));
        for (int port : new int[]{0x8100, 0x8118}) {
            cpu.io().write16(port + 2, 5);
            cpu.io().write8(port + 4, 5);
        }

        clock.millis += 3;
        cpu.hostBridge().submitWorldTime(103, 72_000);
        cpu.hostBridge().submitRedstoneInput(1, 12);
        for (int i = 0; i < 1000; i++) cpu.step();
        assertEquals(12, cpu.io().read8(2), "Inputs still apply between steps");
        assertEquals(1, clock.samples, "Steps must not sample the host clock");
        assertEquals(100, cpu.memory().read16(0x9000, 0xFFF3));
        assertEquals(2, cpu.memory().read16(0x9000, 0xFFF5));
        assertEquals(0, cpu.memory().read16(0x9000, 0xFFF7));
        assertEquals(5, cpu.io().read16(0x8100));
        assertEquals(5, cpu.io().read16(0x8118));

        cpu.beforeBatch();
        assertEquals(2, clock.samples);
        assertEquals(103, cpu.memory().read16(0x9000, 0xFFF3));
        assertEquals(2, cpu.io().read16(0x8100));
        assertEquals(5, cpu.io().read16(0x8118));
        assertEquals(2, cpu.memory().read16(0x9000, 0xFFF5));
        cpu.beforeTick();
        assertEquals(2, clock.samples, "Tick hook must not sample real time");
        assertEquals(3, cpu.memory().read16(0x9000, 0xFFF5));
        assertEquals(3, cpu.memory().read16(0x9000, 0xFFF7));
        assertEquals(2, cpu.io().read16(0x8118));

        clock.millis += 1000;
        cpu.hostBridge().submitWorldTime(110, 72_000);
        cpu.beforeTick();
        assertEquals(0, cpu.io().read16(0x8118));
        assertEquals(2, cpu.io().read16(0x8100));
        assertEquals(0x0300, cpu.io().read16(0xFF00));
        cpu.beforeBatch();
        assertEquals(0, cpu.io().read16(0x8100));
        cpu.io().write8(0xFF02, 1);
        cpu.beforeInstruction();
        assertEquals(0x0200, cpu.io().read16(0xFF00));
        assertEquals(3, clock.samples);
    }

    private static final class CountingClock extends Clock {
        long millis = 100;
        int samples;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { samples++; return Instant.ofEpochMilli(millis); }
    }
}
