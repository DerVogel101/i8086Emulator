package dervogel101.de;

import dervogel101.de.i86.runtime.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.EnumMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KeyboardEditorRomTest {
    private static final Charset SIGN_TEXT = Charset.forName("windows-1252");

    @Test void insertsMovesSplitsJoinsAndErasesTextOnBothFaces() throws Exception {
        var cpu = boot();
        assertEquals("Ready", line(cpu, 2, 3));
        press(cpu, 'A', 'B', 'C', 0x92, 'X');
        assertEquals("ABXC", line(cpu, 1, 0));
        press(cpu, 8, 0x97, 'D');
        assertEquals("ABD", line(cpu, 1, 0));
        press(cpu, 0x92, 0x92, 10);
        assertEquals("A", line(cpu, 1, 0));
        assertEquals("BD", line(cpu, 1, 1));
        assertTrue(line(cpu, 2, 0).startsWith("R2 C01"));
        press(cpu, 0x95, 0x94, 0x93, 8);
        assertEquals("D", line(cpu, 1, 1));
        press(cpu, 8);
        assertEquals("AD", line(cpu, 1, 0));
        assertEquals("", line(cpu, 1, 1));
        assertEquals("Join lines", line(cpu, 2, 3));
        press(cpu, 0x98, 0x97, 0x97, 8, 9, 0x80, 0xe4, 0xb4);
        assertEquals("    €ä´", line(cpu, 1, 0));
        assertTrue(line(cpu, 2, 0).endsWith("Used07"));
        assertMirrored(cpu);
        assertEquals(0, cpu.io().read8(0xff02) & 2, "IRQ manager ACKs the global event");
        assertEquals(3, cpu.io().read8(0x8171), "Device ACK remains pending until the next tick");
        tick(cpu);
        assertEquals(0, cpu.io().read8(0x8171));
    }

    @Test void numpadChangesLineFormatsWithoutInsertingSpecialCodes() throws Exception {
        var cpu = boot();
        press(cpu, 'A', 0x8a, 0x8f, 0x8e, 0x8c, 0x8d, 0x8b);
        assertEquals("A", line(cpu, 1, 0));
        assertEquals(0xe9, cpu.io().read8(0x200));
        assertEquals("Col:9 I1 U1 S1", line(cpu, 2, 1));
        press(cpu, 10, 'B');
        assertEquals(0xe9, cpu.io().read8(0x210), "Split line inherits formatting");
        press(cpu, 0x90);
        assertEquals(0x10, cpu.io().read8(0x210));
        assertEquals(0xe9, cpu.io().read8(0x200), "Other lines retain their formatting");
        press(cpu, 0x91);
        assertEquals(0, cpu.io().read8(0x210));
        press(cpu, 0x84, '+', '-', '*', '/', '.', '=', 0x96);
        assertEquals(3, cpu.io().read8(0x210));
        assertEquals("B+-*/.=", line(cpu, 1, 1));
        assertEquals("Unused key", line(cpu, 2, 3));
        press(cpu, 0x94, 0x95, 0x99, 0x9a, 0x9b);
        assertTrue(line(cpu, 2, 0).startsWith("R4 C01"));
        assertMirrored(cpu);
    }

    @Test void fullLinesAndDocumentRejectEditsWithoutLosingText() throws Exception {
        var cpu = boot();
        for (int i = 0; i < 60; i++) press(cpu, 'x');
        for (int row = 0; row < 4; row++) assertEquals("x".repeat(15), line(cpu, 1, row));
        assertEquals("R4 C16 Used60", line(cpu, 2, 0));
        press(cpu, 'Y');
        assertEquals("Line full", line(cpu, 2, 3));
        press(cpu, 10);
        assertEquals("Last line", line(cpu, 2, 3));
        press(cpu, 0x98, 8);
        assertEquals("Line too long", line(cpu, 2, 3));
        press(cpu, 0x94, 10);
        assertEquals("No free line", line(cpu, 2, 3));
        for (int row = 0; row < 4; row++) assertEquals("x".repeat(15), line(cpu, 1, row));
        press(cpu, 0x9b, 0x99, 8, 'Z');
        assertEquals("x".repeat(14) + "Z", line(cpu, 1, 3));
        assertMirrored(cpu);
    }

    private static CPU8086 boot() throws Exception {
        String path = System.getenv("KEYBOARD_EDITOR_ROM");
        assumeTrue(path != null, "Set KEYBOARD_EDITOR_ROM to the actual IA-16 editor image");
        var cpu = new CPU8086();
        var hardware = new EnumMap<Hardware, Integer>(HardwareProfile.complete().counts());
        hardware.put(Hardware.KEYBOARD, 1);
        cpu.configureHardware(new HardwareProfile(hardware));
        byte[] image = Files.readAllBytes(Path.of(path));
        assertEquals(256 * 1024, image.length);
        cpu.hostBridge().loadRom(image);
        cpu.hostBridge().submitKeyboardConnection(0, true);
        tick(cpu);
        return cpu;
    }

    private static void press(CPU8086 cpu, int... bytes) {
        for (int value : bytes) {
            assertTrue(cpu.hostBridge().submitKeyboardInput(0, value));
            tick(cpu);
        }
    }

    private static void tick(CPU8086 cpu) {
        cpu.beforeTick(); cpu.beforeBatch();
        for (int i = 0; i < 200_000; i++) {
            var outcome = cpu.step();
            if (outcome == CPU8086.StepOutcome.YIELD_TICK) return;
            assertNotEquals(CPU8086.StepOutcome.IDLE, outcome, "Editor must keep running");
        }
        fail("Editor did not yield within its instruction bound");
    }

    private static String line(CPU8086 cpu, int sign, int row) {
        var bytes = new byte[15];
        int count = 0;
        while (count < bytes.length) {
            int value = cpu.io().read8(0x100 + sign * 0x100 + row * 16 + 1 + count);
            if (value == 0) break;
            bytes[count++] = (byte) value;
        }
        return new String(bytes, 0, count, SIGN_TEXT);
    }

    private static void assertMirrored(CPU8086 cpu) {
        for (int sign = 1; sign <= 2; sign++) for (int i = 0; i < 64; i++)
            assertEquals(cpu.io().read8(0x100 + sign * 0x100 + i),
                    cpu.io().read8(0x140 + sign * 0x100 + i));
    }
}
