package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.Playlist;
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
    private SessionComposerPanel composer;
    private SessionQueuePanel queue;

    public DashboardPanel(Runnable importMedia, Consumer<Track> onPlay, Consumer<String> notify,
                          Consumer<SessionPlanner.Request> onBuild, SessionQueuePanel.Actions queueActions) {
        super(new BorderLayout(0, 22));
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(28, 30, 24, 30));
        add(header(importMedia), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(content(onPlay, notify, onBuild, queueActions));
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.BLACK);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                boolean compact = scroll.getViewport().getWidth() < 700;
                composer.setCompact(compact);
                composer.setPreferredSize(new Dimension(0, compact ? 620 : 350));
                composer.setMaximumSize(new Dimension(Integer.MAX_VALUE, compact ? 640 : 380));
            }
        });
        add(scroll, BorderLayout.CENTER);
    }

    public void setTracks(List<Track> tracks) {
        queue.setAvailableTracks(tracks);
        shown.clear();
        shown.addAll(tracks.subList(0, Math.min(8, tracks.size())));
        model.setRowCount(0);
        for (int i = 0; i < shown.size(); i++) {
            Track track = shown.get(i);
            model.addRow(new Object[] {i + 1, track.title(), track.artist(), track.album(), track.durationLabel()});
        }
        libraryCaption.setText(tracks.isEmpty() ? "Import music to fill your library."
            : tracks.size() + " tracks in your library · double-click a row to play");
    }

    public void showPlan(SessionPlanner.Plan plan) {
        composer.showPlan(plan);
        queue.setPlan(plan);
        SwingUtilities.invokeLater(() -> queue.scrollRectToVisible(
            new Rectangle(0, 0, queue.getWidth(), queue.getHeight())));
    }

    public void setPlaylists(List<Playlist> playlists) { queue.setPlaylists(playlists); }
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
        composer = new SessionComposerPanel(notify, onBuild);
        composer.setAlignmentX(Component.LEFT_ALIGNMENT);
        composer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 380));
        composer.setPreferredSize(new Dimension(760, 350));
        content.add(composer);
        content.add(Box.createVerticalStrut(26));

        queue = new SessionQueuePanel(queueActions);
        queue.setAlignmentX(Component.LEFT_ALIGNMENT);
        queue.setPreferredSize(new Dimension(760, 390));
        queue.setMaximumSize(new Dimension(Integer.MAX_VALUE, 410));
        content.add(queue);
        content.add(Box.createVerticalStrut(26));

        JLabel heading = UiFactory.label("In your library", Theme.HEADING, Theme.TEXT);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(heading);
        content.add(Box.createVerticalStrut(6));
        libraryCaption.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(libraryCaption);
        content.add(Box.createVerticalStrut(12));

        JTable table = new JTable(model);
        table.setRowHeight(38);
        table.setFont(Theme.BODY);
        table.getTableHeader().setFont(Theme.BODY_BOLD);
        table.getTableHeader().setPreferredSize(new Dimension(0, 40));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 1));
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
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.getViewport().setBackground(Theme.SURFACE);
        tableScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        tableScroll.setBorder(UiFactory.roundedBorder(Theme.DIVIDER, 14));
        tableScroll.setPreferredSize(new Dimension(700, 220));
        tableScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
        content.add(tableScroll);
        return content;
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
