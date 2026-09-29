package dervogel101.de;

import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CPU8086Test {
    @Test
    void reportsCompletedHaltedAndUnsupportedSteps() {
        CPU8086 cpu = new CPU8086();
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xF4;
        cpu.hostBridge().loadRom(rom);
        assertEquals(CPU8086.StepOutcome.INSTRUCTION, cpu.step());
        assertEquals(CPU8086.StepOutcome.IDLE, cpu.step());

        rom[0x3FFF0] = (byte) 0xD8;
        CPU8086 unsupported = new CPU8086();
        unsupported.hostBridge().loadRom(rom);
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, unsupported::step);
        assertTrue(error.getMessage().contains("D8 at FFFF:0000"));
    }

    @Test
    void executesRomFromResetVectorUntilHalt() {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xEA;
        rom[0x3FFF1] = 0;
        rom[0x3FFF2] = 0;
        rom[0x3FFF3] = 0;
        rom[0x3FFF4] = (byte) 0xC0;
        rom[0] = (byte) 0xB8;
        rom[1] = 0x34;
        rom[2] = 0x12;
        rom[3] = (byte) 0xF4;

        CPU8086 cpu = new CPU8086();
        cpu.hostBridge().loadRom(rom);
        cpu.step();
        assertEquals(0xC000, cpu.registers().getCs());
        assertEquals(0, cpu.registers().getIp());
        cpu.step();
        assertEquals(0x1234, cpu.registers().getAx());
        cpu.step();
        assertTrue(cpu.isHalted());
        assertEquals(4, cpu.registers().getIp());
        cpu.step();
        assertEquals(4, cpu.registers().getIp());
    }

    @Test
    void unsupportedOpcodeReportsItsStartingAddress() {
        CPU8086 cpu = new CPU8086();
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = 0x62;
        cpu.hostBridge().loadRom(rom);
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, cpu::step);
        assertTrue(error.getMessage().contains("62 at FFFF:0000"));
    }
}
