package dervogel101.de;

import dervogel101.de.state.CPUIO;
import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class CpuTerminalTest {
    private static final int TEXT = 0x8130;
    private static final int CONTROL = 0x8150;

    @Test
    void sendDecodesWindows1252SkipsZerosAndClearsAllPortsAtNextBoundary() {
        CPUIO io = new CPUIO();
        CpuHostBridge bridge = new CpuHostBridge(new CPUMemory(), io, Clock.systemUTC());
        io.write16(TEXT, 0x0041); // A followed by a zero byte.
        io.write8(TEXT + 2, 0x80); // Euro sign in Windows-1252.
        io.write8(TEXT + 3, 0x0A);
        io.write8(TEXT + 4, 0xE9); // é
        io.write16(TEXT + 31, 0x015A); // Z in last character port, send in high byte.
        assertEquals(0x015A, io.read16(TEXT + 31));
        assertEquals("", bridge.terminalText());
        bridge.beforeInstruction();
        assertEquals("A€\néZ", bridge.terminalText());
        assertEquals(0, io.read16(TEXT));
        assertEquals(0, io.read16(TEXT + 31));
        io.write8(CONTROL, 0); // Write-zero does not request a send.
        bridge.beforeInstruction();
        assertEquals("A€\néZ", bridge.terminalText());
    }

    @Test
    void exactlyThirtyTwoBytesFitAndOverflowKeepsNewestCharacters() {
        CPUIO io = new CPUIO();
        CpuHostBridge bridge = new CpuHostBridge(new CPUMemory(), io, Clock.systemUTC());
        for (int send = 0; send < 512; send++) send(bridge, io, send == 0 ? 'A' : 'B');
        String full = bridge.terminalText();
        assertEquals(16_384, full.length());
        assertTrue(full.startsWith("A".repeat(32)));
        send(bridge, io, 'C');
        String rolled = bridge.terminalText();
        assertEquals(16_384, rolled.length());
        assertTrue(rolled.startsWith("B".repeat(32)));
        assertTrue(rolled.endsWith("C".repeat(32)));
    }

    @Test
    void romOutSendsBeforeFollowingInstructionAndCpuResetKeepsLog() {
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0x3FFF0] = (byte) 0xEA;
        rom[0x3FFF4] = (byte) 0xC0;
        int[] program = {
                0xBA, 0x30, 0x81, // MOV DX,8130
                0xB8, 0x48, 0x0A, // MOV AX,0A48 (H, newline)
                0xEF,             // OUT DX,AX
                0xBA, 0x50, 0x81, // MOV DX,8150
                0xB0, 0x01,       // MOV AL,1
                0xEE,             // OUT DX,AL
                0x90              // NOP
        };
        for (int i = 0; i < program.length; i++) rom[i] = (byte) program[i];
        CPU8086 cpu = new CPU8086();
        cpu.hostBridge().loadRom(rom);
        cpu.step(); // Reset-vector jump.
        for (int i = 0; i < 6; i++) cpu.step();
        assertEquals("", cpu.hostBridge().terminalText());
        assertEquals(1, cpu.ioForTest().read8(CONTROL));
        cpu.step(); // NOP boundary consumes the message first.
        assertEquals("H\n", cpu.hostBridge().terminalText());
        assertEquals(0, cpu.ioForTest().read8(CONTROL));
        cpu.hostBridge().submitResetPin(true);
        cpu.hostBridge().submitResetPin(false);
        cpu.step();
        assertEquals("H\n", cpu.hostBridge().terminalText());
    }

    private static void send(CpuHostBridge bridge, CPUIO io, char value) {
        for (int i = 0; i < 32; i++) io.write8(TEXT + i, value);
        io.write8(CONTROL, 0xFF);
        assertEquals(1, io.read8(CONTROL));
        bridge.beforeInstruction();
    }
}
