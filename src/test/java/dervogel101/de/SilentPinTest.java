package dervogel101.de;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SilentPinTest {
    @Test void silentNmiSampleDoesNotInventAnInterruptButLaterEdgesWork() {
        var cpu = new CPU8086(); cpu.jumpTo(0, 0); cpu.memory().write8(0, 0, 0x90);
        cpu.hostBridge().submitNmiPin(true, false);
        assertEquals(CPU8086.StepOutcome.INSTRUCTION, cpu.step());
        assertEquals(1, cpu.registers().getIp());
        cpu.hostBridge().submitNmiPin(false, false);
        cpu.hostBridge().submitNmiPin(true);
        assertEquals(CPU8086.StepOutcome.EVENT, cpu.step());
    }
    @Test void silentResetSampleHoldsThePinWithoutReplayingItsRisingEdge() {
        var cpu = new CPU8086(); cpu.jumpTo(0x1234, 0x5678);
        cpu.memory().write8(0x1234, 0x5678, 0x90);
        cpu.hostBridge().submitResetPin(true, false);
        assertEquals(CPU8086.StepOutcome.IDLE, cpu.step());
        assertEquals(0x1234, cpu.registers().getCs()); assertEquals(0x5678, cpu.registers().getIp());
        cpu.hostBridge().submitResetPin(false, false);
        assertEquals(CPU8086.StepOutcome.INSTRUCTION, cpu.step());
        cpu.hostBridge().submitResetPin(true);
        assertEquals(CPU8086.StepOutcome.EVENT, cpu.step());
        assertEquals(0xFFFF, cpu.registers().getCs()); assertEquals(0, cpu.registers().getIp());
    }
    @Test void silentResamplingDoesNotDiscardAnAlreadyObservedPendingEdge() {
        var cpu = new CPU8086(); cpu.hostBridge().submitNmiPin(true);
        cpu.hostBridge().submitNmiPin(false, false);
        assertEquals(CPU8086.StepOutcome.EVENT, cpu.step());
        cpu.hostBridge().submitResetPin(true); cpu.hostBridge().submitResetPin(false, false);
        assertEquals(CPU8086.StepOutcome.EVENT, cpu.step());
        assertEquals(0xFFFF, cpu.registers().getCs());
    }
}
