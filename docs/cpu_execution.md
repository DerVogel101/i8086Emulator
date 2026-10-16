# CPU execution and ROM contract

This is a purpose-built 8086 emulator for the Minecraft mod. The Minecraft host integration is not connected yet. For physical addresses, see [memory_mapping.md](memory_mapping.md); for device ports and the IRQ queue, see [io_mapping.md](io_mapping.md).

## ROM startup

The ROM image passed to `CpuHostBridge.loadRom(byte[])` must be exactly 256 KiB and maps to physical addresses `0xC0000`–`0xFFFFF`. On reset, execution starts at `CS:IP = FFFF:0000`, physical address `0xFFFF0`, which is file offset `0x3FFF0`. Place a far jump there to the startup code elsewhere in ROM.

Configure the compiler and linker for this memory map: code and constants belong in ROM; writable data and the stack belong in RAM. Startup code must initialize RAM data without clearing NVRAM. Reserve the mapped clock and NVRAM addresses so ordinary sections do not overlap them. A code segment spans at most 64 KiB, so code distributed across the 256 KiB ROM needs far calls and jumps; NVRAM access likewise needs an appropriate segment address or far pointer.

## Instruction support

No coprocessor is planned. ROMs must use instructions the emulator implements. An unsupported instruction must stop execution with an explicit opcode and starting `CS:IP` error; it must not be silently skipped or treated as a no-op.

The decoder rejects unhandled opcodes, including the `0xD8`–`0xDF` coprocessor escape range, before consuming their operands.

## Cooperative yield extension

`YIELD imm8` is an emulator-private, two-byte instruction:

| Bytes | Meaning | `CPU8086.StepOutcome` |
| --- | --- | --- |
| `0F 00` | Yield the current batch | `YIELD_BATCH` |
| `0F 01` | Yield the remainder of the current tick | `YIELD_TICK` |

Each valid yield consumes one `step()` call and counts as one completed
instruction. IP advances by two bytes. General and segment registers, flags,
memory, device I/O, and halt state are preserved. Yield is a scheduling request,
not `HLT`, a reset, a power cycle, or a sleep inside the CPU worker.

Hosts, including the standalone launcher and Minecraft scheduler, must honor the
returned outcome:

- Batch yield discards the unused steps of the current batch and resumes at the
  next scheduled batch, which may be in a later tick.
- Tick yield discards every remaining execution opportunity in the current tick
  and resumes at the next eligible tick.
- Unused steps never carry forward. Yield does not suspend real-time or
  world-tick timers; they continue to account for elapsed time or ticks.

Pending interrupts follow the normal CPU boundaries and do not reclaim yielded
budget. Reset and power-off still take precedence. Manual debugger **Step**
executes the yield once and displays the next IP; subsequent manual steps are
explicit user actions.

Modes `02` through `FF`, segment overrides, `LOCK`, and repeat prefixes fail
explicitly with the instruction's starting `CS:IP`. They are not no-ops.

### C helpers (IA-16 GCC)

Emit the bytes explicitly; `YIELD` is documentation syntax, not an instruction
mnemonic added to the assembler:

```c
/* Give up the current batch. */
static inline void i86_yield_batch(void) {
    __asm__ volatile (".byte 0x0f, 0x00" ::: "memory");
}

/* Give up the rest of the current tick. */
static inline void i86_yield_tick(void) {
    __asm__ volatile (".byte 0x0f, 0x01" ::: "memory");
}
```

`volatile` keeps the instruction; the `"memory"` clobber prevents compiler memory
accesses from moving across the scheduling point. No `"cc"` clobber is needed
because yield preserves flags.

### Compatibility

`0F` has no documented purpose in the original 8086 ISA. The physical 8086's
undocumented `POP CS` behavior is outside this emulator's contract. Later x86
instructions using `0F` are also outside the supported ISA. All `D8` through
`DF` coprocessor instructions remain unsupported errors; they do not yield.

Representative IA-16 GCC 6.3.0 output and selected integer/soft-float helpers
have been checked without opcode collisions. See the sibling
[validation evidence](../../emulator_i86_crafted/docs/ia16_validation.md). Final
ROM startup code, custom `abort`, inline assembly, and other runtime paths still
require instruction-level validation.

## Host execution and persistence

