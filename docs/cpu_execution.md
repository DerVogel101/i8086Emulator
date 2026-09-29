# CPU execution and ROM contract

This is a purpose-built 8086 emulator for the Minecraft mod. The Minecraft host integration is not connected yet. For physical addresses, see [memory_mapping.md](memory_mapping.md); for device ports and the IRQ queue, see [io_mapping.md](io_mapping.md).

## ROM startup

The ROM image passed to `CpuHostBridge.loadRom(byte[])` must be exactly 256 KiB and maps to physical addresses `0xC0000`–`0xFFFFF`. On reset, execution starts at `CS:IP = FFFF:0000`, physical address `0xFFFF0`, which is file offset `0x3FFF0`. Place a far jump there to the startup code elsewhere in ROM.

Configure the compiler and linker for this memory map: code and constants belong in ROM; writable data and the stack belong in RAM. Startup code must initialize RAM data without clearing NVRAM. Reserve the mapped clock and NVRAM addresses so ordinary sections do not overlap them. A code segment spans at most 64 KiB, so code distributed across the 256 KiB ROM needs far calls and jumps; NVRAM access likewise needs an appropriate segment address or far pointer.

## Instruction support

No coprocessor is planned. ROMs must use instructions the emulator implements. An unsupported instruction must stop execution with an explicit opcode and starting `CS:IP` error; it must not be silently skipped or treated as a no-op.

The decoder rejects unhandled opcodes, including the `0xD8`–`0xDF` coprocessor escape range, before consuming their operands.

## Host execution and persistence

To run a ROM, the host must load it and repeatedly call `CPU8086.step()` on the CPU thread. Each step applies queued host inputs through `beforeInstruction()` before handling reset, interrupts, or an instruction. A pending device IRQ is delivered as maskable interrupt vector `0x20` when interrupts are enabled; its device ID and acknowledgement are defined in [io_mapping.md](io_mapping.md#irq-queue).

NVRAM is separate from volatile RAM. The host bridge provides copied, versioned NVRAM snapshots after changes. Hosts acknowledge a snapshot version only after a successful save; writes made after that snapshot remain unsaved. The launcher saves images to files, while the future mod can persist them with its world.

## Standalone launcher

Run `./gradlew runLauncher` (or `.\gradlew.bat runLauncher` on Windows) to open the Swing debugger. It starts with one CPU slot; Add CPU creates more. Select a CPU to load its own exact 256 KiB ROM and view its registers, memory, ports, devices, instructions, and log. Each CPU executes on its own worker; all slots share the volatile 64 KiB region at `0xB0000`–`0xBFFFF`. Other memory and devices are private to each CPU. Run, Pause, Step, debugger actions, CPU Reset, and Power Cycle affect the selected CPU; Run All, Pause All, and Power Cycle All affect every loaded slot. A breakpoint or fault stops only that CPU. A CPU in HLT keeps polling for an interrupt.

CPU Reset retains current memory and connected devices. A selected Power Cycle reloads its ROM and saved nonvolatile images while leaving the shared region and other CPUs intact. Power Cycle All clears the shared region and restarts all CPUs. Replacing a ROM keeps that slot's NVRAM and I/O storage file attachments.

Each CPU has independent 64 KiB NVRAM and optional 128 KiB I/O storage. Use Open, New, Save, and Save As for NVRAM in Memory and for I/O storage in its Devices tab; Disconnect removes that CPU's I/O storage. No filename is inferred from the ROM. New images start blank and unsaved; existing images must have the exact size, and old 128 KiB NVRAM files are rejected. Saves replace the target through a temporary file before acknowledging the saved snapshot. Before an operation would discard unsaved image data—including ROM replacement, device disconnect, CPU removal, power cycle, or closing—the launcher asks Save, Discard, or Cancel. Discard restores the last saved image, or blank contents if it has no file; Cancel leaves image data unchanged and aborts the operation.

Memory, raw I/O, and instruction views have address fields, scrollbars, mouse-wheel navigation, and page buttons. The memory region menu jumps to RAM, the host clock, NVRAM, shared memory, ROM, or the reset-vector page. Scrolling instructions stops following the PC and disassembles from the selected byte; use Follow PC to resume tracking execution.

The I/O Devices view groups redstone channels, signs, timers, and the IRQ queue; Raw ports remains available for byte-level inspection and edits. Device Send IRQ buttons enqueue the documented device ID, while the IRQ queue tab also accepts any 16-bit ID. Queued maskable IRQs enter vector `0x20` only when the CPU permits them, and remain pending until acknowledged. Sign text fields accept up to 15 printable ASCII characters per line; the raw view exposes format and control bytes.

Each timer has an Automatic/Manual expiry IRQ setting in the launcher. Manual mode suppresses future automatic expiry IRQs, but its countdown continues and Send IRQ queues one event even if the timer is disabled. The timer's mapped control byte is unchanged, and IRQs already queued remain queued. All timers start Automatic after Power Cycle; CPU Reset preserves their mode. In Automatic mode, the timer control byte's IRQ-enable bit must still be set for expiry IRQs.
