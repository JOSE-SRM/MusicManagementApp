package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.Playlist;
import sangitam.desktop.model.ListeningMoment;
import sangitam.desktop.session.SessionPlanner;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Home screen: real session builder, editable queue, and a preview of indexed local music. */
public final class DashboardPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final DefaultTableModel model = new DefaultTableModel(
        new String[] {"#", "Title", "Artist", "Album", "Duration"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final List<Track> shown = new ArrayList<>();
    private final JLabel libraryCaption = UiFactory.label("Import music to fill your library.", Theme.SMALL, Theme.TEXT_MUTED);
    private final JButton resumeButton = UiFactory.button("Continue listening", AppIcons.of(AppIcons.Kind.PLAY, 15, Theme.BLACK), true);
    private SessionComposerPanel composer;
    private SessionQueuePanel queue;
    private ListeningMomentsPanel moments;
    private JComponent emptyLibrary;
    private JScrollPane trackTable;

    public DashboardPanel(Runnable importMedia, Consumer<Track> onPlay, Consumer<String> notify,
                          Consumer<SessionPlanner.Request> onBuild, SessionQueuePanel.Actions queueActions,
                          Consumer<ListeningMoment> onOpenMoment, Runnable openNowPlaying) {
        super(new BorderLayout(0, 22));
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(24, 30, 24, 30));
        add(header(importMedia, openNowPlaying), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(content(importMedia, onPlay, notify, onBuild, queueActions, onOpenMoment));
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.BLACK);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                boolean compact = scroll.getViewport().getWidth() < 700;
                composer.setCompact(compact);
                composer.setPreferredSize(new Dimension(0, compact ? 850 : 520));
                composer.setMaximumSize(new Dimension(Integer.MAX_VALUE, compact ? 870 : 540));
            }
        });
        add(scroll, BorderLayout.CENTER);
    }

    public void setTracks(List<Track> tracks) {
        queue.setAvailableTracks(tracks);
        composer.setLibraryCount(tracks.size());
        shown.clear();
        shown.addAll(tracks.subList(0, Math.min(8, tracks.size())));
        model.setRowCount(0);
        for (int i = 0; i < shown.size(); i++) {
            Track track = shown.get(i);
            model.addRow(new Object[] {i + 1, track.title(), track.artist(), track.album(), track.durationLabel()});
        }
        libraryCaption.setText(tracks.isEmpty() ? "Your music will live here."
            : tracks.size() + (tracks.size() == 1 ? " track in your collection" : " tracks in your collection"));
        if (emptyLibrary != null && trackTable != null) {
            emptyLibrary.setVisible(tracks.isEmpty());
            trackTable.setVisible(!tracks.isEmpty());
            emptyLibrary.getParent().revalidate();
        }
    }

    public void setMoments(List<ListeningMoment> saved) { moments.setMoments(saved); }
    public ListeningSpace currentSpace() { return composer.currentSpace(); }

    public void setCurrentTrack(Track track) {
        resumeButton.setVisible(track != null);
        if (track != null) {
            resumeButton.setText("Continue  ·  " + track.title());
            resumeButton.setToolTipText("Return to " + track.title() + " by " + track.artist());
        }
    }

    public void showPlan(SessionPlanner.Plan plan) {
        composer.showPlan(plan);
        queue.setPlan(plan);
        SwingUtilities.invokeLater(() -> queue.scrollRectToVisible(
            new Rectangle(0, 0, queue.getWidth(), queue.getHeight())));
    }

    public void setPlaylists(List<Playlist> playlists) { queue.setPlaylists(playlists); }
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

    private JComponent header(Runnable importMedia, Runnable openNowPlaying) {
        JPanel header = new JPanel(new BorderLayout(20, 10));
        header.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(UiFactory.label("Come in. Find your sound.", Theme.TITLE, Theme.TEXT));
        titles.add(Box.createVerticalStrut(5));
        titles.add(UiFactory.label("Make a little room for the music you brought with you.", Theme.BODY, Theme.TEXT_MUTED));
        header.add(titles, BorderLayout.CENTER);
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        resumeButton.addActionListener(event -> openNowPlaying.run());
        resumeButton.setAlignmentX(Component.RIGHT_ALIGNMENT);
        resumeButton.setVisible(false);
        actions.add(resumeButton);
        actions.add(Box.createVerticalStrut(8));
        JButton add = UiFactory.button("Add music", AppIcons.of(AppIcons.Kind.PLUS, 16, Theme.BLACK), true);
        add.addActionListener(event -> importMedia.run());
        add.setAlignmentX(Component.RIGHT_ALIGNMENT);
        actions.add(add);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JComponent content(Runnable importMedia, Consumer<Track> onPlay, Consumer<String> notify,
                               Consumer<SessionPlanner.Request> onBuild,
                               SessionQueuePanel.Actions queueActions,
                               Consumer<ListeningMoment> onOpenMoment) {
        JPanel content = new ResponsiveColumn();
        content.setBackground(Theme.BLACK);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        composer = new SessionComposerPanel(notify, onBuild);
        composer.setAlignmentX(Component.LEFT_ALIGNMENT);
        composer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 540));
        composer.setPreferredSize(new Dimension(760, 520));
        content.add(composer);
        content.add(Box.createVerticalStrut(26));

        queue = new SessionQueuePanel(queueActions);
        queue.setAlignmentX(Component.LEFT_ALIGNMENT);
        queue.setPreferredSize(new Dimension(760, 236));
        queue.setMaximumSize(new Dimension(Integer.MAX_VALUE, 410));
        content.add(queue);
        content.add(Box.createVerticalStrut(26));

        moments = new ListeningMomentsPanel(onOpenMoment);
        moments.setAlignmentX(Component.LEFT_ALIGNMENT);
        moments.setMaximumSize(new Dimension(Integer.MAX_VALUE, 720));
        content.add(moments);
        content.add(Box.createVerticalStrut(26));

        JLabel heading = UiFactory.label("Your collection", Theme.HEADING, Theme.TEXT);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);
        content.add(Box.createVerticalStrut(6));
        libraryCaption.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(libraryCaption);
        content.add(Box.createVerticalStrut(12));

        JTable table = new JTable(model);
        table.setRowHeight(42);
        table.setFont(Theme.BODY);
        table.getTableHeader().setFont(Theme.BODY_BOLD);
        table.getTableHeader().setPreferredSize(new Dimension(0, 40));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setSelectionBackground(Theme.BLUE_STRONG);
        table.setSelectionForeground(Theme.BLACK);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(42);
        table.getColumnModel().getColumn(1).setPreferredWidth(210);
        table.getColumnModel().getColumn(2).setPreferredWidth(175);
        table.getColumnModel().getColumn(3).setPreferredWidth(175);
        table.getColumnModel().getColumn(4).setPreferredWidth(82);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) playSelected(table, onPlay);
            }
        });
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "playSelected");
        table.getActionMap().put("playSelected", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { playSelected(table, onPlay); }
        });
        trackTable = new JScrollPane(table);
        trackTable.getViewport().setBackground(Theme.SURFACE);
        trackTable.setAlignmentX(Component.LEFT_ALIGNMENT);
        trackTable.setBorder(UiFactory.roundedBorder(Theme.DIVIDER, 14));
        trackTable.setPreferredSize(new Dimension(700, 220));
        trackTable.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));

        emptyLibrary = emptyLibrary(importMedia);
        emptyLibrary.setAlignmentX(Component.LEFT_ALIGNMENT);
        emptyLibrary.setPreferredSize(new Dimension(700, 150));
        emptyLibrary.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        content.add(emptyLibrary);
        content.add(trackTable);
        trackTable.setVisible(false);
        return content;
    }

    private JComponent emptyLibrary(Runnable importMedia) {
        JPanel empty = new JPanel(new BorderLayout(18, 0));
        empty.setBackground(Theme.SURFACE);
        empty.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 18), Theme.padding(18, 20, 18, 20)));
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        JLabel title = UiFactory.label("Start with a song you love.", Theme.HEADING, Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel detail = UiFactory.label("Bring in a folder or a few tracks. Sangitam keeps your originals where they are.",
            Theme.SMALL, Theme.TEXT_MUTED);
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        copy.add(title);
        copy.add(Box.createVerticalStrut(6));
        copy.add(detail);
        empty.add(copy, BorderLayout.CENTER);
        JButton add = UiFactory.button("Choose music", AppIcons.of(AppIcons.Kind.PLUS, 14, Theme.BLACK), true);
        add.addActionListener(event -> importMedia.run());
        empty.add(add, BorderLayout.EAST);
        return empty;
    }

    private void playSelected(JTable table, Consumer<Track> onPlay) {
        int row = table.getSelectedRow();
        if (row >= 0 && row < shown.size()) onPlay.accept(shown.get(row));
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
