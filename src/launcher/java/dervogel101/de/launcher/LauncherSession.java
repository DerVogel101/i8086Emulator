package dervogel101.de.launcher;

import dervogel101.de.state.SharedMemoryGroups;
import dervogel101.de.i86.runtime.ShardSpeed;
import dervogel101.de.state.SharedMemoryRegion;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One session file owns its CPU snapshots and the shared region table. */
final class LauncherSession {
    private static final int MAGIC = 0x49383657;
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    record Cpu(int id, byte[] state) {}
    record State(long ticks, int tickRate, int nextCpuId, Map<String, byte[]> regions, List<Cpu> cpus, List<ShardSpeed> shards) {
        State(long ticks, int tickRate, int nextCpuId, Map<String, byte[]> regions, List<Cpu> cpus) {
            this(ticks, tickRate, nextCpuId, regions, cpus, List.of(ShardSpeed.TIER_1, ShardSpeed.TIER_2, ShardSpeed.TIER_3));
        }
        State { shards = List.copyOf(shards); }
    }

    static void save(Path path, State state) throws IOException {
        if (state.ticks < 0 || state.tickRate < 1 || state.tickRate > 1000
                || state.shards.size() != 3 || state.nextCpuId < 1 || state.cpus.isEmpty() || state.cpus.size() > 256 || state.regions.size() > 4096)
            throw new IOException("Invalid session size or clock");
        Path absolute = path.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".i86-session-", ".tmp");
        try {
            var ids = new HashSet<Integer>();
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                out.writeInt(MAGIC); out.writeInt(2); out.writeLong(state.ticks); out.writeInt(state.tickRate); out.writeInt(state.nextCpuId);
                for (ShardSpeed shard : state.shards) { out.writeInt(shard.stepsPerTick()); out.writeInt(shard.batchesPerTick()); }
                out.writeInt(state.regions.size());
                var names = new HashSet<String>();
                for (var entry : state.regions.entrySet()) {
                    String id = SharedMemoryGroups.normalize(entry.getKey());
                    if (!names.add(id) || entry.getValue().length != SharedMemoryRegion.SIZE)
                        throw new IOException("Invalid shared-memory table");
                    out.writeUTF(id); out.write(entry.getValue());
                }
                out.writeInt(state.cpus.size());
                for (Cpu cpu : state.cpus) {
                    if (cpu.id < 1 || cpu.id >= state.nextCpuId || !ids.add(cpu.id)
                            || cpu.state.length < 1 || cpu.state.length > 4 * 1024 * 1024)
                        throw new IOException("Invalid CPU session record");
                    out.writeInt(cpu.id); out.writeInt(cpu.state.length); out.write(cpu.state);
                }
            }
            if (Files.size(temporary) > MAX_BYTES) throw new IOException("Session exceeds 256 MiB");
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    static State load(Path path) throws IOException {
        if (Files.size(path) > MAX_BYTES) throw new IOException("Session exceeds 256 MiB");
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC) throw new IOException("Unsupported session format");
            int version = in.readInt();
            if (version < 1 || version > 2) throw new IOException("Unsupported session format");
            long ticks = in.readLong(); int tickRate = in.readInt(), nextCpuId = in.readInt();
            if (ticks < 0 || tickRate < 1 || tickRate > 1000 || nextCpuId < 1) throw new IOException("Invalid saved clock or ID counter");
            var shards = version == 1 ? List.of(ShardSpeed.TIER_1, ShardSpeed.TIER_2, ShardSpeed.TIER_3)
                    : List.of(new ShardSpeed(in.readInt(), in.readInt()), new ShardSpeed(in.readInt(), in.readInt()),
                              new ShardSpeed(in.readInt(), in.readInt()));
            int count = in.readInt();
            if (count < 0 || count > 4096) throw new IOException("Invalid shared region count");
            var regions = new TreeMap<String, byte[]>();
            for (int i = 0; i < count; i++) {
                String id = SharedMemoryGroups.normalize(in.readUTF());
                byte[] bytes = new byte[SharedMemoryRegion.SIZE]; in.readFully(bytes);
                if (regions.putIfAbsent(id, bytes) != null) throw new IOException("Duplicate shared-memory ID");
            }
            count = in.readInt();
            if (count < 1 || count > 256) throw new IOException("Invalid CPU count");
            var cpus = new ArrayList<Cpu>();
            var ids = new HashSet<Integer>();
            for (int i = 0; i < count; i++) {
                int id = in.readInt(), length = in.readInt();
                if (id < 1 || id >= nextCpuId || !ids.add(id) || length < 1 || length > 4 * 1024 * 1024)
                    throw new IOException("Invalid CPU session record");
                byte[] bytes = new byte[length]; in.readFully(bytes); cpus.add(new Cpu(id, bytes));
            }
            if (in.read() != -1) throw new IOException("Trailing session data");
            return new State(ticks, tickRate, nextCpuId, Collections.unmodifiableMap(regions), List.copyOf(cpus), shards);
        } catch (IllegalArgumentException error) { throw new IOException("Invalid session", error); }
    }
}
