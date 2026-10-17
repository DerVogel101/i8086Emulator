package dervogel101.de.launcher;

import dervogel101.de.state.SharedMemoryGroups;
import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.i86.runtime.ShardSpeed;
import dervogel101.de.i86.runtime.HardwareProfile;
import java.util.EnumMap;
import dervogel101.de.i86.runtime.VirtualGameClock;


import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Path;
import java.nio.file.Files;

public final class LauncherMain {
    private final JFrame frame = new JFrame("8086 ROM launcher");
    private final JLabel status = new JLabel("Paused");
    private final JLabel rate = new JLabel("0 instructions/s");
    private final JTextArea registers = new JTextArea(3, 80);
    private final JTextArea terminalLog = new JTextArea();
    private final JTextArea errors = new JTextArea();
    private final JTabbedPane tabs = new JTabbedPane();
    private final CodeModel codeModel = new CodeModel();
    private final JTable codeTable = new JTable(codeModel);
    private JTextField codeAddressField;
    private JScrollBar codeScroll;
    private int visibleCodeCs = 0xFFFF;
    private boolean syncingCodeScroll;
    private final ByteViewPanel memoryView;
    private final ByteViewPanel ioView;
    private final DeviceViewPanel deviceView;
    private final List<CpuSlot> slots = new ArrayList<>();
    private final JComboBox<CpuSlot> cpuSelector = new JComboBox<>();
    private final JLabel nvramStatus = new JLabel("NVRAM: unsaved");
    private final JCheckBox testPinControl = new JCheckBox("TEST high");
    private SharedMemoryGroups sharedMemory = new SharedMemoryGroups();
    private final JCheckBox psuControl = new JCheckBox("PSU redstone high");
    private final VirtualGameClock worldClock = new VirtualGameClock(20);
    private int nextCpuId = 1;
    private int configuredTicks = 20;
    private ShardSpeed[] shardSpeeds = {ShardSpeed.TIER_1, ShardSpeed.TIER_2, ShardSpeed.TIER_3};
    private final JComboBox<Integer> shardConfiguration = new JComboBox<>(new Integer[]{1, 2, 3});
    private final JSpinner budget = new JSpinner(new SpinnerNumberModel(10_000, 1, Integer.MAX_VALUE, 100));
    private final JSpinner ticks = new JSpinner(new SpinnerNumberModel(20, 1, 1000, 1));
    private final JSpinner batches = new JSpinner(new SpinnerNumberModel(10, 1, Integer.MAX_VALUE, 1));
    private boolean syncingPacing;

    private static final class CpuSlot {
        final int id;
        DebuggerBackend controller;
        DebuggerBackend.Snapshot snapshot;

        CpuSlot(int id) { this.id = id; }

        @Override public String toString() {
            return "CPU " + id + (snapshot == null ? "" : " — " + snapshot.romName() + " — " + snapshot.status());
        }
    }

    interface RemoteFactory {
        DebuggerBackend create(java.util.function.Consumer<DebuggerBackend.Snapshot> snapshots,
                java.util.function.Consumer<String> errors, Runnable closed);
    }
    private final boolean remote;
    private final List<JButton> remoteControls = new ArrayList<>();
    private LauncherMain() { this(null); }
    private LauncherMain(RemoteFactory factory) {
        remote = factory != null;
        if (remote) {
            CpuSlot slot = new CpuSlot(1);
            slot.controller = factory.create(snapshot -> updateSlot(slot, snapshot), this::showError, () -> frame.dispose());
            slots.add(slot); cpuSelector.addItem(slot); cpuSelector.setSelectedItem(slot);
        } else addCpuSlot();
        memoryView = new ByteViewPanel(5, 0xFFFFF, address -> selected().memoryBase(address),
                (address, value) -> selected().writeMemory(address, value), LauncherController::writableMemory);
        ioView = new ByteViewPanel(4, 0xFFFF, port -> selected().ioBase(port),
                (port, value) -> selected().writeIo(port, value), LauncherController::writableIo);
        deviceView = new DeviceViewPanel(this::selected, this::openStorage, this::newStorage,
                () -> saveStorage(false), () -> saveStorage(true), this::disconnectStorage);
        build();
    }

