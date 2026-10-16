package dervogel101.de;

import dervogel101.de.state.CPURegister;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Boots the real IA-16 IRQ library; IRQ_HANDLER_TEST_ROM is its built .bin. */
class IrqHandlerRomTest {
    private static final int STEP_LIMIT = 200_000;
    private CPU8086 cpu;
    private long millis;
    private long gameTicks;
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    };

    @BeforeEach
    void boot() throws IOException {
        String romPath = System.getenv("IRQ_HANDLER_TEST_ROM");
        assumeTrue(romPath != null, "Set IRQ_HANDLER_TEST_ROM to run the C IRQ integration checks");
        assertFalse(romPath.isBlank(), "IRQ_HANDLER_TEST_ROM must name an existing ROM");
        byte[] image = Files.readAllBytes(Path.of(romPath));
        assertEquals(0x40000, image.length, "ROM must be exactly 256 KiB");
        assertArrayEquals(new byte[]{(byte) 0xEA, 0, 0, 0, (byte) 0xC0},
                Arrays.copyOfRange(image, 0x3FFF0, 0x3FFF5));
        cpu = new CPU8086(clock);
        cpu.hostBridge().loadRom(image);
        cpu.hostBridge().submitWorldTime(0, 0);
        cpu.beforeTick();
        cpu.beforeBatch();
        await(() -> word(0) == 0x4952, "ROM startup and registration self-checks");
        assertEquals(6, word(8), "Registration records must occupy six bytes");
        assertEquals(0xC000, cpu.memory().read16(0, 0x0082));
        assertTrue(cpu.registers().getIF());
    }

    @Test
    void normalRomStillBootsWithTheSamePhysicalMemoryLayout() throws IOException {
        byte[] image = Files.readAllBytes(Path.of(System.getenv("IRQ_HANDLER_TEST_ROM"))
                .resolveSibling("rom.bin"));
        assertEquals(0x40000, image.length);
        assertArrayEquals(new byte[]{(byte) 0xEA, 0, 0, 0, (byte) 0xC0},
                Arrays.copyOfRange(image, 0x3FFF0, 0x3FFF5));
        CPU8086 normal = new CPU8086(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        normal.hostBridge().loadRom(image);
        for (int step = 0; step < STEP_LIMIT && !normal.isHalted(); step++) normal.step();
        assertTrue(normal.isHalted(), "Normal ROM must finish within the instruction budget");
        assertEquals(0x42, normal.memory().read8(0, 0x0500));
        assertEquals('T', normal.memory().read8(0, 0x0501));
        assertTrue(normal.hostBridge().terminalText().contains("Time taken: 0\n"));
    }

    @Test
    void exactIdsAreDistinctAcrossTypesAndIncludeBothByteBoundaries() {
        irq(0x0000, 1);
        irq(0x0100, 2);
        irq(0x0107, 3);
        irq(0x0200, 5);
        irq(0x01FF, 6);
        irq(0xFFFF, 6);
        irq(0xFF00, 8); // Index FF is an exact match, not a wildcard.
        irq(0x4200, 8); // Unnamed raw types remain routable.
    }

    @Test
    void typeOverridesHideAndRestoreIdRoutesWithoutRemovingThem() {
        command(1);
        irq(0x0100, 4);
        irq(0x0107, 4);
        irq(0x01FF, 4);
        irq(0x0000, 1);
        command(2);
        irq(0x0100, 2);
        irq(0x0107, 3);
        command(14);
        irq(0xFFFF, 4);
        irq(0xFF00, 4);
        command(15);
        irq(0xFFFF, 6);
    }

    @Test
    void replacementRouterDelegatesAndRestoresPreviousOrDefaultRouting() {
        command(3);
        irq(0x0100, 7);
        irq(0xFFFF, 7);
        irq(0x0200, 5); // The replacement delegates timer routing.
        irq(0x0201, 8); // Delegation includes the default fallback.
        command(4); // Restore the previous callback returned by the setter.
        irq(0x0100, 2);
        command(3);
        command(5); // NULL restores the default router.
        irq(0x0107, 3);
    }

    @Test
    void unmatchedEventsAreAcknowledgedWithAndWithoutFallback() {
        irq(0x4212, 8);
        command(6);
        int count = word(5);
        interrupt(0x4212);
        assertEquals(count, word(5));
        assertEquals(0, cpu.ioForTest().read8(0xFF02));
        command(7);
        irq(0x4212, 8);
        command(8);
        irq(0x0100, 8);
        command(9);
        irq(0x0100, 2);
    }

    @Test
    void initPreservesRouterTypeOverridesFallbackAndInterruptEnable() {
        command(1);
        int vector = cpu.memory().read16(0, 0x0080);
        command(10);
        assertEquals(vector, cpu.memory().read16(0, 0x0080));
        irq(0x0100, 4);
        irq(0x4212, 8);
        command(3);
        command(10);
        irq(0x0100, 7);
        command(4);
        irq(0x0100, 4);
        command(2);
        irq(0x0100, 2);
    }

    @Test
    void emptySoftwareInterruptDoesNotDispatchOrAcknowledge() {
        int count = word(5);
        command(11);
        assertEquals(count, word(5));
        assertEquals(0, cpu.ioForTest().read8(0xFF02));
        irq(0x0000, 1);
    }

    @Test
    void interruptPreservesRegistersSegmentsStackAndFlagsWithDirectionSet() {
        CPURegister registers = cpu.registers();
        registers.setAx(0x1111);
        registers.setBx(0x2222);
        registers.setCx(0x3333);
        registers.setDx(0x4444);
        registers.setSi(0x5555);
        registers.setDi(0x6666);
        registers.setBp(0x7777);
        registers.setDs(0x1234);
        registers.setEs(0x5678);
        registers.setFlags(0x0ED7); // IF and DF set, TF clear, arithmetic flags set.
        int[] before = state();
        irq(0x0107, 3);
        assertArrayEquals(before, state(), "IRET must restore the interrupted state");
        assertEquals(0, word(9), "Callback data segment");
        assertEquals(0, word(10), "Callback extra segment");
    }

    @Test
    void consecutiveAndRepeatedEventsAreHandledExactlyOnceInOrder() {
        int[] ids = {0, 0x0100, 0x0107, 0x0200, 0, 0, 0xFFFF};
        for (int id : ids) cpu.hostBridge().submitIrq(id);
        await(() -> word(5) == ids.length && !cpu.hostBridge().irqPending(),
                "consecutive queue acknowledgements");
        for (int i = 0; i < ids.length; i++)
            assertEquals(ids[i], cpu.memory().read16(0, 0x0540 + i * 2));
        for (int i = 0; i < 500; i++) cpu.step();
        assertEquals(ids.length, word(5), "No callback may be repeated after ACK");
        assertEquals(0, cpu.ioForTest().read8(0xFF02));
    }

    @Test
    void overflowRemainsStickyWhileTheQueueDrains() {
        for (int id = 0; id <= 256; id++) cpu.hostBridge().submitIrq(id);
        await(() -> word(5) == 256 && !cpu.hostBridge().irqPending(), "overflow queue drain");
        assertEquals(0, word(12), "Overflow must not replace the active event");
        assertEquals(256, word(3), "The newest queued event must be delivered");
        assertEquals(4, cpu.ioForTest().read8(0xFF02), "Service must leave overflow sticky");
        command(10);
        assertEquals(4, cpu.ioForTest().read8(0xFF02));
        cpu.ioForTest().write8(0xFF02, 4);
        cpu.step();
        assertEquals(0, cpu.ioForTest().read8(0xFF02));
    }

    @Test
    void sleepUsesBatchYieldForBothClocksAndAllResolutionsWithoutBlockingIrqs() {
        int[][] units = {{1, 5, 10, 50}, {1, 5, 10, 20}};
        for (int mode = 0; mode < 2; mode++) {
            int timer = mode == 0 ? 0x8110 : 0x8128;
            for (int resolution = 0; resolution < 4; resolution++) {
                int completed = word(7);
                startSleep(16 + mode, 2, resolution << 4);
                awaitBatchYield();
                assertTrue(cpu.registers().getIF());
                assertEquals(1 | (resolution << 4), cpu.ioForTest().read8(timer + 4));
                irq(0x0100, 2);
                assertEquals(completed, word(7), "IRQ must return to the sleeping call");
                advanceSleepClock(1 - mode, 1000);
                awaitBatchYield();
                assertEquals(completed, word(7), "The other clock must not expire the sleep");
                advanceSleepClock(mode, 2 * units[mode][resolution] - 1);
                awaitBatchYield();
                assertEquals(completed, word(7), "Sleep must not return early");
                advanceSleepClock(mode, 1);
                await(() -> word(7) == completed + 1, "sleep expiry");
                assertEquals(0, cpu.ioForTest().read16(timer));
                assertEquals(resolution << 4, cpu.ioForTest().read8(timer + 4));
                assertTrue(cpu.registers().getIF());
                assertFalse(cpu.hostBridge().irqPending());
            }
        }
    }

    @Test
    void sleepPreservesDisabledInterruptsAndSupportsMaximumWait() {
        for (int mode = 0; mode < 2; mode++) {
            int completed = word(7);
            int callbacks = word(5);
            startSleep(18 + mode, 0xFFFF, 0xFF);
            awaitBatchYield();
            assertFalse(cpu.registers().getIF());
            int timer = mode == 0 ? 0x8110 : 0x8128;
            assertEquals(0x31, cpu.ioForTest().read8(timer + 4), "Only resolution bits are accepted");
            cpu.hostBridge().submitIrq(0x0100);
            advanceSleepClock(mode, 0xFFFFL * (mode == 0 ? 50 : 20) - 1);
            awaitBatchYield();
            assertEquals(callbacks, word(5));
            assertFalse(cpu.registers().getIF());
            assertTrue(cpu.hostBridge().irqPending());
            advanceSleepClock(mode, 1);
            await(() -> word(7) == completed + 1, "disabled-IF sleep expiry and C IF check");
            await(() -> word(5) == callbacks + 1 && !cpu.hostBridge().irqPending(), "deferred IRQ");
        }
    }

    @Test
    void zeroSleepDoesNotYieldOrReconfigureTimers() {
        for (int command = 16; command <= 19; command++) {
            int timer = (command & 1) == 0 ? 0x8110 : 0x8128;
            cpu.ioForTest().write16(timer + 2, 123);
            int completed = word(7);
            startSleep(command, 0, 0xFF);
            for (int step = 0; word(7) == completed && step < STEP_LIMIT; step++)
                assertNotEquals(CPU8086.StepOutcome.YIELD_BATCH, cpu.step());
            assertEquals(completed + 1, word(7));
            assertEquals(0, word(1), "C IF preservation check");
            assertEquals(123, cpu.ioForTest().read16(timer + 2));
            assertEquals(0, cpu.ioForTest().read8(timer + 4));
        }
    }

    private void startSleep(int command, int wait, int resolution) {
        cpu.memory().write16(0, 0x051A, wait);
        cpu.memory().write16(0, 0x051C, resolution);
        cpu.memory().write16(0, 0x0504, command);
    }

    private void advanceSleepClock(int mode, long amount) {
        if (mode == 0) millis += amount;
        else cpu.hostBridge().submitWorldTime(gameTicks += amount, 0);
        cpu.beforeTick();
        cpu.beforeBatch();
    }

    private void awaitBatchYield() {
        for (int step = 0; step < STEP_LIMIT; step++) {
            assertEquals(0, word(1), "C sleep self-check");
            CPU8086.StepOutcome outcome = cpu.step();
            assertNotEquals(CPU8086.StepOutcome.YIELD_TICK, outcome);
            assertFalse(cpu.isHalted(), "Sleep must yield rather than halt");
            if (outcome == CPU8086.StepOutcome.YIELD_BATCH) return;
        }
        fail("Sleep did not yield a batch");
    }

    private int word(int index) {
        return cpu.memory().read16(0, 0x0500 + index * 2);
    }

    private void command(int command) {
        int completed = word(7);
        cpu.memory().write16(0, 0x0504, command);
        await(() -> word(7) == completed + 1, "foreground command " + command);
        assertTrue(cpu.registers().getIF());
    }

    private void interrupt(int id) {
        int[] before = state();
        cpu.hostBridge().submitIrq(id);
        assertEquals(CPU8086.StepOutcome.EVENT, cpu.step(), "IRQ must enter vector 0x20");
        await(() -> cpu.registers().getCs() == before[8] && cpu.registers().getIp() == before[12],
                "IRQ return");
        assertArrayEquals(before, state(), "Interrupt service must preserve CPU state");
    }

    private void irq(int id, int route) {
        int count = word(5);
        interrupt(id);
        assertEquals(count + 1, word(5));
        assertEquals(id, word(3));
        assertEquals(route, word(4));
        assertEquals(0, word(6) & 0x0600, "Callbacks require IF=0 and DF=0");
        assertEquals(1, word(11), "ACK must happen after the callback returns");
        assertFalse(cpu.hostBridge().irqPending());
    }

    private int[] state() {
        CPURegister r = cpu.registers();
        return new int[]{r.getAx(), r.getBx(), r.getCx(), r.getDx(), r.getSp(), r.getBp(),
                r.getSi(), r.getDi(), r.getCs(), r.getDs(), r.getSs(), r.getEs(), r.getIp(), r.getFlags()};
    }

    private void await(BooleanSupplier condition, String operation) {
        for (int step = 0; step < STEP_LIMIT; step++) {
            if (word(1) != 0) fail("C IRQ self-check failed at source line " + word(1));
            if (condition.getAsBoolean()) return;
            cpu.step();
        }
        fail(operation + " exceeded " + STEP_LIMIT + " instructions at " +
                Integer.toHexString(cpu.registers().getCs()) + ":" +
                Integer.toHexString(cpu.registers().getIp()));
    }
}
