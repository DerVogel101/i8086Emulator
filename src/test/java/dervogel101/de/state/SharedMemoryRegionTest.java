package dervogel101.de.state;

import dervogel101.de.CPU8086;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class SharedMemoryRegionTest {
    @Test
    void twoCpusShareOnlyTheSuppliedRegion() {
        SharedMemoryRegion shared = new SharedMemoryRegion();
        CPU8086 first = new CPU8086(shared);
        CPU8086 second = new CPU8086(shared);

        assertSame(shared, first.hostBridge().sharedMemory());
        assertSame(shared, second.hostBridge().sharedMemory());
        assertNotSame(shared, new CPU8086().hostBridge().sharedMemory());
        first.memory().write16(0xB000, 0x1234, 0xA55A);
        assertEquals(0xA55A, second.memory().read16(0xB000, 0x1234));
        assertNull(first.hostBridge().snapshotNvramIfDirty());
        first.memory().write8(0xA000, 0, 0x77);
        assertEquals(0, second.memory().read8(0xA000, 0));
    }

    @Test
    void regionWideLockAllowsConcurrentReadsButExcludesWriters() throws Exception {
        SharedMemoryRegion shared = new SharedMemoryRegion();
        CPU8086 cpu = new CPU8086(shared);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            shared.lock.readLock().lock();
            Future<?> writer;
            try {
                assertEquals(0, workers.submit(() -> cpu.memory().read16(0xB000, 0x4000))
                        .get(2, TimeUnit.SECONDS));
                CountDownLatch started = new CountDownLatch(1);
                writer = workers.submit(() -> {
                    started.countDown();
                    cpu.memory().write16(0xB000, 0x4000, 0xA55A);
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> writer.get(100, TimeUnit.MILLISECONDS));
            } finally {
                shared.lock.readLock().unlock();
            }
            writer.get(2, TimeUnit.SECONDS);
            assertEquals(0xA55A, shared.read16(0x4000));

            shared.lock.writeLock().lock();
            Future<?> secondWriter;
            try {
                CountDownLatch started = new CountDownLatch(1);
                secondWriter = workers.submit(() -> {
                    started.countDown();
                    shared.write8(0x8000, 0x77);
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> secondWriter.get(100, TimeUnit.MILLISECONDS));
            } finally {
                shared.lock.writeLock().unlock();
            }
            secondWriter.get(2, TimeUnit.SECONDS);
            assertEquals(0x77, shared.read8(0x8000));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void wordReadsNeverSeeHalfOfAnotherCpusWrite() throws Exception {
        SharedMemoryRegion shared = new SharedMemoryRegion();
        CPU8086 first = new CPU8086(shared);
        CPU8086 second = new CPU8086(shared);
        first.memory().write16(0xB000, 0x1234, 0xA55A);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = workers.submit(() -> {
                for (int i = 0; i < 20_000; i++) {
                    first.memory().write16(0xB000, 0x1234, (i & 1) == 0 ? 0x5AA5 : 0xA55A);
                }
            });
            for (int i = 0; i < 20_000; i++) {
                int value = second.memory().read16(0xB000, 0x1234);
                assertTrue(value == 0xA55A || value == 0x5AA5, "Torn word: " + value);
            }
            writer.get(2, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
    }
}
