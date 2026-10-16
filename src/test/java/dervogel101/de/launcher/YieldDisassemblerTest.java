package dervogel101.de.launcher;

import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YieldDisassemblerTest {
    @Test void decodesBothModesAndMarksOtherModesUnsupported() {
        var memory = new CPUMemory(); memory.write8(0, 0, 0x0F);
        for (int mode : new int[] {0, 1, 2, 11, 255}) {
            memory.write8(0, 1, mode);
            var instruction = Disassembler8086.decode(memory, 0, 0);
            assertEquals("YIELD", instruction.mnemonic());
            assertEquals(Integer.toString(mode), instruction.operands());
            assertEquals(2, instruction.length());
            assertEquals(mode > 1, instruction.unsupported());
        }
    }

    @Test void coprocessorOpcodesRemainUnsupported() {
        var memory = new CPUMemory();
        for (int opcode = 0xD8; opcode <= 0xDF; opcode++) {
            memory.write8(0, 0, opcode); memory.write8(0, 1, 0);
            var instruction = Disassembler8086.decode(memory, 0, 0);
            assertTrue(instruction.unsupported()); assertNotEquals("YIELD", instruction.mnemonic());
        }
    }
}
