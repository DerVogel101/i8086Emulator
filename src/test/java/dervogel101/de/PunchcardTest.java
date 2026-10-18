package dervogel101.de;

import dervogel101.de.i86.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PunchcardTest {
    private static byte[] text(String value) { return Arrays.copyOf(value.getBytes(PunchcardDeck.CHARSET),32); }
    @Test void independentMetadataCapturesSelectorAndSurvivesPendingCheckpoint() throws Exception {
        var d = new PunchcardDevice(true); d.insert(PunchcardDeck.blank());
        d.write(0x2c,1);
        byte[] description = text("Separate description");
        for (int i=0;i<32;i++) d.write(i,description[i]);
        d.write(32,PunchcardDevice.WRITE_NAME);
        d.write(0x2c,0);
        var bytes = new ByteArrayOutputStream(); d.writeState(new DataOutputStream(bytes));
        var deck = d.active();
        d = PunchcardDevice.readState(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),id -> deck);
        d.beforeInstruction(); d.tick();
        assertEquals("Separate description",d.active().descriptionText());
        assertFalse(d.active().nameWritten()); assertEquals(4,d.read(0x2b)); ack(d);
        byte[] name = text("Initial name"); for (int i=0;i<32;i++) d.write(i,name[i]);
        complete(d,PunchcardDevice.WRITE_NAME); assertEquals("Initial name",d.active().label()); ack(d);
        d.write(0x2c,1); complete(d,PunchcardDevice.WRITE_NAME); assertEquals(7,d.read(35));
        complete(d,PunchcardDevice.CLEAR_ERROR);
        complete(d,PunchcardDevice.WRITE); ack(d);
        complete(d,PunchcardDevice.EJECT);
        var written = d.collect();
        Path path = folder.resolve("metadata.pdeck"); written.save(path);
        var copy = PunchcardDeck.load(path);
        assertEquals(written.hash(),copy.hash()); assertArrayEquals(description,copy.description());
        var reader = new PunchcardDevice(false); reader.insert(copy);
        reader.write(0x2c,1); complete(reader,PunchcardDevice.READ_NAME);
        for (int i=0;i<32;i++) assertEquals(description[i]&255,reader.read(i));
        assertEquals(0,reader.read(42)); ack(reader);
        reader.write(0x2c,0); complete(reader,PunchcardDevice.READ_NAME);
        for (int i=0;i<32;i++) assertEquals(name[i]&255,reader.read(i));
    }
    @Test void versionOneDecksKeepTheirHashesAndEmptyDescription() throws Exception {
        var old = PunchcardDeck.blank().name(text("Old deck")).row(0,0,text("Data")).seal();
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeInt(0x5044454b); out.writeInt(1);
        out.writeLong(old.id().getMostSignificantBits()); out.writeLong(old.id().getLeastSignificantBits());
        out.writeByte(3); out.write(old.name()); out.writeShort(old.cards()); out.write(old.data());
        var loaded = PunchcardDeck.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        var hash = java.security.MessageDigest.getInstance("SHA-256");
        hash.update(old.name()); hash.update(new byte[]{0,1}); hash.update(old.data());
        assertEquals(HexFormat.of().formatHex(hash.digest()),loaded.hash());
        assertEquals("",loaded.descriptionText()); assertFalse(loaded.descriptionWritten());
        assertNotEquals(loaded.hash(),loaded.description(text("New description")).hash());
        loaded.save(folder.resolve("migrated.pdeck"));
        assertEquals(loaded.hash(),PunchcardDeck.load(folder.resolve("migrated.pdeck")).hash());
        var transport = new PunchcardDevice(false); transport.insert(loaded);
        bytes.reset(); transport.writeState(out);
        byte[] state = Arrays.copyOf(bytes.toByteArray(),bytes.size()-2); state[3]=2;
        assertEquals(0,PunchcardDevice.readState(new DataInputStream(new ByteArrayInputStream(state)),id -> loaded).read(0x2c));
        assertThrows(IllegalArgumentException.class,() -> new PunchcardDeck(old.id(),new byte[32],text("Invalid"),new byte[0],true,false,false));
    }
    @TempDir Path folder;
    private static void complete(PunchcardDevice d, int command) { d.write(32, command); d.beforeInstruction(); d.tick(); }
    private static void ack(PunchcardDevice d) { d.write(32, PunchcardDevice.ACK); d.beforeInstruction(); }
    @Test void rowsBoundariesNameAndFullCapacity() {
        var d = new PunchcardDevice(true); assertTrue(d.insert(PunchcardDeck.blank()));
        d.write(0, 'A'); complete(d, PunchcardDevice.WRITE_NAME); ack(d);
        for (int card=0; card<256; card++) {
            for (int row=0; row<8; row++) {
                d.write(0, row); d.write(32, PunchcardDevice.WRITE); d.beforeInstruction();
                assertEquals(row, d.read(42)); d.write(0, 99); d.tick();
                assertEquals(row, d.active().row(card, row)[0]); ack(d);
            }
            assertTrue((d.read(33) & PunchcardDevice.EOC) != 0);
            if (card<255) { complete(d, PunchcardDevice.NEXT); ack(d); }
        }
        assertEquals(256, d.read(36) | d.read(37)<<8); assertEquals(0, d.read(38) | d.read(39)<<8);
        assertTrue((d.read(33) & PunchcardDevice.END) != 0);
        complete(d, PunchcardDevice.EJECT); var deck = d.collect(); assertEquals(65536, deck.data().length);
        assertEquals("A", deck.label()); assertFalse(d.insert(deck));
        var reader = new PunchcardDevice(false); assertTrue(reader.insert(deck));
        complete(reader, PunchcardDevice.READ); assertEquals(0, reader.read(0));
        reader.write(0, 88); assertEquals(0, reader.read(0));
        complete(reader, PunchcardDevice.READ); assertEquals(1, reader.read(42));
    }
    @Test void pendingStateAndBlockedEjectSurviveRestore() throws Exception {
        var d = new PunchcardDevice(true); d.insert(PunchcardDeck.blank()); complete(d, PunchcardDevice.WRITE);
        ack(d); complete(d, PunchcardDevice.EJECT); ack(d); d.insert(PunchcardDeck.blank());
        complete(d, PunchcardDevice.EJECT); assertNotNull(d.active());
        var decks = Map.of(d.active().id(), d.active(), d.output().id(), d.output());
        var bytes = new ByteArrayOutputStream(); d.writeState(new DataOutputStream(bytes));
        var restored = PunchcardDevice.readState(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), decks::get);
        restored.tick(); assertNotNull(restored.active()); restored.collect(); restored.tick();
        assertNull(restored.active()); assertTrue(restored.output().sealed());
    }
    @Test void fileRoundTripAndValidation() throws Exception {
        var deck = PunchcardDeck.blank().row(0, 0, new byte[32]).seal(); Path path = folder.resolve("a.pdeck"); deck.save(path);
        var copy = PunchcardDeck.load(path); assertEquals(deck.id(), copy.id()); assertEquals(deck.hash(), copy.hash());
        Files.write(path, new byte[]{1}, StandardOpenOption.APPEND); assertThrows(IOException.class, () -> PunchcardDeck.load(path));
    }
    @Test void manualEjectionCancelsIncompleteWriteWithoutFinishingIt() {
        var device = new PunchcardDevice(true); device.insert(PunchcardDeck.blank());
        device.write(0,42); device.write(32,PunchcardDevice.WRITE); device.beforeInstruction();
        device.ejectManually(); assertNull(device.active()); assertEquals(0,device.output().cards()); assertTrue(device.output().sealed());
    }
    @Test void allSlotsGatingIrqsAndCpuSnapshot() throws Exception {
        var cpu = new CPU8086(); var counts = new EnumMap<Hardware,Integer>(HardwareProfile.complete().counts());
        counts.put(Hardware.GPU, 3); counts.put(Hardware.PUNCHCARD, 1); cpu.configureHardware(new HardwareProfile(counts));
        for (int slot=0; slot<16; slot++) for (boolean writer : new boolean[]{false,true}) {
            var d = new PunchcardDevice(writer); cpu.io().punchcards.connect(writer, slot, d);
            assertTrue(d.insert(writer ? PunchcardDeck.blank() : PunchcardDeck.blank().row(0,0,new byte[32]).seal()));
            int base = (writer ? 0x8600 : 0x8200) + slot*64;
            assertEquals(3, cpu.io().read8(base+33) & 3);
        }
        cpu.beforeInstruction(); assertTrue(cpu.hostBridge().irqPending());
        var restored = CPU8086.restoreState(cpu.saveState(), new dervogel101.de.state.SharedMemoryRegion());
        assertTrue(restored.hardware().has(Hardware.PUNCHCARD)); assertEquals(0, restored.io().read8(0x8221));
        cpu.configureHardware(HardwareProfile.complete()); assertEquals(0, cpu.io().read8(0x8221));
    }
    @Test void pollingWithoutInterruptControllerAndRepeatedTickBoundaries() {
        var cpu=new CPU8086(); var hardware=new EnumMap<Hardware,Integer>(HardwareProfile.complete().counts());
        hardware.put(Hardware.PUNCHCARD,1); hardware.remove(Hardware.INTERRUPTS); cpu.configureHardware(new HardwareProfile(hardware));
        var device=new PunchcardDevice(true); device.insert(PunchcardDeck.blank()); cpu.hostBridge().submitPunchcardConnection(true,0,device);
        cpu.hostBridge().submitWorldTime(100,100); cpu.beforeTick();
        cpu.io().write8(0x8620,PunchcardDevice.WRITE); cpu.beforeInstruction(); cpu.beforeTick();
        assertEquals(0,device.read(42),"The same world tick cannot process a new request");
        cpu.hostBridge().submitWorldTime(101,101); cpu.hostBridge().servicePunchcardTick();
        assertEquals(1,device.read(42)); assertFalse(cpu.hostBridge().irqPending());
        cpu.io().write8(0x8620,PunchcardDevice.ACK); cpu.beforeInstruction();
        cpu.io().write8(0x8620,PunchcardDevice.WRITE); cpu.beforeInstruction(); cpu.beforeTick();
        assertEquals(1,device.read(42));
        cpu.hostBridge().submitWorldTime(102,102); cpu.beforeTick(); assertEquals(2,device.read(42));
    }
    @Test void nextInsertionCannotEraseUnacknowledgedEjectCompletion() {
        var device=new PunchcardDevice(true); device.insert(PunchcardDeck.blank()); complete(device,PunchcardDevice.EJECT);
        assertTrue(device.insert(PunchcardDeck.blank())); assertTrue((device.read(33)&PunchcardDevice.READY)!=0);
        complete(device,PunchcardDevice.WRITE); assertEquals(0,device.active().cards());
        ack(device); complete(device,PunchcardDevice.WRITE); assertEquals(1,device.active().cards());
    }
}
