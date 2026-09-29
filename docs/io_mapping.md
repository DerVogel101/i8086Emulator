# I/O port map

`CPUIO` implements this port map. The 8086 has 65,536 I/O ports (`0x0000`–`0xFFFF`), separate from its 1 MiB memory address space; see [memory_mapping.md](memory_mapping.md). CPU execution and host integration are described in [cpu_execution.md](cpu_execution.md). An 8-bit `IN`/`OUT` transfers one port byte; a 16-bit transfer uses two consecutive ports, low byte first.

| Ports             | Device                | Layout                                |
|-------------------|-----------------------|---------------------------------------|
| `0x0000`–`0x00FF` | 128 redstone channels | Two ports per channel                 |
| `0x0100`–`0x80FF` | 128 signs             | `0x100` ports per sign                |
| `0x8100`–`0x812F` | Six timers            | Eight ports per timer                 |
| `0x8130`–`0x8150` | Output terminal       | 32 character bytes and send control   |
| `0x8151`–`0x815F` | Unassigned            | Reads return zero; writes are ignored |
| `0x8160`–`0x8164` | Nonvolatile storage   | Data, word address, command/status    |
| `0x8165`–`0xFEFF` | Unassigned            | Reads return zero; writes are ignored |
| `0xFF00`–`0xFF02` | IRQ queue             | Active device ID and control/status   |
| `0xFF03`–`0xFFFF` | Unassigned            | Reads return zero; writes are ignored |

## IRQ queue

