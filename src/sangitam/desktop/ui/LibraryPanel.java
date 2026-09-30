package sangitam.desktop.ui;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Right-side media library with a smooth width animation.
 * IMPORTANT: Swing state changes stay on the Event Dispatch Thread; the Timer makes expansion safe and responsive.
 */
public final class LibraryPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int COMPACT_WIDTH = 300;
    private boolean expanded;
    private final JButton expandButton;
    private Timer animation;

    public LibraryPanel(Runnable importMedia, Consumer<String> notify) {
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

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Playlists", playlistList(notify));
        tabs.addTab("Folders", folderTree(notify));
        add(tabs, BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        JProgressBar scanProgress = new JProgressBar(0, 100);
        scanProgress.setValue(68);
        scanProgress.setString("Library scan 68%");
        scanProgress.setStringPainted(true);
        scanProgress.setToolTipText("Example scanning progress");
        scanProgress.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(scanProgress);
        footer.add(Box.createVerticalStrut(10));
        JButton add = UiFactory.button("Add to library", AppIcons.of(AppIcons.Kind.PLUS, 15, Theme.BLACK), true);
        add.setAlignmentX(Component.LEFT_ALIGNMENT);
        add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        add.addActionListener(event -> importMedia.run());
        footer.add(add);
        add(footer, BorderLayout.SOUTH);
    }

    private JComponent playlistList(Consumer<String> notify) {
        String[] values = {"Focus flow", "Recently added", "Late night mix", "Acoustic calm", "Discoveries"};
        JList<String> list = new JList<>(values);
        list.setFixedCellHeight(42);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && list.getSelectedValue() != null) {
                notify.accept("Playlist “" + list.getSelectedValue() + "” selected");
            }
        });
        return new JScrollPane(list);
    }

    private JComponent folderTree(Consumer<String> notify) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Music");
        DefaultMutableTreeNode local = new DefaultMutableTreeNode("Local files");
        local.add(new DefaultMutableTreeNode("Albums"));
        local.add(new DefaultMutableTreeNode("Singles"));
        root.add(local);
        root.add(new DefaultMutableTreeNode("Imported folders"));
        JTree tree = new JTree(root);
        tree.addTreeSelectionListener(event -> notify.accept("Folder “" + event.getPath().getLastPathComponent() + "” selected"));
        return new JScrollPane(tree);
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
