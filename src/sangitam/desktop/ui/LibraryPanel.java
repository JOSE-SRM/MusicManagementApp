package sangitam.desktop.ui;

import sangitam.desktop.model.Track;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Right-side local library; the width animation remains on Swing's event thread. */
public final class LibraryPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int COMPACT_WIDTH = 300;
    private final DefaultListModel<Track> songModel = new DefaultListModel<>();
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Imported folders");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JLabel count = UiFactory.label("0 tracks in library", Theme.SMALL, Theme.TEXT_MUTED);
    private final JButton expandButton;
    private boolean expanded;
    private Timer animation;

    public LibraryPanel(Runnable importMedia, Consumer<Track> onPlay) {
        super(new BorderLayout(0, 14));
        setBackground(Theme.SURFACE);
        setPreferredSize(new Dimension(COMPACT_WIDTH, 0));
        setMinimumSize(new Dimension(220, 0));
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.DIVIDER), Theme.padding(20, 18, 18, 18)));
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.add(UiFactory.label("Your library", Theme.HEADING, Theme.TEXT), BorderLayout.CENTER);
        expandButton = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.EXPAND, 17, Theme.BLUE), "Expand library");
        expandButton.addActionListener(event -> toggleExpanded());
        header.add(expandButton, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JList<Track> songs = new JList<>(songModel);
        songs.setFixedCellHeight(42);
        songs.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        songs.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && songs.getSelectedValue() != null) onPlay.accept(songs.getSelectedValue());
            }
        });
        songs.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "playSelected");
        songs.getActionMap().put("playSelected", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (songs.getSelectedValue() != null) onPlay.accept(songs.getSelectedValue());
            }
        });
        JTree folders = new JTree(treeModel);
        folders.setRootVisible(false);
        folders.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() != 2) return;
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) folders.getLastSelectedPathComponent();
                if (node != null && node.getUserObject() instanceof Track track) onPlay.accept(track);
            }
        });
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Songs", new JScrollPane(songs));
        tabs.addTab("Folders", new JScrollPane(folders));
        add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        count.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(count);
        footer.add(Box.createVerticalStrut(12));
        JButton add = UiFactory.button("Add to library", AppIcons.of(AppIcons.Kind.PLUS, 15, Theme.BLACK), true);
        add.setAlignmentX(Component.LEFT_ALIGNMENT);
        add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        add.addActionListener(event -> importMedia.run());
        footer.add(add);
        add(footer, BorderLayout.SOUTH);
    }

    public void setTracks(List<Track> tracks) {
        songModel.clear();
        songModel.addAll(tracks);
        count.setText(tracks.size() + (tracks.size() == 1 ? " track in library" : " tracks in library"));
        treeRoot.removeAllChildren();
        Map<String, DefaultMutableTreeNode> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Track track : tracks) {
            String folder = track.path().getParent().toString();
            folders.computeIfAbsent(folder, DefaultMutableTreeNode::new)
                .add(new DefaultMutableTreeNode(track));
        }
        folders.values().forEach(treeRoot::add);
        treeModel.reload();
    }

    private void toggleExpanded() {
        expanded = !expanded;
        expandButton.setIcon(AppIcons.of(expanded ? AppIcons.Kind.COLLAPSE : AppIcons.Kind.EXPAND, 17, Theme.BLUE));
        expandButton.setToolTipText(expanded ? "Collapse library" : "Expand library");
        int available = getParent() == null ? 900 : getParent().getWidth();
        int target = expanded ? Math.max(480, available - 32) : COMPACT_WIDTH;
        animateWidth(target);
    }

    private void animateWidth(int target) {
        if (animation != null && animation.isRunning()) animation.stop();
        animation = new Timer(15, event -> {
            int current = getPreferredSize().width;
            int distance = target - current;
            if (Math.abs(distance) <= 6) {
                setPreferredSize(new Dimension(target, 0));
                ((Timer) event.getSource()).stop();
            } else {
                int step = Math.max(6, Math.abs(distance) / 5);
                setPreferredSize(new Dimension(current + (distance > 0 ? step : -step), 0));
            }
            revalidate();
        });
        animation.start();
    }
}
