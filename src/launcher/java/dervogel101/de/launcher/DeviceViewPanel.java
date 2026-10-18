package dervogel101.de.launcher;

import javax.swing.*;
import dervogel101.de.i86.runtime.RedstoneIrqMode;
import java.nio.charset.Charset;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.Arrays;
import java.util.function.Supplier;

/** Labeled controls for the existing port map. CPU access stays in DebuggerBackend. */
final class DeviceViewPanel extends JTabbedPane {
    private final Supplier<DebuggerBackend> controller;
    private final KeyboardViewPanel keyboard;
    private final Runnable openStorage, newStorage, saveStorage, saveStorageAs, disconnectStorage;
    private final JLabel storageStatus = new JLabel("Disconnected");
    private final JLabel storageRegisters = new JLabel("Data: 0000  Address: 0000  Control: 00");
    private final JSpinner channel = new JSpinner(new SpinnerNumberModel(0, 0, 127, 1));
    private final JSpinner strength = new JSpinner(new SpinnerNumberModel(0, 0, 15, 1));
    private final JComboBox<RedstoneIrqMode> redstoneIrq = new JComboBox<>(RedstoneIrqMode.values());
    private final JCheckBox frontIrq = new JCheckBox("Front click IRQ"), backIrq = new JCheckBox("Back click IRQ");
    private final JLabel output = new JLabel("Output: 0");
    private final JSpinner sign = new JSpinner(new SpinnerNumberModel(0, 0, 127, 1));
    private final JTextField[] signLines = new JTextField[8];
    private final JLabel[] signLabels = new JLabel[8];
    private final JLabel signControl = new JLabel("Control: 0000");
    private final JSpinner timer = new JSpinner(new SpinnerNumberModel(0, 0, 5, 1));
    private final JLabel countdown = new JLabel("Countdown: 0");
    private final JSpinner reload = new JSpinner(new SpinnerNumberModel(0, 0, 65535, 1));
    private final JCheckBox enabled = new JCheckBox("Enabled");
    private final JCheckBox periodic = new JCheckBox("Periodic");
    private final JCheckBox irqEnabled = new JCheckBox("IRQ on expiry");
    private final JComboBox<String> resolution = new JComboBox<>();
    private final JComboBox<String> timerMode = new JComboBox<>(new String[]{"Automatic", "Manual"});
    private final JLabel activeIrq = new JLabel("Active: none");
    private final JLabel overflow = new JLabel("Overflow: no");
    private final JTextField rawId = new JTextField("0000", 6);
    private final boolean[] signDirty = new boolean[8];
    private boolean syncing;
    private boolean loaded;
    private final java.util.List<JButton> mutationButtons = new java.util.ArrayList<>();
    private boolean timerDirty;
    private int displayedChannel = -1, displayedSign = -1, displayedTimer = -1;

    DeviceViewPanel(Supplier<DebuggerBackend> controller, Runnable openStorage, Runnable newStorage,
                    Runnable saveStorage, Runnable saveStorageAs, Runnable disconnectStorage) {
        this.controller = controller;
        this.openStorage = openStorage;
        this.newStorage = newStorage;
        this.saveStorage = saveStorage;
        this.saveStorageAs = saveStorageAs;
        this.disconnectStorage = disconnectStorage;
        keyboard = new KeyboardViewPanel(controller);
        addTab("Redstone", redstonePanel());
        addTab("Keyboard", keyboard);
        addTab("Signs", signPanel());
        addTab("Timers", timerPanel());
        addTab("IRQ queue", irqPanel());
        addTab("Storage", storagePanel());
    }

    private JPanel storagePanel() {
        JPanel panel = vertical();
        panel.add(row(storageStatus));
        panel.add(row(storageRegisters));
        panel.add(row(button("Open", openStorage), button("New", newStorage),
                button("Save", saveStorage), button("Save As", saveStorageAs),
                button("Disconnect", disconnectStorage)));
        panel.add(row(new JLabel("Data 8160, address 8162, control 8164; edit registers in Raw ports.")));
        return panel;
    }

