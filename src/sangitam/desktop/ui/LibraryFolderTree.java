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
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("Imported folders"));
    private final Map<Path, DefaultMutableTreeNode> nodes = new HashMap<>();
    private final Map<Branch, SwingWorker<LocationIndex.Page, Void>> loads = new HashMap<>();
    private final Set<Path> expandedFolders = new HashSet<>();
    private final BiFunction<List<Track>, String, LocationIndex> build;
    private List<Track> tracks = List.of();
    private Set<Path> unavailable = Set.of();
    private String query = "";
    private LocationIndex index;
    private SwingWorker<LocationIndex, Void> builder;
    private Path selection;
    private long generation;
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
        setBackground(Theme.SURFACE); getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        getAccessibleContext().setAccessibleName("Library folders"); setCellRenderer(new FolderRenderer()); ToolTipManager.sharedInstance().registerComponent(this);
        addTreeWillExpandListener(new TreeWillExpandListener() {
            public void treeWillExpand(TreeExpansionEvent event) { if (event.getPath().getLastPathComponent() instanceof Branch branch && !branch.loaded && !loads.containsKey(branch)) load(branch, -1); }
            public void treeWillCollapse(TreeExpansionEvent event) { }
        });
        addTreeExpansionListener(new TreeExpansionListener() {
            public void treeExpanded(TreeExpansionEvent event) { if (!changing && event.getPath().getLastPathComponent() instanceof Branch branch && branch.path != null) expandedFolders.add(branch.path); }
            public void treeCollapsed(TreeExpansionEvent event) {
                if (changing || closed || !(event.getPath().getLastPathComponent() instanceof Branch branch) || branch.path == null) return;
                expandedFolders.remove(branch.path); changing = true;
                try { forgetChildren(branch); branch.loaded = false; branch.add(new DefaultMutableTreeNode("Expand to load…", false)); treeModel.nodeStructureChanged(branch); }
                finally { changing = false; }
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 2) return;
                TreePath path = getPathForLocation(event.getX(), event.getY()); if (path != null) activate(path, onPlay, false);
            }
        });
        LibraryPanel.bind(this, KeyStroke.getKeyStroke("ENTER"), "openLibraryRow", () -> activate(getSelectionPath(), onPlay, true));
    }
    private void activate(TreePath path, Consumer<Track> onPlay, boolean toggleFolder) {
        if (path == null || closed) return; Object value = nodeValue(path);
        if (value instanceof Track track) onPlay.accept(track);
        else if (value instanceof PageAction action) load(action.branch(), action.offset());
        else if (value instanceof RetryIndex) rebuild();
        else if (toggleFolder && value instanceof Path) { if (isExpanded(path)) collapsePath(path); else expandPath(path); }
    }
    void setTracks(List<Track> updated, Set<Path> unavailable) { setTracks(updated, unavailable, "", ignored -> { }); }
    void setTracks(List<Track> updated, Set<Path> unavailable, String query, IntConsumer count) {
        this.unavailable = unavailable; this.count = count; repaint();
        String normalized = LibraryEntry.lower(query.strip());
        if (updated == tracks && normalized.equals(this.query) && index != null) { count.accept(index.matches()); return; }
        Object selected = getSelectionPath() == null ? null : nodeValue(getSelectionPath());
        if (selected instanceof Track track) selection = track.path();
        else if (selected instanceof Path path) selection = path;
        else if (index != null) selection = null;
        tracks = updated; this.query = normalized; rebuild();
    }
    void invalidateSearch() {
        if (closed) return;
        Object selected = getSelectionPath() == null ? null : nodeValue(getSelectionPath());
        if (selected instanceof Track track) selection = track.path(); else if (selected instanceof Path path) selection = path;
        generation++; index = null; if (builder != null) builder.cancel(true);
        loads.values().forEach(worker -> worker.cancel(true)); loads.clear(); nodes.clear();
        changing = true;
        try { var root = new DefaultMutableTreeNode("Imported folders"); root.add(new DefaultMutableTreeNode("Waiting for search…", false)); treeModel.setRoot(root); }
        finally { changing = false; }
    }
    private void rebuild() {
        if (closed) return; long request = ++generation;
        if (builder != null) builder.cancel(true); loads.values().forEach(worker -> worker.cancel(true)); loads.clear(); nodes.clear(); index = null;
        changing = true;
        try { DefaultMutableTreeNode root = new DefaultMutableTreeNode("Imported folders"); root.add(new DefaultMutableTreeNode("Loading locations…", false)); treeModel.setRoot(root); }
        finally { changing = false; }
        List<Track> input = tracks; String text = query;
        builder = new SwingWorker<>() {
            @Override protected LocationIndex doInBackground() { return build.apply(input, text); }
            @Override protected void done() {
                if (closed || isCancelled() || generation != request) return;
                try {
                    index = get(); count.accept(index.matches()); Branch root = new Branch(null);
                    changing = true; try { treeModel.setRoot(root); } finally { changing = false; }
                    load(root, -1);
                } catch (Exception error) {
                    DefaultMutableTreeNode root = new DefaultMutableTreeNode("Imported folders"); root.add(new DefaultMutableTreeNode(new RetryIndex(), false)); treeModel.setRoot(root);
                }
            }
        }; builder.execute();
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
            @Override protected LocationIndex.Page doInBackground() { return input.page(branch.path, offset < 0 && selectedPath != null ? input.offsetToward(branch.path, selectedPath) : Math.max(0, offset)); }
            @Override protected void done() {
                if (closed || isCancelled() || request != generation || loads.get(branch) != this) return;
                loads.remove(branch);
                changing = true;
                try {
                    LocationIndex.Page page = get(); branch.removeAllChildren(); branch.loaded = true;
                    if (page.offset() > 0) branch.add(new DefaultMutableTreeNode(new PageAction(branch, Math.max(0, page.offset() - LocationIndex.PAGE_SIZE), "‹ Previous 100 entries"), false));
                    for (Object value : page.children()) {
                        DefaultMutableTreeNode child = value instanceof Path folder ? new Branch(folder) : new DefaultMutableTreeNode(value, false);
                        branch.add(child); nodes.put(value instanceof Path folder ? folder : ((Track) value).path(), child);
                    }
                    if (page.hasNext()) branch.add(new DefaultMutableTreeNode(new PageAction(branch, page.offset() + LocationIndex.PAGE_SIZE, "Next 100 entries › (" + (page.total() - page.offset() - page.children().size()) + " remaining)"), false));
                    if (branch.path == null && page.total() == 0) branch.add(new DefaultMutableTreeNode("No matching tracks", false));
                    treeModel.nodeStructureChanged(branch);
                } catch (Exception error) {
                    branch.removeAllChildren(); branch.loaded = true; branch.add(new DefaultMutableTreeNode(new PageAction(branch, offset, "Could not load entries — Enter to retry"), false)); treeModel.nodeStructureChanged(branch);
                } finally { changing = false; }
                expandPath(new TreePath(branch.getPath()));
                var children = branch.children();
                while (children.hasMoreElements()) {
                    var child = children.nextElement(); if (!(child instanceof Branch folder)) continue;
                    if ((branch.path == null && branch.getChildCount() == 1) || expandedFolders.contains(folder.path) || selection != null && selection.startsWith(folder.path)) expandPath(new TreePath(folder.getPath()));
                }
                DefaultMutableTreeNode selected = nodes.get(selection); if (selected != null) setSelectionPath(new TreePath(selected.getPath()));
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
                if (node.getLevel() == 1 && folder.getFileName() != null) name += " · " + folder.getRoot();
                setText(name);
                setIcon(folderIcon);
                setFont(Theme.BODY_BOLD);
                setToolTipText(folder.toString());
            } else if (item instanceof Track track) {
                boolean offline = unavailable.contains(track.path());
                setText(track + (offline ? " · Unavailable" : ""));
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
