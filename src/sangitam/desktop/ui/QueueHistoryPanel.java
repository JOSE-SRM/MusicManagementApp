package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import sangitam.desktop.library.LibraryService;
import sangitam.desktop.session.SessionPlanner;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.*;

/** Bottom half of the library rail. Queue rows preserve order and repeated tracks. */
final class QueueHistoryPanel extends JPanel {
    private final QueueModel queueModel = new QueueModel();
    private final DefaultListModel<Track> recentModel = new DefaultListModel<>();
    private final TrackCellRenderer renderer = new TrackCellRenderer();
    private final JList<Track> queue;
    private final JTabbedPane tabs = new JTabbedPane();
    QueueHistoryPanel(IntConsumer playQueue, Consumer<Track> playRecent, Function<Track, JPopupMenu> menu, Runnable close) {
        super(new BorderLayout(0, 6)); setBackground(Theme.SURFACE); setBorder(Theme.padding(8, 8, 8, 8));
        JPanel top = new JPanel(new BorderLayout()); top.setOpaque(false); top.add(UiFactory.label("Up next & history", Theme.BODY_BOLD, Theme.TEXT));
        JButton hide = UiFactory.button("Close", null, false); hide.addActionListener(event -> close.run()); top.add(hide, BorderLayout.EAST); add(top, BorderLayout.NORTH);
        queue = list(queueModel, "Playback queue", list -> playQueue.accept(list.getSelectedIndex()), menu);
        JList<Track> recent = list(recentModel, "Recently played tracks", list -> playRecent.accept(list.getSelectedValue()), menu);
        JScrollPane queueScroll = new PixelScrollPane(queue), recentScroll = new PixelScrollPane(recent); UiFactory.styleScroll(queueScroll); UiFactory.styleScroll(recentScroll);
        tabs.addTab("Queue", queueScroll); tabs.addTab("Recently played", recentScroll); add(tabs);
        setMinimumSize(new Dimension(230, 130));
    }
    private JList<Track> list(ListModel<Track> model, String name, Consumer<JList<Track>> play, Function<Track, JPopupMenu> menu) {
        JList<Track> list = new JList<>(model); list.setCellRenderer(renderer); list.setFixedCellHeight(68); list.setFixedCellWidth(220); list.getAccessibleContext().setAccessibleName(name);
        LibraryPanel.bind(list, KeyStroke.getKeyStroke("ENTER"), "play", () -> { if (list.getSelectedIndex() >= 0) play.accept(list); });
        LibraryPanel.bind(list, KeyStroke.getKeyStroke("shift F10"), "menu", () -> { if (list.getSelectedIndex() >= 0) menu.apply(list.getSelectedValue()).show(list, 8, list.getCellBounds(list.getSelectedIndex(), list.getSelectedIndex()).y); });
        list.addMouseListener(new MouseAdapter() {
            private void popup(MouseEvent event) { if (!event.isPopupTrigger()) return; int at = list.locationToIndex(event.getPoint()); if (at >= 0 && list.getCellBounds(at, at).contains(event.getPoint())) { list.setSelectedIndex(at); menu.apply(model.getElementAt(at)).show(list, event.getX(), event.getY()); } }
            @Override public void mousePressed(MouseEvent event) { popup(event); } @Override public void mouseReleased(MouseEvent event) { popup(event); }
            @Override public void mouseClicked(MouseEvent event) { int at = list.locationToIndex(event.getPoint()); if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event) && at >= 0 && list.getCellBounds(at, at).contains(event.getPoint())) play.accept(list); }
        }); return list;
    }
    void setCatalog(LibraryService.Catalog catalog) { renderer.setTags(catalog.tags()); renderer.setManuallyEdited(catalog.manualTags()); renderer.setUnavailable(catalog.unavailable()); repaint(); }
    void setQueue(List<SessionPlanner.Choice> choices, int current) {
        showQueue(choices.stream().map(SessionPlanner.Choice::track).toList(), current, "Queue");
    }
    void setShuffledQueue(List<Track> order, boolean hasCurrent) {
        showQueue(order, hasCurrent ? 0 : -1, "Shuffled queue");
    }
    private void showQueue(List<Track> order, int current, String title) {
        queueModel.setTracks(order);
        queue.clearSelection();
        tabs.setTitleAt(0, title);
        if (current >= 0 && current < queueModel.getSize()) {
            queue.setSelectedIndex(current);
            queue.ensureIndexIsVisible(current);
        }
    }
    void setRecent(List<Track> tracks) { recentModel.clear(); recentModel.addAll(tracks); }
    void showRecent() { tabs.setSelectedIndex(1); }

    private static final class QueueModel extends AbstractListModel<Track> {
        private List<Track> tracks = List.of();
        private int size;

        void setTracks(List<Track> next) {
            int oldSize = size;
            tracks = next;
            size = next.size();
            int retained = Math.min(oldSize, size);
            if (retained > 0) fireContentsChanged(this, 0, retained - 1);
            if (size > oldSize) fireIntervalAdded(this, oldSize, size - 1);
            if (size < oldSize) fireIntervalRemoved(this, size, oldSize - 1);
        }

        @Override public int getSize() { return size; }
        @Override public Track getElementAt(int index) { return tracks.get(index); }
    }
}
