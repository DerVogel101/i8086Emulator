package dervogel101.de;

import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectControlTest {
    private static CPU8086 cpu() {
        var cpu = new CPU8086(); byte[] rom = new byte[CPUMemory.ROM_SIZE]; rom[0x3FFF0] = (byte) 0x90;
        cpu.hostBridge().loadRom(rom); return cpu;
    }
    @Test void resetPreservesMemoryDevicesAndHeldResetInput() {
        var cpu = cpu(); cpu.jumpTo(0, 0); cpu.memory().write8(0, 0, 0xF4); cpu.step();
        cpu.memory().write8(0, 0x100, 42); cpu.memory().write8(0xA000, 3, 0xAB); cpu.io().write8(1, 9);
        cpu.registers().setAx(0x1234); cpu.hostBridge().submitResetPin(true, false); cpu.reset();
        assertFalse(cpu.isHalted()); assertEquals(0, cpu.registers().getAx());
        assertEquals(42, cpu.memory().read8(0, 0x100)); assertEquals(0xAB, cpu.memory().read8(0xA000, 3)); assertEquals(9, cpu.io().read8(1));
        assertEquals(CPU8086.StepOutcome.IDLE, cpu.step()); assertEquals(0xFFFF, cpu.registers().getCs()); assertEquals(0, cpu.registers().getIp());
        cpu.hostBridge().submitResetPin(false, false);
        assertEquals(CPU8086.StepOutcome.INSTRUCTION, cpu.step()); assertEquals(1, cpu.registers().getIp());
    }
    @Test void resetDiscardsPendingNmiAndResetsInterruptShadowWhileKeepingDeviceIrq() {
        var cpu = cpu(); cpu.jumpTo(0, 0); cpu.memory().write8(0, 0, 0xFB); cpu.step();
        cpu.hostBridge().submitIrq(0x200); cpu.hostBridge().submitNmiPulse(); cpu.reset();
        assertEquals(0x200, cpu.io().read16(0xFF00));
        assertEquals(CPU8086.StepOutcome.INSTRUCTION, cpu.step()); assertEquals(1, cpu.registers().getIp());
        cpu.registers().setIF(true); assertEquals(CPU8086.StepOutcome.EVENT, cpu.step());
    }
    @Test void manualNmiPreservesHeldNmiInputAndIsSuppressedByReset() {
        var cpu = cpu(); cpu.memory().write16(0, 8, 0x1000); cpu.memory().write16(0, 10, 0);
        cpu.memory().write8(0, 0x1000, 0x90); cpu.hostBridge().submitNmiPin(true, false); cpu.hostBridge().submitNmiPulse();
        assertEquals(CPU8086.StepOutcome.EVENT, cpu.step()); assertEquals(0x1000, cpu.registers().getIp());
        cpu.hostBridge().submitNmiPin(true);
        assertEquals(CPU8086.StepOutcome.INSTRUCTION, cpu.step()); assertEquals(0x1001, cpu.registers().getIp());
        cpu.reset(); cpu.hostBridge().submitResetPin(true, false); cpu.hostBridge().submitNmiPulse();
        assertEquals(CPU8086.StepOutcome.IDLE, cpu.step()); cpu.hostBridge().submitResetPin(false, false);
        assertEquals(CPU8086.StepOutcome.INSTRUCTION, cpu.step()); assertEquals(1, cpu.registers().getIp());
    }
}
