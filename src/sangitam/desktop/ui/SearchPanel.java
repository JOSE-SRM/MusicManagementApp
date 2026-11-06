package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;
import sangitam.desktop.storage.LibrarySearchStore.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.*;

/** Database-backed, bounded pages; workers own SQL and generations reject obsolete results. */
public final class SearchPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int PAGE_SIZE = 100;
    @FunctionalInterface public interface Query { Page search(Request request) throws Exception; }
    private final Query query;
    private final JTextField search = new JTextField();
    private final JCheckBox availableOnly = new JCheckBox("Available only");
    private final JComboBox<Order> order = new JComboBox<>(Order.values());
    private final JLabel count = UiFactory.label("Loading library…", Theme.SMALL, Theme.TEXT_MUTED);
    private final JButton previous = UiFactory.button("Previous", null, false);
    private final JButton next = UiFactory.button("Next", null, false);
    private final JTabbedPane tabs = new JTabbedPane();
    private final Map<Category, DefaultListModel<Hit>> models = new EnumMap<>(Category.class);
    private final TrackCellRenderer songRenderer = new TrackCellRenderer();
    private final DefaultListCellRenderer facetRenderer = new DefaultListCellRenderer();
    private final Timer debounce = new Timer(180, event -> runSearch(0));
    private SwingWorker<Page, Void> activeSearch;
    private Page displayed;
    private long generation;
    private boolean closed;

    public SearchPanel(Query query, Consumer<Track> onPlay, BiConsumer<Category, Hit> onOpen,
                       Function<Track, JPopupMenu> songMenu) {
        super(new BorderLayout(0, 16));
        this.query = query;
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(28, 30, 24, 30));
        JPanel top = new JPanel(new BorderLayout(12, 10));
        top.setOpaque(false);
        top.add(UiFactory.label("Search your music", Theme.TITLE, Theme.TEXT), BorderLayout.NORTH);
        search.setFont(Theme.BODY);
        search.setBorder(BorderFactory.createCompoundBorder(UiFactory.roundedBorder(Theme.DIVIDER, 14), Theme.padding(11, 14, 11, 14)));
        search.setToolTipText("Search titles, artists, albums, classical details and custom tags");
        search.getAccessibleContext().setAccessibleName("Search library");
        top.add(search, BorderLayout.CENTER);
        JPanel filters = new JPanel(new BorderLayout(12, 0));
        filters.setOpaque(false);
        count.getAccessibleContext().setAccessibleName("Search result count");
        filters.add(count, BorderLayout.CENTER);
        JPanel choices = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        choices.setOpaque(false);
        availableOnly.setOpaque(false);
        availableOnly.setToolTipText("Use availability from the last library scan");
        availableOnly.addActionListener(event -> runSearch(0));
        order.getAccessibleContext().setAccessibleName("Search order");
        order.addActionListener(event -> runSearch(0));
        choices.add(availableOnly); choices.add(order);
        filters.add(choices, BorderLayout.EAST);
        top.add(filters, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        for (Category category : Category.values()) {
            var model = new DefaultListModel<Hit>();
            models.put(category, model);
            JList<Hit> list = new JList<>(model);
            ToolTipManager.sharedInstance().registerComponent(list);
            list.setFixedCellHeight(category == Category.SONGS ? 63 : 48);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.getAccessibleContext().setAccessibleName(category + " search results");
            list.setCellRenderer((view, hit, index, selected, focus) -> {
                if (hit.track() != null) return songRenderer.getListCellRendererComponent(view, hit.track(), index, selected, focus);
                return facetRenderer.getListCellRendererComponent(view, hit, index, selected, focus);
            });
            Runnable open = () -> {
                Hit hit = list.getSelectedValue();
                if (hit == null) return;
                if (hit.track() != null) onPlay.accept(hit.track()); else onOpen.accept(category, hit);
            };
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    int index = list.locationToIndex(event.getPoint());
                    if (index >= 0 && list.getCellBounds(index, index).contains(event.getPoint()) && event.getClickCount() == 2) open.run();
                }
                private void context(MouseEvent event) {
                    if (!event.isPopupTrigger() || category != Category.SONGS) return;
                    int index = list.locationToIndex(event.getPoint());
                    if (index < 0 || !list.getCellBounds(index, index).contains(event.getPoint())) return;
                    list.setSelectedIndex(index);
                    songMenu.apply(list.getSelectedValue().track()).show(list, event.getX(), event.getY());
                }
                @Override public void mousePressed(MouseEvent event) { context(event); }
                @Override public void mouseReleased(MouseEvent event) { context(event); }
            });
            LibraryPanel.bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open", open);
            if (category == Category.SONGS) LibraryPanel.bind(list, KeyStroke.getKeyStroke(KeyEvent.VK_F10, KeyEvent.SHIFT_DOWN_MASK), "context", () -> {
                Hit hit = list.getSelectedValue();
                if (hit != null) songMenu.apply(hit.track()).show(list, 20, Math.max(0, list.getCellBounds(list.getSelectedIndex(), list.getSelectedIndex()).y));
            });
            tabs.addTab(switch (category) { case SONGS -> "Songs"; case ALBUMS -> "Albums"; case ARTISTS -> "Artists"; }, new JScrollPane(list));
        }
        tabs.addChangeListener(event -> runSearch(0));
        add(tabs, BorderLayout.CENTER);
        JPanel paging = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        paging.setOpaque(false);
        previous.addActionListener(event -> runSearch(Math.max(0, displayed.request().offset() - PAGE_SIZE)));
        next.addActionListener(event -> runSearch(displayed.request().offset() + PAGE_SIZE));
        previous.setEnabled(false); next.setEnabled(false);
        paging.add(previous); paging.add(next);
        add(paging, BorderLayout.SOUTH);
        debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
            private void changed() {
                generation++;
                previous.setEnabled(false); next.setEnabled(false);
                models.values().forEach(DefaultListModel::clear);
                count.setText("Searching…");
                debounce.restart();
            }
        });
    }
    public void setCatalogState(Map<Path, TrackTags> tags, Set<Path> manual, Set<Path> unavailable) {
        songRenderer.setTags(tags); songRenderer.setManuallyEdited(manual); songRenderer.setUnavailable(unavailable);
        runSearch(0);
    }
    public void selectCategory(String category) {
        tabs.setSelectedIndex(category.equals("albums") ? 1 : category.equals("artists") ? 2 : 0);
        search.requestFocusInWindow();
    }
    private void runSearch(int offset) {
        if (closed) return;
        debounce.stop();
        if (activeSearch != null) activeSearch.cancel(true);
        long requestId = ++generation;
        Request request = new Request(search.getText(), Category.values()[tabs.getSelectedIndex()], availableOnly.isSelected(), (Order) order.getSelectedItem(), offset, PAGE_SIZE);
        previous.setEnabled(false); next.setEnabled(false);
        models.values().forEach(DefaultListModel::clear);
        count.setText("Searching…");
        activeSearch = new SwingWorker<>() {
            @Override protected Page doInBackground() throws Exception { return query.search(request); }
            @Override protected void done() {
                if (isCancelled() || closed || requestId != generation) return;
                try {
                    displayed = get();
                    if (displayed.hits().isEmpty() && offset > 0) { runSearch(0); return; }
                    models.get(request.category()).addAll(displayed.hits());
                    long first = displayed.total() == 0 ? 0 : offset + 1L;
                    count.setText(first + "–" + (offset + displayed.hits().size()) + " of " + displayed.total());
                    previous.setEnabled(offset > 0); next.setEnabled(displayed.hasNext());
                } catch (Exception error) { count.setText("Search could not complete. Try again or rescan the library."); }
            }
        };
        activeSearch.execute();
    }
    public void close() { closed = true; generation++; debounce.stop(); if (activeSearch != null) activeSearch.cancel(true); }
}