    void resetForCpu() {
        keyboard.stopCapture();
        syncing = true;
        for (JTextField line : signLines) line.setText("");
        Arrays.fill(signDirty, false);
        timerDirty = false;
        displayedChannel = displayedSign = displayedTimer = -1;
        loaded = false;
        output.setText("Output strength: 0");
        signControl.setText("Control: 0000");
        countdown.setText("Countdown: 0");
        activeIrq.setText("Active IRQ: none");
        overflow.setText("Overflow: no");
        storageStatus.setText("Disconnected");
        storageRegisters.setText("Data: 0000  Address: 0000  Control: 00");
        syncing = false;
    }

    private JPanel redstonePanel() {
        JPanel panel = vertical();
        channel.addChangeListener(e -> { if (!syncing) controller.get().selectChannel((Integer) channel.getValue()); });
        panel.add(row(new JLabel("Channel (0–127)"), channel, new JLabel("Input strength (0–15)"), strength,
                button("Set input", () -> controller.get().redstone((Integer) channel.getValue(), (Integer) strength.getValue()))));
        redstoneIrq.addActionListener(e -> {
            if (!syncing) controller.get().redstoneIrq((Integer) channel.getValue(), (RedstoneIrqMode) redstoneIrq.getSelectedItem());
        });
        panel.add(row(new JLabel("Input IRQ"), redstoneIrq));
        panel.add(row(output, button("Send IRQ", () -> controller.get().sendIrq((Integer) channel.getValue()))));
        panel.add(row(new JLabel("IRQ ID = channel number; output is written by the ROM.")));
        return panel;
    }

    private JPanel signPanel() {
        JPanel panel = vertical();
        sign.addChangeListener(e -> { if (!syncing) { Arrays.fill(signDirty, false); controller.get().selectSign((Integer) sign.getValue()); } });
        panel.add(row(new JLabel("Sign (0–127)"), sign,
                button("Front click", () -> controller.get().signClick((Integer) sign.getValue(), true)),
                button("Back click", () -> controller.get().signClick((Integer) sign.getValue(), false)),
                button("Send IRQ", () -> controller.get().sendIrq(0x0100 | (Integer) sign.getValue()))));
        Runnable clickIrqs = () -> {
            if (!syncing) controller.get().signIrq((Integer) sign.getValue(), (frontIrq.isSelected() ? 1 : 0) | (backIrq.isSelected() ? 2 : 0));
        };
        frontIrq.addActionListener(e -> clickIrqs.run());
        backIrq.addActionListener(e -> clickIrqs.run());
        panel.add(row(frontIrq, backIrq));
        for (int i = 0; i < signLines.length; i++) {
            int line = i;
            signLabels[i] = new JLabel((i < 4 ? "Front " + (i + 1) : "Back " + (i - 3)) + " (format 00)");
            signLines[i] = new JTextField(18);
            signLines[i].setFont(signFont());
            signLines[i].getDocument().addDocumentListener(new DocumentListener() {
                private void changed() { if (!syncing) signDirty[line] = true; }
                @Override public void insertUpdate(DocumentEvent e) { changed(); }
                @Override public void removeUpdate(DocumentEvent e) { changed(); }
                @Override public void changedUpdate(DocumentEvent e) { changed(); }
            });
            panel.add(row(signLabels[i], signLines[i], button("Apply", () -> applySignLine(line))));
        }
        panel.add(row(signControl, button("Reload text", () -> {
            Arrays.fill(signDirty, false);
            controller.get().selectSign((Integer) sign.getValue());
        }), new JLabel("Text: up to 15 Windows-1252 characters per line.")));
        JPanel outer = new JPanel(new BorderLayout());
        outer.add(new JScrollPane(panel), BorderLayout.CENTER);
        return outer;
    }

    private void applySignLine(int line) {
        try { controller.get().writeSignLine((Integer) sign.getValue(), line, signLines[line].getText()); }
        catch (IllegalArgumentException error) { JOptionPane.showMessageDialog(this, error.getMessage()); }
    }

