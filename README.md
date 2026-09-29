# i8086Emulator

A Java Intel 8086 emulator with a Swing launcher and debugger. Load a ROM, run one or more CPU instances, and inspect registers, disassembly, memory, I/O, and emulated devices.

## Requirements

- JDK 17 or newer
- The included Gradle wrapper

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

The launcher supports multiple CPU slots with per-CPU ROMs and devices, plus a shared 64 KiB memory region. It provides run, pause, step, step-over, and run-to-cursor controls, along with register, disassembly, memory, and raw I/O views. NVRAM and optional I/O storage images can be opened and saved from the UI.

The emulator implements a subset of the 8086 instruction set. An unsupported instruction stops that CPU and reports its `CS:IP` address.

## Tests

Run the unit tests with:

```sh
./gradlew test
```

On Windows, use `.\gradlew.bat test` in PowerShell.

## Documentation

- [CPU execution and ROM format](docs/cpu_execution.md)
- [Physical memory map](docs/memory_mapping.md)
- [I/O port map](docs/io_mapping.md)