To run a ROM, the host must load it and repeatedly call `CPU8086.step()` on the CPU thread.
Call worker-only `beforeTick()` once per host tick to update mapped world
day/uptime values and game-tick timers; call `beforeBatch()` before each executed
batch to sample real time once and update mapped calendar values and real-time
timers. Manual debugger Step prepares one batch. `step()` and
`beforeInstruction()` apply queued inputs/device commands without clock sampling
or timer advancement. Clock values remain stable until their next boundary;
paused/unloaded timers account for elapsed time/ticks when their hook next runs.
 Each step applies queued host inputs through `beforeInstruction()` before handling reset, interrupts, or an instruction. A pending device IRQ is delivered as maskable interrupt vector `0x20` when interrupts are enabled; its device ID and acknowledgement are defined in [io_mapping.md](io_mapping.md#irq-queue).

NVRAM is separate from volatile RAM. The host bridge provides copied, versioned NVRAM snapshots after changes. Hosts acknowledge a snapshot version only after a successful save; writes made after that snapshot remain unsaved. The launcher saves images to files, while the future mod can persist them with its world.

## Complete execution snapshots and optional devices

On the CPU worker, call `saveState()` between steps and restore with
`CPU8086.restoreState(bytes, sharedRegion)`. The versioned binary format preserves
registers, RAM, ROM, retained NVRAM, I/O storage, port values and dirty outputs,
timers and their elapsed-time baselines, IRQ queue/commands, host input and pin
state, terminal history, HLT, and the instruction interrupt shadow. Pending host
inputs are applied at the save boundary. Shared-memory bytes belong to the host's
region table and are saved once, rather than once per CPU. A save/reopen is a
suspension, not a power cycle. Invalid or truncated state fails explicitly.

The host configures installed hardware with `configureHardware(HardwareProfile)`
only while powered off. The default profile includes every device and maximum
port capacities for existing callers. Absent clock, timer, NVRAM, shared-memory,
terminal, disk, IRQ and excess port ranges return zero or ignore writes. Hidden
retained NVRAM and shared bytes are not erased. NMI remains independent of the
optional device Interrupt Controller. Hardware profiles and port IRQ settings
are included in execution snapshots.

## Standalone launcher

The launcher and mod compile the same Minecraft-independent rules from the
sibling `emulator_i86_crafted/src/runtime/java` directory. The launcher also uses
that checkout's bundled sign font and OFL notices. Keep both checkouts side by side.

Execution budgets count `step()` calls, including events/idle calls, split across
configurable paced batches (ten by default). Unused, expired or yielded work does
not carry into later batches or ticks. Batch yield waits for the next batch;
tick yield waits for the next eligible tick. A shared simulated world clock
continues while an individual CPU is paused. Real-time and tick timers account
for the elapsed real time or world ticks on resume. Full register/memory/I/O/code
views refresh while paused or stepping; running updates include status, counters,
terminal output and errors.

Run `./gradlew runLauncher` (Windows: `.\gradlew.bat runLauncher`). Add CPU slots
and load exact 256 KiB ROMs. Each slot has private RAM/NVRAM and device state.
Use **Hardware** while powered off to choose component counts, select no shard
or tier 1/2/3, and select a shared-memory ID. GPIO/GPU modules add 32 ports each,
up to 128. The pacing controls keep separate total-step and batch settings for each tier,
with defaults 1,000 / 5,000 / 10,000 steps and ten batches each. A change applies
to every CPU with that tier installed; world tick rate is shared. These settings
and each installed tier persist in session files. Older session files load with
their existing CPU pacing and default tier settings. Without a speed shard,
execution is refused.

**PSU redstone high** models the power input. Run and manual Step power on a
valid CPU; Pause keeps it powered. Set PSU low before changing ROMs, hardware or
image attachments. PSU high automatically starts a valid off CPU when its
configuration becomes usable. CPU Reset preserves memory and devices. Power Cycle
reinitializes volatile execution/I/O, retains current NVRAM and attached disk
contents, preserves host IRQ settings and TEST input, and powers back on if PSU
is high. A CPU explicitly paused in the debugger remains paused; a running CPU
resumes. Power Cycle All first powers every CPU off, then restarts them.

Memory IDs use 1-32 letters/digits/underscores, compared without case. CPUs with
the same ID share a 64 KiB region. Paused powered CPUs retain membership. The
last power-off clears bytes but retains the named region. A session save retains
bytes and powered membership. Deletion through the host API stops connected CPUs
until an explicit ID selection; the Minecraft admin command/UI is separate work.
A missing or wrong-size ROM source powers the slot off and reports its reference
in status and terminal. Restoring a usable source permits PSU-driven restart.

Use **Save session** / **Open session** to save/reopen all slots, full execution
state, shared regions, clock ticks, ID counter, image attachments and dirty state.
All CPUs are suspended together for a consistent save, without powering off.
Previously running slots resume after saving/opening. Session files are replaced
through a temporary file; corrupt loads are prepared before replacing current
slots. Current format limits: 256 CPUs, 4096 named regions and 256 MiB total.
Session saving is explicit; closing does not automatically create a session file.

NVRAM images are 64 KiB; disks are 128 KiB. Open/New/Disconnect are powered-off
installation operations. Save/Save As export current contents. New images are
blank and unsaved; wrong-size images fail before replacing the current device.
Power cycles retain unsaved NVRAM. CPU removal, disconnect and launcher closing
still offer Save/Discard/Cancel for dirty exported images. A session also retains
the data and its dirty status; it does not mark an exported image saved.

The device panel selects redstone IRQ modes DISABLED/HIGH/LOW/BOTH/ANALOG.
HIGH/LOW/BOTH use zero/nonzero transitions; ANALOG includes nonzero level changes.
Silent input resampling is available through the bridge for reattachment. Sign
click IRQs select front/back independently, with the mapped click flags and ACK
semantics unchanged. The sign editor accepts 15 Windows-1252 characters and uses
the bundled Monosign font. Raw views expose format/control bytes. Timer automatic
IRQ settings survive power cycles; countdown/control registers reinitialize.
