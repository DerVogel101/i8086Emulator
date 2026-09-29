package dervogel101.de;

import dervogel101.de.state.CPUIO;
import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class CpuHostBridgeTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-26T11:14:15Z"), ZoneId.of("Europe/Berlin"));

    @Test
    void queuedHostInputsApplyAtTheNextInstructionBoundary() throws InterruptedException {
        CPUMemory memory = new CPUMemory();
        CPUIO io = new CPUIO();
        CpuHostBridge bridge = new CpuHostBridge(memory, io, CLOCK);
        Thread producer = new Thread(() -> {
            bridge.submitWorldTime(65_537, 48_000);
            bridge.submitRedstoneInput(1, 12);
            bridge.submitSignClick(2, CpuHostBridge.SignSide.FRONT);
        });
        producer.start();
        producer.join();

        assertEquals(0, memory.read16(0x9000, 0xFFF5));
        assertEquals(0, io.read8(2));
        assertEquals(0, io.read16(0x0380));
        bridge.beforeInstruction();
        assertEquals(2, memory.read16(0x9000, 0xFFF5));
        assertEquals(0, memory.read16(0x9000, 0xFFF7)); // First game-time submission sets power-on origin.
        assertEquals(13, memory.read8(0x9000, 0xFFF9));
        assertEquals(12, io.read8(2));
        assertEquals(0x0300, io.read16(0x0380));

        io.write8(0x0381, 0);
        bridge.submitSignClick(2, CpuHostBridge.SignSide.FRONT);
        bridge.submitWorldTime(65_538, 48_000);
        assertEquals(0, io.read16(0x0380));
        bridge.beforeInstruction();
        assertEquals(1, memory.read16(0x9000, 0xFFF7));
        assertEquals(0x0300, io.read16(0x0380));

        io.write8(0x0003, 7);
        assertEquals(7, bridge.pollChangedOutputs().redstone().get(1));
        assertTrue(bridge.pollChangedOutputs().redstone().isEmpty());
    }

    @Test
    void cpuFetchStartsAtTheResetVector() {
        CPU8086 cpu = new CPU8086(CLOCK);
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xEA;
        cpu.hostBridge().loadRom(rom);
        assertEquals(0xEA, cpu.fetch8());
    }
}
