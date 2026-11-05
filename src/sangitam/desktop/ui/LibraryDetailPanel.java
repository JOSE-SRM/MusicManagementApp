package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.Track;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** The main content page for an album, artist, or playlist. */
final class LibraryDetailPanel extends JPanel {
    private final JLabel title = UiFactory.label("", Theme.TITLE, Theme.TEXT);
    private final JLabel summary = UiFactory.label("", Theme.BODY, Theme.TEXT_MUTED);
    private final DefaultListModel<Track> model = new DefaultListModel<>();
    private final JList<Track> songs = new JList<>(model);
    private final TrackCellRenderer renderer = new TrackCellRenderer();
    private final JButton play;
    private final JButton queue;
    private List<Track> tracks = List.of();

    LibraryDetailPanel(Runnable back, BiConsumer<List<Track>, Integer> onPlay, Consumer<List<Track>> onQueue,
                       Function<Track, JPopupMenu> trackMenu) {
        super(new BorderLayout(0, 22));
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(25, 28, 25, 28));
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JButton home = UiFactory.button("‹ Listen now", null, false);
        home.addActionListener(event -> back.run());
        heading.add(home);
        heading.add(Box.createVerticalStrut(15));
        title.putClientProperty("html.disable", true);
        summary.putClientProperty("html.disable", true);
        heading.add(title);
        heading.add(Box.createVerticalStrut(5));
        heading.add(summary);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        play = UiFactory.button("Play all", AppIcons.of(AppIcons.Kind.PLAY, 15, Theme.BLACK), true);
        play.addActionListener(event -> onPlay.accept(tracks, 0));
        queue = UiFactory.button("Add to queue", null, false);
        queue.addActionListener(event -> onQueue.accept(tracks));
        actions.add(play);
        actions.add(queue);
        heading.add(Box.createVerticalStrut(14));
        heading.add(actions);
        add(heading, BorderLayout.NORTH);
        songs.setFixedCellHeight(63);
        songs.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        songs.setCellRenderer(renderer);
        songs.getAccessibleContext().setAccessibleName("Collection songs");
        ToolTipManager.sharedInstance().registerComponent(songs);
        Runnable playSelection = () -> {
            if (songs.getSelectedIndex() >= 0) onPlay.accept(tracks, songs.getSelectedIndex());
        };
        songs.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                int index = songs.locationToIndex(event.getPoint());
                if (index >= 0 && songs.getCellBounds(index, index).contains(event.getPoint())) {
                    songs.setSelectedIndex(index);
                    trackMenu.apply(model.get(index)).show(songs, event.getX(), event.getY());
                }
            }
            @Override public void mouseClicked(MouseEvent event) {
                int index = songs.locationToIndex(event.getPoint());
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2 && index >= 0
                    && songs.getCellBounds(index, index).contains(event.getPoint())) playSelection.run();
            }
        });
        LibraryPanel.bind(songs, KeyStroke.getKeyStroke("ENTER"), "play", playSelection);
        LibraryPanel.bind(songs, KeyStroke.getKeyStroke("shift F10"), "context", () -> {
            int index = songs.getSelectedIndex();
            if (index < 0) return;
            Rectangle row = songs.getCellBounds(index, index);
            trackMenu.apply(model.get(index)).show(songs, 12, row.y + row.height);
        });
        JScrollPane scroll = new JScrollPane(songs, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.DIVIDER));
        add(scroll, BorderLayout.CENTER);
    }

    void showEntry(LibraryEntry entry, LibraryService.Catalog catalog) {
        title.setText(entry == null ? "Collection unavailable" : entry.name());
        if (entry == null) summary.setText("Choose another album or playlist from your library.");
        else {
            String type = switch (entry.kind()) {
                case PLAYLIST -> "Playlist";
                case ALBUM -> "Album";
                default -> "Artist";
            };
            String count = entry.tracks().size() + " tracks";
            summary.setText(type + " · " + count + (entry.detail().equals(count) ? "" : " · " + entry.detail()));
        }
        renderer.setTags(catalog.tags());
        renderer.setManuallyEdited(catalog.manualTags());
        renderer.setUnavailable(catalog.unavailable());
        List<Track> updated = entry == null ? List.of() : entry.tracks();
        if (!updated.equals(tracks)) {
            Track selection = songs.getSelectedValue();
            tracks = updated;
            model.clear();
            model.addAll(tracks);
            if (selection != null) tracks.stream().filter(track -> track.path().equals(selection.path()))
                .findFirst().ifPresent(track -> songs.setSelectedValue(track, false));
        }
        songs.repaint();
        play.setEnabled(!tracks.isEmpty());
        queue.setEnabled(!tracks.isEmpty());
    }
}
