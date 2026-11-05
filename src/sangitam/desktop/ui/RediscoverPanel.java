package sangitam.desktop.ui;

import sangitam.desktop.history.Favorite;
import sangitam.desktop.history.FavoritesAnalyzer;
import sangitam.desktop.storage.ProviderCredentials;
import sangitam.desktop.history.HistoryService;
import sangitam.desktop.history.HistorySource;
import sangitam.desktop.history.LastFmHistorySource;
import sangitam.desktop.history.ListenBrainzHistorySource;
import sangitam.desktop.history.SpotifyHistorySource;
import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.Track;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.function.Consumer;

/** Swing collection of known repeat listens, including optional account history. */
public final class RediscoverPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final String LISTENBRAINZ_USER = "listenbrainz-user";
    private static final String LASTFM_USER = "lastfm-user";

    private final HistoryService history;
    private final Consumer<Track> play;
    private final Consumer<String> notify;
    private final Preferences preferences = Preferences.userNodeForPackage(RediscoverPanel.class);
    private final JTextField listenBrainz = new JTextField(18);
    private final JTextField lastFm = new JTextField(18);
    private final JLabel status = UiFactory.label("Local plays appear here automatically.", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel summary = UiFactory.label("No listening history yet", Theme.BODY, Theme.TEXT_MUTED);
    private final FavoriteTableModel mostModel = new FavoriteTableModel();
    private final FavoriteTableModel forgottenModel = new FavoriteTableModel();
    private final JTable mostTable = table(mostModel);
    private final JTable forgottenTable = table(forgottenModel);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JButton playButton = UiFactory.button("Play local file", null, true);
    private final JButton openButton = UiFactory.button("Open source", null, false);
    private List<Favorite> favorites = List.of();
    private List<Track> localTracks = List.of();

    public RediscoverPanel(HistoryService history, Consumer<Track> play, Consumer<String> notify) {
        super(new BorderLayout(0, 16));
        this.history = history;
        this.play = play;
        this.notify = notify;
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(25, 28, 20, 28));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = UiFactory.label("Rediscover", Theme.TITLE, Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);
        header.add(Box.createVerticalStrut(5));
        JLabel intro = UiFactory.label("Songs you replayed, kept together for later.", Theme.BODY,
            Theme.TEXT_MUTED);
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(intro);
        header.add(Box.createVerticalStrut(15));
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(summary);
        header.add(Box.createVerticalStrut(17));
        JPanel connections = buildConnections();
        connections.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(connections);
        add(header, BorderLayout.NORTH);

        tabs.addTab("Most played", new JScrollPane(mostTable));
        tabs.addTab("Forgotten favorites", new JScrollPane(forgottenTable));
        tabs.addChangeListener(event -> updateActions());
        mostTable.getSelectionModel().addListSelectionListener(event -> updateActions());
        forgottenTable.getSelectionModel().addListSelectionListener(event -> updateActions());
        add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        JButton export = UiFactory.button("Export collection…", null, false);
        export.addActionListener(event -> exportCollection());
        playButton.addActionListener(event -> playSelected());
        openButton.addActionListener(event -> openSelected());
        actions.add(playButton);
        actions.add(openButton);
        actions.add(export);
        footer.add(actions, BorderLayout.NORTH);
        footer.add(status, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        listenBrainz.setText(preferences.get(LISTENBRAINZ_USER, ""));
        lastFm.setText(preferences.get(LASTFM_USER, ""));
        history.setListener(this::setFavorites, notify);
        updateActions();
    }

    /** MainFrame calls this when local playback or imports change the library. */
    public void setCatalog(LibraryService.Catalog catalog) {
        localTracks = catalog.tracks();
        history.updateCatalog(catalog);
    }

    private JPanel buildConnections() {
        JPanel card = UiFactory.surface(new BorderLayout(0, 9));
        card.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 12), Theme.padding(13, 15, 13, 15)));
        JLabel heading = UiFactory.label("Connect listening history", Theme.BODY_BOLD, Theme.TEXT);
        card.add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 9));
        body.setOpaque(false);
        JPanel fields = new JPanel(new GridLayout(1, 2, 12, 0));
        fields.setOpaque(false);
        fields.add(field("ListenBrainz username", listenBrainz));
        fields.add(field("Last.fm username", lastFm));
        body.add(fields, BorderLayout.NORTH);

        SpotifyHistorySource spotify = new SpotifyHistorySource();
        String spotifyText = spotify.available() ? "Spotify token ready (recent plays permission required)"
            : "Spotify: add a token in Settings";
        JPanel hints = new JPanel();
        hints.setOpaque(false);
        hints.setLayout(new BoxLayout(hints, BoxLayout.Y_AXIS));
        JLabel spotifyHint = UiFactory.label(spotifyText, Theme.SMALL, Theme.TEXT_MUTED);
        spotifyHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hints.add(spotifyHint);
        String lastFmCredential = ProviderCredentials.get(ProviderCredentials.LASTFM_KEY, "SANGITAM_LASTFM_API_KEY");
        boolean lastFmKey = lastFmCredential != null && !lastFmCredential.isBlank();
        JLabel lastFmHint = UiFactory.label(lastFmKey ? "Last.fm API key ready"
            : "Last.fm: add an API key in Settings", Theme.SMALL, Theme.TEXT_MUTED);
        lastFmHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hints.add(Box.createVerticalStrut(3));
        hints.add(lastFmHint);
        body.add(hints, BorderLayout.CENTER);
        card.add(body, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setOpaque(false);
        JLabel note = UiFactory.label("Recent history only · YouTube does not expose listening history",
            Theme.SMALL, Theme.TEXT_MUTED);
        bottom.add(note, BorderLayout.CENTER);
        JButton sync = UiFactory.button("Save & sync", null, true);
        sync.addActionListener(event -> sync(sync));
        bottom.add(sync, BorderLayout.EAST);
        card.add(bottom, BorderLayout.SOUTH);
        return card;
    }

    private JPanel field(String label, JTextField input) {
        JPanel box = new JPanel(new BorderLayout(0, 3));
        box.setOpaque(false);
        box.add(UiFactory.label(label, Theme.SMALL, Theme.TEXT_MUTED), BorderLayout.NORTH);
        input.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 10), Theme.padding(7, 9, 7, 9)));
        input.getAccessibleContext().setAccessibleName(label);
        box.add(input, BorderLayout.CENTER);
        input.addActionListener(event -> sync(null));
        return box;
    }

    private void sync(JButton button) {
        String lb = listenBrainz.getText().strip();
        String lf = lastFm.getText().strip();
        preferences.put(LISTENBRAINZ_USER, lb);
        preferences.put(LASTFM_USER, lf);
        List<HistorySource> sources = List.of(new SpotifyHistorySource(),
            new ListenBrainzHistorySource(lb), new LastFmHistorySource(lf));
        if (sources.stream().noneMatch(HistorySource::available)) {
            status.setText("Add a username or configure provider tokens in Settings first.");
            return;
        }
        status.setText("Syncing available sources in the background…");
        if (button != null) button.setEnabled(false);
        history.sync(sources, message -> {
            status.setText(message);
            if (button != null) button.setEnabled(true);
        });
    }

    private static JTable table(FavoriteTableModel model) {
        JTable table = new JTable(model);
        table.setRowHeight(46);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        return table;
    }

    private void setFavorites(List<Favorite> updated) {
        favorites = updated;
        mostModel.setRows(updated);
        List<Favorite> forgotten = FavoritesAnalyzer.forgotten(updated, System.currentTimeMillis());
        forgottenModel.setRows(forgotten);
        summary.setText(updated.isEmpty() ? "No listening history yet"
            : updated.size() + " songs with known plays · " + forgotten.size() + " ready to rediscover");
        updateActions();
    }

    private Favorite selected() {
        JTable table = tabs.getSelectedIndex() == 0 ? mostTable : forgottenTable;
        FavoriteTableModel model = tabs.getSelectedIndex() == 0 ? mostModel : forgottenModel;
        int row = table.getSelectedRow();
        int modelRow = row < 0 ? -1 : table.convertRowIndexToModel(row);
        return modelRow < 0 || modelRow >= model.getRowCount() ? null : model.row(modelRow);
    }

    private void updateActions() {
        Favorite selected = selected();
        playButton.setEnabled(selected != null && selected.localPath() != null
            && Files.isRegularFile(selected.localPath()));
        openButton.setEnabled(selected != null && selected.link() != null);
    }

    private void playSelected() {
        Favorite favorite = selected();
        if (favorite == null || favorite.localPath() == null) return;
        for (Track track : localTracks) {
            if (track.path().equals(favorite.localPath())) { play.accept(track); return; }
        }
        notify.accept("The local file is no longer in your library.");
    }

    private void openSelected() {
        Favorite favorite = selected();
        if (favorite == null || favorite.link() == null) return;
        URI link = favorite.link();
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            notify.accept("Opening a browser is not supported here.");
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { Desktop.getDesktop().browse(link); return null; }
            @Override protected void done() {
                try { get(); } catch (Exception error) { notify.accept("Could not open the provider page."); }
            }
        }.execute();
    }

    private void exportCollection() {
        if (favorites.isEmpty()) { status.setText("Nothing to export yet."); return; }
        Path folder = FileBrowserDialog.chooseDirectory(SwingUtilities.getWindowAncestor(this), "Choose export folder");
        if (folder == null) return;
        List<Favorite> snapshot = favorites;
        status.setText("Exporting collection…");
        new SwingWorker<Path, Void>() {
            @Override protected Path doInBackground() throws Exception {
                StringBuilder csv = new StringBuilder("Title,Artist,Album,Known plays,Last heard,Sources,Local file,Provider link\n");
                for (Favorite favorite : snapshot) {
                    csv.append(csv(favorite.title())).append(',').append(csv(favorite.artist())).append(',')
                        .append(csv(favorite.album())).append(',').append(favorite.knownPlays()).append(',')
                        .append(csv(date(favorite.lastHeardMillis()))).append(',')
                        .append(csv(String.join("; ", favorite.sources()))).append(',')
                        .append(csv(favorite.localPath() == null ? "" : favorite.localPath().toString())).append(',')
                        .append(csv(favorite.link() == null ? "" : favorite.link().toString())).append('\n');
                }
                for (int suffix = 0; suffix < 1000; suffix++) {
                    Path target = folder.resolve(suffix == 0 ? "Sangitam Favorites.csv"
                        : "Sangitam Favorites " + suffix + ".csv");
                    try {
                        Files.writeString(target, "\uFEFF" + csv, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                        return target;
                    } catch (java.nio.file.FileAlreadyExistsException existing) { /* Preserve prior exports. */ }
                }
                throw new IOException("Too many previous exports in this folder.");
            }
            @Override protected void done() {
                try { status.setText("Exported to " + get()); }
                catch (Exception error) { status.setText("Could not export the collection: check folder access."); }
            }
        }.execute();
    }

    private static String csv(String value) {
        // Spreadsheet programs can execute cells beginning with formula characters.
        String leading = value.stripLeading();
        String safe = !leading.isEmpty() && "=+@-".indexOf(leading.charAt(0)) >= 0
            ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    private static String date(long millis) {
        return millis <= 0 ? "Unknown" : DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    private static final class FavoriteTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"Song", "Artist", "Known plays", "Last heard", "Sources"};
        private List<Favorite> rows = List.of();
        void setRows(List<Favorite> updated) { rows = List.copyOf(updated); fireTableDataChanged(); }
        Favorite row(int index) { return rows.get(index); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public Object getValueAt(int row, int column) {
            Favorite favorite = rows.get(row);
            return switch (column) {
                case 0 -> favorite.title();
                case 1 -> favorite.artist();
                case 2 -> favorite.knownPlays();
                case 3 -> date(favorite.lastHeardMillis());
                default -> String.join(", ", favorite.sources().stream().sorted().toList());
            };
        }
        @Override public Class<?> getColumnClass(int column) { return column == 2 ? Integer.class : String.class; }
    }
}
