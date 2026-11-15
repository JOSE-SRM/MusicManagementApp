package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.library.ManagedLibraryCopy;
import sangitam.desktop.library.ManagedLibraryCopy.Policy;
import sangitam.desktop.storage.LibraryStore;
import sangitam.desktop.storage.ManagedLibraryStore;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Shows the destination and storage policy before importing managed files. */
final class ManagedLibraryDialog extends JDialog {
    private final LibraryService library;
    private final LibraryStore store;
    private final Consumer<String> notify;
    private final ManagedLibraryCopy copies = new ManagedLibraryCopy();
    private final JTextField destination = new JTextField(42);
    private final JTextField source = new JTextField(42);
    private final JCheckBox recursive = new JCheckBox("Include subfolders", true);
    private final JCheckBox genre = new JCheckBox("Genre", true);
    private final JCheckBox period = new JCheckBox("Period", true);
    private final JCheckBox composer = new JCheckBox("Composer", true);
    private final JCheckBox instrument = new JCheckBox("Instrument", true);
    private final JCheckBox work = new JCheckBox("Work", true);
    private final JCheckBox recording = new JCheckBox("Recording or album", true);
    private final JCheckBox writeTags = new JCheckBox("Write verified metadata to managed audio files");
    private final JComboBox<ManagedLibraryCopy.Layout> layout = new JComboBox<>(ManagedLibraryCopy.Layout.values());
    private final JComboBox<Policy> policy = new JComboBox<>(Policy.values());
    private final JLabel policyHelp = UiFactory.label("", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel status = UiFactory.label("Choose a managed folder and source files.", Theme.SMALL, Theme.TEXT_MUTED);
    private final JButton chooseRoot = UiFactory.button("Choose library folder…", null, false);
    private final JButton chooseSource = UiFactory.button("Choose source files…", null, false);
    private final JButton preview = UiFactory.button("Preview import", null, false);
    private final JButton apply = UiFactory.button("Copy and add to library", null, true);
    private final JButton cancel = UiFactory.button("Cancel operation", null, false);
    private final JButton details = UiFactory.button("Details…", null, false);
    private final Entries entries = new Entries();
    private List<Path> selections = List.of();
    private ManagedLibraryCopy.Plan plan;
    private SwingWorker<ManagedLibraryCopy.Plan, Void> planning;
    private List<String> notes = List.of();
    private boolean applying;
    private boolean configured;
    private SwingWorker<?, ?> activation;

    ManagedLibraryDialog(Window owner, LibraryService library, LibraryStore store, Consumer<String> notify) {
        super(owner, "Import into managed library", ModalityType.MODELESS);
        this.library = library;
        this.store = store;
        this.notify = notify;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(content());
        setSize(930, 590);
        setMinimumSize(new Dimension(750, 460));
        setLocationRelativeTo(owner);
        updateButtons();
        new SwingWorker<ManagedLibraryCopy.Settings, Void>() {
            @Override protected ManagedLibraryCopy.Settings doInBackground() throws Exception {
                store.initialize();
                return new ManagedLibraryStore(store).settings();
            }
            @Override protected void done() {
                if (!isDisplayable() || !destination.getText().isBlank()) return;
                try {
                    var saved = get();
                    if (saved == null) return;
                    configured = true;
                    destination.setText(saved.root().toString());
                    policy.setSelectedItem(saved.policy());
                    layout.setSelectedItem(saved.options().layout());
                    genre.setSelected(saved.options().genre());
                    period.setSelected(saved.options().period());
                    composer.setSelected(saved.options().composer());
                    instrument.setSelected(saved.options().instrument());
                    work.setSelected(saved.options().work());
                    recording.setSelected(saved.options().recording());
                    writeTags.setSelected(saved.writeTags());
                    updateButtons();
                } catch (Exception ignored) { status.setText("Choose the managed library folder."); }
            }
        }.execute();
    }

    private JPanel content() {
        var rootPanel = new JPanel(new BorderLayout(0, 10));
        rootPanel.setBorder(Theme.padding(14, 16, 14, 16));
        var settings = new JPanel();
        settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));
        destination.getAccessibleContext().setAccessibleName("Managed library folder");
        destination.setToolTipText("Enter an absolute folder path. The final folder can be created during import.");
        destination.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { invalidatePreview(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { invalidatePreview(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { invalidatePreview(); }
        });
        source.setEditable(false);
        source.getAccessibleContext().setAccessibleName("Selected source files");
        settings.add(row(chooseRoot, destination));
        settings.add(row(chooseSource, source, recursive));
        var policyLabel = UiFactory.label("Import policy", Theme.BODY, Theme.TEXT);
        policyLabel.setLabelFor(policy);
        var layoutLabel = UiFactory.label("Classical layout", Theme.BODY, Theme.TEXT);
        layoutLabel.setLabelFor(layout);
        settings.add(row(policyLabel, policy, layoutLabel, layout));
        settings.add(row(genre, period, composer, instrument, work, recording));
        settings.add(row(writeTags));
        settings.add(row(policyHelp));
        rootPanel.add(settings, BorderLayout.NORTH);

        var table = new JTable(entries);
        table.getAccessibleContext().setAccessibleName("Source and destination preview");
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(310);
        table.getColumnModel().getColumn(1).setPreferredWidth(365);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);
        rootPanel.add(new PixelScrollPane(table), BorderLayout.CENTER);

        var footer = new JPanel(new BorderLayout(0, 5));
        footer.add(status, BorderLayout.NORTH);
        var close = UiFactory.button("Close", null, false);
        footer.add(row(preview, apply, cancel, details, close), BorderLayout.SOUTH);
        rootPanel.add(footer, BorderLayout.SOUTH);

        chooseRoot.addActionListener(event -> {
            Path selected = FileBrowserDialog.chooseDirectory(this, "Choose managed library folder");
            if (selected != null) destination.setText(selected.toString());
        });
        chooseSource.addActionListener(event -> {
            var menu = new JPopupMenu();
            var files = new JMenuItem("Audio files…");
            files.addActionListener(ignored -> selectSources(FileBrowserDialog.choose(this)));
            var folders = new JMenuItem("Folders…");
            folders.addActionListener(ignored -> selectSources(FileBrowserDialog.chooseFolders(this, "Choose source folders")));
            menu.add(files);
            menu.add(folders);
            menu.show(chooseSource, 0, chooseSource.getHeight());
        });
        for (var check : List.of(recursive, genre, period, composer, instrument, work, recording, writeTags))
            check.addActionListener(event -> invalidatePreview());
        layout.addActionListener(event -> invalidatePreview());
        policy.addActionListener(event -> invalidatePreview());
        preview.addActionListener(event -> plan());
        apply.addActionListener(event -> applyPlan());
        cancel.addActionListener(event -> {
            if (planning != null) { planning.cancel(true); planning = null; updateButtons(); }
            if (activation != null) activation.cancel(true);
            status.setText(applying ? "Stopping after the current operation…" : "Preview cancelled.");
        });
        details.addActionListener(event -> showDetails());
        close.addActionListener(event -> dispose());
        return rootPanel;
    }

