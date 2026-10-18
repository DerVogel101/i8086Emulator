package dervogel101.de;

import dervogel101.de.i86.runtime.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PunchcardRomTest {
    @Test void realCLibraryWritesSwitchesEjectsAndReadsWithIrqs() throws Exception {
        String path = System.getenv("PUNCHCARD_TEST_ROM"); assumeTrue(path != null,"Set PUNCHCARD_TEST_ROM to the IA-16 test ROM");
        var cpu = new CPU8086(); var counts = new EnumMap<Hardware,Integer>(HardwareProfile.complete().counts());
        counts.put(Hardware.GPU,3); counts.put(Hardware.PUNCHCARD,1); cpu.configureHardware(new HardwareProfile(counts));
        cpu.hostBridge().loadRom(Files.readAllBytes(Path.of(path)));
        var writer = new PunchcardDevice(true); var reader = new PunchcardDevice(false);
        cpu.hostBridge().submitPunchcardConnection(true,0,writer); cpu.hostBridge().submitPunchcardConnection(false,0,reader);
        cpu.beforeTick(); cpu.beforeBatch();
        for (int tick=0;tick<250 && word(cpu,0)!=3;tick++) {
            if (word(cpu,0)==1 && writer.active()==null && writer.output()==null) writer.insert(PunchcardDeck.blank());
            if (word(cpu,0)==2 && reader.active()==null && writer.output()!=null) reader.insert(writer.collect());
            cpu.beforeTick();
            for (int step=0;step<2000;step++) { var result=cpu.step(); if (result==CPU8086.StepOutcome.YIELD_TICK) break; }
        }
        assertEquals(3,word(cpu,0),"ROM must finish"); assertEquals(0,word(cpu,1),"ROM self-checks");
        assertEquals(2,word(cpu,2),"Both insertions generated IRQ callbacks"); assertEquals(25,word(cpu,3),"9 writes plus 16 reads");
        assertEquals(2,reader.output().cards()); assertEquals("Punchcard C test",reader.output().label());
        assertEquals("Independent description",reader.output().descriptionText());
    }
    private static int word(CPU8086 cpu,int index) { return cpu.memory().read16(0,0x500+index*2); }
}
