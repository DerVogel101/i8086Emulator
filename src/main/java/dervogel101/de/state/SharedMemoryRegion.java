package dervogel101.de.state;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** One 64 KiB backing region that may be passed to multiple CPUs. */
public final class SharedMemoryRegion {
    public static final int SIZE = 0x10000;

    private final byte[] bytes = new byte[SIZE];
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public int read8(int offset) {
        check(offset, 1);
        lock.readLock().lock();
        try {
            return Byte.toUnsignedInt(bytes[offset]);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int read16(int offset) {
        check(offset, 2);
        lock.readLock().lock();
        try {
            return Byte.toUnsignedInt(bytes[offset]) | (Byte.toUnsignedInt(bytes[offset + 1]) << 8);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void write8(int offset, int value) {
        check(offset, 1);
        lock.writeLock().lock();
        try {
            bytes[offset] = (byte) value;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void write16(int offset, int value) {
        check(offset, 2);
        lock.writeLock().lock();
        try {
            bytes[offset] = (byte) value;
            bytes[offset + 1] = (byte) (value >>> 8);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void check(int offset, int width) {
        if (offset < 0 || offset > SIZE - width) {
            throw new IndexOutOfBoundsException("Shared memory offset: " + offset);
        }
    }

    public byte[] snapshot() {
        lock.readLock().lock();
        try { return bytes.clone(); } finally { lock.readLock().unlock(); }
    }

    public void load(byte[] image) {
        if (image.length != SIZE) throw new IllegalArgumentException("Shared memory must be exactly 64 KiB");
        lock.writeLock().lock();
        try { System.arraycopy(image, 0, bytes, 0, SIZE); } finally { lock.writeLock().unlock(); }
    }

    public void clear() { load(new byte[SIZE]); }
}