    private JPanel timerPanel() {
        JPanel panel = vertical();
        timer.addChangeListener(e -> { if (!syncing) { timerDirty = false; controller.get().selectTimer((Integer) timer.getValue()); } });
        reload.addChangeListener(e -> { if (!syncing) timerDirty = true; });
        enabled.addActionListener(e -> { if (!syncing) timerDirty = true; });
        periodic.addActionListener(e -> { if (!syncing) timerDirty = true; });
        irqEnabled.addActionListener(e -> { if (!syncing) timerDirty = true; });
        resolution.addActionListener(e -> { if (!syncing) timerDirty = true; });
        timerMode.addActionListener(e -> {
            if (!syncing && loaded) controller.get().timerAutomatic((Integer) timer.getValue(), timerMode.getSelectedIndex() == 0);
        });
        panel.add(row(new JLabel("Timer (0–2 real time, 3–5 game time)"), timer, countdown));
        panel.add(row(new JLabel("Wait/reload"), reload, new JLabel("Resolution"), resolution));
        panel.add(row(enabled, periodic, irqEnabled));
        panel.add(row(new JLabel("Expiry IRQ mode"), timerMode,
                button("Apply timer", () -> controller.get().writeTimer((Integer) timer.getValue(), (Integer) reload.getValue(),
                        (enabled.isSelected() ? 1 : 0) | (periodic.isSelected() ? 2 : 0) |
                                (irqEnabled.isSelected() ? 4 : 0) | (resolution.getSelectedIndex() << 4))),
                button("Reset timer", () -> { timerDirty = false; controller.get().resetTimer((Integer) timer.getValue()); }),
                button("Reload settings", () -> { timerDirty = false; controller.get().selectTimer((Integer) timer.getValue()); }),
                button("Send IRQ", () -> controller.get().sendIrq(timerId((Integer) timer.getValue())))));
        panel.add(row(new JLabel("Manual suppresses future expiry IRQs; the countdown still advances.")));
        return panel;
    }

    private JPanel irqPanel() {
        JPanel panel = vertical();
        panel.add(row(activeIrq, overflow));
        panel.add(row(button("Acknowledge active", () -> controller.get().writeIo(0xFF02, 1)),
                button("Clear overflow", () -> controller.get().writeIo(0xFF02, 4))));
        panel.add(row(new JLabel("Raw device ID (hex)"), rawId, button("Send IRQ", () -> {
            try {
                int id = Integer.parseInt(rawId.getText().trim().replaceFirst("(?i)^0x", ""), 16);
                if (id < 0 || id > 0xFFFF) throw new NumberFormatException();
                controller.get().sendIrq(id);
            } catch (NumberFormatException error) { JOptionPane.showMessageDialog(this, "Enter a 16-bit hex device ID."); }
        })));
        panel.add(row(new JLabel("IDs: redstone 00xx, sign 01xx, real timer 02xx, game timer 03xx, keyboard 0400–0407.")));
        return panel;
    }

