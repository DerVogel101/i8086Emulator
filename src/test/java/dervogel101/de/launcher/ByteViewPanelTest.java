package dervogel101.de.launcher;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ByteViewPanelTest {
    @Test
    void snapshotRefreshKeepsInstructionAndByteSelections() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LauncherMain.CodeModel code = new LauncherMain.CodeModel();
            JTable instructions = new JTable(code);
            var first = new Disassembler8086.Instruction(0xC000, 0, new byte[]{(byte) 0x90}, "NOP", "", false, false);
            var second = new Disassembler8086.Instruction(0xC000, 1, new byte[]{(byte) 0x90}, "NOP", "", false, false);
            code.update(List.of(first, second));
            instructions.setRowSelectionInterval(1, 1);
            code.update(List.of(
                    new Disassembler8086.Instruction(0xC000, 0, new byte[]{(byte) 0x90}, "NOP", "", false, false),
                    new Disassembler8086.Instruction(0xC000, 1, new byte[]{(byte) 0x90}, "NOP", "", false, false)));
            assertEquals(1, instructions.getSelectedRow());

            ByteViewPanel view = new ByteViewPanel(4, 0xFFFF, a -> {}, (a, b) -> {}, a -> true);
            JTable memory = (JTable) ((JScrollPane) ((BorderLayout) view.getLayout())
                    .getLayoutComponent(BorderLayout.CENTER)).getViewport().getView();
            view.update(0x100, new byte[256], true);
            memory.changeSelection(2, 3, false, false);
            view.update(0x100, new byte[256], true);
            assertEquals(2, memory.getSelectedRow());
            assertEquals(3, memory.getSelectedColumn());
            JPanel controls = (JPanel) ((BorderLayout) view.getLayout()).getLayoutComponent(BorderLayout.NORTH);
            for (Component component : controls.getComponents()) {
                if (component instanceof JComboBox<?> format) format.setSelectedItem(ByteViewPanel.Format.BINARY);
            }
            assertEquals(2, memory.getSelectedRow());
        });
    }

    @Test
    void scrollbarWheelAndPageButtonsBrowseFullAddressRange() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger address = new AtomicInteger(-1);
            ByteViewPanel view = new ByteViewPanel(5, 0xFFFFF, address::set, (a, b) -> {}, a -> true);
            view.update(0xFFF00, new byte[256], true);
            JScrollBar scrollbar = (JScrollBar) ((BorderLayout) view.getLayout()).getLayoutComponent(BorderLayout.EAST);
            assertEquals(0xFFF00, scrollbar.getValue());
            JTable table = (JTable) ((JScrollPane) ((BorderLayout) view.getLayout())
                    .getLayoutComponent(BorderLayout.CENTER)).getViewport().getView();
            table.dispatchEvent(new MouseWheelEvent(table, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                    0, 0, 0, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1));
            assertEquals(0xFFF10, address.get());
            scrollbar.setValue(0xFFFFF);
            JPanel controls = (JPanel) ((BorderLayout) view.getLayout()).getLayoutComponent(BorderLayout.NORTH);
            for (Component component : controls.getComponents()) {
                if (component instanceof JButton button && button.getText().equals("Next page")) button.doClick();
            }
            assertEquals(0xFFFFF, scrollbar.getValue());
            scrollbar.setValue(0);
            for (Component component : controls.getComponents()) {
                if (component instanceof JButton button && button.getText().equals("Previous page")) button.doClick();
            }
            assertEquals(0, scrollbar.getValue());
        });
    }
}
