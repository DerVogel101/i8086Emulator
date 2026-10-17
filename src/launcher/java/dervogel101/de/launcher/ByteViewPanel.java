package dervogel101.de.launcher;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.charset.Charset;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

final class ByteViewPanel extends JPanel {
    enum Format { HEX, BINARY, WINDOWS_1252 }
    private static final Charset CP1252 = Charset.forName("windows-1252");
    private final ByteModel model;
    private final JTextField address;
    private final JScrollBar addressScroll;
    private boolean syncingScroll;

    ByteViewPanel(int digits, int mask, IntConsumer onAddress, BiConsumer<Integer, Integer> onEdit,
                  IntPredicate writable) {
        super(new BorderLayout());
        model = new ByteModel(digits, mask, onEdit, writable);
        address = new JTextField(digits == 5 ? "FFF00" : "0000", 8);
        JButton go = new JButton("Go");
        go.addActionListener(e -> {
            try {
                int value = Integer.parseInt(address.getText().trim().replaceFirst("(?i)^0x", ""), 16);
                if (value < 0 || value > mask) throw new NumberFormatException();
                onAddress.accept(value);
            } catch (NumberFormatException ex) { JOptionPane.showMessageDialog(this, "Enter a valid hex address."); }
        });
        address.addActionListener(e -> go.doClick());
        JComboBox<Format> format = new JComboBox<>(Format.values());
        format.addActionListener(e -> model.setFormat((Format) format.getSelectedItem()));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Address (hex)")); controls.add(address); controls.add(go);
        JButton previous = new JButton("Previous page");
        previous.addActionListener(e -> shift(-256));
        JButton next = new JButton("Next page");
        next.addActionListener(e -> shift(256));
        controls.add(previous); controls.add(next);
        controls.add(new JLabel("Display")); controls.add(format);
        add(controls, BorderLayout.NORTH);
        JTable table = new JTable(model);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(72);
        for (int i = 1; i < 17; i++) table.getColumnModel().getColumn(i).setPreferredWidth(78);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setWheelScrollingEnabled(false);
        java.awt.event.MouseWheelListener wheel = e -> { shift(e.getWheelRotation() * 16); e.consume(); };
        table.addMouseWheelListener(wheel);
        tableScroll.addMouseWheelListener(wheel);
        add(tableScroll, BorderLayout.CENTER);
        addressScroll = new JScrollBar(JScrollBar.VERTICAL, 0, 1, 0, mask + 1);
        addressScroll.setUnitIncrement(16);
        addressScroll.setBlockIncrement(256);
        addressScroll.addAdjustmentListener(e -> {
            if (!syncingScroll && !e.getValueIsAdjusting()) onAddress.accept(e.getValue());
        });
        add(addressScroll, BorderLayout.EAST);
    }

    private void shift(int bytes) {
        int next = (int) Math.max(0, Math.min((long) model.mask, (long) addressScroll.getValue() + bytes));
        addressScroll.setValue(next);
    }

    void update(int base, byte[] bytes, boolean loaded) {
        model.update(base, bytes, loaded);
        if (!addressScroll.getValueIsAdjusting()) {
            syncingScroll = true;
            addressScroll.setValue(base);
            syncingScroll = false;
        }
        if (!address.isFocusOwner()) address.setText(("%0" + model.digits + "X").formatted(base));
    }

    static String format(int value, Format mode) {
        value &= 0xFF;
        return switch (mode) {
            case HEX -> "%02X".formatted(value);
            case BINARY -> String.format("%8s", Integer.toBinaryString(value)).replace(' ', '0');
            case WINDOWS_1252 -> {
                String decoded = new String(new byte[]{(byte) value}, CP1252);
                char c = decoded.charAt(0);
                yield Character.isISOControl(c) || c == '\uFFFD' || Character.isWhitespace(c)
                        ? "\\x%02X".formatted(value) : decoded;
            }
        };
    }

    static int parse(String text, Format mode) {
        if (mode != Format.WINDOWS_1252) text = text.trim();
        int value;
        if (mode == Format.WINDOWS_1252 && !text.matches("(?i)\\\\x[0-9a-f]{2}")) {
            if (text.length() != 1 || !CP1252.newEncoder().canEncode(text))
                throw new IllegalArgumentException("Enter one Windows-1252 character or \\xNN.");
            byte[] encoded = text.getBytes(CP1252);
            if (encoded.length != 1) throw new IllegalArgumentException("Not a single byte.");
            return Byte.toUnsignedInt(encoded[0]);
        }
        if (mode == Format.WINDOWS_1252) text = text.substring(2);
        else if (mode == Format.HEX) text = text.replaceFirst("(?i)^0x", "");
        try { value = Integer.parseInt(text, mode == Format.BINARY ? 2 : 16); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid byte value."); }
        if (value < 0 || value > 255 || mode == Format.BINARY && text.length() != 8)
            throw new IllegalArgumentException("Enter one byte (00–FF or eight binary digits).");
        return value;
    }

    private static final class ByteModel extends AbstractTableModel {
        private final int digits, mask;
        private final BiConsumer<Integer, Integer> onEdit;
        private final IntPredicate writable;
        private Format format = Format.HEX;
        private int base;
        private byte[] bytes = new byte[256];
        private boolean loaded;

        ByteModel(int digits, int mask, BiConsumer<Integer, Integer> onEdit, IntPredicate writable) {
            this.digits = digits; this.mask = mask; this.onEdit = onEdit; this.writable = writable;
        }
        void update(int base, byte[] bytes, boolean loaded) {
            boolean samePage = this.base == base;
            this.base = base; this.bytes = bytes.clone(); this.loaded = loaded;
            if (samePage) fireTableRowsUpdated(0, 15);
            else fireTableDataChanged();
        }
        void setFormat(Format format) { this.format = format; fireTableRowsUpdated(0, 15); }
        @Override public int getRowCount() { return 16; }
        @Override public int getColumnCount() { return 17; }
        @Override public String getColumnName(int column) { return column == 0 ? "Address" : "%X".formatted(column - 1); }
        @Override public Object getValueAt(int row, int column) {
            int address = (base + row * 16) & mask;
            return column == 0 ? ("%0" + digits + "X").formatted(address)
                    : format(Byte.toUnsignedInt(bytes[row * 16 + column - 1]), format);
        }
        @Override public boolean isCellEditable(int row, int column) {
            return loaded && column > 0 && writable.test((base + row * 16 + column - 1) & mask);
        }
        @Override public void setValueAt(Object value, int row, int column) {
            try {
                int address = (base + row * 16 + column - 1) & mask;
                int parsed = parse(String.valueOf(value), format);
                onEdit.accept(address, parsed);
            } catch (IllegalArgumentException error) { JOptionPane.showMessageDialog(null, error.getMessage()); }
        }
    }
}
