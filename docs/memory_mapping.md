# Physical memory map

`CPUMemory` enforces these regions. ROM startup and host integration are described in [cpu_execution.md](cpu_execution.md).

The 8086 has a 20-bit physical address space (`0x00000`–`0xFFFFF`, 1 MiB). A physical address is `(segment << 4) + offset`, wrapped to 20 bits. RAM, NVRAM, shared memory, and ROM have separate backing storage within that one address space. I/O ports are a separate address space; see [io_mapping.md](io_mapping.md).

| Physical addresses  |               Size | Region                        | Access                                               |
|---------------------|-------------------:|-------------------------------|------------------------------------------------------|
| `0x00000`–`0x9FFF2` | 640 KiB − 13 bytes | RAM                           | Read/write, volatile                                 |
| `0x9FFF3`–`0x9FFFF` |           13 bytes | Minecraft time and host clock | Read-only, updated by the host                       |
| `0xA0000`–`0xAFFFF` |             64 KiB | NVRAM                         | Read/write; the host persists dirty snapshots        |
| `0xB0000`–`0xBFFFF` |             64 KiB | Shared memory                 | Read/write, volatile; may be shared by multiple CPUs |
| `0xC0000`–`0xFFFFF` |            256 KiB | ROM                           | Read-only, loaded from the compiled ROM image        |

NVRAM images and dirty snapshots are exactly 64 KiB. Older 128 KiB images are rejected, with no implicit import. A host can pass one `SharedMemoryRegion` to multiple `CPU8086` instances and obtain the same reference from each CPU's bridge. The region uses one read/write lock: reads can run concurrently, while writes wait for all other reads and writes. Byte accesses and word accesses wholly inside the region each hold the lock for their entire access. A word crossing a region boundary still accesses each region separately. Shared-memory writes do not dirty NVRAM and CPU-only RESET does not clear either region.

## Minecraft time and host clock

The clock uses the Minecraft server host's local date and time, including milliseconds. The day counter is `(world day time / 24000) & 0xFFFF`; it advances with Minecraft's daylight cycle and responds to `/time` commands. The tick counter is the low 16 bits of game ticks elapsed since CPU power-on and wraps from `65535` to `0`. The first submitted world game time establishes the origin; before that it is zero. Later positive game-time deltas accumulate, while a backward game-time change does not reduce uptime. CPU-only RESET does not restart this count. Timers use the full accumulated tick count, not its wrapped 16-bit view. These bytes are refreshed at each `beforeInstruction()` boundary from one host-clock sample. Writes to this region do not change them.

| Address             | Value                                                 | Range                      |
|---------------------|-------------------------------------------------------|----------------------------|
| `0x9FFF3`–`0x9FFF4` | Millisecond of second, unsigned 16-bit, little-endian | `0`–`999`                  |
| `0x9FFF5`–`0x9FFF6` | Minecraft day counter, unsigned 16-bit, little-endian | `0`–`65535`                |
| `0x9FFF7`–`0x9FFF8` | Powered-on Minecraft tick uptime, low 16 bits         | `0`–`65535`                |
| `0x9FFF9`           | Hour                                                  | `0`–`23`                   |
| `0x9FFFA`           | Minute                                                | `0`–`59`                   |
| `0x9FFFB`           | Second                                                | `0`–`59`                   |
| `0x9FFFC`           | Day of month, zero-based                              | `0`–`30` (first day = `0`) |
| `0x9FFFD`           | Month, zero-based                                     | `0`–`11` (January = `0`)   |
| `0x9FFFE`–`0x9FFFF` | Year, unsigned 16-bit, little-endian                  | `0`–`65535`                |

Each byte read is valid on its own. A program reading several fields over multiple instructions can see values from either side of a second, day, or year change; the map does not currently provide an atomic multi-field snapshot.
