package dervogel101.de.launcher;

import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Disassembler8086Test {
    @Test
    void recognizesTheSupported8086OpcodeMapAndYieldExtension() {
        CPUMemory memory = new CPUMemory();
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        for (int opcode = 0; opcode < 256; opcode++) {
            rom[0] = (byte) opcode;
            memory.loadRom(rom);
            boolean defined = opcode <= 0x5F
                    || opcode >= 0x70 && opcode <= 0x81
                    || opcode >= 0x83 && opcode <= 0xBF
                    || opcode >= 0xC2 && opcode <= 0xC7
                    || opcode >= 0xCA && opcode <= 0xCF
                    || opcode >= 0xD0 && opcode <= 0xD5
                    || opcode >= 0xD7 && opcode <= 0xF0
                    || opcode >= 0xF2;
            var instruction = Disassembler8086.decode(memory, 0xC000, 0);
            assertEquals(defined, !instruction.mnemonic().equals("DB"), "opcode %02X".formatted(opcode));
            assertTrue(instruction.length() >= 1 && instruction.length() <= 15);
        }
    }

    @Test
    void decodesInstructionBoundariesOperandsAndTargets() {
        CPUMemory memory = new CPUMemory();
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        byte[] code = {
                (byte) 0xB8, 0x34, 0x12,                       // MOV AX,1234
                0x01, 0x46, (byte) 0xFE,                      // ADD [BP-2],AX
                (byte) 0xE8, 0x05, 0x00,                     // CALL +5
                (byte) 0xF3, (byte) 0xA4,                    // REP MOVSB
                (byte) 0xD8, 0x46, 0x02,                     // unsupported ESC
                (byte) 0xF4,                                 // HLT
                0x62                                         // undefined on 8086
        };
        System.arraycopy(code, 0, rom, 0, code.length);
        memory.loadRom(rom);
        var mov = Disassembler8086.decode(memory, 0xC000, 0);
        assertEquals("MOV AX, 0x1234", mov.assembly());
        assertEquals(3, mov.length());
        var add = Disassembler8086.decode(memory, 0xC000, 3);
        assertEquals("ADD WORD [BP-0x02], AX", add.assembly());
        assertEquals(3, add.length());
        var call = Disassembler8086.decode(memory, 0xC000, 6);
        assertEquals("CALL 0x000E", call.assembly());
        assertTrue(call.call());
        assertEquals(9, call.nextIp());
        assertEquals("REP MOVSB", Disassembler8086.decode(memory, 0xC000, 9).assembly());
        var esc = Disassembler8086.decode(memory, 0xC000, 11);
        assertEquals(3, esc.length());
        assertTrue(esc.unsupported());
        assertEquals("HLT", Disassembler8086.decode(memory, 0xC000, 14).assembly());
        var undefined = Disassembler8086.decode(memory, 0xC000, 15);
        assertEquals("DB 0x62", undefined.assembly());
        assertEquals(1, undefined.length());
    }

    @Test
    void fetchWrapsWithinTheCodeSegment() {
        CPUMemory memory = new CPUMemory();
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0xFFFE] = (byte) 0xE9;
        rom[0xFFFF] = (byte) 0xFE;
        rom[0] = (byte) 0xFF;
        memory.loadRom(rom);
        var jump = Disassembler8086.decode(memory, 0xC000, 0xFFFE);
        assertEquals("JMP 0xFFFF", jump.assembly());
        assertEquals(3, jump.length());
        assertEquals(1, jump.nextIp());
    }

    @Test
    void editsUseTheDisplayedByteFormat() {
        assertEquals("10100101", ByteViewPanel.format(0xA5, ByteViewPanel.Format.BINARY));
        assertEquals(0xA5, ByteViewPanel.parse("10100101", ByteViewPanel.Format.BINARY));
        assertEquals("€", ByteViewPanel.format(0x80, ByteViewPanel.Format.WINDOWS_1252));
        assertEquals(0x80, ByteViewPanel.parse("€", ByteViewPanel.Format.WINDOWS_1252));
        assertEquals("\\x81", ByteViewPanel.format(0x81, ByteViewPanel.Format.WINDOWS_1252));
        assertEquals(0x81, ByteViewPanel.parse("\\x81", ByteViewPanel.Format.WINDOWS_1252));
        assertThrows(IllegalArgumentException.class, () -> ByteViewPanel.parse("111", ByteViewPanel.Format.BINARY));
    }

    @Test
    void mappedEditsRespectReadOnlyRegions() {
        assertTrue(LauncherController.writableMemory(0x9FFF2));
        assertFalse(LauncherController.writableMemory(0x9FFF3));
        assertTrue(LauncherController.writableMemory(0xA0000));
        assertFalse(LauncherController.writableMemory(0xC0000));
        assertFalse(LauncherController.writableIo(0x0000));
        assertTrue(LauncherController.writableIo(0x0001));
        assertTrue(LauncherController.writableIo(0x0100));
        assertFalse(LauncherController.writableIo(0x0180));
        assertTrue(LauncherController.writableIo(0x8102));
        assertFalse(LauncherController.writableIo(0x8100));
        assertTrue(LauncherController.writableIo(0xFF02));
    }
}
