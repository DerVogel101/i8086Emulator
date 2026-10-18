package dervogel101.de.launcher;

import dervogel101.de.i86.runtime.*;
import dervogel101.de.state.SharedMemoryRegion;
import java.nio.file.Path;
import java.util.List;

/** Commands and copied views shared by the local and server-connected Swing debugger. */
public interface DebuggerBackend extends AutoCloseable {
    default boolean remote() { return false; }
    default boolean canControl() { return true; }
    default boolean canEdit() { return canControl(); }
    record Snapshot(String romName, String status, String registers, long instructionsPerSecond,
                    long totalInstructions,
                    int memoryBase, byte[] memory, int ioBase, byte[] io,
                    List<Disassembler8086.Instruction> code, boolean running, boolean faulted,
            DeviceSnapshot devices, String terminalLog, ImageState images, boolean testHigh, MachineState machine) {}
    record DeviceSnapshot(int channel, int signIndex, int timerIndex, byte[] redstone,
            byte[] sign, byte[] timer, byte[] irq, byte[] storage, boolean timerAutomatic, RedstoneIrqMode redstoneIrq, int signIrqSides,
            int keyboardIndex, KeyboardDevice.Snapshot keyboard) {
        public DeviceSnapshot withKeyboard(int index, KeyboardDevice.Snapshot snapshot) {
            return new DeviceSnapshot(channel, signIndex, timerIndex, redstone, sign, timer, irq, storage,
                    timerAutomatic, redstoneIrq, signIrqSides, index, snapshot);
        }
    }
    record MachineState(boolean powered, boolean psuHigh, HardwareProfile hardware, int shardTier, String memoryId, ShardSpeed speed) {
        public boolean shardPresent() { return shardTier != 0; }
    }
    record ImageState(Path nvramPath, boolean nvramDirty, Path storagePath,
                      boolean storageDirty, boolean storageConnected) {}


    void loadRom(Path path);
    void configure(int instructions, int ticks);
    void configure(int instructions, int ticks, int batches);
    void run();
    void runTo(int cs, int ip);
    void stepOver();
    void pause();
    void step();
    void skipInstruction();
    void setPc(int cs, int ip);
    void reset();
    void powerCycle();
    void pauseAndWait();
    ImageState inspectImages();
    void openNvram(Path path);
    void newNvram();
    void saveNvram(Path path);
    void discardNvram();
    void openStorage(Path path);
    void newStorage();
    void saveStorage(Path path);
    void discardStorage();
    void disconnectStorage();
    void powerCycleWithSharedMemory(SharedMemoryRegion nextRegion);
    void nmi();
    void testPin(boolean high);
    void redstone(int channel, int strength);
    void signClick(int sign, boolean front);
    void selectChannel(int channel);
    void selectSign(int sign);
    void selectTimer(int timer);
    void selectKeyboard(int slot);
    void keyboardInput(int slot, int value);
    void sendIrq(int deviceId);
    void timerAutomatic(int timer, boolean automatic);
    void writeTimer(int timer, int reload, int control);
    void resetTimer(int timer);
    void writeSignLine(int sign, int line, String text);
    void memoryBase(int address);
    void ioBase(int port);
    void codeAddress(int cs, int ip);
    void followPc();
    void writeMemory(int address, int value);
    void writeIo(int port, int value);
    void close();
    MachineState inspectMachine();
    void powerSupply(boolean high);
    void configureMachine(HardwareProfile profile, boolean crystal, String sharedId);
    void configureMachine(HardwareProfile profile, int tier, String sharedId);
    void sharedRegionDeleted(String id);
    void redstoneIrq(int channel, RedstoneIrqMode mode);
    void signIrq(int sign, int sides);
    boolean powerOffForCycle();
    void powerOnAfterCycle(boolean restart);
    byte[] saveSessionState(boolean resume);
    boolean restoreSessionState(byte[] saved);
    boolean suspendForSession();
}
