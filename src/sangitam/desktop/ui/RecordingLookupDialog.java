package sangitam.desktop.ui;

import sangitam.desktop.discovery.MusicBrainzProvider;
import sangitam.desktop.discovery.RecordingLookup;
import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.RecordingCandidate;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata.Field;
import sangitam.desktop.storage.RecordingMatchStore;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Starts an online lookup only on request and lets the user review recording and release separately. */
final class RecordingLookupDialog extends JDialog {
    private record Result(RecordingCandidate recording, RecordingLookup.Evidence evidence) {
        @Override public String toString() {
            return recording.title() + "  —  " + recording.credit()
                + (recording.durationMillis() > 0 ? "  ·  " + duration(recording.durationMillis()) : "");
        }
    }

    private final MusicBrainzProvider provider;
    private final LibraryService library;
    private final Consumer<String> report;
    private final JTextField title = new JTextField(32);
    private final JCheckBox includeDetails = new JCheckBox("Include known credits, release, work, and duration", true);
    private final JButton search = UiFactory.button("Search MusicBrainz", null, true);
    private final JButton refresh = UiFactory.button("Refresh results", null, false);
    private final JButton cancelSearch = UiFactory.button("Cancel search", null, false);
    private final JLabel status = UiFactory.label("Loading catalog details…", Theme.SMALL, Theme.TEXT_MUTED);
    private final JTextArea local = textArea(3);
    private final JTextArea candidateDetails = textArea(4);
    private final DefaultListModel<Result> results = new DefaultListModel<>();
    private final JList<Result> candidates = new JList<>(results);
    private final JComboBox<Object> release = new JComboBox<>();
    private final JCheckBox fill = new JCheckBox("Fill corroborated missing catalog fields");
    private final JButton save = UiFactory.button("Link selected recording", null, true);
    private final JButton remove = UiFactory.button("Remove saved link", null, false);
    private final JButton openSource = UiFactory.button("Open MusicBrainz", null, false);
    private RecordingMatchStore.Context context;
    private SwingWorker<List<Result>, Void> lookup;
    private String reviewedQuery;

    RecordingLookupDialog(Window owner, Path path, RecordingMatchStore matches, MusicBrainzProvider provider,
                          LibraryService library, Consumer<String> report) {
        super(owner, "Identify recording", ModalityType.MODELESS);
        this.provider = provider;
        this.library = library;
        this.report = report;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(content());
        setMinimumSize(new Dimension(620, 525));
        setSize(765, 605);
        setLocationRelativeTo(owner);
        setSearchState(false);
        new SwingWorker<RecordingMatchStore.Context, Void>() {
            @Override protected RecordingMatchStore.Context doInBackground() throws Exception { return matches.load(path); }
            @Override protected void done() {
                if (!isDisplayable()) return;
                try {
                    context = get();
                    if (context == null) {
                        status.setText("This recording is no longer indexed.");
                        return;
                    }
                    Track track = context.track();
                    title.setText(RecordingLookup.initialTitle(track));
                    local.setText(track.title() + " — " + track.artist() + " · " + track.album() + " · " + track.durationLabel());
                    var known = new ArrayList<String>();
                    for (Field field : List.of(Field.COMPOSER, Field.SOLOIST, Field.PERFORMERS, Field.WORK, Field.OPUS, Field.MOVEMENT)) {
                        String value = track.metadata().text(field);
                        if (!value.isBlank()) known.add(field + " " + value);
                    }
                    if (!known.isEmpty()) local.append("\n" + String.join(" · ", known));
                    var saved = context.saved();
                    if (saved != null) {
                        local.append("\nSaved MusicBrainz link: " + saved.title() + " — " + saved.credit()
                            + (saved.stamp().equals(context.stamp()) ? "" : " · File changed since review"));
                        candidateDetails.setText("Saved recording: " + saved.id() + "\n" + saved.evidence());
                    }
                    local.setCaretPosition(0);
                    status.setText("Review candidates before linking.");
                    setSearchState(false);
                } catch (Exception error) { status.setText("Could not read local recording details."); }
            }
        }.execute();
    }