    private void selectSources(List<Path> selected) {
        if (selected.isEmpty()) return;
        selections = selected;
        source.setText(selected.size() == 1 ? selected.getFirst().toString() : selected.size() + " sources selected");
        invalidatePreview();
    }

    private void invalidatePreview() {
        plan = null;
        entries.set(List.of(), selectedPolicy());
        setNotes(List.of());
        status.setText("Preview shows the destination and any conflicts before import.");
        updateButtons();
    }

    private void updateButtons() {
        boolean busy = planning != null || applying;
        boolean linking = selectedPolicy() == Policy.HARD_LINK;
        policyHelp.setText(linking
            ? "Hard links require the same drive. Both paths share one file, so in-place edits affect both."
            : "Copy keeps the originals and adds independent files to your catalog.");
        apply.setText(configured ? "Save managed settings" : "Activate managed library");
        chooseRoot.setEnabled(!busy && !configured);
        chooseSource.setEnabled(!busy);
        destination.setEnabled(!busy && !configured);
        preview.setEnabled(!busy && !destination.getText().isBlank());
        apply.setEnabled(!busy && plan != null && plan.bytes() <= plan.available());
        cancel.setEnabled(busy);
        details.setEnabled(!notes.isEmpty());
        layout.setEnabled(!busy);
        policy.setEnabled(!busy && !configured);
        writeTags.setEnabled(!busy && !linking);
        writeTags.setToolTipText("Updates supported MP3 and FLAC copies. Hard-linked source files keep their tags.");
        for (var check : List.of(recursive, genre, period, composer, instrument, work, recording)) check.setEnabled(!busy);
    }

