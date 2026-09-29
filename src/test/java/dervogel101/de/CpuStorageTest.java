package dervogel101.de;

import dervogel101.de.state.CPUIO;
import dervogel101.de.state.IoStorageDevice;
import dervogel101.de.state.CPUMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CpuStorageTest {
    @Test
    void cpuInAndOutReachStorageAcrossInstructionBoundaries() {
        CPU8086 cpu = new CPU8086();
        byte[] rom = new byte[CPUMemory.ROM_SIZE];
        rom[0] = (byte) 0xEF; // OUT DX,AX: data
        rom[1] = (byte) 0xEF; // OUT DX,AX: address
        rom[2] = (byte) 0xEE; // OUT DX,AL: WRITE
        rom[3] = (byte) 0xEE; // OUT DX,AL: READ
        rom[4] = (byte) 0xED; // IN AX,DX: data
        cpu.hostBridge().loadRom(rom);
        cpu.jumpTo(0xC000, 0);
        IoStorageDevice storage = new IoStorageDevice();
        cpu.hostBridge().submitStorageDevice(storage);

        cpu.registers().setDx(0x8160);
        cpu.registers().setAx(0x3412);
        cpu.step();
        cpu.registers().setDx(0x8162);
        cpu.registers().setAx(0xFFFF);
        cpu.step();
        cpu.registers().setDx(0x8164);
        cpu.registers().setAx(2);
        cpu.step();
        assertEquals(0, storage.readWord(0xFFFF));
        cpu.registers().setAx(1);
        cpu.step(); // WRITE completes before READ is requested.
        assertEquals(0x3412, storage.readWord(0xFFFF));
        cpu.io().write16(0x8160, 0);
        cpu.registers().setDx(0x8160);
        cpu.step(); // READ completes before IN AX,DX.
        assertEquals(0x3412, cpu.registers().getAx());
        assertEquals(5, cpu.registers().getIp());
    }

    @Test
    void portWordsAndCommandsUseTheNextBoundary() {
        CPU8086 cpu = new CPU8086();
        CPUIO io = cpu.io();
        IoStorageDevice storage = new IoStorageDevice();
        assertEquals(0, io.read8(0x8164));

        cpu.hostBridge().submitStorageDevice(storage);
        assertEquals(0, io.read8(0x8164));
        cpu.beforeInstruction();
        assertEquals(4, io.read8(0x8164));
        io.write8(0x8164, 4); // Connection status is read-only.
        assertEquals(4, io.read8(0x8164));

        io.write8(0x8160, 0x34);
        io.write8(0x8161, 0x12);
        io.write16(0x8162, 0xFFFF);
        assertEquals(0x1234, io.read16(0x8160));
        assertEquals(0xFF, io.read8(0x8162));
        assertEquals(0xFF, io.read8(0x8163));
        io.write8(0x8164, 2);
        assertEquals(6, io.read8(0x8164));
        assertEquals(0, storage.readWord(0xFFFF));
        cpu.beforeInstruction();
        assertEquals(0x1234, storage.readWord(0xFFFF));
        assertEquals(4, io.read8(0x8164));

        io.write16(0x8160, 0xABCD);
        io.write8(0x8164, 3); // READ wins over WRITE.
        cpu.beforeInstruction();
        assertEquals(0x1234, io.read16(0x8160));
        assertEquals(0x1234, storage.readWord(0xFFFF));
        io.write16(0x8162, 0);
        io.write8(0x8164, 1);
        cpu.beforeInstruction();
        assertEquals(0, io.read16(0x8160));
    }

    @Test
    void connectionChangesPrecedeCommandsAndSnapshotsRemainHostOwned() {
        CPU8086 cpu = new CPU8086();
        CPUIO io = cpu.io();
        IoStorageDevice first = new IoStorageDevice();
        IoStorageDevice second = new IoStorageDevice();
        io.write16(0x8160, 0xCAFE);
        io.write16(0x8162, 0x8000);
        io.write8(0x8164, 2);
        cpu.beforeInstruction(); // Disconnected command is consumed without changing registers.
        assertEquals(0, io.read8(0x8164));
        assertEquals(0xCAFE, io.read16(0x8160));
        io.write8(0x8164, 1);
        cpu.beforeInstruction();
        assertEquals(0xCAFE, io.read16(0x8160));

        cpu.hostBridge().submitStorageDevice(first);
        io.write8(0x8164, 2);
        cpu.beforeInstruction(); // Connect and execute on this boundary.
        assertEquals(0xCAFE, first.readWord(0x8000));
        IoStorageDevice.Snapshot snapshot = cpu.hostBridge().snapshotStorageIfDirty();
        assertNotNull(snapshot);
        assertEquals(IoStorageDevice.SIZE, snapshot.bytes().length);
        snapshot.bytes()[0x10000] = 0;
        assertEquals(0xFE, Byte.toUnsignedInt(snapshot.bytes()[0x10000]));
        cpu.hostBridge().acknowledgeStorageSaved(snapshot.version());
        assertNull(cpu.hostBridge().snapshotStorageIfDirty());
        first.writeWord(0x8001, 0x55AA);
        cpu.hostBridge().acknowledgeStorageSaved(snapshot.version());
        assertNotNull(cpu.hostBridge().snapshotStorageIfDirty());

        cpu.hostBridge().submitStorageDevice(second);
        io.write8(0x8164, 2);
        cpu.beforeInstruction();
        assertEquals(0xCAFE, second.readWord(0x8000));
        assertEquals(0xCAFE, first.readWord(0x8000));
        cpu.hostBridge().submitStorageDevice(null);
        io.write8(0x8164, 2);
        cpu.beforeInstruction();
        assertEquals(0, io.read8(0x8164));
        assertEquals(0xCAFE, io.read16(0x8160));
        assertEquals(0xCAFE, second.readWord(0x8000));
        assertNull(cpu.hostBridge().snapshotStorageIfDirty());
        assertThrows(IllegalStateException.class, () -> cpu.hostBridge().acknowledgeStorageSaved(0));

        second.writeWord(0, 0x5678);
        IoStorageDevice.Snapshot detachedSnapshot = second.snapshotIfDirty();
        assertNotNull(detachedSnapshot);
        second.acknowledgeSaved(detachedSnapshot.version());
        assertNull(second.snapshotIfDirty());
        byte[] image = detachedSnapshot.bytes();
        IoStorageDevice restored = new IoStorageDevice();
        restored.load(image);
        assertEquals(0x5678, restored.readWord(0));
        assertNull(restored.snapshotIfDirty());
        assertThrows(IllegalArgumentException.class, () -> restored.load(new byte[0x10000]));
    }
}