    private JPanel content() {
        var root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(Theme.padding(14, 16, 14, 16));
        var top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(row(UiFactory.label("Local recording", Theme.BODY_BOLD, Theme.TEXT)));
        top.add(new PixelScrollPane(local));
        JLabel label = UiFactory.label("Recording or work title", Theme.BODY, Theme.TEXT);
        label.setLabelFor(title);
        top.add(row(label, title));
        top.add(row(includeDetails));
        top.add(row(UiFactory.label("Search and linking use MusicBrainz. Audio and paths stay local.",
            Theme.SMALL, Theme.TEXT_MUTED)));
        top.add(row(search, refresh, cancelSearch));
        root.add(top, BorderLayout.NORTH);

        candidates.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        candidates.setFixedCellHeight(32);
        candidates.getAccessibleContext().setAccessibleName("MusicBrainz recording candidates");
        candidates.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                putClientProperty("html.disable", true);
                return super.getListCellRendererComponent(list, value, index, selected, focus);
            }
        });
        candidates.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showCandidate();
        });
        root.add(new PixelScrollPane(candidates), BorderLayout.CENTER);

        var bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.add(new PixelScrollPane(candidateDetails));
        JLabel releaseLabel = UiFactory.label("Release (optional)", Theme.BODY, Theme.TEXT);
        releaseLabel.setLabelFor(release);
        release.setPreferredSize(new Dimension(435, 28));
        bottom.add(row(releaseLabel, release));
        bottom.add(row(fill));
        bottom.add(row(status));
        JButton close = UiFactory.button("Close", null, false);
        bottom.add(row(save, remove, openSource, close));
        root.add(bottom, BorderLayout.SOUTH);

        search.addActionListener(event -> search(false));
        title.addActionListener(event -> { if (search.isEnabled()) search(false); });
        refresh.addActionListener(event -> search(true));
        cancelSearch.addActionListener(event -> cancelLookup());
        save.addActionListener(event -> save());
        remove.addActionListener(event -> {
            library.removeRecordingMatch(context, report);
            dispose();
        });
        close.addActionListener(event -> dispose());
        openSource.addActionListener(event -> openSource());
        release.addActionListener(event -> updateSelection());
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        return root;
    }

    private void search(boolean forceRefresh) {
        String query;
        try { query = RecordingLookup.query(title.getText(), context.track(), includeDetails.isSelected()); }
        catch (IllegalArgumentException error) { status.setText(error.getMessage()); return; }
        results.clear();
        showCandidate();
        status.setText("Searching MusicBrainz…");
        Track track = context.track();
        lookup = new SwingWorker<>() {
            @Override protected List<Result> doInBackground() throws Exception {
                return provider.lookupRecordings(query, forceRefresh).stream()
                    .map(candidate -> new Result(candidate, RecordingLookup.compare(track, candidate)))
                    .sorted(Comparator.comparingInt((Result result) -> result.evidence().rank()).reversed())
                    .toList();
            }

            @Override protected void done() {
                if (!isDisplayable() || lookup != this) return;
                lookup = null;
                try {
                    List<Result> found = get();
                    reviewedQuery = query;
                    results.addAll(found);
                    status.setText(found.isEmpty() ? "No recordings found. Try a shorter or more specific title."
                        : found.size() + " candidates · Select one to compare");
                } catch (ExecutionException error) {
                    status.setText(error.getCause().getMessage());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText("Lookup interrupted. Search again to retry.");
                }
                setSearchState(false);
            }
        };
        setSearchState(true);
        lookup.execute();
    }

    private void cancelLookup() {
        var active = lookup;
        lookup = null;
        if (active != null) active.cancel(true);
        status.setText("Search cancelled.");
        setSearchState(false);
    }

    private void setSearchState(boolean searching) {
        boolean ready = !searching && context != null;
        search.setEnabled(ready);
        refresh.setEnabled(ready && reviewedQuery != null);
        cancelSearch.setEnabled(searching);
        title.setEnabled(ready);
        includeDetails.setEnabled(ready);
        remove.setEnabled(ready && context.saved() != null);
        updateSelection();
    }

    private void showCandidate() {
        Result selected = candidates.getSelectedValue();
        release.removeAllItems();
        release.addItem("No release selected");
        fill.setSelected(false);
        if (selected == null) candidateDetails.setText("");
        else {
            var recording = selected.recording();
            recording.releases().forEach(release::addItem);
            candidateDetails.setText(selected + "\n" + selected.evidence().summary() + "\n" + recording.link()
                + (recording.disambiguation().isBlank() ? "" : "\n" + recording.disambiguation()));
            candidateDetails.setCaretPosition(0);
        }
        updateSelection();
    }

    private void updateSelection() {
        Result selected = candidates.getSelectedValue();
        boolean ready = context != null && lookup == null && selected != null;
        save.setEnabled(ready);
        openSource.setEnabled(context != null && lookup == null && (selected != null || context.saved() != null));
        release.setEnabled(ready && release.getItemCount() > 1);
        var fields = new ArrayList<String>(3);
        if (ready && selected.evidence().corroborated()) {
            if (context.missingTitle()) fields.add("title");
            if (context.missingArtist() && !selected.recording().credit().isBlank()) fields.add("artist credit");
            if (context.missingAlbum() && release.getSelectedItem() instanceof RecordingCandidate.Release) fields.add("album");
        }
        fill.setEnabled(!fields.isEmpty());
        fill.setText(fields.isEmpty() ? "Fill corroborated missing catalog fields"
            : "Fill missing " + String.join(", ", fields));
        if (!fill.isEnabled()) fill.setSelected(false);
    }

    private void save() {
        var edition = release.getSelectedItem() instanceof RecordingCandidate.Release value ? value : null;
        library.saveRecordingMatch(context, candidates.getSelectedValue().recording(), edition,
            fill.isEnabled() && fill.isSelected(), reviewedQuery, report);
        dispose();
    }

    private void openSource() {
        Result selected = candidates.getSelectedValue();
        URI link = selected != null ? selected.recording().link()
            : URI.create("https://musicbrainz.org/recording/" + context.saved().id());
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                Desktop.getDesktop().browse(link);
                return null;
            }
            @Override protected void done() {
                if (!isDisplayable()) return;
                try { get(); }
                catch (Exception error) { status.setText("Could not open the MusicBrainz page in your browser."); }
            }
        }.execute();
    }

    @Override public void dispose() {
        if (lookup != null) lookup.cancel(true);
        lookup = null;
        super.dispose();
    }

    private static JPanel row(Component... components) {
        var row = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 3));
        for (Component component : components) row.add(component);
        return row;
    }

    private static JTextArea textArea(int rows) {
        var area = new JTextArea(rows, 45);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Theme.SMALL);
        return area;
    }

    private static String duration(long millis) {
        long seconds = Math.round(millis / 1000.0);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
