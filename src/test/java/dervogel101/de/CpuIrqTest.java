package dervogel101.de;

import dervogel101.de.state.CPUIO;
import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class CpuIrqTest {
    @Test
    void queuePortsAreLittleEndianAndAckAtTheNextBoundary() {
        CPUIO io = new CPUIO();
        CpuHostBridge bridge = new CpuHostBridge(new CPUMemory(), io, Clock.systemUTC());
        assertThrows(IllegalArgumentException.class, () -> bridge.submitIrq(-1));
        assertThrows(IllegalArgumentException.class, () -> bridge.submitIrq(0x10000));
        bridge.submitRedstoneInput(0, 5);
        bridge.submitSignClick(0, CpuHostBridge.SignSide.FRONT);
        bridge.beforeInstruction();
        assertEquals(0, io.read16(0xFF00));
        assertEquals(0, io.read8(0xFF02));

        bridge.submitIrq(0);
        bridge.submitIrq(0xFFFF);
        bridge.beforeInstruction();
        assertEquals(0, io.read16(0xFF00));
        assertEquals(2, io.read8(0xFF02)); // ID zero is still pending.
        io.write16(0xFF00, 0x1234); // ID is read-only.
        assertEquals(0, io.read16(0xFF00));
        io.write8(0xFF02, 1);
        assertEquals(0, io.read16(0xFF00));
        bridge.beforeInstruction();
        assertEquals(0xFFFF, io.read16(0xFF00));
        assertEquals(0xFF, io.read8(0xFF00));
        assertEquals(0xFF, io.read8(0xFF01));
        io.write16(0xFF02, 1);
        bridge.beforeInstruction();
        assertEquals(0, io.read16(0xFF00));
        assertEquals(0, io.read8(0xFF02));
    }

    @Test
    void romInAndOutReadStatusAndAcknowledgeThroughDxByteAndWordForms() {
        CPU8086 cpu = boot(0xBA, 0x00, 0xFF, 0xEC, 0xED,
                0xBA, 0x02, 0xFF, 0xEC, 0xB8, 0x01, 0x00, 0xEF,
                0x90, 0xB0, 0x01, 0xEE, 0x90);
        cpu.hostBridge().submitIrq(0x1234);
        cpu.hostBridge().submitIrq(0xABCD);
        cpu.registers().setAx(0xEE00);
        cpu.step(); // MOV DX,FF00
        cpu.step(); // IN AL,DX
        assertEquals(0xEE34, cpu.registers().getAx());
        cpu.step(); // IN AX,DX
        assertEquals(0x1234, cpu.registers().getAx());
        cpu.step(); // MOV DX,FF02
        cpu.step(); // IN AL,DX
        assertEquals(0x1202, cpu.registers().getAx());
        cpu.step(); // MOV AX,1
        cpu.step(); // OUT DX,AX
        assertEquals(0x1234, cpu.ioForTest().read16(0xFF00));
        cpu.step(); // ACK takes effect; NOP executes.
        assertEquals(0xABCD, cpu.ioForTest().read16(0xFF00));
        cpu.step(); // MOV AL,1
        cpu.step(); // OUT DX,AL
        assertEquals(0xABCD, cpu.ioForTest().read16(0xFF00));
        cpu.step(); // ACK takes effect.
        assertEquals(0, cpu.ioForTest().read8(0xFF02));
    }

    @Test
    void overflowReplacesOldestWaitingEventAndClearPrecedesNewSubmissions() {
        CPUIO io = new CPUIO();
        CpuHostBridge bridge = new CpuHostBridge(new CPUMemory(), io, Clock.systemUTC());
        for (int id = 0; id < 256; id++) bridge.submitIrq(id);
        bridge.beforeInstruction();
        bridge.submitIrq(256);
        bridge.beforeInstruction();
        assertEquals(0, io.read16(0xFF00)); // Active event cannot be displaced.
        assertEquals(6, io.read8(0xFF02));
        io.write8(0xFF02, 1);
        bridge.beforeInstruction();
        assertEquals(2, io.read16(0xFF00)); // Oldest waiting event was displaced.
        assertEquals(6, io.read8(0xFF02));

        io.write8(0xFF02, 4);
        bridge.submitIrq(257);
        bridge.submitIrq(258); // Full again: clear happens before this overflow.
        bridge.beforeInstruction();
        assertEquals(6, io.read8(0xFF02));
        io.write8(0xFF02, 4);
        bridge.beforeInstruction();
        assertEquals(2, io.read8(0xFF02));
    }

    @Test
    void irqUsesVectorTwentyAndRetriggersUntilAcknowledged() {
        CPU8086 cpu = boot(0x90, 0x90);
        vector(cpu, 0x20, 0x0100);
        cpu.registers().setSs(0x3000);
        cpu.registers().setSp(0x0100);
        cpu.registers().setFlags(0x0301);
        cpu.hostBridge().submitIrq(128);
        cpu.step();
        assertEquals(0x0100, cpu.registers().getIp());
        assertEquals(0x00FA, cpu.registers().getSp());
        assertEquals(0, cpu.memory().read16(0x3000, 0x00FA));
        assertEquals(0xC000, cpu.memory().read16(0x3000, 0x00FC));
        assertEquals(0x0301, cpu.memory().read16(0x3000, 0x00FE));
        assertFalse(cpu.registers().getIF());
        assertFalse(cpu.registers().getTF());
        cpu.step(); // IRET restores IF.
        assertEquals(0, cpu.registers().getIp());
        cpu.step(); // No ACK: same level-held event interrupts again.
        assertEquals(0x0100, cpu.registers().getIp());
        cpu.ioForTest().write8(0xFF02, 1);
        cpu.step(); // ACK applies before handler IRET.
        cpu.step();
        assertEquals(1, cpu.registers().getIp());
    }

    @Test
    void ifGatesDeliveryAndResetWinsOverNmiAndIrq() {
        CPU8086 cpu = boot(0x90, 0x90);
        vector(cpu, 0x20, 0x0100);
        vector(cpu, 2, 0x0100);
        cpu.registers().setSs(0x3000);
        cpu.registers().setSp(0x0100);
        cpu.hostBridge().submitIrq(0xFFFF);
        cpu.step(); // IF is clear.
        assertEquals(1, cpu.registers().getIp());
        cpu.hostBridge().submitNmiPin(true);
        cpu.hostBridge().submitResetPin(true);
        cpu.step(); // RESET wins and discards NMI.
        assertEquals(0xFFFF, cpu.registers().getCs());
        assertEquals(2, cpu.ioForTest().read8(0xFF02));
        cpu.hostBridge().submitResetPin(false);
        cpu.step(); // Reset vector executes; queue survives.
        cpu.registers().setSs(0x3000);
        cpu.registers().setSp(0x0100);
        cpu.registers().setIF(true);
        cpu.step();
        assertEquals(0x0100, cpu.registers().getIp());
        assertEquals(0, cpu.memory().read16(0x3000, 0x00FA));
    }

    @Test
    void stiMovSsAndPopSsDelayIrqForOneCompletedInstruction() {
        assertDelayed(0xFB, 0x90, 0x90); // STI; NOP; NOP
        assertDelayed(0x8E, 0xD0, 0x90); // MOV SS,AX; NOP
        assertDelayed(0x17, 0x90, 0x90); // POP SS; NOP; NOP
    }

    @Test
    void resetClearsPendingStiDelayButPreservesTheIrq() {
        CPU8086 cpu = boot(0xFB, 0x90);
        vector(cpu, 0x20, 0x0100);
        cpu.registers().setSs(0x3000);
        cpu.registers().setSp(0x0100);
        cpu.hostBridge().submitIrq(7);
        cpu.step(); // STI, with an IRQ already pending.
        cpu.hostBridge().submitResetPin(true);
        cpu.hostBridge().submitResetPin(false);
        cpu.step(); // Reset clears the STI delay, but not the queue.
        cpu.step(); // Reset-vector jump with IF clear.
        cpu.registers().setSs(0x3000);
        cpu.registers().setSp(0x0100);
        cpu.registers().setIF(true);
        cpu.step();
        assertEquals(0x0100, cpu.registers().getIp());
        assertEquals(7, cpu.ioForTest().read16(0xFF00));
    }

    private static void assertDelayed(int first, int second, int third) {
        CPU8086 cpu = boot(first, second, third);
        vector(cpu, 0x20, 0x0100);
        cpu.registers().setSs(0x3000);
        cpu.registers().setSp(0x0100);
        cpu.memory().write16(0x3000, 0x0100, 0x3000);
        if (first != 0xFB) cpu.registers().setIF(true);
        cpu.step();
        int afterFirst = cpu.registers().getIp();
        cpu.hostBridge().submitIrq(1);
        cpu.step();
        assertEquals(afterFirst + 1, cpu.registers().getIp());
        cpu.step();
        assertEquals(0x0100, cpu.registers().getIp());
    }

    @Test
    void irqWakesHltAndWaitButResetAndNmiTakePriority() {
        CPU8086 halted = boot(0xF4, 0x90);
        vector(halted, 0x20, 0x0100);
        halted.registers().setSs(0x3000);
        halted.registers().setSp(0x0100);
        halted.registers().setIF(true);
        halted.step();
        assertTrue(halted.isHalted());
        halted.hostBridge().submitIrq(256);
        halted.step();
        assertFalse(halted.isHalted());
        assertEquals(1, halted.memory().read16(0x3000, 0x00FA));

        CPU8086 waiting = boot(0x9B, 0x90);
        vector(waiting, 0x20, 0x0100);
        vector(waiting, 2, 0x0100);
        waiting.registers().setSs(0x3000);
        waiting.registers().setSp(0x0100);
        waiting.registers().setIF(true);
        waiting.hostBridge().submitTestPin(true);
        waiting.step();
        assertEquals(0, waiting.registers().getIp());
        waiting.hostBridge().submitIrq(42);
        waiting.hostBridge().submitNmiPin(true);
        waiting.step();
        assertEquals(0, waiting.memory().read16(0x3000, 0x00FA));
        assertEquals(2, waiting.ioForTest().read8(0xFF02));
        waiting.hostBridge().submitResetPin(true);
        waiting.step();
        assertEquals(0xFFFF, waiting.registers().getCs());
        assertEquals(2, waiting.ioForTest().read8(0xFF02));
        waiting.hostBridge().submitResetPin(false);
        waiting.step(); // Reset vector.
        waiting.registers().setIF(true);
        waiting.step(); // Preserved IRQ is delivered before WAIT.
        assertEquals(0x0100, waiting.registers().getIp());
    }

    private static void vector(CPU8086 cpu, int number, int ip) {
        cpu.memory().write16(0, number * 4, ip);
        cpu.memory().write16(0, number * 4 + 2, 0xC000);
    }

    private static CPU8086 boot(int... program) {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xEA;
        rom[0x3FFF4] = (byte) 0xC0;
        rom[0x0100] = (byte) 0xCF;
        for (int i = 0; i < program.length; i++) rom[i] = (byte) program[i];
        CPU8086 cpu = new CPU8086();
        cpu.hostBridge().loadRom(rom);
        cpu.step();
        return cpu;
    }
}
