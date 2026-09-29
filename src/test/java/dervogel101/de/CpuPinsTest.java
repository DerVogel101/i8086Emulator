package dervogel101.de;

import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CpuPinsTest {
    @Test
    void waitRetriesAtItsPrefixUntilTestGoesLowAndHostInputStillArrives() {
        CPU8086 ready = boot(0x9B, 0x90);
        ready.step();
        assertEquals(1, ready.registers().getIp());

        CPU8086 waiting = boot(0x36, 0x9B, 0xE4, 0x00);
        waiting.registers().setAx(0xABCD);
        waiting.hostBridge().submitTestPin(true);
        waiting.step();
        assertEquals(0, waiting.registers().getIp());
        waiting.step();
        assertEquals(0, waiting.registers().getIp());
        waiting.hostBridge().submitRedstoneInput(0, 11);
        waiting.step();
        assertEquals(0, waiting.registers().getIp());
        assertFalse(waiting.isHalted());

        waiting.hostBridge().submitTestPin(false);
        waiting.step();
        assertEquals(2, waiting.registers().getIp());
        waiting.step();
        assertEquals(0xAB0B, waiting.registers().getAx());
        assertEquals(4, waiting.registers().getIp());
    }

    @Test
    void heldResetRestartsCpuAndPreservesRomRamAndPorts() {
        CPU8086 cpu = boot(0xE4, 0x00, 0xF4);
        cpu.hostBridge().submitRedstoneInput(0, 9);
        cpu.step();
        cpu.memory().write8(0x1000, 0x20, 0x66);
        cpu.registers().setBx(0x1234);
        cpu.registers().setFlags(0xFFFF);
        cpu.hostBridge().submitResetPin(true);
        cpu.step();
        assertEquals(0xFFFF, cpu.registers().getCs());
        assertEquals(0, cpu.registers().getIp());
        assertEquals(0, cpu.registers().getAx());
        assertEquals(0, cpu.registers().getBx());
        assertEquals(0, cpu.registers().getFlags());
        assertFalse(cpu.isHalted());
        assertEquals(0x66, cpu.memory().read8(0x1000, 0x20));

        cpu.step(); // RESET is still high.
        assertEquals(0xFFFF, cpu.registers().getCs());
        assertEquals(0, cpu.registers().getIp());
        cpu.hostBridge().submitResetPin(false);
        cpu.step(); // Reset-vector far jump from the preserved ROM.
        assertEquals(0xC000, cpu.registers().getCs());
        assertEquals(0, cpu.registers().getIp());
        cpu.step(); // The host input port also survived RESET.
        assertEquals(9, cpu.registers().getAx());
    }

    @Test
    void aResetPulseBetweenStepsWakesHlt() {
        CPU8086 cpu = boot(0xF4, 0x90);
        cpu.step();
        assertTrue(cpu.isHalted());
        cpu.hostBridge().submitResetPin(true);
        cpu.hostBridge().submitResetPin(false);
        cpu.step();
        assertFalse(cpu.isHalted());
        assertEquals(0xFFFF, cpu.registers().getCs());
        assertEquals(0, cpu.registers().getIp());
        cpu.step();
        assertEquals(0xC000, cpu.registers().getCs());
        assertEquals(0, cpu.registers().getIp());
    }

    @Test
    void resetInterruptsWaitButDoesNotClearTheExternalTestPin() {
        CPU8086 cpu = boot(0x9B, 0x90);
        cpu.hostBridge().submitTestPin(true);
        cpu.step();
        assertEquals(0, cpu.registers().getIp());
        cpu.hostBridge().submitResetPin(true);
        cpu.hostBridge().submitResetPin(false);
        cpu.step();
        assertEquals(0xFFFF, cpu.registers().getCs());
        cpu.step(); // Far jump after the reset pulse.
        cpu.step(); // TEST remains high, so WAIT stalls again.
        assertEquals(0, cpu.registers().getIp());
        cpu.hostBridge().submitTestPin(false);
        cpu.step();
        assertEquals(1, cpu.registers().getIp());
    }

    @Test
    void nmiWakesHltRegardlessOfIfAndIretRestoresTheFrame() {
        CPU8086 cpu = boot(0xF4, 0xB0, 0x42);
        installNmi(cpu);
        cpu.registers().setSs(0x3000);
        cpu.registers().setSp(0x0100);
        cpu.registers().setFlags(0x0101); // TF set, IF clear.
        cpu.step();
        assertTrue(cpu.isHalted());
        assertEquals(1, cpu.registers().getIp());

        cpu.hostBridge().submitNmiPin(true);
        cpu.step();
        assertFalse(cpu.isHalted());
        assertEquals(0xC000, cpu.registers().getCs());
        assertEquals(0x0100, cpu.registers().getIp());
        assertEquals(0x00FA, cpu.registers().getSp());
        assertEquals(1, cpu.memory().read16(0x3000, 0x00FA));
        assertEquals(0xC000, cpu.memory().read16(0x3000, 0x00FC));
        assertEquals(0x0101, cpu.memory().read16(0x3000, 0x00FE));
        assertEquals(0x0001, cpu.registers().getFlags());

        cpu.step(); // IRET at the NMI handler.
        assertEquals(1, cpu.registers().getIp());
        assertEquals(0x0100, cpu.registers().getSp());
        assertEquals(0x0101, cpu.registers().getFlags());
        cpu.step(); // Held-high NMI does not retrigger.
        assertEquals(0x42, cpu.registers().getAx());
        assertEquals(3, cpu.registers().getIp());

        cpu.hostBridge().submitNmiPin(false);
        cpu.hostBridge().submitNmiPin(true);
        cpu.step();
        assertEquals(0x0100, cpu.registers().getIp());
        assertEquals(3, cpu.memory().read16(0x3000, 0x00FA));
    }

    @Test
    void nmiPulseBetweenStepsInterruptsAStalledWaitAndIretRetriesIt() {
        CPU8086 cpu = boot(0x9B, 0x90);
        installNmi(cpu);
        cpu.registers().setSs(0x3000);
        cpu.registers().setSp(0x0100);
        cpu.hostBridge().submitTestPin(true);
        cpu.step();
        assertEquals(0, cpu.registers().getIp());

        cpu.hostBridge().submitNmiPin(true);
        cpu.hostBridge().submitNmiPin(false);
        cpu.step();
        assertEquals(0x0100, cpu.registers().getIp());
        assertEquals(0, cpu.memory().read16(0x3000, 0x00FA));
        cpu.step(); // IRET resumes at WAIT.
        assertEquals(0, cpu.registers().getIp());
        cpu.step();
        assertEquals(0, cpu.registers().getIp());
        cpu.hostBridge().submitTestPin(false);
        cpu.step();
        assertEquals(1, cpu.registers().getIp());
        cpu.step();
        assertEquals(2, cpu.registers().getIp());
    }

    @Test
    void resetWinsOverPendingNmiAndNmiEdgesDuringResetAreIgnored() {
        CPU8086 cpu = boot(0x90);
        installNmi(cpu);
        cpu.hostBridge().submitNmiPin(true);
        cpu.hostBridge().submitNmiPin(false);
        cpu.hostBridge().submitResetPin(true);
        cpu.step();
        assertEquals(0xFFFF, cpu.registers().getCs());
        assertEquals(0, cpu.registers().getIp());

        cpu.hostBridge().submitNmiPin(true);
        cpu.hostBridge().submitNmiPin(false);
        cpu.step(); // Held RESET ignores NMI edges.
        assertEquals(0xFFFF, cpu.registers().getCs());
        cpu.hostBridge().submitResetPin(false);
        cpu.step(); // Neither earlier NMI pulse is delivered.
        assertEquals(0xC000, cpu.registers().getCs());
        assertEquals(0, cpu.registers().getIp());

        cpu.registers().setSs(0x3000);
        cpu.registers().setSp(0x0100);
        cpu.hostBridge().submitNmiPin(true);
        cpu.step();
        assertEquals(0x0100, cpu.registers().getIp());
    }

    private static void installNmi(CPU8086 cpu) {
        cpu.memory().write16(0, 8, 0x0100);
        cpu.memory().write16(0, 10, 0xC000);
    }

    private static CPU8086 boot(int... program) {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xEA;
        rom[0x3FFF4] = (byte) 0xC0;
        rom[0x0100] = (byte) 0xCF; // NMI handler: IRET.
        for (int i = 0; i < program.length; i++) rom[i] = (byte) program[i];
        CPU8086 cpu = new CPU8086();
        cpu.hostBridge().loadRom(rom);
        cpu.step();
        return cpu;
    }
}
