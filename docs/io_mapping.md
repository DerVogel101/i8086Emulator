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
| `0x8165`–`0x816F` | Unassigned            | Reads return zero; writes are ignored |
| `0x8170`-`0x817F` | Eight keyboards       | Two ports per slot                    |
| `0x8180`–`0x81FF` | Unassigned            | Reads return zero; writes are ignored |
| `0x8200`–`0x85FF` | 16 punchcard readers  | 64 ports per reader |
| `0x8600`–`0x89FF` | 16 punchcard writers  | 64 ports per writer |
| `0x8A00`–`0xFEFF` | Unassigned            | Reads return zero; writes are ignored |
| `0xFF00`–`0xFF02` | IRQ queue             | Active device ID and control/status   |
| `0xFF03`–`0xFFFF` | Unassigned            | Reads return zero; writes are ignored |

## IRQ queue

The 16-bit device ID has an 8-bit type in its high byte and an 8-bit index in its low byte. Types `0`, `1`, `2`, `3`, and `4` identify redstone, signs, real-time timers, Minecraft timers, and keyboards respectively. For example, sign 0 is `0x0100`, real-time timer 0 is `0x0200`, and Minecraft timer 0 is `0x0300`. The host explicitly calls `submitIrq(type, index)` or the compatible raw-ID overload; redstone and sign input updates generate IRQs only when explicitly configured. Redstone modes default to DISABLED and sign sides default to none. Use `submitRedstoneIrqMode(channel, mode)` and `submitSignIrqSides(sign, sides)` (bit 0 front, bit 1 back); redstone modes HIGH/LOW/BOTH use zero/nonzero transitions, while ANALOG uses every level change. `submitRedstoneInput(channel, strength, false)` silently resamples a reconnecting port. All device IRQs are suppressed without the Interrupt Controller. The active event asserts maskable INTR until acknowledged; see [cpu_execution.md](cpu_execution.md#host-execution-and-persistence) for interrupt delivery. The queue holds 256 events including the active one. If full, a new event replaces the oldest waiting event and sets overflow; the active ID never changes until ACK.

| Port     | Read                                         | Write                                                           |
|----------|----------------------------------------------|-----------------------------------------------------------------|
| `0xFF00` | Active ID low byte, or zero if empty         | Ignored                                                         |
| `0xFF01` | Active ID high byte, or zero if empty        | Ignored                                                         |
| `0xFF02` | Bit 1: event pending; bit 2: sticky overflow | Write one to bit 0 to ACK; write one to bit 2 to clear overflow |

The pending bit distinguishes an empty queue from device ID zero. ACK and overflow-clear commands take effect at the next `beforeInstruction()` boundary; the next waiting ID is then published. Overflow is cleared before new submissions at that boundary, so a fresh overflow remains visible. CPU RESET preserves the queue and overflow state.

## Keyboards

Eight keyboard slots use `0x8170` through `0x817F`, two byte ports per slot. Slot `n` has base `0x8170 + 2*n`.

| Offset | Read                                       | Write                      |
|--------|--------------------------------------------|----------------------------|
| `+0`   | Current character byte, or zero when empty | Ignored                    |
| `+1`   | Bit 0: ACK pending; bit 1: character ready | Write bit 0 to request ACK |

Each slot holds eight bytes including its visible head. Host admission is bounded and thread-safe; a full queue drops the new byte. The first character becomes visible at the next instruction boundary and generates IRQ ID `0x0400 | n`. Waiting characters generate no IRQ until published.

An ACK is confirmed at the next instruction boundary by setting ACK-pending. The byte remains readable until the next game-tick boundary, which removes exactly one acknowledged character, clears ACK-pending, and presents the next byte with its IRQ. Repeated ACKs while pending count once; ACK with no ready character does nothing. Instruction and batch boundaries alone do not consume input. The global IRQ queue's ACK never consumes keyboard data.

Keyboard ports require the optional Keyboard Controller. Without it, reads return zero and writes are ignored. Without an Interrupt Controller, polling still works but keyboard IRQs are suppressed. CPU RESET preserves input and pending ACKs. Device detach/unload and mainframe shutdown clear the affected FIFO; leaving input capture preserves it. FIFO contents are not restored from saved execution snapshots.

Text uses Windows-1258. Unmodified Backspace, Tab and Enter are `0x08`, `0x09` and `0x0A`. Standard DE/US Shift, Caps Lock and German AltGr are supported through stateless physical-key maps; dead accents do not compose. Modified control/navigation/numpad combinations and Ctrl/Alt shortcuts are ignored.

| Codes                 | Special keys, in order                                         |
|-----------------------|----------------------------------------------------------------|
| `0x81` through `0x8A` | Numpad 0 through 9                                             |
| `0x8B` through `0x91` | Numpad decimal, divide, multiply, subtract, add, Enter, equals |
| `0x92` through `0x95` | Left, Right, Up, Down                                          |
| `0x96` through `0x9B` | Insert, Delete, Home, End, Page Up, Page Down                  |

These special codes replace characters unreachable through standard DE/US typing. Num Lock does not alter them; main-section operators remain text, and euro remains `0x80`. The C hardware library exports the port/bit macros and `IRQ_DEVICE_KEYBOARD`; `keyboard.h` adds key constants, nonblocking reads and IRQ-manager bindings.

## Punchcards

One optional Punchcard Controller enables all 16 reader and 16 writer slots.
Reader base is `0x8200 + 64*n`, writer base `0x8600 + 64*n`. IRQ types 5 and 6
identify readers and writers, with the slot in the low byte. Polling requires no
Interrupt Controller. Missing Punchcard Controllers return zero and ignore writes.

Offsets `00–1F` are a shared 32-byte row/metadata buffer; `20` command, `21` status,
`22` write-one-clear event reasons, `23` error, `24–25` total cards, `26–27`
reader unread cards / writer unused card capacity, `28–29` current card index,
`2A` row position (0–8), `2B` sealed/name-written/description-written flags
(bits 0/1/2). `2C` selects metadata: 0 name (default), 1 description.
Offsets `2D–3F` are reserved. Word registers are unsigned little-endian.

Command bits 0–7 respectively request row read, row write, name read, name write,
next card, eject, completion ACK, and error clear. Submit one at a time. A write
captures 32 bytes, admission occurs at an instruction boundary, and the transfer
finishes at the next host tick. A result remains protected until completion ACK.
Eight rows fill a 256-byte card; next-card is a separate one-tick operation.
No register read advances the deck. Reader reinsertion rewinds; writers accept
blank media, grow to 256 cards, and seal on removal/eject. Partial cards are padded
with zero rows. The name and description are separate 32-byte Windows-1252 fields, each
writeable once before data. Name commands use the `2C` selector captured at
submission, with the same completion event and ACK. Metadata never moves the
card cursor. The media name supplies the Minecraft item's initial name;
anvil custom names override it without modifying media. Byte size is 32-bit `cards * 256`.

Status bits 0–7: connected, media present, busy, result ready, end-of-card,
end-of-deck/full, output occupied, error. Event bits 0–6: insertion, row completion,
name completion, card switch, eject, removal, error. Each event raises the device
IRQ; queue ACK, event clear, and result ACK are independent. Ejection waits while
output is occupied. CPU RESET preserves state; the host owns/persists media and
transport progress separately from CPU snapshots and reconnects after power-on.

Error codes: 1 invalid/conflicting command, 2 busy/result pending, 3 no media,
4 card boundary, 5 deck end, 6 sealed, 7 metadata field locked, 8 media I/O. Failed commands
leave data unchanged. C macros and IRQ types are exported by `hardware.h`, with
nonblocking transfers and caller-owned IRQ bindings in `punchcard.h`.

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

Game-tick timers advance at `CPU8086.beforeTick()` boundaries; real-time timers advance at `CPU8086.beforeBatch()` boundaries, including batches executing halted or RESET-held steps. Timer work is not repeated by `step()` / `beforeInstruction()`. Real-time timers use elapsed milliseconds from the host clock; Minecraft timers use accumulated game ticks since the CPU was powered on. The first submitted game time establishes the origin. A backward source-time change does not decrement a timer. CPU-only RESET preserves timers; software can reset each timer through its control register.

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
