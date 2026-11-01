package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryScanner;
import sangitam.desktop.library.LibraryService;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Imports selected MP3/WAV files or folders without blocking the Swing event thread. */
public final class ImportMediaDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final LibraryService library;
    private final transient Consumer<String> notify;
    private final JTextField selectedPath = new JTextField("No file or folder selected");
    private final JCheckBox includeSubfolders = new JCheckBox("Include subfolders", true);
    private final JLabel status = UiFactory.label("MP3 and WAV files are supported.", Theme.SMALL, Theme.TEXT_MUTED);
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea details = new JTextArea(3, 40);
    private final JButton start = UiFactory.button("Import music", AppIcons.of(AppIcons.Kind.NEXT, 15, Theme.BLACK), true);
    private final JButton browse = UiFactory.button("Browse…", AppIcons.of(AppIcons.Kind.FOLDER, 15), false);
    private final JButton close = UiFactory.button("Close", null, false);
    private File[] selectedItems = new File[0];

    public ImportMediaDialog(Window owner, LibraryService library, Consumer<String> notify) {
        super(owner, "Add music", ModalityType.APPLICATION_MODAL);
        this.library = library;
        this.notify = notify;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(600, 380));
        setSize(690, 440);
        setLocationRelativeTo(owner);
        setContentPane(buildContent());
    }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 22));
        root.setBackground(Theme.BLACK);
        root.setBorder(Theme.padding(25, 28, 22, 28));
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.add(UiFactory.label("Add music to your library", Theme.TITLE, Theme.TEXT));
        heading.add(Box.createVerticalStrut(5));
        heading.add(UiFactory.label("Choose files or a folder. The original files stay where they are.", Theme.BODY, Theme.TEXT_MUTED));
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
        root.add(form, BorderLayout.CENTER);

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
        details.setVisible(false);
        details.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(Box.createVerticalStrut(8));
        footer.add(details);
        footer.add(Box.createVerticalStrut(10));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        close.addActionListener(event -> dispose());
        start.addActionListener(event -> beginImport());
        actions.add(close);
        actions.add(start);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(actions);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose MP3/WAV files or a folder");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        selectedItems = chooser.getSelectedFiles();
        if (selectedItems.length == 0 && chooser.getSelectedFile() != null) {
            selectedItems = new File[] {chooser.getSelectedFile()};
        }
        if (selectedItems.length == 1) selectedPath.setText(selectedItems[0].getAbsolutePath());
        else selectedPath.setText(selectedItems.length + " items selected");
    }

    private void beginImport() {
        if (selectedItems.length == 0) {
            status.setText("Choose at least one file or folder first.");
            Toolkit.getDefaultToolkit().beep();
            return;
        }
        List<Path> sources = Arrays.stream(selectedItems).map(File::toPath).toList();
        browse.setEnabled(false);
        start.setEnabled(false);
        progress.setIndeterminate(true);
        progress.setVisible(true);
        status.setText("Scanning music…");
        close.setText("Continue in background");
        library.importMedia(sources, includeSubfolders.isSelected(), this::onProgress,
            this::onComplete, this::onError);
    }

    private void onProgress(LibraryScanner.Progress current) {
        status.setText("Checked " + current.visited() + " files · found " + current.accepted() + " tracks");
    }

    private void onComplete(LibraryService.ImportSummary result) {
        progress.setVisible(false);
        close.setText("Done");
        if (result.cancelled()) {
            status.setText("Import cancelled.");
        } else {
            status.setText("Added " + result.added() + " new tracks · indexed " + result.indexed()
                + " · skipped " + result.skipped());
            notify.accept("Library updated: " + result.added() + " new tracks");
        }
        if (!result.errors().isEmpty()) {
            details.setText(String.join("\n", result.errors()));
            details.setVisible(true);
        }
    }

    private void onError(String message) {
        progress.setVisible(false);
        close.setText("Close");
        status.setText(message);
        notify.accept(message);
    }
}
