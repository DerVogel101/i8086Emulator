# i8086Emulator

A Java Intel 8086 emulator with a Swing launcher and debugger. Load a ROM, run one or more CPU instances, and inspect registers, disassembly, memory, I/O, and emulated devices.

## Requirements

- JDK 21 or newer (development checks use JDK 25)
- The included Gradle wrapper
- The sibling `emulator_i86_crafted` checkout, supplying the shared host-independent
  runtime rules in `src/runtime/java`

## Run

From Linux or macOS:

```sh
./gradlew runLauncher
```

From Windows PowerShell:

```powershell
.\gradlew.bat runLauncher
```

In the launcher, choose **Load ROM** and select a 256 KiB ROM image. The image is mapped to physical addresses `0xC0000`–`0xFFFFF`; execution starts at the 8086 reset vector `FFFF:0000` (`0xFFFF0`).

The launcher supports multiple CPU slots with per-CPU ROMs and devices, plus named shared 64 KiB memory regions. It provides run, pause, step, step-over, and run-to-cursor controls, along with register, disassembly, memory, and raw I/O views. NVRAM and optional I/O storage images can be opened and saved from the UI.

The emulator implements a subset of the 8086 instruction set. An unsupported instruction stops that CPU and reports its `CS:IP` address.

## Tests

Run the unit tests with:

```sh
./gradlew test
```

On Windows, use `.\gradlew.bat test` in PowerShell.

## Documentation

- [CPU execution and ROM format](docs/cpu_execution.md)
- [Cooperative yield extension and C helpers](docs/cpu_execution.md#cooperative-yield-extension)
- [Physical memory map](docs/memory_mapping.md)
- [I/O port map](docs/io_mapping.md)

The Hardware dialog and PSU control model powered-off installation and optional devices. Save session/Open session preserve complete execution state and shared memory. Sign editing uses Windows-1252 and the bundled Monosign font. See the execution documentation for lifecycle rules and current limits.
