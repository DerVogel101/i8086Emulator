package dervogel101.de.launcher;

import dervogel101.de.i86.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class LauncherPunchcardTest {
    @TempDir Path folder;
    @Test void pausedCommandsFilesAndSessionsRetainMediaAndDirtyState() throws Exception {
        var views=new LinkedBlockingQueue<DebuggerBackend.Snapshot>(); var errors=new LinkedBlockingQueue<String>(); byte[] session;
        UUID id;
        try (var cpu=new LauncherController(views::add,errors::add)) {
            var counts=new EnumMap<Hardware,Integer>(HardwareProfile.complete().counts()); counts.put(Hardware.PUNCHCARD,1);
            cpu.configureMachine(new HardwareProfile(counts),1,"punchcard_test");
            byte[] rom=new byte[262144]; rom[rom.length-16]=15; rom[rom.length-15]=1; rom[rom.length-14]=(byte)0xeb; rom[rom.length-13]=(byte)0xfc;
            Path path=folder.resolve("test.bin"); Files.write(path,rom); cpu.loadRom(path); cpu.step(); cpu.pauseAndWait();
            cpu.selectPunchcard(16); var blank=PunchcardDeck.blank(); cpu.insertPunchcard(16,blank); id=blank.id();
            assertThrows(IllegalStateException.class,() -> cpu.insertPunchcard(17,blank));
            cpu.punchcardCommand(16,PunchcardDevice.WRITE_NAME,HexFormat.of().formatHex(Arrays.copyOf("Launcher description".getBytes(PunchcardDeck.CHARSET),32)),1);
            await(views,s -> s.devices().punchcardIndex()==16 && (s.devices().punchcard()[33]&PunchcardDevice.READY)!=0);
            assertEquals("Launcher description",cpu.punchcardMedia(16,false).descriptionText());
            cpu.punchcardCommand(16,PunchcardDevice.ACK,"");
            cpu.punchcardCommand(16,PunchcardDevice.WRITE,"2a"+"00".repeat(31));
            await(views,s -> s.devices().punchcardIndex()==16 && (s.devices().punchcard()[33]&PunchcardDevice.READY)!=0);
            assertTrue(cpu.punchcardDirty(16,false));
            cpu.savePunchcard(16,false,folder.resolve("deck.pdeck")); assertFalse(cpu.punchcardDirty(16,false));
            var exported=PunchcardDeck.load(folder.resolve("deck.pdeck")); assertEquals(id,exported.id()); assertTrue(exported.sealed());
            cpu.punchcardCommand(16,PunchcardDevice.ACK,""); cpu.punchcardCommand(16,PunchcardDevice.EJECT,"");
            await(views,s -> s.devices().punchcardIndex()==16 && (s.devices().punchcard()[33]&PunchcardDevice.OUTPUT)!=0);
            session=cpu.saveSessionState(false); assertEquals(0,errors.size());
        }
        try (var restored=new LauncherController(s -> {},errors::add)) {
            restored.restoreSessionState(session); assertEquals(id,restored.punchcardMedia(16,true).id());
            assertFalse(restored.punchcardDirty(16,true));
            assertEquals("Launcher description",restored.punchcardMedia(16,true).descriptionText()); restored.collectPunchcard(16); assertNull(restored.punchcardMedia(16,true));
        }
    }
    private static void await(BlockingQueue<DebuggerBackend.Snapshot> views,Predicate<DebuggerBackend.Snapshot> predicate) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime()<end) { var view=views.poll(100,TimeUnit.MILLISECONDS); if (view!=null && predicate.test(view)) return; }
        fail("Punchcard operation did not finish while paused");
    }
}
