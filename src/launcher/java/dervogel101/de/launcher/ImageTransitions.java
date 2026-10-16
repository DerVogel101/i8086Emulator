package dervogel101.de.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Collects all save decisions before changing any CPU image. */
final class ImageTransitions {
    enum Kind { NVRAM, STORAGE }
    enum Decision { SAVE, DISCARD, CANCEL }

    interface Prompt {
        Decision decide(DebuggerBackend cpu, Kind kind);
        Path choosePath(DebuggerBackend cpu, Kind kind);
    }

    static boolean resolve(List<? extends DebuggerBackend> cpus, boolean nvram, boolean storage, Prompt prompt) {
        List<Runnable> saves = new ArrayList<>();
        List<Runnable> discards = new ArrayList<>();
        for (DebuggerBackend cpu : cpus) {
            DebuggerBackend.ImageState state = cpu.inspectImages();
            if (nvram && state.nvramDirty()) {
                Decision decision = prompt.decide(cpu, Kind.NVRAM);
                if (decision == Decision.CANCEL) return false;
                if (decision == Decision.DISCARD) discards.add(cpu::discardNvram);
                else {
                    Path path = state.nvramPath() == null ? prompt.choosePath(cpu, Kind.NVRAM) : state.nvramPath();
                    if (path == null) return false;
                    saves.add(() -> cpu.saveNvram(path));
                }
            }
            if (storage && state.storageDirty()) {
                Decision decision = prompt.decide(cpu, Kind.STORAGE);
                if (decision == Decision.CANCEL) return false;
                if (decision == Decision.DISCARD) discards.add(cpu::discardStorage);
                else {
                    Path path = state.storagePath() == null ? prompt.choosePath(cpu, Kind.STORAGE) : state.storagePath();
                    if (path == null) return false;
                    saves.add(() -> cpu.saveStorage(path));
                }
            }
        }
        for (Runnable save : saves) save.run();
        for (Runnable discard : discards) discard.run();
        return true;
    }

    private ImageTransitions() {}
}
