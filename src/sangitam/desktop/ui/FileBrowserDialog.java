package sangitam.desktop.ui;

import javax.swing.*;
import sangitam.desktop.storage.AppPreferences;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Small Swing file browser for choosing music and folders without a native file chooser. */
public final class FileBrowserDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private static final List<String> AUDIO_EXTENSIONS = List.of("mp3", "wav", "flac", "ogg", "oga", "aac", "m4a", "aif", "aiff", "wma");

    private record Entry(Path path, boolean folder) { }

    private final JTextField location = new JTextField();
    private final JTextField saveName;
    private final JComboBox<Path> drives = new JComboBox<>();
    private final JButton up = UiFactory.button("Up", AppIcons.of(AppIcons.Kind.FOLDER, 15), false);
    private final JLabel status = UiFactory.label(" ", Theme.SMALL, Theme.TEXT_MUTED);
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> entries = new JList<>(model);
    private JButton choose;
    private boolean updatingDrives;
    private Path directory;
    private Path resultPath;
    private final boolean directoryOnly;
    private final boolean saveMode;
    private final String extension;
    private volatile SwingWorker<List<Entry>, Void> listing;
    private List<Path> result = List.of();

    private FileBrowserDialog(Window owner, String title, boolean directoryOnly, boolean saveMode,
                              String defaultName, String extension) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.directoryOnly = directoryOnly;
        this.saveMode = saveMode;
        this.extension = extension == null ? "" : extension;
        this.saveName = saveMode ? new JTextField(defaultName, 28) : null;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(700, 470));
        setSize(820, 560);
        setLocationRelativeTo(owner);
        setContentPane(buildContent());
        loadLocations();
    }

    public static List<Path> choose(Window owner) {
        FileBrowserDialog dialog = new FileBrowserDialog(owner, "Choose music", false, false, null, null);
        dialog.setVisible(true);
        return dialog.result;
    }

    public static Path chooseDirectory(Window owner, String title) {
        FileBrowserDialog dialog = new FileBrowserDialog(owner, title, true, false, null, null);
        dialog.setVisible(true);
        return dialog.resultPath;
    }

    public static Path chooseSaveFile(Window owner, String title, String defaultName, String extension) {
        FileBrowserDialog dialog = new FileBrowserDialog(owner, title, true, true, defaultName, extension);
        dialog.setVisible(true);
        return dialog.resultPath;
    }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBackground(Theme.BLACK);
        root.setBorder(Theme.padding(18, 18, 18, 18));
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        navigation.setOpaque(false);
        JLabel driveLabel = new JLabel("Drive");
        driveLabel.setLabelFor(drives);
        drives.setToolTipText("Choose a drive or filesystem root");
        drives.addActionListener(event -> {
            if (!updatingDrives && drives.getSelectedItem() instanceof Path rootPath) navigate(rootPath);
        });
        up.addActionListener(event -> navigate(directory.getParent()));
        up.setEnabled(false);
        navigation.add(driveLabel);
        navigation.add(drives);
        navigation.add(up);
        top.add(navigation, BorderLayout.WEST);
        location.getAccessibleContext().setAccessibleName("Folder path");
        location.setToolTipText("Enter a full folder path, including a network share, and press Enter");
        location.addActionListener(event -> openLocation());
        location.setBackground(Theme.SURFACE);
        location.setForeground(Theme.TEXT);
        location.setCaretColor(Theme.TEXT);
        top.add(location, BorderLayout.CENTER);
        JButton go = UiFactory.button("Go", null, false);
        go.addActionListener(event -> openLocation());
        top.add(go, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        entries.setSelectionMode(directoryOnly ? ListSelectionModel.SINGLE_SELECTION
            : ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        entries.setFixedCellHeight(36);
        entries.setBackground(Theme.SURFACE);
        entries.setForeground(Theme.TEXT);
        entries.setSelectionBackground(Theme.BLUE.darker());
        entries.setCellRenderer(new PathRenderer());
        entries.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                int index = entries.locationToIndex(event.getPoint());
                if (event.getClickCount() == 2 && index >= 0
                    && entries.getCellBounds(index, index).contains(event.getPoint())) openSelection();
            }
        });
        root.add(new JScrollPane(entries), BorderLayout.CENTER);

        String hintText = saveMode ? "Choose the destination folder, then enter a file name."
            : directoryOnly ? "Choose a folder for the export."
            : "Select audio files or folders. With no selection, use the current folder.";
        JLabel hint = UiFactory.label(hintText, Theme.SMALL, Theme.TEXT_MUTED);
        JButton cancel = UiFactory.button("Cancel", null, false);
        choose = UiFactory.button("", AppIcons.of(AppIcons.Kind.NEXT, 15, Theme.BLACK), true);
        entries.addListSelectionListener(event -> updateChoose());
        entries.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openEntry");
        entries.getActionMap().put("openEntry", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { openSelection(); }
        });
        updateChoose();
        cancel.addActionListener(event -> dispose());
        choose.addActionListener(event -> acceptSelection());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(choose);
        JPanel bottom = new JPanel(new BorderLayout(8, 10));
        bottom.setOpaque(false);
        JPanel messages = new JPanel(new GridLayout(2, 1, 0, 4));
        messages.setOpaque(false);
        messages.add(hint);
        messages.add(status);
        bottom.add(messages, BorderLayout.CENTER);
        if (saveMode) {
            JPanel saveRow = new JPanel(new BorderLayout(8, 0));
            saveRow.setOpaque(false);
            saveRow.add(UiFactory.label("File name", Theme.SMALL, Theme.TEXT), BorderLayout.WEST);
            saveRow.add(saveName, BorderLayout.CENTER);
            bottom.add(saveRow, BorderLayout.NORTH);
        }
        bottom.add(buttons, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(choose);
        return root;
    }

    private void loadLocations() {
        new SwingWorker<List<Path>, Void>() {
            @Override protected List<Path> doInBackground() {
                return Arrays.stream(File.listRoots()).map(File::toPath).toList();
            }

            @Override protected void done() {
                if (!isDisplayable()) return;
                try {
                    updatingDrives = true;
                    get().forEach(drives::addItem);
                    selectCurrentDrive();
                } catch (Exception error) {
                    status.setText("Could not list drives. Enter a folder path to continue.");
                } finally {
                    updatingDrives = false;
                }
            }
        }.execute();
        navigate(null);
    }

    private void openLocation() {
        try {
            Path path = Path.of(location.getText().strip());
            if (!path.isAbsolute()) throw new InvalidPathException(path.toString(), "Expected a full path");
            navigate(path);
        } catch (InvalidPathException error) {
            status.setText("Enter a full folder path, such as D:\\Music or a network share.");
        }
    }

    private void navigate(Path path) {
        if (listing != null) listing.cancel(true);
        up.setEnabled(false);
        entries.setEnabled(false);
        status.setText("Loading folder…");
        if (path != null) location.setText(path.toString());
        listing = new SwingWorker<>() {
            private Path target;

            @Override protected List<Entry> doInBackground() throws IOException {
                target = path == null ? null : path.toAbsolutePath().normalize();
                if (target == null) {
                    target = AppPreferences.lastMediaDirectory();
                    try {
                        return listAndRemember();
                    } catch (IOException | SecurityException unavailable) {
                        target = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
                    }
                }
                return listAndRemember();
            }

            private List<Entry> listAndRemember() throws IOException {
                List<Entry> children = readFolder(target);
                if (!isCancelled() && listing == this) AppPreferences.setLastMediaDirectory(target);
                return children;
            }

            @Override protected void done() {
                if (isCancelled() || listing != this || !isDisplayable()) return;
                listing = null;
                try {
                    List<Entry> children = get();
                    directory = target;
                    location.setText(directory.toString());
                    model.clear();
                    model.addAll(children);
                    status.setText(children.isEmpty() ? "No matching files or folders here." : " ");
                } catch (Exception error) {
                    status.setText("Could not open this folder. Check the path, drive connection, and permissions.");
                    if (directory != null) location.setText(directory.toString());
                }
                updatingDrives = true;
                selectCurrentDrive();
                updatingDrives = false;
                up.setEnabled(directory != null && directory.getParent() != null);
                entries.setEnabled(directory != null);
                updateChoose();
            }
        };
        updateChoose();
        listing.execute();
    }

    private List<Entry> readFolder(Path path) throws IOException {
        try (var children = Files.list(path)) {
            return children.map(child -> new Entry(child, Files.isDirectory(child)))
                .filter(entry -> entry.folder() || (!directoryOnly && isAudio(entry.path())))
                .sorted(Comparator.comparing((Entry entry) -> !entry.folder())
                    .thenComparing(entry -> entry.path().getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        }
    }

    private void selectCurrentDrive() {
        Path root = directory == null ? null : directory.getRoot();
        drives.setSelectedItem(null);
        drives.setSelectedItem(root);
    }

    private void updateChoose() {
        choose.setText(saveMode ? "Save here" : entries.isSelectionEmpty() ? "Use current folder"
            : directoryOnly ? "Use selected folder" : "Use selected items");
        choose.setEnabled(directory != null && listing == null);
    }

    private void openSelection() {
        Entry choice = entries.getSelectedValue();
        if (choice == null || listing != null) return;
        if (choice.folder()) navigate(choice.path());
        else acceptSelection();
    }

    private void acceptSelection() {
        if (directory == null || listing != null) return;
        if (saveMode) {
            String name = saveName.getText().strip();
            if (name.isBlank()) { Toolkit.getDefaultToolkit().beep(); return; }
            Path filename;
            try { filename = Path.of(name); }
            catch (RuntimeException invalid) { filename = null; }
            if (filename == null || filename.getRoot() != null || filename.getNameCount() != 1
                || name.equals(".") || name.equals("..")) {
                JOptionPane.showMessageDialog(this, "Enter a file name without a folder path.",
                    "Invalid file name", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!extension.isBlank() && !name.toLowerCase(Locale.ROOT).endsWith("." + extension.toLowerCase(Locale.ROOT)))
                name += "." + extension;
            resultPath = directory.resolve(name);
        } else if (directoryOnly) {
            Entry selected = entries.getSelectedValue();
            resultPath = selected == null ? directory : selected.path();
        } else {
            List<Path> choices = entries.getSelectedValuesList().stream().map(Entry::path).toList();
            result = choices.isEmpty() ? List.of(directory) : choices;
        }
        dispose();
    }

    private boolean isAudio(Path path) {
        if (!Files.isRegularFile(path)) return false;
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    @Override public void dispose() {
        if (listing != null) listing.cancel(true);
        super.dispose();
    }

    private static final class PathRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            Entry entry = (Entry) value;
            Path path = entry.path();
            boolean folder = entry.folder();
            setText(path.getFileName() == null ? path.toString() : path.getFileName().toString());
            setIcon(AppIcons.of(folder ? AppIcons.Kind.FOLDER : AppIcons.Kind.MUSIC, 17,
                folder ? Theme.BLUE : Theme.TEXT_MUTED));
            setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            return this;
        }
    }
}
