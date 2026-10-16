package dervogel101.de.state;

import java.util.*;

/** World/session-owned regions. Paused and unloaded powered CPUs remain members. */
public final class SharedMemoryGroups {
    private final Map<String, SharedMemoryRegion> regions = new TreeMap<>();
    private final Map<Long, String> poweredMembers = new HashMap<>();

    public static String normalize(String id) {
        Objects.requireNonNull(id, "id");
        if (!id.matches("[A-Za-z0-9_]{1,32}"))
            throw new IllegalArgumentException("Memory ID requires 1 to 32 letters, digits or underscores");
        return id.toLowerCase(Locale.ROOT);
    }

    public synchronized SharedMemoryRegion region(String id) {
        return regions.computeIfAbsent(normalize(id), ignored -> new SharedMemoryRegion());
    }

    public synchronized SharedMemoryRegion powerOn(long mainframeId, String id) {
        id = normalize(id);
        String previous = poweredMembers.get(mainframeId);
        if (previous != null && !previous.equals(id)) powerOff(mainframeId);
        poweredMembers.put(mainframeId, id);
        return region(id);
    }

    public synchronized void powerOff(long mainframeId) {
        String id = poweredMembers.remove(mainframeId);
        if (id != null && !poweredMembers.containsValue(id)) regions.get(id).clear();
    }

    public synchronized Set<Long> delete(String id) {
        id = normalize(id);
        var affected = new HashSet<Long>();
        for (var entry : poweredMembers.entrySet()) if (entry.getValue().equals(id)) affected.add(entry.getKey());
        for (long mainframeId : affected) poweredMembers.remove(mainframeId);
        SharedMemoryRegion old = regions.remove(id);
        if (old != null) old.clear();
        return Set.copyOf(affected);
    }

    public synchronized Map<String, byte[]> snapshot() {
        var result = new TreeMap<String, byte[]>();
        regions.forEach((id, region) -> result.put(id, region.snapshot()));
        return Collections.unmodifiableMap(result);
    }

    /** Restore the region table before restoring powered CPU membership. */
    public synchronized void restore(Map<String, byte[]> saved) {
        if (!poweredMembers.isEmpty()) throw new IllegalStateException("Cannot restore regions while CPUs are powered");
        var checked = new TreeMap<String, SharedMemoryRegion>();
        saved.forEach((id, bytes) -> {
            var region = new SharedMemoryRegion();
            region.load(bytes);
            if (checked.putIfAbsent(normalize(id), region) != null)
                throw new IllegalArgumentException("Duplicate memory ID: " + id);
        });
        regions.clear(); regions.putAll(checked);
    }
}
