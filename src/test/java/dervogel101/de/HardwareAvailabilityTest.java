package dervogel101.de;

import dervogel101.de.i86.runtime.*;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HardwareAvailabilityTest {
    static HardwareProfile minimum() {
        var counts = new EnumMap<Hardware, Integer>(Hardware.class);
        for (var device : Hardware.values()) if (device.required) counts.put(device, 1);
        return new HardwareProfile(counts);
    }

    @Test void absentDevicesReadZeroAndDoNotEraseRetainedMemory() {
        var cpu = new CPU8086();
        cpu.memory().write16(0xA000, 0, 0xABCD);
        cpu.memory().write16(0xB000, 0, 0x5678);
        cpu.configureHardware(minimum());
        cpu.memory().write16(0xA000, 0, 0xFFFF);
        cpu.memory().write16(0xB000, 0, 0xFFFF);
        assertEquals(0, cpu.memory().read16(0xA000, 0));
        assertEquals(0, cpu.memory().read16(0xB000, 0));
        for (int port : new int[]{0x100, 0x8102, 0x8130, 0x8150, 0x8160, 0xFF02}) {
            cpu.io().write8(port, 0xFF);
            assertEquals(0, cpu.io().read8(port));
        }
        cpu.beforeInstruction();
        assertEquals(0, cpu.memory().read16(0x9FFF, 3));
        assertEquals("", cpu.hostBridge().terminalText());
        cpu.configureHardware(HardwareProfile.complete());
        assertEquals(0xABCD, cpu.memory().read16(0xA000, 0));
        assertEquals(0x5678, cpu.memory().read16(0xB000, 0));
    }

    @Test void capacitiesDisablePortsBeyondInstalledModules() {
        var cpu = new CPU8086();
        cpu.configureHardware(minimum());
        cpu.io().write8(31 * 2 + 1, 15);
        cpu.io().write8(32 * 2 + 1, 15);
        cpu.hostBridge().submitRedstoneInput(32, 12);
        cpu.hostBridge().submitSignClick(0, CpuHostBridge.SignSide.FRONT);
        cpu.beforeInstruction();
        assertEquals(15, cpu.io().read8(63));
        assertEquals(0, cpu.io().read8(65));
        assertEquals(0, cpu.io().read8(64));
        assertEquals(0, cpu.io().read8(0x181));
    }

    @Test void nmiStillWorksWithoutDeviceInterruptController() {
        var cpu = new CPU8086();
        cpu.configureHardware(minimum());
        cpu.memory().write16(0, 8, 0x1234);
        cpu.memory().write16(0, 10, 0x2000);
        cpu.registers().setSp(0x1000);
        cpu.hostBridge().submitIrq(0x200);
        cpu.hostBridge().submitNmiPin(true);
        assertEquals(CPU8086.StepOutcome.EVENT, cpu.step());
        assertEquals(0x1234, cpu.registers().getIp());
        assertEquals(0x2000, cpu.registers().getCs());
        assertEquals(0, cpu.io().read8(0xFF02));
    }

    @Test void configuredInputIrqsAndSilentReattachmentMatchTheMod() {
        var cpu = new CPU8086();
        var bridge = cpu.hostBridge();
        bridge.submitRedstoneIrqMode(3, RedstoneIrqMode.ANALOG);
        bridge.submitRedstoneInput(3, 5, false);
        cpu.beforeInstruction();
        assertEquals(5, cpu.io().read8(6));
        assertEquals(0, cpu.io().read8(0xFF02));
        bridge.submitRedstoneInput(3, 6);
        cpu.beforeInstruction();
        assertEquals(3, cpu.io().read16(0xFF00));
        assertEquals(2, cpu.io().read8(0xFF02));
        cpu.io().write8(0xFF02, 1);
        bridge.submitSignIrqSides(4, 2);
        bridge.submitSignClick(4, CpuHostBridge.SignSide.FRONT);
        cpu.beforeInstruction();
        assertEquals(0, cpu.io().read8(0xFF02));
        bridge.submitSignClick(4, CpuHostBridge.SignSide.BACK);
        cpu.beforeInstruction();
        assertEquals(0x104, cpu.io().read16(0xFF00));
        assertEquals(7, cpu.io().read8(0x581));
    }
}
