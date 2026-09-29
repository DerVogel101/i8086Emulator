package dervogel101.de.state;

import java.util.Objects;

/** Host-persisted storage addressed as 65,536 little-endian words. */
public final class IoStorageDevice {
    public static final int SIZE = 0x20000;

    private final byte[] bytes = new byte[SIZE];
    private long version;
    private long savedVersion;

    public synchronized void load(byte[] image) {
        Objects.requireNonNull(image);
        if (image.length != SIZE) {
            throw new IllegalArgumentException("I/O storage image must be exactly 128 KiB");
        }
        System.arraycopy(image, 0, bytes, 0, SIZE);
        version = 0;
        savedVersion = 0;
    }

    public synchronized int readWord(int address) {
        int offset = offset(address);
        return Byte.toUnsignedInt(bytes[offset]) | (Byte.toUnsignedInt(bytes[offset + 1]) << 8);
    }

    public synchronized void writeWord(int address, int value) {
        int offset = offset(address);
        if (bytes[offset] != (byte) value || bytes[offset + 1] != (byte) (value >>> 8)) {
            bytes[offset] = (byte) value;
            bytes[offset + 1] = (byte) (value >>> 8);
            version++;
        }
    }

    public synchronized Snapshot snapshotIfDirty() {
        return version == savedVersion ? null : new Snapshot(bytes, version);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(bytes, version);
    }

    public synchronized void acknowledgeSaved(long savedVersion) {
        if (savedVersion < 0 || savedVersion > version) {
            throw new IllegalArgumentException("Unknown I/O storage version: " + savedVersion);
        }
        this.savedVersion = Math.max(this.savedVersion, savedVersion);
    }

    private static int offset(int address) {
        if (address < 0 || address > 0xFFFF) {
            throw new IndexOutOfBoundsException("I/O storage word address: " + address);
        }
        return address << 1;
    }

    public static final class Snapshot {
        private final byte[] bytes;
        private final long version;

        private Snapshot(byte[] bytes, long version) {
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
}