    private void addCpuSlot() {
        CpuSlot slot = new CpuSlot(nextCpuId++);
        slot.controller = new LauncherController(snapshot -> updateSlot(slot, snapshot), this::showError,
                sharedMemory, slot.id, worldClock::ticks);
        slot.controller.configure(shardSpeeds[2].stepsPerTick(), configuredTicks, shardSpeeds[2].batchesPerTick());
        slots.add(slot);
        cpuSelector.addItem(slot);
        cpuSelector.setSelectedItem(slot);
    }

    private DebuggerBackend selected() {
        return ((CpuSlot) cpuSelector.getSelectedItem()).controller;
    }

    private void updateSlot(CpuSlot slot, DebuggerBackend.Snapshot snapshot) {
        if (!slots.contains(slot)) return;
        slot.snapshot = snapshot;
        cpuSelector.repaint();
        if (cpuSelector.getSelectedItem() == slot) update(snapshot);
    }

    static void openRemote(RemoteFactory factory) {
        new LauncherMain(factory).frame.setVisible(true);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LauncherMain().frame.setVisible(true));
    }

    private void build() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { closeLauncher(); }
            @Override public void windowClosed(WindowEvent e) {
                for (CpuSlot slot : slots) slot.controller.close();
            }
        });
        frame.setLayout(new BorderLayout(6, 6));

        JPanel buttons = new JPanel(new GridLayout(3, 1));
        JPanel cpuButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel machineButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        cpuSelector.addActionListener(e -> {
            CpuSlot slot = (CpuSlot) cpuSelector.getSelectedItem();
            if (slot != null) {
                deviceView.resetForCpu();
                codeTable.clearSelection();
                if (slot.snapshot != null) update(slot.snapshot);
                else {
                    status.setText("Paused");
                    rate.setText("0 instructions/s");
                    registers.setText("");
                    terminalLog.setText("");
                    codeModel.update(List.of());
                    memoryView.update(0, new byte[256], false);
                    ioView.update(0, new byte[256], false);
                    nvramStatus.setText("NVRAM: unsaved");
                }
            }
        });
        if (!remote) {
            cpuButtons.add(new JLabel("CPU"));
            cpuButtons.add(cpuSelector);
            addButton(cpuButtons, "Add CPU", this::addCpuSlot);
            addButton(cpuButtons, "Remove CPU", this::removeCpu);
            addButton(cpuButtons, "Load ROM", this::chooseRom);
            addButton(cpuButtons, "Hardware", this::configureHardware);
            addButton(cpuButtons, "Save session", this::saveSession);
            addButton(cpuButtons, "Open session", this::openSession);
            psuControl.addActionListener(e -> selected().powerSupply(psuControl.isSelected()));
            cpuButtons.add(psuControl);
        } else cpuButtons.add(new JLabel("Minecraft mainframe — hardware and images are managed in game"));

        addButton(machineButtons, "Run", () -> selected().run());
        addButton(machineButtons, "Pause", () -> selected().pause());
        if (!remote) addButton(machineButtons, "Run All", this::runAll);
        if (!remote) addButton(machineButtons, "Pause All", this::pauseAll);
        addButton(machineButtons, "Step", () -> selected().step());
        addButton(machineButtons, "CPU Reset", () -> selected().reset());
        addButton(machineButtons, "Power Cycle", () -> { if (remote) selected().powerCycle(); else powerCycleSelected(); });
        if (!remote) addButton(machineButtons, "Power Cycle All", this::powerCycleAll);
        JPanel debugButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        addButton(debugButtons, "Skip", () -> selected().skipInstruction());
        addButton(debugButtons, "Step Over", () -> selected().stepOver());
        addButton(debugButtons, "Set CS:IP", () -> withCodeSelection(i -> selected().setPc(i.cs(), i.ip())));
        addButton(debugButtons, "Run to Cursor", () -> withCodeSelection(i -> selected().runTo(i.cs(), i.ip())));
        addButton(debugButtons, "NMI", () -> selected().nmi());
        testPinControl.addActionListener(e -> selected().testPin(testPinControl.isSelected()));
        if (!remote) debugButtons.add(testPinControl);
        if (remote) for (JPanel panel : List.of(machineButtons, debugButtons))
            for (Component component : panel.getComponents()) if (component instanceof JButton button) remoteControls.add(button);
        buttons.add(cpuButtons);
        buttons.add(machineButtons);
        buttons.add(debugButtons);

        JPanel pacing = new JPanel(new FlowLayout(FlowLayout.LEFT));
        shardConfiguration.setSelectedIndex(2);
        shardConfiguration.addActionListener(e -> refreshPacingControls());
        budget.addChangeListener(e -> applyPacing());
        ticks.addChangeListener(e -> applyPacing());
        batches.addChangeListener(e -> applyPacing());
        if (!remote) {
            pacing.add(new JLabel("Configure shard tier")); pacing.add(shardConfiguration);
            pacing.add(new JLabel("Steps/tick")); pacing.add(budget);
            pacing.add(new JLabel("Ticks/s")); pacing.add(ticks);
            pacing.add(new JLabel("Batches/tick")); pacing.add(batches);
        }
        pacing.add(rate); pacing.add(status);

        registers.setEditable(false);
        registers.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JPanel top = new JPanel(new BorderLayout());
        top.add(buttons, BorderLayout.NORTH);
        top.add(pacing, BorderLayout.CENTER);
        top.add(new JScrollPane(registers), BorderLayout.SOUTH);
        frame.add(top, BorderLayout.NORTH);

        tabs.addTab("Memory", memoryPanel());
        tabs.addTab("I/O", ioPanel());
        tabs.addTab("Instructions", codePanel());
        terminalLog.setEditable(false);
        terminalLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        tabs.addTab("Log", new JScrollPane(terminalLog));
        errors.setEditable(false);
        errors.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("Errors", new JScrollPane(errors));
        frame.add(tabs, BorderLayout.CENTER);
        frame.setSize(1240, 840);
        frame.setLocationByPlatform(true);
    }

    private JPanel memoryPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JComboBox<String> regions = new JComboBox<>(new String[]{
                "Jump to region", "RAM 00000", "Host clock 9FFF3", "NVRAM A0000",
                "Shared memory B0000", "ROM C0000", "Reset vector FFFF0"});
        regions.addActionListener(e -> {
            int[] addresses = {0, 0, 0x9FFF0, 0xA0000, 0xB0000, 0xC0000, 0xFFF00};
            if (regions.getSelectedIndex() > 0) selected().memoryBase(addresses[regions.getSelectedIndex()]);
        });
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(regions);
        controls.add(nvramStatus);
        if (!remote) addButton(controls, "Open NVRAM", this::openNvram);
        if (!remote) addButton(controls, "New NVRAM", this::newNvram);
        if (!remote) addButton(controls, "Save NVRAM", () -> saveNvram(false));
        if (!remote) addButton(controls, "Save NVRAM As", () -> saveNvram(true));
        panel.add(controls, BorderLayout.NORTH);
        panel.add(memoryView, BorderLayout.CENTER);
        return panel;
    }

    private JPanel ioPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JTabbedPane views = new JTabbedPane();
        views.addTab("Devices", deviceView);
        views.addTab("Raw ports", ioView);
        panel.add(views, BorderLayout.CENTER);
        return panel;
    }

    private JPanel codePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JTextField address = new JTextField("FFFF:0000", 12);
        codeAddressField = address;
        JButton go = new JButton("Go to CS:IP");
        go.addActionListener(e -> {
            try {
                String[] parts = address.getText().trim().split(":", -1);
                if (parts.length != 2) throw new NumberFormatException();
                int cs = Integer.parseInt(parts[0].replaceFirst("(?i)^0x", ""), 16);
                int ip = Integer.parseInt(parts[1].replaceFirst("(?i)^0x", ""), 16);
                if (cs < 0 || cs > 0xFFFF || ip < 0 || ip > 0xFFFF) throw new NumberFormatException();
            selected().codeAddress(cs, ip);
            } catch (NumberFormatException ex) { JOptionPane.showMessageDialog(frame, "Enter CS:IP as two four-digit hex values."); }
        });
        address.addActionListener(e -> go.doClick());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Code at")); controls.add(address); controls.add(go);
        addButton(controls, "Follow PC", () -> selected().followPc());
        addButton(controls, "Previous page", () -> shiftCode(-256));
        addButton(controls, "Next page", () -> shiftCode(256));
        panel.add(controls, BorderLayout.NORTH);
        codeTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        codeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        codeTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        codeTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        codeTable.getColumnModel().getColumn(2).setPreferredWidth(520);
        JScrollPane tableScroll = new JScrollPane(codeTable);
        tableScroll.setWheelScrollingEnabled(false);
        java.awt.event.MouseWheelListener wheel = e -> { shiftCode(e.getWheelRotation() * 16); e.consume(); };
        codeTable.addMouseWheelListener(wheel);
        tableScroll.addMouseWheelListener(wheel);
        panel.add(tableScroll, BorderLayout.CENTER);
        codeScroll = new JScrollBar(JScrollBar.VERTICAL, 0, 1, 0, 0x10000);
        codeScroll.setUnitIncrement(1);
        codeScroll.setBlockIncrement(256);
        codeScroll.addAdjustmentListener(e -> {
                if (!syncingCodeScroll && !e.getValueIsAdjusting()) selected().codeAddress(visibleCodeCs, e.getValue());
        });
        panel.add(codeScroll, BorderLayout.EAST);
        return panel;
    }

    private void shiftCode(int bytes) {
        codeScroll.setValue((int) Math.max(0, Math.min(0xFFFFL, (long) codeScroll.getValue() + bytes)));
    }

    private void chooseRom() {
        if (selected().inspectMachine().powered()) {
            showActionError(new IllegalStateException("Set PSU redstone low before replacing the ROM"));
            return;
        }
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        try {
            if (Files.size(path) != dervogel101.de.state.CPUMemory.ROM_SIZE)
                throw new IllegalArgumentException("ROM must be exactly 256 KiB");
            DebuggerBackend cpu = selected();
            cpu.pauseAndWait();
            cpu.loadRom(path);
        } catch (Exception error) { showActionError(error); }
    }

    private void update(DebuggerBackend.Snapshot snapshot) {
        frame.setTitle(remote ? "Minecraft mainframe debugger — " + snapshot.romName()
                : "8086 ROM launcher — CPU " + ((CpuSlot) cpuSelector.getSelectedItem()).id + " — " + snapshot.romName());
        if (remote) for (JButton button : remoteControls) button.setEnabled(selected().canControl());
        status.setText(snapshot.status() + (remote && !selected().canControl() ? " — read only" : ""));
        psuControl.setSelected(snapshot.machine().psuHigh());
        rate.setText(snapshot.instructionsPerSecond() + " instructions/s; total " + snapshot.totalInstructions());
        registers.setText(snapshot.registers());
        boolean loaded = !snapshot.romName().equals("No ROM loaded")
                && (!remote || selected().canEdit());
        memoryView.update(snapshot.memoryBase(), snapshot.memory(), loaded);
        ioView.update(snapshot.ioBase(), snapshot.io(), loaded);
        deviceView.update(snapshot.devices(), snapshot.images(), loaded);
        testPinControl.setSelected(snapshot.testHigh());
        nvramStatus.setText(remote ? "NVRAM: managed by Minecraft" : "NVRAM: " + (snapshot.images().nvramPath() == null
                ? "Unsaved" : snapshot.images().nvramPath().getFileName())
                + (snapshot.images().nvramDirty() ? " *" : ""));
        if (!terminalLog.getText().equals(snapshot.terminalLog())) {
            terminalLog.setText(snapshot.terminalLog());
            terminalLog.setCaretPosition(terminalLog.getDocument().getLength());
        }
        codeModel.update(snapshot.code());
        if (!codeAddressField.isFocusOwner() && !codeScroll.getValueIsAdjusting() && !snapshot.code().isEmpty()) {
            var first = snapshot.code().getFirst();
            visibleCodeCs = first.cs();
            syncingCodeScroll = true;
            codeScroll.setValue(first.ip());
            syncingCodeScroll = false;
            codeAddressField.setText("%04X:%04X".formatted(first.cs(), first.ip()));
        }
    }

    private void removeCpu() {
        if (slots.size() == 1) return;
        CpuSlot slot = (CpuSlot) cpuSelector.getSelectedItem();
        try {
            slot.controller.pauseAndWait();
            if (!confirmDirty(List.of(slot.controller), true, true)) return;
            slot.controller.close();
            slots.remove(slot);
            cpuSelector.removeItem(slot);
        } catch (Exception error) { showActionError(error); }
    }

    private void runAll() {
        for (CpuSlot slot : slots) slot.controller.run();
    }

    private void pauseAll() {
        for (CpuSlot slot : slots) slot.controller.pause();
    }

    private void powerCycleSelected() {
        selected().powerCycle();
    }

    private void powerCycleAll() {
        try {
            List<DebuggerBackend> cpus = slots.stream().map(slot -> slot.controller).toList();
            List<Boolean> restart = cpus.stream().map(DebuggerBackend::powerOffForCycle).toList();
            for (int i = 0; i < cpus.size(); i++) cpus.get(i).powerOnAfterCycle(restart.get(i));
        } catch (Exception error) { showActionError(error); }
    }

    private void closeLauncher() {
        if (remote) { selected().close(); frame.dispose(); return; }
        try {
            List<DebuggerBackend> cpus = slots.stream().map(slot -> slot.controller).toList();
            for (DebuggerBackend cpu : cpus) cpu.pauseAndWait();
            if (confirmDirty(cpus, true, true)) frame.dispose();
        } catch (Exception error) { showActionError(error); }
    }

    private void openNvram() {
        Path path = chooseFile(false);
        if (path == null) return;
        try {
            requireSize(path, dervogel101.de.state.CPUMemory.NVRAM_SIZE);
            DebuggerBackend cpu = selected();
            cpu.pauseAndWait();
            if (confirmDirty(List.of(cpu), true, false)) cpu.openNvram(path);
        } catch (Exception error) { showActionError(error); }
    }

    private void newNvram() {
        try {
            DebuggerBackend cpu = selected();
            cpu.pauseAndWait();
            if (confirmDirty(List.of(cpu), true, false)) cpu.newNvram();
        } catch (Exception error) { showActionError(error); }
    }

    private void saveNvram(boolean saveAs) {
        try {
            DebuggerBackend cpu = selected();
            Path path = saveAs ? null : cpu.inspectImages().nvramPath();
            if (path == null) path = chooseFile(true);
            if (path != null) cpu.saveNvram(path);
        } catch (Exception error) { showActionError(error); }
    }

    private void openStorage() {
        Path path = chooseFile(false);
        if (path == null) return;
        try {
            requireSize(path, dervogel101.de.state.IoStorageDevice.SIZE);
            DebuggerBackend cpu = selected();
            cpu.pauseAndWait();
            if (confirmDirty(List.of(cpu), false, true)) cpu.openStorage(path);
        } catch (Exception error) { showActionError(error); }
    }

    private void newStorage() {
        try {
            DebuggerBackend cpu = selected();
            cpu.pauseAndWait();
            if (confirmDirty(List.of(cpu), false, true)) cpu.newStorage();
        } catch (Exception error) { showActionError(error); }
    }

    private void saveStorage(boolean saveAs) {
        try {
            DebuggerBackend cpu = selected();
            DebuggerBackend.ImageState images = cpu.inspectImages();
            if (!images.storageConnected()) return;
            Path path = saveAs ? null : images.storagePath();
            if (path == null) path = chooseFile(true);
            if (path != null) cpu.saveStorage(path);
        } catch (Exception error) { showActionError(error); }
    }

    private void disconnectStorage() {
        try {
            DebuggerBackend cpu = selected();
            cpu.pauseAndWait();
            if (confirmDirty(List.of(cpu), false, true)) cpu.disconnectStorage();
        } catch (Exception error) { showActionError(error); }
    }

    private boolean confirmDirty(List<DebuggerBackend> cpus, boolean nvram, boolean storage) {
        return ImageTransitions.resolve(cpus, nvram, storage, new ImageTransitions.Prompt() {
            @Override public ImageTransitions.Decision decide(DebuggerBackend cpu, ImageTransitions.Kind kind) {
                int id = slots.stream().filter(slot -> slot.controller == cpu).findFirst().orElseThrow().id;
                int choice = dirtyChoice("CPU " + id + " " + (kind == ImageTransitions.Kind.NVRAM ? "NVRAM" : "I/O storage"));
                return switch (choice) {
                    case 0 -> ImageTransitions.Decision.SAVE;
                    case 1 -> ImageTransitions.Decision.DISCARD;
                    default -> ImageTransitions.Decision.CANCEL;
                };
            }

            @Override public Path choosePath(DebuggerBackend cpu, ImageTransitions.Kind kind) {
                return chooseFile(true);
            }
        });
    }

    private int dirtyChoice(String image) {
        return JOptionPane.showOptionDialog(frame, image + " has unsaved changes.", "Save changes?",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                new String[]{"Save", "Discard", "Cancel"}, "Save");
    }

    private Path chooseFile(boolean save) {
        JFileChooser chooser = new JFileChooser();
        int result = save ? chooser.showSaveDialog(frame) : chooser.showOpenDialog(frame);
        if (result != JFileChooser.APPROVE_OPTION) return null;
        Path path = chooser.getSelectedFile().toPath();
        if (save && Files.exists(path) && JOptionPane.showConfirmDialog(frame,
                "Replace " + path.getFileName() + "?", "Overwrite file", JOptionPane.YES_NO_OPTION)
                != JOptionPane.YES_OPTION) return null;
        return path;
    }

    private static void requireSize(Path path, int expected) throws java.io.IOException {
        if (Files.size(path) != expected) throw new IllegalArgumentException("Image must be exactly " + expected + " bytes");
    }

    private void showActionError(Exception error) {
        showError(error.toString());
        JOptionPane.showMessageDialog(frame, error.toString(), "Operation failed", JOptionPane.ERROR_MESSAGE);
    }

    private void showError(String details) {
        errors.append(details + "\n");
        errors.setCaretPosition(errors.getDocument().getLength());
        tabs.setSelectedIndex(tabs.indexOfTab("Errors"));
    }

    private void withCodeSelection(java.util.function.Consumer<Disassembler8086.Instruction> action) {
        int row = codeTable.getSelectedRow();
        if (row < 0) { JOptionPane.showMessageDialog(frame, "Select an instruction first."); return; }
        action.accept(codeModel.code.get(row));
    }

    private static void addButton(JPanel panel, String label, Runnable action) {
        JButton button = new JButton(label);
        button.addActionListener(e -> action.run());
        panel.add(button);
    }

    static final class CodeModel extends AbstractTableModel {
        private List<Disassembler8086.Instruction> code = List.of();
        void update(List<Disassembler8086.Instruction> code) {
            boolean sameRows = this.code.size() == code.size();
            for (int row = 0; sameRows && row < code.size(); row++) {
                var old = this.code.get(row);
                var next = code.get(row);
                sameRows = old.cs() == next.cs() && old.ip() == next.ip();
            }
            this.code = code;
            if (sameRows) {
                if (!code.isEmpty()) fireTableRowsUpdated(0, code.size() - 1);
            } else fireTableDataChanged();
        }
        @Override public int getRowCount() { return code.size(); }
        @Override public int getColumnCount() { return 3; }
        @Override public String getColumnName(int col) { return switch (col) {
            case 0 -> "CS:IP / physical"; case 1 -> "Bytes"; default -> "Instruction";
        }; }
        @Override public Object getValueAt(int row, int col) {
            var ins = code.get(row);
            if (col == 0) return "%04X:%04X / %05X".formatted(ins.cs(), ins.ip(), ins.physicalAddress());
            if (col == 1) return java.util.HexFormat.ofDelimiter(" ").formatHex(ins.bytes());
            return ins.assembly() + (ins.unsupported() ? "  [unsupported]" : "");
        }
    }

    private void configureHardware() {
        try {
            DebuggerBackend cpu = selected();
            var machine = cpu.inspectMachine();
            if (machine.powered()) throw new IllegalStateException("Set PSU redstone low before changing hardware");
            JPanel panel = new JPanel(new GridLayout(0, 2, 8, 4));
            var fields = new EnumMap<Hardware, JSpinner>(Hardware.class);
            for (Hardware part : Hardware.values()) {
                panel.add(new JLabel(part.displayName));
                JSpinner count = new JSpinner(new SpinnerNumberModel(machine.hardware().count(part), part.required ? 1 : 0, part.maximum, 1));
                fields.put(part, count); panel.add(count);
            }
            JComboBox<String> crystal = new JComboBox<>(new String[]{"No shard", "Tier 1", "Tier 2", "Tier 3"});
            crystal.setSelectedIndex(machine.shardTier());
            JTextField memoryId = new JTextField(machine.memoryId(), 32);
            panel.add(new JLabel("Speed shard")); panel.add(crystal);
            panel.add(new JLabel("Shared-memory ID")); panel.add(memoryId);
            if (JOptionPane.showConfirmDialog(frame, panel, "CPU hardware", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            var counts = new EnumMap<Hardware, Integer>(Hardware.class);
            fields.forEach((part, count) -> counts.put(part, (Integer) count.getValue()));
            int tier = crystal.getSelectedIndex();
            var profile = new HardwareProfile(counts);
            profile.validationError().ifPresent(error -> { throw new IllegalArgumentException(error); });
            SharedMemoryGroups.normalize(memoryId.getText());
            ShardSpeed speed = shardSpeeds[tier == 0 ? 2 : tier - 1];
            cpu.configure(speed.stepsPerTick(), configuredTicks, speed.batchesPerTick());
            cpu.configureMachine(profile, tier, memoryId.getText());
        } catch (Exception error) { showActionError(error); }
    }

    private void saveSession() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        List<Boolean> resume = new ArrayList<>();
        try {
            for (CpuSlot slot : slots) resume.add(slot.controller.suspendForSession());
            List<LauncherSession.Cpu> saved = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                CpuSlot slot = slots.get(i);
                saved.add(new LauncherSession.Cpu(slot.id, slot.controller.saveSessionState(resume.get(i))));
            }
            LauncherSession.save(chooser.getSelectedFile().toPath(), new LauncherSession.State(
                    worldClock.ticks(), configuredTicks, nextCpuId, sharedMemory.snapshot(), saved, List.of(shardSpeeds)));
        } catch (Exception error) { showActionError(error); }
        finally {
            for (int i = 0; i < resume.size(); i++) if (resume.get(i)) slots.get(i).controller.run();
        }
    }

    private void openSession() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        List<CpuSlot> prepared = new ArrayList<>();
        List<Boolean> resume = new ArrayList<>();
        try {
            var saved = LauncherSession.load(chooser.getSelectedFile().toPath());
            var nextGroups = new SharedMemoryGroups(); nextGroups.restore(saved.regions());
            for (var record : saved.cpus()) {
                CpuSlot slot = new CpuSlot(record.id());
                slot.controller = new LauncherController(snapshot -> updateSlot(slot, snapshot), this::showError,
                        nextGroups, slot.id, worldClock::ticks);
                prepared.add(slot);
                resume.add(slot.controller.restoreSessionState(record.state()));
            }
            List<DebuggerBackend> old = slots.stream().map(slot -> slot.controller).toList();
            for (var cpu : old) cpu.pauseAndWait();
            if (!confirmDirty(old, true, true)) return;
            for (var cpu : old) cpu.close();
            slots.clear(); cpuSelector.removeAllItems(); sharedMemory = nextGroups;
            configuredTicks = saved.tickRate(); worldClock.setRate(configuredTicks); worldClock.restoreTicks(saved.ticks());
            shardSpeeds = saved.shards().toArray(ShardSpeed[]::new); refreshPacingControls();
            nextCpuId = saved.nextCpuId(); slots.addAll(prepared);
            for (var slot : slots) cpuSelector.addItem(slot);
            cpuSelector.setSelectedIndex(0);
            prepared.clear();
            for (int i = 0; i < slots.size(); i++) if (resume.get(i)) slots.get(i).controller.run();
        } catch (Exception error) { showActionError(error); }
        finally { for (var slot : prepared) slot.controller.close(); }
    }

    private void refreshPacingControls() {
        syncingPacing = true;
        try {
            ShardSpeed speed = shardSpeeds[shardConfiguration.getSelectedIndex()];
            budget.setValue(speed.stepsPerTick()); batches.setValue(speed.batchesPerTick()); ticks.setValue(configuredTicks);
        } finally { syncingPacing = false; }
    }

    private void applyPacing() {
        if (syncingPacing) return;
        syncingPacing = true;
        try {
            int steps = (Integer) budget.getValue();
            if ((Integer) batches.getValue() > steps) batches.setValue(steps);
            shardSpeeds[shardConfiguration.getSelectedIndex()] = new ShardSpeed(steps, (Integer) batches.getValue());
            configuredTicks = (Integer) ticks.getValue(); worldClock.setRate(configuredTicks);
            for (CpuSlot slot : slots) {
                int tier = slot.controller.inspectMachine().shardTier();
                ShardSpeed speed = shardSpeeds[tier == 0 ? 2 : tier - 1];
                slot.controller.configure(speed.stepsPerTick(), configuredTicks, speed.batchesPerTick());
            }
        } finally { syncingPacing = false; }
    }
}
