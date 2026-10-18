package dervogel101.de.launcher;

import dervogel101.de.i86.runtime.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;

final class KeyboardViewPanel extends JPanel {
    private final JSpinner slot = new JSpinner(new SpinnerNumberModel(0, 0, 7, 1));
    private final JComboBox<KeyboardInput.Layout> layout = new JComboBox<>(KeyboardInput.Layout.values());
    private final JLabel state = new JLabel("Keyboard controller is offline"), queue = new JLabel("FIFO: empty");
    private final JPanel capture = new JPanel();
    private final JLabel prompt = new JLabel("Click Capture keys; Escape releases input");
    private final JButton start = new JButton("Capture keys"), ack = new JButton("Request data ACK");
    private boolean syncing, canInput, capturing, rightAlt;
    private KeyboardDevice.Snapshot snapshot = KeyboardDevice.Snapshot.EMPTY;

    KeyboardViewPanel(Supplier<DebuggerBackend> backend) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        layout.setSelectedItem(Locale.getDefault().getLanguage().equals("de") ? KeyboardInput.Layout.DE : KeyboardInput.Layout.US);
        slot.addChangeListener(e -> { if (!syncing) backend.get().selectKeyboard((Integer)slot.getValue()); });
        add(row(new JLabel("Keyboard (0–7)"), slot, new JLabel("Layout"), layout, start));
        add(row(state)); add(row(queue));
        capture.add(prompt); capture.setFocusable(true); capture.setFocusTraversalKeysEnabled(false);
        capture.enableInputMethods(false);
        capture.setBorder(BorderFactory.createEtchedBorder());
        capture.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        start.addActionListener(e -> {
            if (canInput) { capturing = true; refreshPrompt(); capture.requestFocusInWindow(); }
        });
        capture.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { capturing = canInput; refreshPrompt(); }
            @Override public void focusLost(FocusEvent event) { stopCapture(); }
        });
        capture.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                event.consume();
                if (!capturing || !canInput || !backend.get().canControl()) return;
                if (event.getKeyCode() == KeyEvent.VK_ALT_GRAPH || event.getKeyCode() == KeyEvent.VK_ALT && event.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT)
                    rightAlt = true;
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) { stopCapture(); start.requestFocusInWindow(); return; }
                int value = SwingKeyboardInput.translate(event, (KeyboardInput.Layout)layout.getSelectedItem(), capsLock(), rightAlt);
                if (value < 0) return;
                if (snapshot.queue().length == 8) { Toolkit.getDefaultToolkit().beep(); return; }
                backend.get().keyboardInput((Integer)slot.getValue(), value);
            }
            @Override public void keyTyped(KeyEvent event) { event.consume(); }
            @Override public void keyReleased(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ALT_GRAPH || event.getKeyCode() == KeyEvent.VK_ALT && event.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT)
                    rightAlt = false;
                event.consume();
            }
        });
        add(capture);
        ack.addActionListener(e -> backend.get().writeIo(0x8171 + 2 * (Integer)slot.getValue(), 1));
        add(row(ack, new JLabel("Data ACK is separate from global IRQ ACK. One byte advances per game tick.")));
        add(row(new JLabel("Enter / Backspace / Tab, arrows and numpad use the keyboard device codes. Paste is ignored.")));
    }
    void update(int index, KeyboardDevice.Snapshot snapshot, boolean input, boolean editable, boolean installed) {
        this.snapshot = snapshot; canInput = input;
        syncing = true;
        try { slot.setValue(index); } finally { syncing = false; }
        if (!canInput) stopCapture();
        start.setEnabled(canInput); layout.setEnabled(canInput);
        ack.setEnabled(editable && installed && snapshot.ready() && !snapshot.ackPending());
        byte[] bytes = snapshot.queue();
        state.setText((!installed ? "Controller absent" : !snapshot.connected() ? "Disconnected" : "Connected")
                + "; ready=" + snapshot.ready() + "; ACK pending=" + snapshot.ackPending()
                + "; head=" + (snapshot.ready() ? "%02X".formatted(Byte.toUnsignedInt(bytes[0])) : "empty")
                + "; IRQ=040" + index + "; ports=%04X/%04X".formatted(0x8170 + index * 2, 0x8171 + index * 2));
        queue.setText("FIFO " + bytes.length + "/8: " + (bytes.length == 0 ? "empty" : HexFormat.ofDelimiter(" ").withUpperCase().formatHex(bytes)));
    }
    void stopCapture() { capturing = rightAlt = false; refreshPrompt(); }
    private void refreshPrompt() { prompt.setText(capturing ? "Keyboard input captured — Escape to release" : "Click Capture keys; Escape releases input"); }
    private static boolean capsLock() {
        try { return Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_CAPS_LOCK); }
        catch (UnsupportedOperationException ignored) { return false; }
    }
    private static JPanel row(Component... components) {
        var panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (var component : components) panel.add(component);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }
}
