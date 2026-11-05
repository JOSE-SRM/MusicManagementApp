package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryScanner;
import sangitam.desktop.library.LibraryService;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Imports selected audio files or folders without blocking the Swing event thread. */
public final class ImportMediaDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final LibraryService library;
    private final transient Consumer<String> notify;
    private final transient Consumer<List<Path>> imported;
    private final boolean rescan;
    private final JTextField selectedPath = new JTextField("No file or folder selected");
    private final JCheckBox includeSubfolders = new JCheckBox("Include subfolders", true);
    private final JLabel status = UiFactory.label("MP3, WAV, FLAC, OGG, AAC, M4A, AIFF, and WMA are supported.", Theme.SMALL, Theme.TEXT_MUTED);
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea details = new JTextArea(3, 40);
    private final JScrollPane detailScroll = new JScrollPane(details);
    private final JButton start = UiFactory.button("Import music", AppIcons.of(AppIcons.Kind.NEXT, 15, Theme.BLACK), true);
    private final JButton browse = UiFactory.button("Browse…", AppIcons.of(AppIcons.Kind.FOLDER, 15), false);
    private final JButton close = UiFactory.button("Close", null, false);
    private List<Path> selectedItems = List.of();

    public ImportMediaDialog(Window owner, LibraryService library, Consumer<String> notify) {
        this(owner, library, notify, false, ignored -> { });
    }

    public ImportMediaDialog(Window owner, LibraryService library, Consumer<String> notify, Consumer<List<Path>> imported) {
        this(owner, library, notify, false, imported);
    }

    private ImportMediaDialog(Window owner, LibraryService library, Consumer<String> notify, boolean rescan,
                              Consumer<List<Path>> imported) {
        super(owner, rescan ? "Rescan library" : "Add music", ModalityType.APPLICATION_MODAL);
        this.library = library;
        this.notify = notify;
        this.imported = imported;
        this.rescan = rescan;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(600, rescan ? 285 : 380));
        setSize(690, rescan ? 320 : 440);
        setLocationRelativeTo(owner);
        setContentPane(buildContent());
    }

    public static void rescan(Window owner, LibraryService library, Consumer<String> notify) {
        ImportMediaDialog dialog = new ImportMediaDialog(owner, library, notify, true, ignored -> { });
        dialog.beginImport();
        dialog.setVisible(true);
    }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 22));
        root.setBackground(Theme.BLACK);
        root.setBorder(Theme.padding(25, 28, 22, 28));
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(UiFactory.label(rescan ? "Rescan your library" : "Add music to your library", Theme.TITLE, Theme.TEXT));
        heading.add(Box.createVerticalStrut(5));
        heading.add(UiFactory.label(rescan ? "Check saved sources for new, changed, or unavailable tracks."
            : "Choose files or a folder. The original files stay where they are.", Theme.BODY, Theme.TEXT_MUTED));
        root.add(heading, BorderLayout.NORTH);

        JPanel form = new JPanel(new BorderLayout(10, 16));
        form.setBackground(Theme.SURFACE);
        form.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 16), Theme.padding(20, 20, 20, 20)));
        JPanel chooserRow = new JPanel(new BorderLayout(10, 0));
        chooserRow.setOpaque(false);
        selectedPath.setEditable(false);
        selectedPath.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 10), Theme.padding(9, 12, 9, 12)));
        chooserRow.add(selectedPath, BorderLayout.CENTER);
        browse.addActionListener(event -> browse());
        chooserRow.add(browse, BorderLayout.EAST);
        form.add(chooserRow, BorderLayout.NORTH);
        includeSubfolders.setOpaque(false);
        includeSubfolders.setFont(Theme.BODY);
        includeSubfolders.setForeground(Theme.TEXT);
        form.add(includeSubfolders, BorderLayout.CENTER);
        if (!rescan) root.add(form, BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(status);
        footer.add(Box.createVerticalStrut(8));
        progress.setVisible(false);
        progress.setAlignmentX(Component.LEFT_ALIGNMENT);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        footer.add(progress);
        details.setEditable(false);
        details.setOpaque(false);
        details.setForeground(Theme.TEXT_MUTED);
        details.setFont(Theme.SMALL);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        detailScroll.setVisible(false);
        detailScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(Box.createVerticalStrut(8));
        footer.add(detailScroll);
        footer.add(Box.createVerticalStrut(10));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        close.addActionListener(event -> dispose());
        start.addActionListener(event -> beginImport());
        actions.add(close);
        if (!rescan) actions.add(start);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(actions);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private void browse() {
        List<Path> selection = FileBrowserDialog.choose(this);
        if (selection.isEmpty()) return;
        selectedItems = selection;
        if (selectedItems.size() == 1) selectedPath.setText(selectedItems.get(0).toString());
        else selectedPath.setText(selectedItems.size() + " items selected");
    }

    private void beginImport() {
        if (!rescan && selectedItems.isEmpty()) {
            status.setText("Choose at least one file or folder first.");
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        List<Path> sources = selectedItems;
        browse.setEnabled(false);
        start.setEnabled(false);
        progress.setIndeterminate(true);
        progress.setVisible(true);
        status.setText(rescan ? "Checking saved sources…" : "Scanning music…");
        close.setText("Continue in background");
        if (rescan) library.rescan(this::onProgress, this::onComplete, this::onError);
        else library.importMedia(sources, includeSubfolders.isSelected(), this::onProgress, this::onComplete, this::onError);
    }

    private void onProgress(LibraryScanner.Progress current) {
        if (current.phase().equals("Checking analysis")) {
            status.setText("Checking analysis for track " + current.visited() + " of " + current.accepted() + "…");
        } else {
            status.setText("Checked " + current.visited() + " files · found " + current.accepted() + " tracks");
        }
    }

    private void onComplete(LibraryService.ImportSummary result) {
        progress.setVisible(false);
        close.setText("Done");
        if (result.cancelled()) {
            status.setText(rescan ? "Rescan cancelled." : "Import cancelled.");
        } else {
            int refreshed = result.indexed() - result.reused() - result.added();
            status.setText("Added " + result.added() + " · refreshed " + refreshed + " · unchanged " + result.reused()
                + " · unavailable " + result.unavailable() + " · skipped " + result.skipped()
                + (result.unanalyzed() == 0 ? "" : " · " + result.unanalyzed() + " without audio tags"));
            notify.accept((rescan ? "Rescan complete" : "Import complete") + " · " + result.added()
                + " added · " + result.unavailable() + " unavailable");
            imported.accept(result.paths());
        }
        if (!result.errors().isEmpty()) {
            details.setText(String.join("\n", result.errors()));
            details.setCaretPosition(0);
            detailScroll.setVisible(true);
        }
    }

    private void onError(String message) {
        progress.setVisible(false);
        close.setText("Close");
        status.setText(message);
        notify.accept(message);
    }
}