The 16-bit device ID has an 8-bit type in its high byte and an 8-bit index in its low byte. Types `0`, `1`, `2`, and `3` identify redstone, signs, real-time timers, and Minecraft timers respectively. For example, sign 0 is `0x0100`, real-time timer 0 is `0x0200`, and Minecraft timer 0 is `0x0300`. The host explicitly calls `submitIrq(type, index)` or the compatible raw-ID overload; redstone and sign input updates do not generate interrupts automatically. The active event asserts maskable INTR until acknowledged; see [cpu_execution.md](cpu_execution.md#host-execution-and-persistence) for interrupt delivery. The queue holds 256 events including the active one. If full, a new event replaces the oldest waiting event and sets overflow; the active ID never changes until ACK.

| Port     | Read                                         | Write                                                           |
|----------|----------------------------------------------|-----------------------------------------------------------------|
| `0xFF00` | Active ID low byte, or zero if empty         | Ignored                                                         |
| `0xFF01` | Active ID high byte, or zero if empty        | Ignored                                                         |
| `0xFF02` | Bit 1: event pending; bit 2: sticky overflow | Write one to bit 0 to ACK; write one to bit 2 to clear overflow |

The pending bit distinguishes an empty queue from device ID zero. ACK and overflow-clear commands take effect at the next `beforeInstruction()` boundary; the next waiting ID is then published. Overflow is cleared before new submissions at that boundary, so a fresh overflow remains visible. CPU RESET preserves the queue and overflow state.

## Timers

Real-time timers 0–2 start at `0x8100`, `0x8108`, and `0x8110`; Minecraft timers 0–2 start at `0x8118`, `0x8120`, and `0x8128`. Each block uses these byte offsets:

| Offset    | Read                                       | Write                                 |
|-----------|--------------------------------------------|---------------------------------------|
| `+0`–`+1` | Countdown, unsigned 16-bit little-endian   | Ignored                               |
| `+2`–`+3` | Wait/reload, unsigned 16-bit little-endian | Sets wait/reload                      |
| `+4`      | Control                                    | Sets control; bit 3 resets this timer |
| `+5`–`+7` | Zero                                       | Ignored                               |

Control bit 0 enables the timer, bit 1 selects periodic operation, bit 2 enables IRQ generation, and bit 3 is write-one reset. Bits 4–5 select resolution; bits 6–7 are ignored. Resolution values 0–3 mean 1, 5, 10, or 50 milliseconds for real-time timers, and 1, 5, 10, or 20 game ticks for Minecraft timers. Reset clears wait, countdown, control, and fractional elapsed time; it overrides other bits in the same write.

Enabling loads countdown from wait. The countdown is read-only; wait changes while running apply at the next reload. Disabling freezes countdown and generates no IRQ. Wait zero leaves an enabled timer idle. At zero, a one-shot timer clears enable and stays at zero; a periodic timer reloads wait. Every elapsed periodic expiration generates an IRQ when bit 2 is set, subject to the queue's capacity and overflow rule. Timer 0–2 IRQ IDs are `0x0200`–`0x0202` or `0x0300`–`0x0302`.

Timers advance from elapsed time at `beforeInstruction()` boundaries, including halted and RESET-held steps. Real-time timers use elapsed milliseconds from the host clock; Minecraft timers use accumulated game ticks since the CPU was powered on. The first submitted game time establishes the origin. A backward source-time change does not decrement a timer. CPU-only RESET preserves timers; software can reset each timer through its control register.

## Output terminal

Ports `0x8130`–`0x814F` hold 32 Windows-1252 character bytes. Port `0x8150` has one control bit: writing one to bit 0 requests a send; other bits are ignored. The send bit reads as one until the next `beforeInstruction()` boundary. At that boundary, the bridge copies all nonzero character bytes in order into its log, then clears both the 32-byte field and the send bit before the next CPU instruction. A zero byte is ignored wherever it occurs; it does not terminate the message.

The bridge retains the newest 16,384 decoded characters, dropping the oldest characters when full. The CPU must write byte `0x0A` for a newline; sending does not add one. The terminal generates no IRQ. CPU-only RESET preserves the log, while a power cycle starts with an empty log. The launcher's **Log** tab displays the bridge log.

## Nonvolatile I/O storage

The host connects a separate 128 KiB `IoStorageDevice` through `CpuHostBridge.submitStorageDevice(device)`; passing `null` disconnects it. It starts disconnected. Connection changes take effect at the next `beforeInstruction()` boundary, before storage commands. The host retains the device reference and persists its exact-size image through `load`, dirty snapshots, and acknowledgement after a successful save. The bridge also exposes snapshot and acknowledgement methods for the currently connected device. A detached device remains available to the host for saving. This storage is independent of memory-mapped NVRAM.

| Port              | Access                                     | Value                                             |
|-------------------|--------------------------------------------|---------------------------------------------------|
| `0x8160`–`0x8161` | Read/write                                 | 16-bit data, low byte first                       |
| `0x8162`–`0x8163` | Read/write                                 | 16-bit word address, low byte first (`0`–`65535`) |
| `0x8164` bit 0    | Write-one command, readable until serviced | READ selected word into data                      |
| `0x8164` bit 1    | Write-one command, readable until serviced | WRITE data to selected word                       |
| `0x8164` bit 2    | Read-only                                  | Device connected (`1`) or absent (`0`)            |

READ and WRITE complete at the next instruction boundary and their bits then clear. READ takes priority if both are requested. Commands issued while disconnected clear without changing data or storage; the data and address registers retain their values. Word operations on the device and on the ports are little-endian. No command raises an IRQ. CPU-only RESET leaves the connected device and its contents intact.

## Redstone channels

For channel `n` (`0`–`127`), the input port is `2 * n` and the output port is `2 * n + 1`. For example, channel 0 uses `0x0000`/`0x0001`; channel 127 uses `0x00FE`/`0x00FF`. The host supplies the input; CPU writes to input ports are ignored. The CPU writes the output.

Each port is one byte. Redstone strength is `0`–`15` in the low four bits; input high bits are zero, and output high bits are ignored. This leaves one byte for each direction per channel.

## Sign slots

For sign `n` (`0`–`127`), its base port is `0x0100 + n * 0x0100`. Sign 0 occupies `0x0100`–`0x01FF`; sign 127 occupies `0x8000`–`0x80FF`. Add the offsets below to the sign's base port.

| Offset        | Contents                                         |
|---------------|--------------------------------------------------|
| `0x00`–`0x0F` | Front, line 1                                    |
| `0x10`–`0x1F` | Front, line 2                                    |
| `0x20`–`0x2F` | Front, line 3                                    |
| `0x30`–`0x3F` | Front, line 4                                    |
| `0x40`–`0x4F` | Back, line 1                                     |
| `0x50`–`0x5F` | Back, line 2                                     |
| `0x60`–`0x6F` | Back, line 3                                     |
| `0x70`–`0x7F` | Back, line 4                                     |
| `0x80`–`0x81` | Control word, unsigned 16-bit, little-endian     |
| `0x82`–`0xFF` | Unused; reads return zero and writes are ignored |

Each line uses 16 bytes: a format byte at its first offset, followed by 15 single-byte character positions. The character positions use ASCII; `0x00` marks the end of text and pads unused positions. A line can therefore contain up to 15 characters. The eight lines use 128 bytes, and the control word uses two more: 130 data bytes (`0x82`) per sign slot.

### Format byte

| Bits    | Mask   | Meaning                |
|---------|--------|------------------------|
| `0`–`3` | `0x0F` | Color index (`0`–`15`) |
| `4`     | `0x10` | Disable explicit color |
| `5`     | `0x20` | Strikethrough          |
| `6`     | `0x40` | Italic                 |
| `7`     | `0x80` | Underlined             |

A set style bit enables that style. With bit 4 set, the sign uses its default text color instead of the color index.

| Index | Color       | Index | Color        |
|------:|-------------|------:|--------------|
|   `0` | black       |   `8` | dark_gray    |
|   `1` | dark_blue   |   `9` | blue         |
|   `2` | dark_green  |  `10` | green        |
|   `3` | dark_aqua   |  `11` | aqua         |
|   `4` | dark_red    |  `12` | red          |
|   `5` | dark_purple |  `13` | light_purple |
|   `6` | gold        |  `14` | yellow       |
|   `7` | gray        |  `15` | white        |

Color names follow [Minecraft text formatting](https://minecraft.wiki/w/Text_component_format).

### Control word

Read the word at offsets `0x80`–`0x81` (low byte at `0x80`). The host sets the click flags at offset `0x81`; repeated clicks while a flag is set coalesce. To acknowledge flags, the CPU uses `OUT` at `0x81` (or a word `OUT` starting at `0x80`): each flag bit written as zero clears, while each bit written as one stays unchanged. A byte `OUT` at `0x80` does not affect the flags.

|  Bit | Mask     | Meaning       |
|-----:|----------|---------------|
|  `8` | `0x0100` | Sign clicked  |
|  `9` | `0x0200` | Front clicked |
| `10` | `0x0400` | Back clicked  |

A front click sets bits 8 and 9; a back click sets bits 8 and 10. All other bits are reserved and should be written as zero.
