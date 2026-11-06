package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.Playlist;
import sangitam.desktop.session.SessionPlanner;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Home shelves, an explainable session builder, and an editable listening journey. */
public final class DashboardPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private SessionComposerPanel composer;
    private SessionQueuePanel queue;
    private final HomeLibraryPanel homeLibrary;

    public DashboardPanel(Runnable importMedia, Consumer<Track> onPlay, Consumer<String> notify,
                          Consumer<SessionPlanner.Request> onBuild, SessionQueuePanel.Actions queueActions,
                          Consumer<LibraryEntry> openCollection, Function<LibraryEntry, JPopupMenu> entryMenu) {
        super(new BorderLayout(0, 22));
        homeLibrary = new HomeLibraryPanel(entry -> {
            if (entry.kind() == sangitam.desktop.model.LibraryCollections.Kind.SONG) onPlay.accept(entry.tracks().getFirst());
            else openCollection.accept(entry);
        }, entryMenu);
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(28, 30, 24, 30));
        add(header(importMedia), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(content(onPlay, notify, onBuild, queueActions));
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.BLACK);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
    }

    public void setAnalysisCatalog(sangitam.desktop.library.LibraryService.Catalog catalog) { composer.setCatalog(catalog); queue.setAnalysis(catalog.tags()); }
    public void setAnalyzeAction(Runnable action) { composer.setAnalyzeAction(action); }
    public void setContext(String text) { composer.showContext(text); }
    public void setTaste(java.util.Map<java.nio.file.Path,Integer> ratings) { queue.setTaste(ratings); }
    public void setTracks(List<Track> tracks) {
        queue.setAvailableTracks(tracks);

    }

    public void showPlan(SessionPlanner.Plan plan) {
        composer.showPlan(plan);
        queue.setPlan(plan);
        SwingUtilities.invokeLater(() -> queue.scrollRectToVisible(
            new Rectangle(0, 0, queue.getWidth(), queue.getHeight())));
    }

    public void setPlaylists(List<Playlist> playlists) { queue.setPlaylists(playlists); }
    void setHomeData(HomeLibraryData data) { homeLibrary.setData(data); }
    void close() { homeLibrary.close(); }
    public void setQueue(List<SessionPlanner.Choice> choices) {
        queue.setPlan(new SessionPlanner.Plan(choices, 0, 0));
    }
    public void selectPlaylist(String name) { queue.selectPlaylist(name); }
    public List<SessionPlanner.Choice> currentQueue() { return queue.currentChoices(); }
    public void focusPlaylists() {
        queue.scrollRectToVisible(new Rectangle(0, 0, queue.getWidth(), queue.getHeight()));
        queue.focusSavedPlaylists();
    }

    public void showPlaylist(Playlist playlist, SessionPlanner.Plan plan) {
        composer.showPlaylist(playlist.name(), plan);
        queue.setPlan(plan);
        queue.selectPlaylist(playlist.name());
    }

    private JComponent header(Runnable importMedia) {
        JPanel header = new JPanel(new BorderLayout(20, 10));
        header.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(UiFactory.label("Music for the moment you’re in", Theme.TITLE, Theme.TEXT));
        titles.add(Box.createVerticalStrut(5));
        titles.add(UiFactory.label("Shape a session, browse your music, or bring in something new.", Theme.BODY, Theme.TEXT_MUTED));
        header.add(titles, BorderLayout.CENTER);
        JButton add = UiFactory.button("Add music", AppIcons.of(AppIcons.Kind.PLUS, 16, Theme.BLACK), true);
        add.addActionListener(event -> importMedia.run());
        header.add(add, BorderLayout.EAST);
        return header;
    }

    private JComponent content(Consumer<Track> onPlay, Consumer<String> notify,
                               Consumer<SessionPlanner.Request> onBuild,
                               SessionQueuePanel.Actions queueActions) {
        JPanel content = new ResponsiveColumn();
        content.setBackground(Theme.BLACK);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        homeLibrary.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(homeLibrary);
        content.add(Box.createVerticalStrut(26));
        composer = new SessionComposerPanel(notify, onBuild);
        composer.setAlignmentX(Component.LEFT_ALIGNMENT);
        composer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 540));
        composer.setPreferredSize(new Dimension(760, 520));
        content.add(composer);
        content.add(Box.createVerticalStrut(26));

        queue = new SessionQueuePanel(queueActions);
        queue.setAlignmentX(Component.LEFT_ALIGNMENT);
        queue.setPreferredSize(new Dimension(760, 620));
        queue.setMaximumSize(new Dimension(Integer.MAX_VALUE, 660));
        content.add(queue);
        content.add(Box.createVerticalStrut(26));

        return content;
    }

    private static final class ResponsiveColumn extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(100, visible.height - 32);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