    void update(DebuggerBackend.DeviceSnapshot devices, DebuggerBackend.ImageState images, boolean loaded, DebuggerBackend.MachineState machine) {
        boolean installed = machine.hardware().has(dervogel101.de.i86.runtime.Hardware.KEYBOARD);
        keyboard.update(devices.keyboardIndex(), devices.keyboard(), controller.get().canControl() && machine.powered()
                && installed && devices.keyboard().connected(), controller.get().canEdit() && machine.powered(), installed);
        if (controller.get().remote()) loaded &= controller.get().canEdit();
        this.loaded = loaded;
        if (controller.get().remote()) {
            for (JButton button : mutationButtons) button.setEnabled(loaded);
            strength.setEnabled(false); redstoneIrq.setEnabled(loaded);
            frontIrq.setEnabled(loaded); backIrq.setEnabled(loaded);
            for (JTextField line : signLines) line.setEditable(loaded);
            for (JComponent field : new JComponent[]{reload, enabled, periodic, irqEnabled, resolution, timerMode, rawId}) field.setEnabled(loaded);
        }
        syncing = true;
        try {
            redstoneIrq.setSelectedItem(devices.redstoneIrq());
            frontIrq.setSelected((devices.signIrqSides() & 1) != 0);
            backIrq.setSelected((devices.signIrqSides() & 2) != 0);
            channel.setValue(devices.channel());
            sign.setValue(devices.signIndex());
            timer.setValue(devices.timerIndex());
            if (devices.channel() != displayedChannel) {
                displayedChannel = devices.channel();
                strength.setValue(Byte.toUnsignedInt(devices.redstone()[0]));
            }
            output.setText("Output strength: " + (Byte.toUnsignedInt(devices.redstone()[1]) & 15));
            if (devices.signIndex() != displayedSign) {
                displayedSign = devices.signIndex();
                Arrays.fill(signDirty, false);
            }
            for (int i = 0; i < 8; i++) {
                signLabels[i].setText((i < 4 ? "Front " + (i + 1) : "Back " + (i - 3)) +
                        " (format %02X)".formatted(Byte.toUnsignedInt(devices.sign()[i * 16])));
                String current = signText(devices.sign(), i);
                if (signDirty[i] && signLines[i].getText().equals(current)) signDirty[i] = false;
                if (!signDirty[i] && !signLines[i].getText().equals(current)) signLines[i].setText(current);
            }
            signControl.setText("Control: %04X".formatted(word(devices.sign(), 0x80)));
            int control = Byte.toUnsignedInt(devices.timer()[4]);
            countdown.setText("Countdown: " + word(devices.timer(), 0));
            if (devices.timerIndex() != displayedTimer) {
                displayedTimer = devices.timerIndex();
                timerDirty = false;
            }
            int requestedControl = (enabled.isSelected() ? 1 : 0) | (periodic.isSelected() ? 2 : 0) |
                    (irqEnabled.isSelected() ? 4 : 0) | (resolution.getSelectedIndex() << 4);
            if (timerDirty && (Integer) reload.getValue() == word(devices.timer(), 2) &&
                    requestedControl == (control & 0x37)) timerDirty = false;
            if (!timerDirty) {
                reload.setValue(word(devices.timer(), 2));
                enabled.setSelected((control & 1) != 0);
                periodic.setSelected((control & 2) != 0);
                irqEnabled.setSelected((control & 4) != 0);
                resolution.setModel(new DefaultComboBoxModel<>(devices.timerIndex() < 3
                        ? new String[]{"1 ms", "5 ms", "10 ms", "50 ms"}
                        : new String[]{"1 tick", "5 ticks", "10 ticks", "20 ticks"}));
                resolution.setSelectedIndex((control >>> 4) & 3);
            }
            timerMode.setSelectedIndex(devices.timerAutomatic() ? 0 : 1);
            boolean pending = (devices.irq()[2] & 2) != 0;
            activeIrq.setText(pending ? "Active IRQ: %04X".formatted(word(devices.irq(), 0)) : "Active IRQ: none");
            overflow.setText("Overflow: " + (((devices.irq()[2] & 4) != 0) ? "yes" : "no"));
            storageStatus.setText(images.storageConnected()
                    ? "Connected: " + (images.storagePath() == null ? "Unsaved" : images.storagePath().getFileName())
                    + (images.storageDirty() ? " *" : "") : "Disconnected");
            storageRegisters.setText("Data: %04X  Address: %04X  Control: %02X".formatted(
                    word(devices.storage(), 0), word(devices.storage(), 2), devices.storage()[4] & 0xFF));
        } finally { syncing = false; }
    }

    static int timerId(int timer) { return ((timer < 3 ? 2 : 3) << 8) | (timer % 3); }

    static String signText(byte[] sign, int line) {
        int start = line * 16 + 1, end = start;
        while (end < start + 15 && sign[end] != 0) end++;
        return new String(sign, start, end - start, Charset.forName("windows-1252"));
    }

    private static int word(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private JButton button(String label, Runnable action) {
        JButton button = new JButton(label);
        boolean reload = label.startsWith("Reload");
        boolean localOnly = controller.get().remote() && java.util.Set.of("Open", "New", "Save", "Save As", "Disconnect", "Set input", "Front click", "Back click").contains(label);
        if (!reload && !localOnly) mutationButtons.add(button);
        button.setVisible(!localOnly); button.setEnabled(!localOnly);
        button.addActionListener(e -> { if (loaded || reload && controller.get().remote()) action.run(); });
        return button;
    }

    private static JPanel vertical() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private static JPanel row(Component... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (Component component : components) panel.add(component);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    static Font signFont() {
        try (var input = DeviceViewPanel.class.getResourceAsStream("/assets/emulator_i86_crafted/font/monosign.ttf")) {
            if (input == null) throw new IllegalStateException("Bundled sign font is missing");
            return Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(18f);
        } catch (java.io.IOException | FontFormatException error) {
            throw new IllegalStateException("Cannot load bundled sign font", error);
        }
    }
}