    private void plan() {
        invalidatePreview();
        var options = selectedOptions();
        Path target;
        try {
            target = Path.of(destination.getText().strip());
            if (!target.isAbsolute()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            status.setText("Enter a full path for the managed library folder.");
            return;
        }
        var sources = new java.util.LinkedHashSet<>(selections);
        if (!configured) library.catalog().tracks().stream().map(sangitam.desktop.model.Track::path)
            .filter(path -> !path.startsWith(target)).forEach(sources::add);
        boolean includeSubfolders = recursive.isSelected();
        Policy chosen = selectedPolicy();
        var catalog = library.catalog().byPath();
        planning = new SwingWorker<>() {
            @Override protected ManagedLibraryCopy.Plan doInBackground() throws Exception {
                return copies.preview(target, List.copyOf(sources), includeSubfolders, options, chosen, catalog, this::isCancelled);
            }
            @Override protected void done() {
                if (planning != this) return;
                planning = null;
                try {
                    plan = get();
                    entries.set(plan.entries(), plan.policy());
                    String space = chosen == Policy.HARD_LINK ? "shared audio storage"
                        : size(plan.bytes()) + " to copy · " + size(plan.available()) + " free";
                    status.setText(plan.entries().size() + " tracks · " + space
                        + (plan.bytes() > plan.available() ? " · Not enough free space" : "")
                        + (plan.scanNotes().isEmpty() ? "" : " · Some files were skipped"));
                    setNotes(plan.scanNotes());
                } catch (Exception error) { status.setText("Preview failed. " + reason(error)); }
                updateButtons();
            }
        };
        status.setText("Reading file metadata and checking destinations…");
        updateButtons();
        planning.execute();
    }

    private void applyPlan() {
        ManagedLibraryCopy.Plan selected = plan;
        plan = null;
        applying = true;
        updateButtons();
        var settings = new ManagedLibraryCopy.Settings(selected.root(), selected.policy(), selectedOptions(),
            selected.policy() == Policy.COPY && writeTags.isSelected());
        activation = library.activateManaged(settings, selections, recursive.isSelected(),
            progress -> status.setText(progress.phase() + " · " + progress.current().getFileName()),
            result -> {
                if (result.cancelled()) { finish("Managed setup stopped"); return; }
                configured = true;
                setNotes(result.errors());
                finish("Managed library is active · " + result.indexed() + " tracks processed"
                    + (result.errors().isEmpty() ? "" : " · See details"));
            }, this::finish);
    }

    private ManagedLibraryCopy.Options selectedOptions() {
        return new ManagedLibraryCopy.Options(genre.isSelected(), period.isSelected(), composer.isSelected(),
            instrument.isSelected(), work.isSelected(), recording.isSelected(),
            (ManagedLibraryCopy.Layout) layout.getSelectedItem());
    }

    private void finish(String message) {
        applying = false;
        activation = null;
        status.setText(message);
        notify.accept(message);
        updateButtons();
    }

    private Policy selectedPolicy() {
        return (Policy) policy.getSelectedItem();
    }

    private void setNotes(List<String> messages) {
        notes = List.copyOf(messages);
        details.setEnabled(!notes.isEmpty());
    }

    private void showDetails() {
        var text = new JTextArea(String.join("\n\n", notes), 12, 54);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new PixelScrollPane(text), "Managed import details", JOptionPane.INFORMATION_MESSAGE);
    }

    @Override public void dispose() {
        if (planning != null) { planning.cancel(true); planning = null; }
        super.dispose();
    }

    private static String reason(Exception error) {
        return (error.getCause() == null ? error : error.getCause()).getMessage();
    }

    private static String size(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }

    private static JPanel row(Component... children) {
        var row = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 3));
        for (Component child : children) row.add(child);
        return row;
    }

    private static final class Entries extends AbstractTableModel {
        private List<ManagedLibraryCopy.Entry> values = List.of();
        private Policy policy = Policy.COPY;
        void set(List<ManagedLibraryCopy.Entry> values, Policy policy) {
            this.values = values;
            this.policy = policy;
            fireTableDataChanged();
        }
        @Override public int getRowCount() { return values.size(); }
        @Override public int getColumnCount() { return 4; }
        @Override public String getColumnName(int column) {
            return switch (column) { case 0 -> "Source"; case 1 -> "Destination"; case 2 -> "Action"; default -> "Size"; };
        }
        @Override public Object getValueAt(int row, int column) {
            var entry = values.get(row);
            return switch (column) {
                case 0 -> entry.source();
                case 1 -> entry.target();
                case 2 -> action(entry);
                default -> size(entry.stamp().size());
            };
        }

        private String action(ManagedLibraryCopy.Entry entry) {
            boolean link = policy == Policy.HARD_LINK;
            if (entry.reuse()) return link ? "Use existing hard link" : "Use identical copy";
            String action = link ? "Hard link" : "Copy";
            return entry.renamed() ? action + " with unique name" : action;
        }
    }
}
