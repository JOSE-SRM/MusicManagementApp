package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;
import sangitam.desktop.library.TrackSearch;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Right-side local library; the width animation remains on Swing's event thread. */
public final class LibraryPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int COMPACT_WIDTH = 300;
    private final DefaultListModel<Track> songModel = new DefaultListModel<>();
    private final JList<Track> songs = new JList<>(songModel);
    private final JTextField searchField = new JTextField();
    private final JButton clearSearch = new JButton("×");
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Imported folders");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JLabel count = UiFactory.label("0 tracks in library", Theme.SMALL, Theme.TEXT_MUTED);
    private final TrackCellRenderer songRenderer = new TrackCellRenderer();
    private final Timer searchDebounce;
    private final JButton expandButton;
    private List<Track> allTracks = List.of();
    private SwingWorker<TrackSearch.Results, Void> activeSearch;
    private long searchGeneration;
    private boolean expanded;
    private Timer animation;

    public LibraryPanel(Runnable importMedia, Consumer<Track> onPlay, Consumer<Track> onEdit,
                        BiConsumer<String, List<Track>> onCreatePlaylist) {
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
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(header);
        top.add(Box.createVerticalStrut(14));
        int menuShortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        String shortcutLabel = (menuShortcut & KeyEvent.META_DOWN_MASK) != 0 ? "⌘+Shift+F" : "Ctrl+Shift+F";
        JLabel searchLabel = UiFactory.label("Find music  ·  " + shortcutLabel, Theme.SMALL, Theme.TEXT_MUTED);
        searchLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(searchLabel);
        top.add(Box.createVerticalStrut(6));
        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.setOpaque(false);
        searchField.setFont(Theme.BODY);
        searchField.setToolTipText("Search titles, artists, albums, genres, and folder names");
        searchField.getAccessibleContext().setAccessibleName("Search your music library");
        searchField.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 4));
        searchRow.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 11),
            BorderFactory.createEmptyBorder(1, 1, 1, 4)));
        searchRow.add(searchField, BorderLayout.CENTER);
        clearSearch.setFont(Theme.font(Font.PLAIN, 20));
        clearSearch.setForeground(Theme.TEXT_MUTED);
        clearSearch.setBackground(Theme.SURFACE);
        clearSearch.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        clearSearch.setFocusPainted(false);
        clearSearch.setToolTipText("Clear search");
        clearSearch.getAccessibleContext().setAccessibleName("Clear library search");
        clearSearch.setVisible(false);
        clearSearch.addActionListener(event -> searchField.setText(""));
        searchRow.add(clearSearch, BorderLayout.EAST);
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(searchRow);
        add(top, BorderLayout.NORTH);

        searchDebounce = new Timer(140, event -> runSearch());
        searchDebounce.setRepeats(false);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                clearSearch.setVisible(!searchField.getText().isEmpty());
                searchDebounce.restart();
            }
            @Override public void insertUpdate(DocumentEvent event) { changed(); }
            @Override public void removeUpdate(DocumentEvent event) { changed(); }
            @Override public void changedUpdate(DocumentEvent event) { changed(); }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(
            KeyEvent.VK_F, menuShortcut | KeyEvent.SHIFT_DOWN_MASK), "focusLibrarySearch");
        getActionMap().put("focusLibrarySearch", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { searchField.requestFocusInWindow(); searchField.selectAll(); }
        });
        searchField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearLibrarySearch");
        searchField.getActionMap().put("clearLibrarySearch", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { searchField.setText(""); }
        });

        songs.setFixedCellHeight(56);
        songs.setCellRenderer(songRenderer);
        songs.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        songs.setToolTipText("Select several tracks with Ctrl/Command-click or Shift-click to create a playlist");
        songs.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() != 2) return;
                int index = songs.locationToIndex(event.getPoint());
                if (index >= 0 && songs.getCellBounds(index, index).contains(event.getPoint()))
                    onPlay.accept(songModel.get(index));
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
        JButton editTags = UiFactory.button("Edit selected tags…", null, false);
        editTags.setAlignmentX(Component.LEFT_ALIGNMENT);
        editTags.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        editTags.setEnabled(false);
        songs.addListSelectionListener(event -> editTags.setEnabled(songs.getSelectedValue() != null));
        editTags.addActionListener(event -> {
            if (songs.getSelectedValue() != null) onEdit.accept(songs.getSelectedValue());
        });
        footer.add(editTags);
        footer.add(Box.createVerticalStrut(8));
        JButton createPlaylist = UiFactory.button("Create playlist from selected", null, false);
        createPlaylist.setAlignmentX(Component.LEFT_ALIGNMENT);
        createPlaylist.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        createPlaylist.setEnabled(false);
        songs.addListSelectionListener(event -> createPlaylist.setEnabled(!songs.isSelectionEmpty()));
        createPlaylist.addActionListener(event -> {
            List<Track> selected = List.copyOf(songs.getSelectedValuesList());
            if (selected.isEmpty()) return;
            JTextField name = new JTextField(24);
            JPanel prompt = new JPanel(new BorderLayout(0, 8));
            prompt.add(UiFactory.label("Playlist name · an existing name will be replaced", Theme.SMALL, Theme.TEXT), BorderLayout.NORTH);
            prompt.add(name, BorderLayout.CENTER);
            if (JOptionPane.showConfirmDialog(this, prompt, "Create playlist",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String playlistName = name.getText().strip();
            if (playlistName.isEmpty()) {
                Toolkit.getDefaultToolkit().beep();
                return;
            }
            onCreatePlaylist.accept(playlistName, selected);
        });
        footer.add(createPlaylist);
        footer.add(Box.createVerticalStrut(8));
        JButton add = UiFactory.button("Add to library", AppIcons.of(AppIcons.Kind.PLUS, 15, Theme.BLACK), true);
        add.setAlignmentX(Component.LEFT_ALIGNMENT);
        add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        add.addActionListener(event -> importMedia.run());
        footer.add(add);
        add(footer, BorderLayout.SOUTH);
    }

    public void setTracks(List<Track> tracks, Map<Path, TrackTags> tags, Set<Path> manualTags) {
        songRenderer.setTags(tags);
        songRenderer.setManuallyEdited(manualTags);
        allTracks = List.copyOf(tracks);
        searchDebounce.stop();
        runSearch();
    }

    private void runSearch() {
        if (activeSearch != null) activeSearch.cancel(true);
        long request = ++searchGeneration;
        String query = searchField.getText();
        List<Track> source = allTracks;
        activeSearch = new SwingWorker<>() {
            @Override protected TrackSearch.Results doInBackground() {
                return TrackSearch.find(source, query);
            }

            @Override protected void done() {
                if (isCancelled() || request != searchGeneration) return;
                try {
                    List<Track> matches = get().songs();
                    songModel.clear();
                    songModel.addAll(matches);
                    updateCount(query, matches.size(), source.size());
                    updateFolderTree(matches);
                } catch (Exception error) {
                    count.setText("Search could not complete. Try again.");
                }
            }
        };
        activeSearch.execute();
    }

    private void updateCount(String query, int matches, int total) {
        if (query.isBlank()) {
            count.setText(total + (total == 1 ? " track in library" : " tracks in library"));
        } else if (matches == 0) {
            count.setText("No matches · try a title, artist, album, genre, or folder");
        } else {
            count.setText(matches + (matches == 1 ? " match" : " matches") + " · Esc clears search");
        }
    }

    private void updateFolderTree(List<Track> tracks) {
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
