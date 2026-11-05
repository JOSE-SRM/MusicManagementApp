package sangitam.desktop.ui;

import sangitam.desktop.model.Track;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** A view of the paths already in the catalog, grouped by drive or filesystem root. */
final class LibraryFolderTree extends JTree {
    private static final long serialVersionUID = 1L;
    private static final Comparator<Path> FOLDER_ORDER = Comparator
        .comparing(Path::toString, String.CASE_INSENSITIVE_ORDER).thenComparing(Path::toString);
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
    private List<Track> tracks = List.of();
    private Set<Path> unavailable = Set.of();
    private SwingWorker<FolderModel, Void> builder;

    LibraryFolderTree(Consumer<Track> onPlay) {
        setModel(treeModel);
        setRootVisible(false);
        setShowsRootHandles(true);
        setRowHeight(29);
        setLargeModel(true);
        setBackground(Theme.SURFACE);
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        getAccessibleContext().setAccessibleName("Library folders");
        setCellRenderer(new FolderRenderer());
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event)) return;
                Track track = trackAt(getPathForLocation(event.getX(), event.getY()));
                if (track != null) onPlay.accept(track);
            }
        });
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openLibraryRow");
        getActionMap().put("openLibraryRow", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                Track track = selectedTrack();
                if (track != null) onPlay.accept(track);
                else if (getSelectionPath() != null) {
                    TreePath selection = getSelectionPath();
                    if (isExpanded(selection)) collapsePath(selection);
                    else expandPath(selection);
                }
            }
        });
    }

    void setTracks(List<Track> updated, Set<Path> unavailable) {
        this.unavailable = unavailable;
        repaint();
        if (updated.equals(tracks)) return;
        tracks = updated;
        if (builder != null) builder.cancel(true);
        builder = new SwingWorker<>() {
            @Override protected FolderModel doInBackground() {
                return buildModel(updated);
            }

            @Override protected void done() {
                if (isCancelled() || builder != this) return;
                try {
                    replaceTree(get());
                } catch (java.util.concurrent.ExecutionException error) {
                    System.getLogger(LibraryFolderTree.class.getName()).log(
                        System.Logger.Level.WARNING, "Could not build the library folder tree", error.getCause());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        builder.execute();
    }

    Track selectedTrack() { return trackAt(getSelectionPath()); }

    void onContext(BiConsumer<Object, Point> show) {
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }

            private void popup(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                TreePath path = getPathForLocation(event.getX(), event.getY());
                if (path != null) setSelectionPath(path);
                show.accept(path == null ? null : nodeValue(path), event.getPoint());
            }
        });
        LibraryPanel.bind(this, KeyStroke.getKeyStroke(KeyEvent.VK_F10, KeyEvent.SHIFT_DOWN_MASK), "context", () -> {
            TreePath path = getSelectionPath();
            var bounds = path == null ? null : getPathBounds(path);
            show.accept(path == null ? null : nodeValue(path),
                bounds == null ? new Point(12, 12) : new Point(bounds.x, bounds.y + bounds.height));
        });
    }

    private static Track trackAt(TreePath path) {
        return path != null && nodeValue(path) instanceof Track track ? track : null;
    }

    private static Object nodeValue(TreePath path) {
        return ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    record FolderModel(DefaultMutableTreeNode root, Map<Path, DefaultMutableTreeNode> nodes) { }

    static FolderModel buildModel(List<Track> tracks) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Imported folders");
        Map<Path, DefaultMutableTreeNode> nodes = new HashMap<>();
        List<Path> folders = tracks.stream().map(track -> track.path().getParent()).distinct()
            .sorted(FOLDER_ORDER).toList();
        Map<Path, Path> commonFolders = new HashMap<>();
        for (Path folder : folders) commonFolders.merge(folder.getRoot(), folder, LibraryFolderTree::commonParent);
        for (Path folder : folders) addFolder(folder, commonFolders.get(folder.getRoot()), root, nodes);
        for (Track track : tracks) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(track, false);
            nodes.get(track.path().getParent()).add(node);
            nodes.put(track.path(), node);
        }
        return new FolderModel(root, nodes);
    }

    private static Path commonParent(Path left, Path right) {
        while (!right.startsWith(left)) left = left.getParent();
        return left;
    }

    private static void addFolder(Path path, Path top, DefaultMutableTreeNode root,
                                  Map<Path, DefaultMutableTreeNode> nodes) {
        if (nodes.containsKey(path)) return;
        Path parent = path.equals(top) ? null : path.getParent();
        if (parent != null) addFolder(parent, top, root, nodes);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(path);
        (parent == null ? root : nodes.get(parent)).add(node);
        nodes.put(path, node);
    }

    private void replaceTree(FolderModel model) {
        TreePath rootPath = new TreePath(treeModel.getRoot());
        var expanded = getExpandedDescendants(rootPath);
        List<Path> folders = new ArrayList<>();
        if (expanded != null) {
            while (expanded.hasMoreElements()) {
                if (nodeValue(expanded.nextElement()) instanceof Path path) folders.add(path);
            }
        }
        Object selected = getSelectionPath() == null ? null : nodeValue(getSelectionPath());
        boolean firstLoad = treeModel.getChildCount(treeModel.getRoot()) == 0;
        treeModel.setRoot(model.root());
        if (firstLoad) {
            DefaultMutableTreeNode folder = model.root();
            while (folder.getChildCount() == 1 && folder.getFirstChild() instanceof DefaultMutableTreeNode child
                   && child.getUserObject() instanceof Path) {
                expandPath(new TreePath(child.getPath()));
                folder = child;
            }
        }
        for (Path path : folders) {
            DefaultMutableTreeNode node = model.nodes().get(path);
            if (node != null) expandPath(new TreePath(node.getPath()));
        }
        Path selectionKey = selected instanceof Track track ? track.path() : selected instanceof Path path ? path : null;
        DefaultMutableTreeNode selection = model.nodes().get(selectionKey);
        if (selection != null) setSelectionPath(new TreePath(selection.getPath()));
    }

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
            }
            return this;
        }
    }
}
