package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.LibraryCollections;
import sangitam.desktop.model.LibraryCollections.Key;
import sangitam.desktop.model.LibraryCollections.Kind;
import sangitam.desktop.model.Track;
import sangitam.desktop.storage.CollectionStore;
import sangitam.desktop.model.SmartPlaylistRule;
import sangitam.desktop.model.Playlist;
import sangitam.desktop.storage.SmartPlaylistStore;

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
import sangitam.desktop.ui.GeneralLibraryPages.Order;

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
        default void taste(List<Track> tracks,int rating) { }
        default Integer tasteRating(List<Track> tracks) { return null; }
        void editDetails(List<Track> tracks, boolean group);
        void restoreDetails(List<Track> tracks);
        void remove(List<Path> paths, Path scope);
        void open(LibraryEntry entry);
        void organize(String message, CollectionEdit edit);
        void deletePlaylist(long id);
        void entriesChanged();
        SmartPlaylistStore.Preview previewSmart(SmartPlaylistRule rule) throws Exception;
    }

    private final Actions actions;
    private final JTextField search = new JTextField();
    private final JComboBox<String> category = new JComboBox<>(new String[]{"All", "Songs", "Artists", "Albums", "Playlists", "Folders"});
    private final JComboBox<Order> sort = new JComboBox<>(Order.values());
    private final JLabel count = UiFactory.label("0 tracks", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel location = UiFactory.label("Library", Theme.SMALL, Theme.TEXT_MUTED);
    private final JButton back = UiFactory.button("Back", null, false);
    private final JTabbedPane tabs = new JTabbedPane();
    private final DefaultListModel<LibraryEntry> model = new DefaultListModel<>();
    private final JList<LibraryEntry> general = new JList<>(model) {
        @Override public String getToolTipText(MouseEvent event) {
            LibraryEntry entry = entryAt(event.getPoint());
            return entry == null ? null : LibraryHover.describe(entry, catalog, collections.pins().contains(entry.key()));
        }
    };
    private final LibraryFolderTree locations;
    private final JButton expand;
    private final Timer debounce = new Timer(160, event -> refreshView());
    private final JButton previous = UiFactory.button("‹ Previous", null, false), next = UiFactory.button("Next ›", null, false), retry = UiFactory.button("Retry", null, false);
    private final JPanel paging = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
    private SwingWorker<GeneralLibraryPages.Page, Void> pageWorker;
    private long pageGeneration;
    private int pageOffset;
    private boolean entriesReady = true, closed;
    private sangitam.desktop.model.LibraryCollections.Key restoreSelection;
    private final LibraryArtwork artwork = new LibraryArtwork();
    private final LibraryMenus menus;
    private LibraryService.Catalog catalog = new LibraryService.Catalog(List.of(), Map.of(), Map.of());
    private LibraryCollections collections = LibraryCollections.empty();
    private GeneralLibraryPages.Index entries = new GeneralLibraryPages.Index(List.of(), collections);
    private SwingWorker<GeneralLibraryPages.Index, Void> loader;
    private long folder;
    private boolean expanded;
    private Timer animation;
    private Runnable pageShown = () -> { };
    void onFirstPage(Runnable action) { pageShown = action; }

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
        JPanel top = new JPanel(new GridLayout(4, 1, 0, 8));
        top.setOpaque(false);
        top.add(heading);
        top.add(tools);
        top.add(controls);
        category.getAccessibleContext().setAccessibleName("Library category"); category.addActionListener(event -> refreshView()); top.add(category);
        top.setComponentPopupMenu(menus.libraryMenu());
        search.setComponentPopupMenu(menus.libraryMenu());
        add(top, BorderLayout.NORTH);

        general.setFixedCellHeight(63);
        general.getAccessibleContext().setAccessibleName("General library page");
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
        listen(search, () -> { pageOffset = 0; invalidatePage(); locations.invalidateSearch(); count.setText("Searching…"); debounce.restart(); });
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
        previous.addActionListener(event -> requestPage(Math.max(0, pageOffset - GeneralLibraryPages.PAGE_SIZE)));
        next.addActionListener(event -> requestPage(pageOffset + GeneralLibraryPages.PAGE_SIZE));
        previous.setToolTipText("Previous library page · Alt+Page Up"); next.setToolTipText("Next library page · Alt+Page Down");
        retry.addActionListener(event -> { if (entriesReady) requestPage(pageOffset); else reloadEntries(); });
        paging.setOpaque(false); paging.add(previous); paging.add(next); paging.add(retry); retry.setVisible(false);
        JPanel footer = new JPanel(new BorderLayout(0, 5)); footer.setOpaque(false); footer.add(count, BorderLayout.NORTH); footer.add(paging, BorderLayout.SOUTH); add(footer, BorderLayout.SOUTH);
        bind(general, KeyStroke.getKeyStroke("alt PAGE_UP"), "previousPage", () -> { if (previous.isEnabled()) previous.doClick(); });
        bind(general, KeyStroke.getKeyStroke("alt PAGE_DOWN"), "nextPage", () -> { if (next.isEnabled()) next.doClick(); });
        refreshView();
    }

    void setCatalog(LibraryService.Catalog catalog) {
        this.catalog = catalog;
        reloadEntries();
    }

    void setCollections(LibraryCollections collections) {
        if (this.collections.equals(collections)) return;
        this.collections = collections;
        if (folder != 0 && collections.folders().stream().noneMatch(item -> item.id() == folder)) folder = 0;
        reloadEntries();
    }

    LibraryCollections collections() { return collections; }
    void selectSort(int index) { tabs.setSelectedIndex(0); sort.setSelectedIndex(index); }
    List<Track> tracks() { return catalog.tracks(); }
    long currentFolder() { return folder; }

    LibraryEntry entry(Key key) {
        return entries.entry(key);
    }

    JPopupMenu trackMenu(Track track) {
        return menus.entryMenu(new LibraryEntry(new Key(SONG, track.path().toString()), track.title(), track.artist(), List.of(track), 0, 0, 0));
    }

    JPopupMenu menuForEntry(LibraryEntry entry) { return menus.entryMenu(entry); }

    private void invalidatePage() {
        pageGeneration++;
        if (pageWorker != null) pageWorker.cancel(true);
        LibraryEntry selected = general.getSelectedValue();
        if (selected != null) restoreSelection = selected.key();
        model.clear(); previous.setEnabled(false); next.setEnabled(false); retry.setVisible(false);
    }

    private void reloadEntries() {
        if (closed) return;
        invalidatePage(); entriesReady = false; count.setText("Updating library…");
        if (loader != null) loader.cancel(true);
        LibraryService.Catalog input = catalog;
        LibraryCollections organization = collections;
        loader = new SwingWorker<>() {
            @Override protected GeneralLibraryPages.Index doInBackground() { return new GeneralLibraryPages.Index(LibraryEntry.build(input, organization), organization); }
            @Override protected void done() {
                if (closed || isCancelled() || loader != this) return;
                try {
                    entries = get(); entriesReady = true;
                    updateView(pageOffset);
                    actions.entriesChanged();
                } catch (Exception error) {
                    count.setText("Library could not load. Retry to continue."); retry.setVisible(true);
                }
            }
        };
        loader.execute();
    }

    private void refreshView() { pageOffset = 0; updateView(0); }

    private void updateView(int offset) {
        if (closed) return;
        debounce.stop();
        boolean local = tabs.getSelectedIndex() == 1;
        back.setVisible(!local && folder != 0); sort.setVisible(!local); category.setVisible(!local); paging.setVisible(!local);
        location.setText(local ? "On this computer" : folderName(folder));
        count.setToolTipText(catalog.tracks().size() + " tracks · " + catalog.unavailable().size() + " unavailable");
        if (local) {
            invalidatePage(); count.setText("Loading locations…");
            long generation = pageGeneration;
            locations.setTracks(catalog.tracks(), catalog.unavailable(), search.getText(), matches -> {
                if (!closed && generation == pageGeneration && tabs.getSelectedIndex() == 1) count.setText(matches + " matching tracks");
            });
        } else requestPage(offset);
    }

    private void requestPage(int offset) {
        if (closed) return;
        invalidatePage(); pageOffset = offset;
        if (!entriesReady) { count.setText("Updating library…"); return; }
        long generation = pageGeneration;
        var request = new GeneralLibraryPages.Request(search.getText(), folder, (Order) sort.getSelectedItem(), offset, category.getSelectedIndex()==0?null:Kind.values()[category.getSelectedIndex()-1]);
        GeneralLibraryPages.Index input = entries;
        count.setText("Loading page…");
        pageWorker = new SwingWorker<>() {
            @Override protected GeneralLibraryPages.Page doInBackground() { return input.query(request); }
            @Override protected void done() {
                if (closed || isCancelled() || generation != pageGeneration) return;
                try {
                    var page = get(); pageOffset = page.offset(); model.addAll(page.entries());
                    if (restoreSelection != null) for (int i = 0; i < model.size(); i++) if (model.get(i).key().equals(restoreSelection)) { general.setSelectedIndex(i); break; }
                    long first = page.total() == 0 ? 0 : pageOffset + 1L;
                    count.setText(first + "–" + (pageOffset + model.size()) + " of " + page.total() + " entries");
                    previous.setEnabled(pageOffset > 0); next.setEnabled(page.hasNext());
                    pageShown.run();
                } catch (Exception error) { count.setText("Page could not load. Retry to continue."); retry.setVisible(true); }
            }
        }; pageWorker.execute();
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
        closed = true; invalidatePage(); locations.close();
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
        pane.setBorder(UiFactory.roundedBorder(Theme.DIVIDER, 12));
        UiFactory.styleScroll(pane);
        pane.getViewport().setBackground(Theme.SURFACE);
        return pane;
    }

    private java.util.function.Consumer<Boolean> expansionChanged = ignored -> { };
    void setExpansionListener(java.util.function.Consumer<Boolean> listener) { expansionChanged = listener; }
    private void setExpanded(boolean expanded) {
        this.expanded = expanded;
        expansionChanged.accept(expanded);
        expand.setIcon(AppIcons.of(expanded ? AppIcons.Kind.COLLAPSE : AppIcons.Kind.EXPAND, 17, Theme.BLUE));
        expand.setToolTipText(expanded ? "Collapse library" : "Expand library");
        expand.getAccessibleContext().setAccessibleName(expand.getToolTipText());
        Container availableParent = getParent();
        if (availableParent instanceof JSplitPane split && split.getOrientation() == JSplitPane.VERTICAL_SPLIT) availableParent = availableParent.getParent();
        int available = availableParent == null ? 900 : availableParent.getWidth();
        int target = expanded ? Math.max(COMPACT_WIDTH, available - 32) : COMPACT_WIDTH;
        animateWidth(target, () -> { });
    }
    private boolean railVisible = true;
    boolean isRailRequestedVisible() { return railVisible; }
    void setRailVisible(boolean visible) {
        if (closed || visible == railVisible) return; Component rail = rail(); railVisible = visible;
        if (visible) { boolean hidden = !rail.isVisible(); rail.setVisible(true); if (hidden) { rail.setSize(0,rail.getHeight()); setPreferredSize(new Dimension(0,0)); if (rail instanceof JComponent component) component.setPreferredSize(new Dimension(0,0)); } }
        animateWidth(visible ? COMPACT_WIDTH : 0, () -> { if (!visible && !railVisible) { rail.setVisible(false); setPreferredSize(new Dimension(COMPACT_WIDTH,0)); if (rail instanceof JComponent component) component.setPreferredSize(null); } });
    }
    private Component rail() { return getParent() instanceof JSplitPane split && split.getOrientation()==JSplitPane.VERTICAL_SPLIT ? split : this; }
    private void animateWidth(int target,Runnable complete) {
        if (animation != null) animation.stop();
        Component rail = rail(); int start = rail.getWidth(); if (!rail.isShowing() && getPreferredSize().width == 0) start=0;
        if(start==target){setPreferredSize(new Dimension(target,0));complete.run();return;}
        WidthTransition transition = new WidthTransition(Math.max(0,start),target,System.nanoTime(),220_000_000L);
        animation = new Timer(16,event -> {
            long now=System.nanoTime(); int width=transition.at(now);
            setPreferredSize(new Dimension(width,0)); if (rail != this && rail instanceof JComponent component) component.setPreferredSize(new Dimension(width,0));
            Container parent=rail.getParent(); if(parent!=null){parent.revalidate();parent.repaint();}
            if (transition.finished(now)) { ((Timer)event.getSource()).stop(); if (rail != this && rail instanceof JComponent component) component.setPreferredSize(null); complete.run(); }
        }); animation.setCoalesce(true); animation.start();
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
        private final Icon pin = AppIcons.of(AppIcons.Kind.PIN, 15, Theme.BLUE);
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
            title.setText(entry.name());
            title.setIcon(collections.pins().contains(entry.key()) ? pin : null);
            subtitle.setText(kind + " · " + entry.detail());
            Icon cover = artwork.forEntry(entry, catalog.unavailable(), catalog.stamps(), list::repaint);
            picture.setIcon(cover == null ? fallback.get(entry.kind()) : cover);
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
            return acceptsDrop(dragged, destination, collections);
        }

        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            LibraryEntry destination = model.get(((JList.DropLocation) support.getDropLocation()).getIndex());
            menus.drop(dragged, destination);
            return true;
        }

        @Override protected void exportDone(JComponent source, Transferable data, int action) { dragged = null; }
    }

    static boolean acceptsDrop(LibraryEntry source, LibraryEntry target, LibraryCollections collections) {
        if (source.kind() == PLAYLIST && source.id() == Playlist.LIKED_ID) return false;
        if (source == null || target == null || source.key().equals(target.key()) || source.kind() == ARTIST) return false;
        if (target.kind() == FOLDER) return true;
        return target.kind() == PLAYLIST && source.kind() != FOLDER && !source.tracks().isEmpty()
            && collections.playlists().stream().anyMatch(playlist -> playlist.id() == target.id() && playlist.acceptsManualEdits());
    }
}
