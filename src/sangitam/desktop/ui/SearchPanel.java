package sangitam.desktop.ui;

import sangitam.desktop.library.TrackSearch;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Searches the same imported tracks shown by the dashboard and library. */
public final class SearchPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JTextField search = new JTextField();
    private final JCheckBox availableOnly = new JCheckBox("Available only");
    private final JLabel count = UiFactory.label("Import music to begin searching.", Theme.SMALL, Theme.TEXT_MUTED);
    private final DefaultListModel<Track> songsModel = new DefaultListModel<>();
    private final DefaultListModel<String> albumsModel = new DefaultListModel<>();
    private final DefaultListModel<String> artistsModel = new DefaultListModel<>();
    private final JTabbedPane tabs = new JTabbedPane();
    private final TrackCellRenderer songRenderer = new TrackCellRenderer();
    private final Timer debounce;
    private List<Track> library = List.of();
    private Set<Path> unavailable = Set.of();
    private SwingWorker<TrackSearch.Results, Void> activeSearch;
    private long generation;

    public SearchPanel(Consumer<Track> onPlay) {
        super(new BorderLayout(0, 20));
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(28, 30, 24, 30));
        JPanel top = new JPanel(new BorderLayout(12, 10));
        top.setOpaque(false);
        top.add(UiFactory.label("Search your music", Theme.TITLE, Theme.TEXT), BorderLayout.NORTH);
        search.setFont(Theme.BODY);
        search.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 14), Theme.padding(11, 14, 11, 14)));
        search.setToolTipText("Search titles, artists, albums and genre tags");
        top.add(search, BorderLayout.CENTER);
        JPanel summary = new JPanel(new BorderLayout(12, 0));
        summary.setOpaque(false);
        summary.add(count, BorderLayout.CENTER);
        availableOnly.setOpaque(false);
        availableOnly.setToolTipText("Hide tracks marked unavailable by the last rescan");
        availableOnly.addActionListener(event -> runSearch());
        summary.add(availableOnly, BorderLayout.EAST);
        top.add(summary, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        JList<Track> songs = new JList<>(songsModel);
        songs.setFixedCellHeight(63);
        songs.setCellRenderer(songRenderer);
        ToolTipManager.sharedInstance().registerComponent(songs);
        songs.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        songs.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && songs.getSelectedValue() != null) onPlay.accept(songs.getSelectedValue());
            }
        });
        songs.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "playSelected");
        songs.getActionMap().put("playSelected", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (songs.getSelectedValue() != null) onPlay.accept(songs.getSelectedValue());
            }
        });
        JList<String> albums = new JList<>(albumsModel);
        JList<String> artists = new JList<>(artistsModel);
        albums.setFixedCellHeight(42);
        artists.setFixedCellHeight(42);
        albums.addListSelectionListener(event -> chooseFacet(event.getValueIsAdjusting(), albums.getSelectedValue()));
        artists.addListSelectionListener(event -> chooseFacet(event.getValueIsAdjusting(), artists.getSelectedValue()));
        tabs.addTab("Songs", new JScrollPane(songs,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
        tabs.addTab("Albums", new JScrollPane(albums));
        tabs.addTab("Artists", new JScrollPane(artists));
        add(tabs, BorderLayout.CENTER);

        debounce = new Timer(180, event -> runSearch());
        debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { debounce.restart(); }
            public void removeUpdate(DocumentEvent event) { debounce.restart(); }
            public void changedUpdate(DocumentEvent event) { debounce.restart(); }
        });
    }

    public void setTracks(List<Track> tracks, Map<Path, TrackTags> tags, Set<Path> manualTags, Set<Path> unavailable) {
        library = List.copyOf(tracks);
        this.unavailable = Set.copyOf(unavailable);
        songRenderer.setTags(tags);
        songRenderer.setManuallyEdited(manualTags);
        songRenderer.setUnavailable(unavailable);
        debounce.stop();
        runSearch();
    }

    public void selectCategory(String category) {
        tabs.setSelectedIndex(switch (category) {
            case "albums" -> 1;
            case "artists" -> 2;
            default -> 0;
        });
        search.requestFocusInWindow();
    }

    private void chooseFacet(boolean adjusting, String value) {
        if (adjusting || value == null || tabs.getSelectedIndex() == 0) return;
        search.setText(value);
        tabs.setSelectedIndex(0);
    }

    private void runSearch() {
        if (activeSearch != null) activeSearch.cancel(true);
        long request = ++generation;
        String query = search.getText();
        List<Track> input = library;
        Set<Path> excluded = availableOnly.isSelected() ? unavailable : Set.of();
        activeSearch = new SwingWorker<>() {
            @Override protected TrackSearch.Results doInBackground() {
                return TrackSearch.find(excluded.isEmpty() ? input
                    : input.stream().filter(track -> !excluded.contains(track.path())).toList(), query);
            }

            @Override protected void done() {
                if (isCancelled() || request != generation) return;
                try {
                    TrackSearch.Results result = get();
                    replace(songsModel, result.songs());
                    replace(albumsModel, result.albums());
                    replace(artistsModel, result.artists());
                    count.setText(result.songs().size() + " songs · " + result.albums().size()
                        + " albums · " + result.artists().size() + " artists");
                } catch (Exception error) {
                    count.setText("Search could not complete.");
                }
            }
        };
        activeSearch.execute();
    }

    private <T> void replace(DefaultListModel<T> model, List<T> values) {
        model.clear();
        model.addAll(values);
    }
}
