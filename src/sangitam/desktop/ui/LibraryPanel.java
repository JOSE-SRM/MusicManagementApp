package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.LibraryCollections;
import sangitam.desktop.model.LibraryCollections.Key;
import sangitam.desktop.model.LibraryCollections.Kind;
import sangitam.desktop.model.Track;
import sangitam.desktop.storage.CollectionStore;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static sangitam.desktop.model.LibraryCollections.Kind.*;

public final class LibraryPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int COMPACT_WIDTH = 300;

    @FunctionalInterface
    interface CollectionEdit { void run(CollectionStore store) throws Exception; }

    interface Actions {
        void importMusic(long folder);
        void rescan();
        void play(List<Track> tracks);
        void queue(List<Track> tracks);
        void editDetails(List<Track> tracks, boolean group);
        void remove(List<Path> paths, Path scope);
        void open(LibraryEntry entry);
        void organize(String message, CollectionEdit edit);
        void deletePlaylist(long id);
        void entriesChanged();
    }

    private enum Order {
        RECENT("Recent"), ALPHABETICAL("Alphabetical"), PLAYED("Most played");
        private final String label;
        Order(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final Actions actions;
    private final JTextField search = new JTextField();
    private final JComboBox<Order> sort = new JComboBox<>(Order.values());
    private final JLabel count = UiFactory.label("0 tracks", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel location = UiFactory.label("Library", Theme.SMALL, Theme.TEXT_MUTED);
    private final JButton back = UiFactory.button("Back", null, false);
    private final JTabbedPane tabs = new JTabbedPane();
    private final DefaultListModel<LibraryEntry> model = new DefaultListModel<>();
    private final JList<LibraryEntry> general = new JList<>(model);
    private final LibraryFolderTree locations;
    private final JButton expand;
    private final Timer debounce = new Timer(160, event -> refreshView());
    private final LibraryArtwork artwork = new LibraryArtwork();
    private final LibraryMenus menus;
    private LibraryService.Catalog catalog = new LibraryService.Catalog(List.of(), Map.of(), Map.of());
    private LibraryCollections collections = LibraryCollections.empty();
    private List<LibraryEntry> entries = List.of();
    private SwingWorker<List<LibraryEntry>, Void> loader;
    private long folder;
    private boolean expanded;
    private Timer animation;

    LibraryPanel(Actions actions) {
        super(new BorderLayout(0, 12));
        this.actions = actions;
        menus = new LibraryMenus(this, actions);
        setBackground(Theme.SURFACE);
        setPreferredSize(new Dimension(COMPACT_WIDTH, 0));
        setMinimumSize(new Dimension(220, 0));
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.DIVIDER), Theme.padding(18, 16, 14, 16)));

        expand = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.EXPAND, 17, Theme.BLUE), "Expand library");
        expand.addActionListener(event -> setExpanded(!expanded));
        JPanel heading = row();
        heading.add(UiFactory.label("Your library", Theme.HEADING, Theme.TEXT), BorderLayout.CENTER);
        heading.add(expand, BorderLayout.EAST);

        JPanel tools = row();
        search.setToolTipText("Search the current library tab");
        search.getAccessibleContext().setAccessibleName("Search library");
        search.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 8), Theme.padding(7, 9, 7, 9)));
        listen(search, debounce::restart);
        debounce.setRepeats(false);
        tools.add(new JLabel(AppIcons.of(AppIcons.Kind.SEARCH, 16, Theme.TEXT_MUTED)), BorderLayout.WEST);
        tools.add(search, BorderLayout.CENTER);
        JButton add = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.PLUS, 17, Theme.BLUE), "Add to library");
        add.addActionListener(event -> menus.addMenu().show(add, 0, add.getHeight()));
        tools.add(add, BorderLayout.EAST);

        JPanel controls = row();
        back.addActionListener(event -> browseFolder(parentOf(folder)));
        controls.add(back, BorderLayout.WEST);
        controls.add(location, BorderLayout.CENTER);
        sort.setToolTipText("Order General library");
        sort.getAccessibleContext().setAccessibleName("Library sort order");
        sort.addActionListener(event -> refreshView());
        controls.add(sort, BorderLayout.EAST);
        JPanel top = new JPanel(new GridLayout(3, 1, 0, 8));
        top.setOpaque(false);
        top.add(heading);
        top.add(tools);
        top.add(controls);
        top.setComponentPopupMenu(menus.libraryMenu());
        search.setComponentPopupMenu(menus.libraryMenu());
        add(top, BorderLayout.NORTH);

        general.setFixedCellHeight(63);
        general.setFixedCellWidth(210);
        general.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        general.setCellRenderer(new EntryRenderer());
        general.setDragEnabled(!GraphicsEnvironment.isHeadless());
        general.setDropMode(DropMode.ON);
        general.setTransferHandler(new EntryTransfer());
        ToolTipManager.sharedInstance().registerComponent(general);
        general.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    LibraryEntry entry = entryAt(event.getPoint());
                    if (entry != null) open(entry);
                }
            }
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                LibraryEntry entry = entryAt(event.getPoint());
                if (entry != null) general.setSelectedValue(entry, true);
                (entry == null ? menus.libraryMenu() : menus.entryMenu(entry)).show(general, event.getX(), event.getY());
            }
        });
        bind(general, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open", () -> {
            if (general.getSelectedValue() != null) open(general.getSelectedValue());
        });
        bind(general, KeyStroke.getKeyStroke(KeyEvent.VK_F10, KeyEvent.SHIFT_DOWN_MASK), "context", () -> {
            LibraryEntry entry = general.getSelectedValue();
            if (entry == null) return;
            Rectangle row = general.getCellBounds(general.getSelectedIndex(), general.getSelectedIndex());
            menus.entryMenu(entry).show(general, 12, row.y + row.height);
        });

        locations = new LibraryFolderTree(track -> actions.play(List.of(track)));
        locations.onContext((value, point) -> {
            JPopupMenu menu;
            if (value instanceof Path path) menu = menus.locationMenu(path);
            else if (value instanceof Track track) menu = trackMenu(track);
            else menu = menus.libraryMenu();
            menu.show(locations, point.x, point.y);
        });
        tabs.addTab("General", scroll(general, false));
        tabs.addTab("Locations", scroll(locations, true));
        tabs.addChangeListener(event -> refreshView());
        add(tabs, BorderLayout.CENTER);
        add(count, BorderLayout.SOUTH);
        refreshView();
    }

    void setCatalog(LibraryService.Catalog catalog) {
        this.catalog = catalog;
        reloadEntries();
    }

    void setCollections(LibraryCollections collections) {
        this.collections = collections;
        if (folder != 0 && collections.folders().stream().noneMatch(item -> item.id() == folder)) folder = 0;
        reloadEntries();
    }

    LibraryCollections collections() { return collections; }
    List<Track> tracks() { return catalog.tracks(); }
    long currentFolder() { return folder; }

    LibraryEntry entry(Key key) {
        return entries.stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
    }

    JPopupMenu trackMenu(Track track) {
        LibraryEntry row = entry(new Key(SONG, track.path().toString()));
        return row == null ? menus.libraryMenu() : menus.entryMenu(row);
    }

    private void reloadEntries() {
        if (loader != null) loader.cancel(true);
        LibraryService.Catalog input = catalog;
        LibraryCollections organization = collections;
        loader = new SwingWorker<>() {
            @Override protected List<LibraryEntry> doInBackground() { return LibraryEntry.build(input, organization); }
            @Override protected void done() {
                if (isCancelled() || loader != this) return;
                try {
                    entries = get();
                    refreshView();
                    actions.entriesChanged();
                } catch (java.util.concurrent.ExecutionException error) {
                    System.getLogger(LibraryPanel.class.getName()).log(System.Logger.Level.WARNING,
                        "Could not build General library", error.getCause());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        loader.execute();
    }

    private void refreshView() {
        String query = LibraryEntry.lower(search.getText().strip());
        boolean local = tabs.getSelectedIndex() == 1;
        back.setVisible(!local && folder != 0);
        sort.setVisible(!local);
        location.setText(local ? "On this computer" : folderName(folder));
        count.setText(catalog.tracks().size() + " tracks" + (catalog.unavailable().isEmpty() ? ""
            : " · " + catalog.unavailable().size() + " unavailable"));
        if (local) {
            List<Track> tracks = query.isEmpty() ? catalog.tracks() : catalog.tracks().stream()
                .filter(track -> LibraryEntry.lower(track + " " + track.album() + " " + track.path()).contains(query)).toList();
            locations.setTracks(tracks, catalog.unavailable());
            return;
        }
        Set<Key> pins = collections.pins();
        Comparator<LibraryEntry> alphabetical = Comparator.comparing(LibraryEntry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(item -> item.key().id());
        Comparator<LibraryEntry> order = switch ((Order) sort.getSelectedItem()) {
            case ALPHABETICAL -> alphabetical;
            case PLAYED -> Comparator.comparingLong(LibraryEntry::plays).reversed().thenComparing(alphabetical);
            case RECENT -> Comparator.comparingLong(LibraryEntry::recent).reversed().thenComparing(alphabetical);
        };
        Set<Path> members = collections.folders().stream().filter(item -> item.id() == folder)
            .findFirst().map(item -> Set.copyOf(item.tracks())).orElse(Set.of());
        LibraryEntry selected = general.getSelectedValue();
        List<LibraryEntry> visible = entries.stream()
            .filter(item -> folder == 0 ? !query.isEmpty() || item.parent() == 0 || pins.contains(item.key())
                : (item.parent() == folder || item.kind() == SONG && members.contains(item.tracks().getFirst().path())))
            .filter(item -> query.isEmpty() || LibraryEntry.lower(item.name() + " " + item.detail()
                + " " + item.kind()).contains(query))
            .sorted(Comparator.<LibraryEntry, Boolean>comparing(item -> !pins.contains(item.key())).thenComparing(order))
            .toList();
        model.clear();
        model.addAll(visible);
        if (selected != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).key().equals(selected.key())) { general.setSelectedIndex(i); break; }
            }
        }
    }

    String folderName(long id) {
        return collections.folders().stream().filter(item -> item.id() == id)
            .findFirst().map(LibraryCollections.Folder::name).orElse("Library");
    }

    private long parentOf(long id) {
        return collections.folders().stream().filter(item -> item.id() == id)
            .findFirst().map(LibraryCollections.Folder::parentId).orElse(0L);
    }

    void browseFolder(long id) {
        folder = id;
        tabs.setSelectedIndex(0);
        search.setText("");
        refreshView();
    }

    void collapse() {
        if (expanded) setExpanded(false);
    }

    void close() {
        debounce.stop();
        if (animation != null) animation.stop();
        if (loader != null) loader.cancel(true);
        artwork.close();
    }

    private void open(LibraryEntry entry) {
        switch (entry.kind()) {
            case SONG -> actions.play(entry.tracks());
            case FOLDER -> browseFolder(entry.id());
            default -> actions.open(entry);
        }
    }

    private LibraryEntry entryAt(Point point) {
        int index = general.locationToIndex(point);
        return index >= 0 && general.getCellBounds(index, index).contains(point) ? model.get(index) : null;
    }

    private JPanel row() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        return row;
    }

    private JScrollPane scroll(JComponent view, boolean horizontal) {
        JScrollPane pane = new JScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            horizontal ? ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED : ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setBorder(BorderFactory.createLineBorder(Theme.DIVIDER));
        pane.getViewport().setBackground(Theme.SURFACE);
        return pane;
    }

    private void setExpanded(boolean expanded) {
        this.expanded = expanded;
        expand.setIcon(AppIcons.of(expanded ? AppIcons.Kind.COLLAPSE : AppIcons.Kind.EXPAND, 17, Theme.BLUE));
        expand.setToolTipText(expanded ? "Collapse library" : "Expand library");
        expand.getAccessibleContext().setAccessibleName(expand.getToolTipText());
        int available = getParent() == null ? 900 : getParent().getWidth();
        int target = expanded ? Math.max(COMPACT_WIDTH, available - 32) : COMPACT_WIDTH;
        if (animation != null) animation.stop();
        animation = new Timer(15, event -> {
            int width = getPreferredSize().width;
            int distance = target - width;
            if (Math.abs(distance) <= 6) {
                setPreferredSize(new Dimension(target, 0));
                ((Timer) event.getSource()).stop();
            } else {
                int step = Math.max(6, Math.abs(distance) / 5);
                setPreferredSize(new Dimension(width + (distance > 0 ? step : -step), 0));
            }
            revalidate();
        });
        animation.start();
    }

    static void listen(JTextField field, Runnable change) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { change.run(); }
            @Override public void removeUpdate(DocumentEvent event) { change.run(); }
            @Override public void changedUpdate(DocumentEvent event) { change.run(); }
        });
    }

    static void bind(JComponent component, KeyStroke key, String name, Runnable action) {
        component.getInputMap().put(key, name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { action.run(); }
        });
    }

    private final class EntryRenderer extends JPanel implements ListCellRenderer<LibraryEntry> {
        private final JLabel picture = new JLabel();
        private final JLabel title = new JLabel();
        private final JLabel subtitle = new JLabel();
        private final Map<Kind, Icon> fallback = new java.util.EnumMap<>(Kind.class);

        EntryRenderer() {
            super(new BorderLayout(10, 0));
            setBorder(Theme.padding(5, 8, 5, 8));
            picture.setPreferredSize(new Dimension(46, 46));
            picture.setHorizontalAlignment(SwingConstants.CENTER);
            picture.setOpaque(true);
            picture.setBackground(Theme.SURFACE_RAISED);
            for (Kind kind : Kind.values()) {
                fallback.put(kind, AppIcons.of(kind == FOLDER ? AppIcons.Kind.FOLDER : AppIcons.Kind.MUSIC, 24,
                    kind == SONG ? Theme.TEXT_MUTED : Theme.BLUE));
            }
            JPanel labels = new JPanel(new GridLayout(2, 1, 0, 2));
            labels.setOpaque(false);
            title.setFont(Theme.BODY_BOLD);
            title.setForeground(Theme.TEXT);
            title.putClientProperty("html.disable", true);
            subtitle.setFont(Theme.SMALL);
            subtitle.setForeground(Theme.TEXT_MUTED);
            subtitle.putClientProperty("html.disable", true);
            labels.add(title);
            labels.add(subtitle);
            add(picture, BorderLayout.WEST);
            add(labels, BorderLayout.CENTER);
        }

        @Override public Component getListCellRendererComponent(JList<? extends LibraryEntry> list,
            LibraryEntry entry, int index, boolean selected, boolean focus) {
            String kind = switch (entry.kind()) {
                case SONG -> "Song";
                case ALBUM -> "Album";
                case ARTIST -> "Artist";
                case PLAYLIST -> "Playlist";
                case FOLDER -> "Folder";
            };
            title.setText((collections.pins().contains(entry.key()) ? "★  " : "") + entry.name());
            subtitle.setText(kind + " · " + entry.detail());
            Icon cover = artwork.forEntry(entry, catalog.unavailable(), list::repaint);
            picture.setIcon(cover == null ? fallback.get(entry.kind()) : cover);
            setToolTipText(entry.name() + " · " + subtitle.getText());
            boolean dropTarget = list.getDropLocation() != null && list.getDropLocation().getIndex() == index;
            setBackground(dropTarget ? Theme.BLUE_STRONG.darker() : selected ? Theme.SURFACE_HOVER : Theme.SURFACE);
            return this;
        }
    }

    private final class EntryTransfer extends TransferHandler {
        private LibraryEntry dragged;

        @Override public int getSourceActions(JComponent component) { return COPY_OR_MOVE; }

        @Override protected Transferable createTransferable(JComponent component) {
            dragged = general.getSelectedValue();
            return dragged == null || dragged.kind() == ARTIST ? null : new StringSelection(dragged.name());
        }

        @Override public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || dragged == null || !support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false;
            int index = ((JList.DropLocation) support.getDropLocation()).getIndex();
            if (index < 0 || index >= model.size()) return false;
            LibraryEntry destination = model.get(index);
            if (destination.key().equals(dragged.key())) return false;
            if (destination.kind() == FOLDER) return dragged.kind() != ARTIST;
            return destination.kind() == PLAYLIST && dragged.kind() != FOLDER && !dragged.tracks().isEmpty();
        }

        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            LibraryEntry destination = model.get(((JList.DropLocation) support.getDropLocation()).getIndex());
            menus.drop(dragged, destination);
            return true;
        }

        @Override protected void exportDone(JComponent source, Transferable data, int action) { dragged = null; }
    }
}
