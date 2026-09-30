package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.function.Consumer;

/**
 * File-and-folder import workflow.
 * IMPORTANT: JFileChooser uses FILES_AND_DIRECTORIES so selecting a directory is a valid path to Continue.
 */
public final class ImportMediaDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final JTextField selectedPath = new JTextField();
    private final transient Consumer<String> notify;
    private File[] selectedItems = new File[0];

    public ImportMediaDialog(Window owner, Consumer<String> notify) {
        super(owner, "Add music", ModalityType.APPLICATION_MODAL);
        this.notify = notify;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(650, 530));
        setSize(720, 590);
        setLocationRelativeTo(owner);
        setContentPane(buildContent());
    }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 20));
        root.setBackground(Theme.BLACK);
        root.setBorder(Theme.padding(24, 26, 22, 26));

        JPanel title = new JPanel();
        title.setOpaque(false);
        title.setLayout(new BoxLayout(title, BoxLayout.Y_AXIS));
        title.add(UiFactory.label("Add files or a folder", Theme.TITLE, Theme.TEXT));
        title.add(Box.createVerticalStrut(5));
        title.add(UiFactory.label("Choose audio files, or select a folder to scan and continue.", Theme.BODY, Theme.TEXT_MUTED));
        root.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Theme.SURFACE);
        form.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 16), Theme.padding(20, 20, 20, 20)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.weightx = 0; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 12, 12);
        form.add(UiFactory.label("Source", Theme.BODY_BOLD, Theme.TEXT), c);

        selectedPath.setEditable(false);
        selectedPath.setText("No file or folder selected");
        selectedPath.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 12), Theme.padding(10, 12, 10, 12)));
        c.gridx = 1; c.weightx = 1;
        form.add(selectedPath, c);
        JButton browse = UiFactory.button("Browse…", AppIcons.of(AppIcons.Kind.FOLDER, 15), false);
        browse.addActionListener(event -> browse());
        c.gridx = 2; c.weightx = 0; c.insets = new Insets(0, 0, 12, 0);
        form.add(browse, c);

        addFormLabel(form, "Add to", 1);
        JComboBox<String> destination = new JComboBox<>(new String[] {"Main library", "Focus flow", "Recently added", "New playlist…"});
        addFormControl(form, destination, 1);

        addFormLabel(form, "Title", 2);
        addFormControl(form, new JTextField(), 2);

        addFormLabel(form, "Artist", 3);
        addFormControl(form, new JTextField(), 3);

        addFormLabel(form, "Genre", 4);
        addFormControl(form, new JComboBox<>(new String[] {"Auto-detect", "Electronic", "Pop", "Rock", "Classical", "Other"}), 4);

        addFormLabel(form, "Rating", 5);
        JSpinner rating = new JSpinner(new SpinnerNumberModel(0, 0, 5, 1));
        addFormControl(form, rating, 5);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        options.setOpaque(false);
        options.add(new JCheckBox("Include subfolders", true));
        options.add(new JCheckBox("Read embedded metadata", true));
        c.gridx = 1; c.gridy = 6; c.gridwidth = 2; c.weightx = 1; c.insets = new Insets(4, 0, 0, 0);
        form.add(options, c);
        root.add(form, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        JButton cancel = UiFactory.button("Cancel", null, false);
        cancel.addActionListener(event -> dispose());
        JButton continueButton = UiFactory.button("Continue", AppIcons.of(AppIcons.Kind.NEXT, 15, Theme.BLACK), true);
        continueButton.addActionListener(event -> continueImport());
        buttons.add(cancel); buttons.add(continueButton);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private void addFormLabel(JPanel panel, String text, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 12, 12);
        panel.add(UiFactory.label(text, Theme.BODY_BOLD, Theme.TEXT), c);
    }

    private void addFormControl(JPanel panel, JComponent component, int row) {
        if (component instanceof JTextField) {
            component.setBorder(BorderFactory.createCompoundBorder(
                UiFactory.roundedBorder(Theme.DIVIDER, 12), Theme.padding(9, 11, 9, 11)));
        }
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1; c.gridy = row; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 12, 0);
        panel.add(component, c);
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose audio files or a folder");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedItems = chooser.getSelectedFiles();
            if (selectedItems.length == 0 && chooser.getSelectedFile() != null) {
                selectedItems = new File[] {chooser.getSelectedFile()};
            }
            if (selectedItems.length == 1) selectedPath.setText(selectedItems[0].getAbsolutePath());
            else if (selectedItems.length > 1) selectedPath.setText(selectedItems.length + " items selected");
        }
    }

    private void continueImport() {
        if (selectedItems.length == 0) {
            notify.accept("Choose at least one file or folder first");
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        long folders = java.util.Arrays.stream(selectedItems).filter(File::isDirectory).count();
        notify.accept("Continue pressed: " + selectedItems.length + " item(s), " + folders + " folder(s)");
        dispose();
    }
}
