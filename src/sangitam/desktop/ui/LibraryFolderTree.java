package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.*;

/** Lazy presentation of indexed paths. No directory listing or audio read runs in this tree. */
final class LibraryFolderTree extends JTree {
    private static final java.util.regex.Pattern RECORDING_SUFFIX = java.util.regex.Pattern.compile("(.+ · .+)-[a-z0-9]{5,7}$");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("Imported folders"));
    private final Map<Path, DefaultMutableTreeNode> nodes = new HashMap<>();
    private final Map<Branch, SwingWorker<LocationIndex.Page, Void>> loads = new HashMap<>();
    private final Set<Path> expandedFolders = new HashSet<>();
    private final Set<Path> collapsedFolders = new HashSet<>();
    private final Map<Path, Integer> pageOffsets = new HashMap<>();
    private final List<Path> openFolders = new ArrayList<>();
    private final BiFunction<List<Track>, String, LocationIndex> build;
    private List<Track> tracks = List.of();
    private Set<Path> unavailable = Set.of();
    private String query = "";
    private String installedQuery;
    private List<Track> installedTracks = List.of();
    private Path managedRoot;
    private LocationIndex index;
    private SwingWorker<LocationIndex, Void> builder;
    private Path selection;
    private Runnable folderChanged = () -> { };
    private record ViewPosition(Point point, Path anchor, int delta) { }
    private ViewPosition refreshPosition;
    private long generation, buildRevision;
    private boolean closed, changing;
    private IntConsumer count = ignored -> { };
    private static final class Branch extends DefaultMutableTreeNode {
        final Path path;
        boolean loaded;
        Branch(Path path) { super(path == null ? "Imported folders" : path); this.path = path; add(new DefaultMutableTreeNode("Expand to load…", false)); }
    }
    private record PageAction(Branch branch, int offset, String label) { @Override public String toString() { return label; } }
    private record RetryIndex() { @Override public String toString() { return "Could not load locations — Enter to retry"; } }

    LibraryFolderTree(Consumer<Track> onPlay) { this(onPlay, LocationIndex::build); }
    LibraryFolderTree(Consumer<Track> onPlay, BiFunction<List<Track>, String, LocationIndex> build) {
        this.build = build;
        setModel(treeModel); setRootVisible(false); setShowsRootHandles(true); setRowHeight(29); setLargeModel(true);
        setToggleClickCount(0);
        putClientProperty("JTree.lineStyle", "None");
        if (getUI() instanceof javax.swing.plaf.basic.BasicTreeUI ui) {
            ui.setCollapsedIcon(disclosure(false));
            ui.setExpandedIcon(disclosure(true));
        }
        setBackground(Theme.SURFACE); getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        getAccessibleContext().setAccessibleName("Library folders"); setCellRenderer(new FolderRenderer()); ToolTipManager.sharedInstance().registerComponent(this);
        addTreeSelectionListener(event -> {
            TreePath selected = getSelectionPath();
            if (!changing && selected != null && nodeValue(selected) instanceof Track track) selection = track.path();
        });
        addTreeWillExpandListener(new TreeWillExpandListener() {
            public void treeWillExpand(TreeExpansionEvent event) { if (event.getPath().getLastPathComponent() instanceof Branch branch && !branch.loaded && !loads.containsKey(branch)) load(branch, -1); }
            public void treeWillCollapse(TreeExpansionEvent event) { }
        });
        addTreeExpansionListener(new TreeExpansionListener() {
            public void treeExpanded(TreeExpansionEvent event) {
                if (!changing && event.getPath().getLastPathComponent() instanceof Branch branch && branch.path != null) {
                    expandedFolders.add(branch.path);
                    collapsedFolders.remove(branch.path);
                }
            }
            public void treeCollapsed(TreeExpansionEvent event) {
                if (changing || closed || !(event.getPath().getLastPathComponent() instanceof Branch branch) || branch.path == null) return;
                expandedFolders.remove(branch.path); changing = true;
                collapsedFolders.add(branch.path);
                try { forgetChildren(branch); branch.loaded = false; branch.add(new DefaultMutableTreeNode("Expand to load…", false)); treeModel.nodeStructureChanged(branch); }
                finally { changing = false; }
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 2) return;
                TreePath path = getPathForLocation(event.getX(), event.getY()); if (path != null) activate(path, onPlay);
            }
        });
        LibraryPanel.bind(this, KeyStroke.getKeyStroke("ENTER"), "openLibraryRow", () -> activate(getSelectionPath(), onPlay));
    }
    private static Icon disclosure(boolean expanded) {
        return new Icon() {
            @Override public int getIconWidth() { return 12; }
            @Override public int getIconHeight() { return 12; }
            @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Theme.TEXT_MUTED);
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (expanded) g.drawPolyline(new int[]{x + 3, x + 6, x + 9}, new int[]{y + 4, y + 7, y + 4}, 3);
                else g.drawPolyline(new int[]{x + 4, x + 7, x + 4}, new int[]{y + 3, y + 6, y + 9}, 3);
                g.dispose();
            }
        };
    }
    private void activate(TreePath path, Consumer<Track> onPlay) {
        if (path == null || closed) return; Object value = nodeValue(path);
        if (value instanceof Track track) onPlay.accept(track);
        else if (value instanceof PageAction action) load(action.branch(), action.offset());
        else if (value instanceof RetryIndex) rebuild();
        else if (value instanceof Path folder) enterFolder(folder);
    }
    void onFolderChanged(Runnable listener) { folderChanged = listener; }
    Path currentFolder() { return openFolders.isEmpty() ? null : openFolders.getLast(); }
    void enterFolder(Path folder) {
        if (folder.equals(currentFolder()) || !nodes.containsKey(folder)) return;
        openFolders.add(folder);
        selection = null;
        installIndex(null);
        folderChanged.run();
    }
    void leaveFolder() {
        if (openFolders.isEmpty()) return;
        selection = openFolders.removeLast();
        installIndex(null);
        folderChanged.run();
    }
    void resetNavigation() {
        openFolders.clear();
        selection = null;
        expandedFolders.clear();
        collapsedFolders.clear();
        pageOffsets.clear();
        invalidateSearch();
        generation++;
        index = null;
        loads.values().forEach(worker -> worker.cancel(true));
        loads.clear();
        nodes.clear();
        refreshPosition = null;
        changing = true;
        try { treeModel.setRoot(new DefaultMutableTreeNode("Locations")); }
        finally { changing = false; }
        folderChanged.run();
    }
    boolean folderExpanded(Path folder) {
        var node = nodes.get(folder);
        return node != null && isExpanded(new TreePath(node.getPath()));
    }
    void toggleFolder(Path folder) {
        var node = nodes.get(folder);
        if (node == null) return;
        var path = new TreePath(node.getPath());
        if (isExpanded(path)) collapsePath(path); else expandPath(path);
    }
    void collapseRecursively(Path folder) {
        var node = nodes.get(folder);
        if (node == null) return;
        expandedFolders.removeIf(path -> path.startsWith(folder));
        pageOffsets.keySet().removeIf(path -> path != null && path.startsWith(folder));
        collapsedFolders.add(folder);
        selection = folder;
        var path = new TreePath(node.getPath());
        setSelectionPath(path);
        collapsePath(path);
    }
    void setTracks(List<Track> updated, Set<Path> unavailable) { setTracks(updated, unavailable, "", ignored -> { }); }
    void showManagedFolders(Path root) {
        managedRoot = root;
        repaint();
    }
    void setTracks(List<Track> updated, Set<Path> unavailable, String query, IntConsumer count) {
        this.unavailable = unavailable; this.count = count; repaint();
        String normalized = LibraryEntry.lower(query.strip());
        if ((updated == installedTracks || updated.equals(installedTracks)) && normalized.equals(installedQuery) && index != null) {
            invalidateSearch();
            count.accept(index.matches(currentFolder()));
            return;
        }
        if (builder != null && (updated == tracks || updated.equals(tracks)) && normalized.equals(this.query)) return;
        Object selected = getSelectionPath() == null ? null : nodeValue(getSelectionPath());
        if (selected instanceof Track track) selection = track.path();
        else if (selected instanceof Path path) selection = path;
        else if (index != null) selection = null;
        tracks = updated; this.query = normalized; rebuild();
    }
    void invalidateSearch() {
        if (closed) return;
        if (builder != null) { buildRevision++; builder.cancel(true); builder = null; }
    }
    private void rebuild() {
        if (closed) return; long request = ++buildRevision;
        if (builder != null) builder.cancel(true);
        List<Track> input = tracks; String text = query;
        builder = new SwingWorker<>() {
            @Override protected LocationIndex doInBackground() { return build.apply(input, text); }
            @Override protected void done() {
                if (closed || isCancelled() || buildRevision != request) return;
                builder = null;
                try {
                    ViewPosition position = viewPosition();
                    index = get(); installedTracks = input; installedQuery = text;
                    if (text.isEmpty()) {
                        boolean removed = false;
                        while (!openFolders.isEmpty() && index.matches(currentFolder()) == 0) {
                            openFolders.removeLast();
                            removed = true;
                        }
                        if (removed) folderChanged.run();
                    }
                    installIndex(position);
                } catch (Exception error) {
                    index = null;
                    DefaultMutableTreeNode root = new DefaultMutableTreeNode("Imported folders"); root.add(new DefaultMutableTreeNode(new RetryIndex(), false)); treeModel.setRoot(root);
                }
            }
        }; builder.execute();
    }
    private ViewPosition viewPosition() {
        var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
        if (viewport == null) return null;
        Point position = viewport.getViewPosition();
        TreePath visible = getClosestPathForLocation(position.x, position.y);
        if (visible == null) return new ViewPosition(position, null, 0);
        Object value = nodeValue(visible);
        Path anchor = value instanceof Track track ? track.path() : value instanceof Path path ? path : null;
        Rectangle bounds = getPathBounds(visible);
        return new ViewPosition(position, anchor, bounds == null ? 0 : position.y - bounds.y);
    }
    private void installIndex(ViewPosition position) {
        if (index == null || closed) return;
        generation++;
        loads.values().forEach(worker -> worker.cancel(true));
        loads.clear(); nodes.clear();
        refreshPosition = position;
        count.accept(index.matches(currentFolder()));
        Branch root = new Branch(currentFolder());
        changing = true;
        try { treeModel.setRoot(root); } finally { changing = false; }
        load(root, -1);
    }
    private void forgetChildren(Branch branch) {
        var enumeration = branch.depthFirstEnumeration();
        while (enumeration.hasMoreElements()) {
            var child = (DefaultMutableTreeNode) enumeration.nextElement(); if (child == branch) continue;
            Object value = child.getUserObject(); Path key = value instanceof Track track ? track.path() : value instanceof Path path ? path : null;
            if (key != null) nodes.remove(key, child);
            if (child instanceof Branch nested) { var work = loads.remove(nested); if (work != null) work.cancel(true); }
        }
        var own = loads.remove(branch); if (own != null) own.cancel(true); branch.removeAllChildren();
    }
    private void load(Branch branch, int offset) {
        if (closed || index == null) return; long request = generation; LocationIndex input = index; Path selectedPath = selection;
        changing = true;
        try { forgetChildren(branch); branch.loaded = false; branch.add(new DefaultMutableTreeNode("Loading…", false)); treeModel.nodeStructureChanged(branch); }
        finally { changing = false; }
        SwingWorker<LocationIndex.Page, Void> worker = new SwingWorker<>() {
            private final int rememberedOffset = pageOffsets.getOrDefault(branch.path, 0);
            @Override protected LocationIndex.Page doInBackground() {
                int page = offset >= 0 ? offset : selectedPath != null && (branch.path == null || selectedPath.startsWith(branch.path))
                    ? input.offsetToward(branch.path, selectedPath) : rememberedOffset;
                return input.page(branch.path, page);
            }
            @Override protected void done() {
                if (closed || isCancelled() || request != generation || loads.get(branch) != this) return;
                loads.remove(branch);
                changing = true;
                try {
                    LocationIndex.Page page = get(); branch.removeAllChildren(); branch.loaded = true;
                    pageOffsets.put(branch.path, page.offset());
                    if (page.offset() > 0) branch.add(new DefaultMutableTreeNode(new PageAction(branch, Math.max(0, page.offset() - LocationIndex.PAGE_SIZE), "‹ Previous 100 entries"), false));
                    for (Object value : page.children()) {
                        DefaultMutableTreeNode child = value instanceof Path folder ? new Branch(folder) : new DefaultMutableTreeNode(value, false);
                        branch.add(child); nodes.put(value instanceof Path folder ? folder : ((Track) value).path(), child);
                    }
                    if (page.hasNext()) branch.add(new DefaultMutableTreeNode(new PageAction(branch, page.offset() + LocationIndex.PAGE_SIZE, "Next 100 entries › (" + (page.total() - page.offset() - page.children().size()) + " remaining)"), false));
                    if (branch.getParent() == null && page.total() == 0) branch.add(new DefaultMutableTreeNode("No matching tracks", false));
                    treeModel.nodeStructureChanged(branch);
                } catch (Exception error) {
                    branch.removeAllChildren(); branch.loaded = true; branch.add(new DefaultMutableTreeNode(new PageAction(branch, offset, "Could not load entries — Enter to retry"), false)); treeModel.nodeStructureChanged(branch);
                } finally { changing = false; }
                expandPath(new TreePath(branch.getPath()));
                var children = branch.children();
                while (children.hasMoreElements()) {
                    var child = children.nextElement(); if (!(child instanceof Branch folder)) continue;
                    boolean restore = selection != null && !selection.equals(folder.path) && selection.startsWith(folder.path);
                    if (!collapsedFolders.contains(folder.path) && (branch.path == null && branch.getChildCount() == 1
                        || expandedFolders.contains(folder.path) || restore)) expandPath(new TreePath(folder.getPath()));
                }
                DefaultMutableTreeNode selected = nodes.get(selection); if (selected != null) setSelectionPath(new TreePath(selected.getPath()));
                if (loads.isEmpty() && refreshPosition != null) {
                    var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, LibraryFolderTree.this);
                    if (viewport != null) {
                        Point point = refreshPosition.point();
                        var anchor = nodes.get(refreshPosition.anchor());
                        if (anchor != null) {
                            Rectangle bounds = getPathBounds(new TreePath(anchor.getPath()));
                            if (bounds != null) point = new Point(point.x, Math.max(0, bounds.y + refreshPosition.delta()));
                        }
                        viewport.setViewPosition(point);
                    }
                    refreshPosition = null;
                }
            }
        }; loads.put(branch, worker); worker.execute();
    }
    Track selectedTrack() { return getSelectionPath() != null && nodeValue(getSelectionPath()) instanceof Track track ? track : null; }
    void onContext(BiConsumer<Object, Point> show) {
        addMouseListener(new MouseAdapter() {
            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return; TreePath path = getPathForLocation(event.getX(), event.getY()); Object value = path == null ? null : nodeValue(path);
                if (value != null && !(value instanceof Path) && !(value instanceof Track)) return;
                if (path != null) setSelectionPath(path); show.accept(value, event.getPoint());
            }
            @Override public void mousePressed(MouseEvent event) { popup(event); } @Override public void mouseReleased(MouseEvent event) { popup(event); }
        });
        LibraryPanel.bind(this, KeyStroke.getKeyStroke("shift F10"), "context", () -> {
            TreePath path = getSelectionPath(); Object value = path == null ? null : nodeValue(path);
            if (value != null && !(value instanceof Path) && !(value instanceof Track)) return;
            Rectangle bounds = path == null ? null : getPathBounds(path); show.accept(value, bounds == null ? new Point(12,12) : new Point(bounds.x, bounds.y + bounds.height));
        });
    }
    private static Object nodeValue(TreePath path) { return ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject(); }
    void close() { closed = true; generation++; if (builder != null) builder.cancel(true); loads.values().forEach(worker -> worker.cancel(true)); loads.clear(); nodes.clear(); }

    private final class FolderRenderer extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 1L;
        private final Icon folderIcon = AppIcons.of(AppIcons.Kind.FOLDER, 16, Theme.BLUE);
        private final Icon musicIcon = AppIcons.of(AppIcons.Kind.MUSIC, 15, Theme.TEXT_MUTED);

        FolderRenderer() {
            putClientProperty("html.disable", true);
            setTextNonSelectionColor(Theme.TEXT);
            setTextSelectionColor(Theme.TEXT);
            setBackgroundNonSelectionColor(Theme.SURFACE);
            setBackgroundSelectionColor(Theme.SURFACE_HOVER);
            setBorderSelectionColor(Theme.BLUE);
            setIconTextGap(8);
        }

        @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
            boolean expanded, boolean leaf, int row, boolean focused) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focused);
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object item = node.getUserObject();
            setToolTipText(null);
            if (item instanceof Path folder) {
                String name = folder.getFileName() == null ? folder.toString() : folder.getFileName().toString();
                if (managedRoot != null && folder.startsWith(managedRoot)) {
                    name = RECORDING_SUFFIX.matcher(name).replaceFirst("$1");
                    Path parent = folder.getParent();
                    if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("Musical Forms")
                        && !name.equals("Other forms")
                        || Set.of("piano", "violin", "viola", "cello", "clarinet", "flute", "orchestra", "organ")
                            .contains(name.toLowerCase(Locale.ROOT)))
                        name = sangitam.desktop.library.ClassicalHints.categoryCase(name);
                }
                if (managedRoot == null && currentFolder() == null && node.getLevel() == 1 && folder.getFileName() != null)
                    name += " · " + folder.getRoot();
                setText(name);
                setIcon(folderIcon);
                setFont(Theme.BODY_BOLD);
                setToolTipText(folder.toString());
            } else if (item instanceof Track track) {
                boolean offline = unavailable.contains(track.path());
                String composer = track.metadata().text(sangitam.desktop.model.TrackMetadata.Field.COMPOSER);
                String title = track.artist().equalsIgnoreCase("Unknown artist") && !composer.isBlank()
                    ? track.title() + " — " + composer + " (composer)" : track.toString();
                setText(title + (offline ? " · Unavailable" : ""));
                setForeground(offline && !selected ? Theme.TEXT_MUTED : Theme.TEXT);
                setIcon(musicIcon);
                setFont(Theme.BODY);
                setToolTipText(track.path().toString());
            } else {
                setText(item.toString()); setIcon(AppIcons.of(item instanceof PageAction ? AppIcons.Kind.NEXT : AppIcons.Kind.HELP, 16, Theme.BLUE));
                setFont(Theme.SMALL); setForeground(Theme.BLUE);
            }
            return this;
        }
    }
}
