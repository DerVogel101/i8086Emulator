package dervogel101.de.launcher;

import dervogel101.de.i86.runtime.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/** The connected debugger exposes bus operations only, never host file paths. */
final class PunchcardPanel extends JPanel {
    private final Supplier<DebuggerBackend> backend;
    private final JComboBox<String> type = new JComboBox<>(new String[]{"Reader","Writer"});
    private final JSpinner slot = new JSpinner(new SpinnerNumberModel(0,0,15,1));
    private final JTextField row = new JTextField("00 ".repeat(31)+"00",66);
    private final JTextField name = new JTextField(32), description = new JTextField(32);
    private final JLabel status = new JLabel("No punchcard controller");
    private final java.util.List<JButton> commands = new ArrayList<>();
    PunchcardPanel(Supplier<DebuggerBackend> backend,boolean remote) {
        super(new BorderLayout(6,6)); this.backend = backend;
        JPanel select = new JPanel(); select.add(type); select.add(slot); select.add(status); add(select,BorderLayout.NORTH);
        JPanel controls = new JPanel(new GridLayout(0,1,4,4));
        JPanel buffer = new JPanel(); buffer.add(new JLabel("Row bytes (hex)")); buffer.add(row); controls.add(buffer);
        JPanel header = new JPanel(); header.add(new JLabel("Name (32 Windows-1252 bytes)")); header.add(name); controls.add(header);
        JPanel details = new JPanel(); details.add(new JLabel("Description (32 Windows-1252 bytes)")); details.add(description);
        JButton readDescription = button("Read description",() -> command(PunchcardDevice.READ_NAME,1));
        JButton writeDescription = button("Write description",() -> command(PunchcardDevice.WRITE_NAME,1));
        commands.add(readDescription); commands.add(writeDescription);
        details.add(readDescription); details.add(writeDescription); controls.add(details);
        JPanel operations = new JPanel();
        for (var entry : new Object[][]{{"Read row",1},{"Write row",2},{"Read name",4},{"Write name",8},{"Next card",16},{"Eject",32},{"ACK result",64},{"Clear error",128}}) {
            int command = (int)entry[1]; JButton button = button((String)entry[0],() -> command(command)); commands.add(button); operations.add(button);
        }
        controls.add(operations);
        if (!remote) {
            JPanel files = new JPanel();
            files.add(button("New blank",() -> backend.get().insertPunchcard(index(),PunchcardDeck.blank())));
            files.add(button("Import .pdeck",() -> { Path path = choose(false); if (path != null) { try { backend.get().insertPunchcard(index(),PunchcardDeck.load(path)); } catch (java.io.IOException e) { throw new IllegalArgumentException(e.getMessage(),e); } } }));
            files.add(button("Export active",() -> export(false))); files.add(button("Export output",() -> export(true)));
            files.add(button("Collect/remove",() -> { var b = backend.get(); boolean output = b.punchcardMedia(index(),true) != null; if (confirmMedia(this,b,index(),output)) b.collectPunchcard(index()); }));
            controls.add(files);
        }
        add(controls,BorderLayout.CENTER);
        type.addActionListener(e -> select()); slot.addChangeListener(e -> select());
    }
    private int index() { return (type.getSelectedIndex()==1 ? 16 : 0)+(int)slot.getValue(); }
    private void select() { backend.get().selectPunchcard(index()); }
    private JButton button(String label,Runnable action) {
        JButton button = new JButton(label); button.addActionListener(e -> { try { action.run(); } catch (RuntimeException failure) { JOptionPane.showMessageDialog(this,failure.getMessage(),"Punchcard",JOptionPane.ERROR_MESSAGE); } }); return button;
    }
    private void command(int operation) { command(operation,0); }
    private void command(int operation,int metadata) {
        String hex = row.getText();
        String textValue = (metadata == 0 ? name : description).getText();
        if (operation == PunchcardDevice.WRITE_NAME) {
            if (!PunchcardDeck.CHARSET.newEncoder().canEncode(textValue)) throw new IllegalArgumentException("Text requires Windows-1252 characters");
            byte[] text = textValue.getBytes(PunchcardDeck.CHARSET);
            if (text.length > 32) throw new IllegalArgumentException("Text exceeds 32 bytes");
            hex = HexFormat.of().formatHex(Arrays.copyOf(text,32));
        }
        backend.get().punchcardCommand(index(),operation,hex,metadata);
    }
    void update(DebuggerBackend.Snapshot snapshot) {
        var d = snapshot.devices(); if (d.punchcardIndex() != index()) return;
        byte[] p = d.punchcard(); int count = word(p,36), remaining = word(p,38);
        status.setText(d.punchcardName()+" · Flags %02X · error %d · %d cards / %d bytes · card %d row %d · remaining %d".formatted(p[33]&255,p[35]&255,count,count*256,word(p,40),p[42]&255,remaining));
        if (!row.hasFocus()) row.setText(HexFormat.ofDelimiter(" ").formatHex(Arrays.copyOf(p,32)));
        for (var button : commands) button.setEnabled(snapshot.machine().powered() && !snapshot.running() && backend.get().canEdit());
    }
    private static int word(byte[] p,int offset) { return (p[offset]&255) | (p[offset+1]&255)<<8; }
    private void export(boolean output) { Path path = choose(true); if (path != null) backend.get().savePunchcard(index(),output,path); }
    private Path choose(boolean save) { return chooseFile(this,save); }
    private static Path chooseFile(Component parent,boolean save) {
        JFileChooser chooser = new JFileChooser(); chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Punchcard decks (*.pdeck)","pdeck"));
        if ((save ? chooser.showSaveDialog(parent) : chooser.showOpenDialog(parent)) != JFileChooser.APPROVE_OPTION) return null;
        Path path = chooser.getSelectedFile().toPath();
        if (save && !path.toString().endsWith(".pdeck")) path = path.resolveSibling(path.getFileName()+".pdeck");
        if (save && java.nio.file.Files.exists(path) && JOptionPane.showConfirmDialog(parent,"Replace " + path.getFileName() + "?","Export deck",JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return null;
        return path;
    }
    static boolean confirmMedia(Component parent,DebuggerBackend b,int index,boolean output) {
        if (!b.punchcardDirty(index,output)) return true;
        int choice = JOptionPane.showOptionDialog(parent,"Save the unsaved punchcard deck?","Unsaved punchcards",JOptionPane.DEFAULT_OPTION,JOptionPane.WARNING_MESSAGE,null,new String[]{"Save","Discard","Cancel"},"Save");
        if (choice == 0) { Path path = chooseFile(parent,true); if (path == null) return false; b.savePunchcard(index,output,path); return true; }
        if (choice == 1) { b.discardPunchcardChanges(index,output); return true; }
        return false;
    }
}
