package dervogel101.de.state;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.time.LocalDateTime;
import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.i86.runtime.HardwareProfile;
import java.util.Objects;

public class CPUMemory {
    public static final int NVRAM_SIZE = 0x10000;
    public static final int ROM_SIZE = 0x40000;

    private static final int CLOCK_START = 0x9FFF3;
    private static final int NVRAM_START = 0xA0000;
    private static final int SHARED_START = 0xB0000;
    private static final int ROM_START = 0xC0000;

    private final byte[] ram = new byte[CLOCK_START];
    private final byte[] clock = new byte[NVRAM_START - CLOCK_START];
    private final byte[] nvram = new byte[NVRAM_SIZE];
    private final SharedMemoryRegion sharedMemory;
    private final byte[] rom = new byte[ROM_SIZE];
    private boolean clockAvailable = true, nvramAvailable = true, sharedAvailable = true;
    private long nvramVersion;
    private long savedNvramVersion;

    public CPUMemory() {
        this(new SharedMemoryRegion());
    }

    public CPUMemory(SharedMemoryRegion sharedMemory) {
        this.sharedMemory = Objects.requireNonNull(sharedMemory);
    }

    public void configureHardware(HardwareProfile profile) {
        clockAvailable = profile.has(Hardware.CLOCK);
        nvramAvailable = profile.has(Hardware.NVRAM);
        sharedAvailable = profile.has(Hardware.SHARED_MEMORY);
    }

    public SharedMemoryRegion sharedMemory() {
        return sharedMemory;
    }

    public int physicalAddress(int segment, int offset) {
        return ((segment << 4) + offset) & 0xFFFFF;
    }

    public int read8(int segment, int offset) {
        return readPhysical8(physicalAddress(segment, offset));
    }

    public int read16(int segment, int offset) {
        int address = physicalAddress(segment, offset);
        if (sharedAvailable && address >= SHARED_START && address < ROM_START - 1) {
            return sharedMemory.read16(address - SHARED_START);
        }
        return readPhysical8(address) | (readPhysical8(address + 1) << 8);
    }

    public void write8(int segment, int offset, int value) {
        writePhysical8(physicalAddress(segment, offset), value);
    }

    public void write16(int segment, int offset, int value) {
        int address = physicalAddress(segment, offset);
        if (sharedAvailable && address >= SHARED_START && address < ROM_START - 1) {
            sharedMemory.write16(address - SHARED_START, value);
            return;
        }
        writePhysical8(address, value);
        writePhysical8(address + 1, value >>> 8);
    }

    public byte[] romImage() { return rom.clone(); }

    public void loadRom(byte[] image) {
        Objects.requireNonNull(image);
        if (image.length != ROM_SIZE) {
            throw new IllegalArgumentException("ROM image must be exactly 256 KiB");
        }
        System.arraycopy(image, 0, rom, 0, ROM_SIZE);
    }

    public synchronized void loadNvram(byte[] image) {
        Objects.requireNonNull(image);
        if (image.length != NVRAM_SIZE) {
            throw new IllegalArgumentException("NVRAM image must be exactly 64 KiB");
        }
        System.arraycopy(image, 0, nvram, 0, NVRAM_SIZE);
        nvramVersion = 0;
        savedNvramVersion = 0;
    }

    public synchronized NvramSnapshot snapshotNvramIfDirty() {
        return nvramVersion == savedNvramVersion ? null : new NvramSnapshot(nvram, nvramVersion);
    }

    public synchronized NvramSnapshot snapshotNvram() {
        return new NvramSnapshot(nvram, nvramVersion);
    }

    public synchronized void acknowledgeNvramSaved(long version) {
        if (version < 0 || version > nvramVersion) {
            throw new IllegalArgumentException("Unknown NVRAM version: " + version);
        }
        savedNvramVersion = Math.max(savedNvramVersion, version);
    }

    public void updateClock(long poweredOnGameTicks, long dayTime, LocalDateTime hostTime) {
        updateTickClock(poweredOnGameTicks, dayTime);
        updateRealtimeClock(hostTime);
    }

    public void updateTickClock(long poweredOnGameTicks, long dayTime) {
        int day = (int) ((dayTime / 24_000) & 0xFFFF);
        int tick = (int) poweredOnGameTicks;
        clock[2] = (byte) day;
        clock[3] = (byte) (day >>> 8);
        clock[4] = (byte) tick;
        clock[5] = (byte) (tick >>> 8);
    }

    public void updateRealtimeClock(LocalDateTime hostTime) {
        Objects.requireNonNull(hostTime);
        int millisecond = hostTime.getNano() / 1_000_000;
        clock[0] = (byte) millisecond;
        clock[1] = (byte) (millisecond >>> 8);
        clock[6] = (byte) hostTime.getHour();
        clock[7] = (byte) hostTime.getMinute();
        clock[8] = (byte) hostTime.getSecond();
        clock[9] = (byte) (hostTime.getDayOfMonth() - 1);
        clock[10] = (byte) (hostTime.getMonthValue() - 1);
        clock[11] = (byte) hostTime.getYear();
        clock[12] = (byte) (hostTime.getYear() >>> 8);
    }

    private int readPhysical8(int address) {
        address &= 0xFFFFF;
        if (address < CLOCK_START) return Byte.toUnsignedInt(ram[address]);
        if (address < NVRAM_START) return clockAvailable ? Byte.toUnsignedInt(clock[address - CLOCK_START]) : 0;
        if (address < SHARED_START) return nvramAvailable ? Byte.toUnsignedInt(nvram[address - NVRAM_START]) : 0;
        if (address < ROM_START) return sharedAvailable ? sharedMemory.read8(address - SHARED_START) : 0;
        return Byte.toUnsignedInt(rom[address - ROM_START]);
    }

    private void writePhysical8(int address, int value) {
        address &= 0xFFFFF;
        if (address < CLOCK_START) {
            ram[address] = (byte) value;
        } else if (nvramAvailable && address >= NVRAM_START && address < SHARED_START) {
            synchronized (this) {
                int index = address - NVRAM_START;
                if (nvram[index] != (byte) value) {
                    nvram[index] = (byte) value;
                    nvramVersion++;
                }
            }
        } else if (sharedAvailable && address >= SHARED_START && address < ROM_START) {
            sharedMemory.write8(address - SHARED_START, value);
        }
    }

    public static final class NvramSnapshot {
        private final byte[] bytes;
        private final long version;

        private NvramSnapshot(byte[] bytes, long version) {
            this.bytes = bytes.clone();
            this.version = version;
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        public long version() {
            return version;
        }
    }

    public synchronized void writeState(DataOutput out) throws IOException {
        out.write(ram); out.write(clock); out.write(nvram); out.write(rom);
        out.writeLong(nvramVersion); out.writeLong(savedNvramVersion);
    }

    public synchronized void readState(DataInput in) throws IOException {
        in.readFully(ram); in.readFully(clock); in.readFully(nvram); in.readFully(rom);
        nvramVersion = in.readLong(); savedNvramVersion = in.readLong();
        if (nvramVersion < 0 || savedNvramVersion < 0 || savedNvramVersion > nvramVersion)
            throw new IOException("Invalid NVRAM versions");
    }

}
