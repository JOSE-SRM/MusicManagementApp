package sangitam.desktop.ui;

import sangitam.desktop.model.LibraryCollections.Folder;
import sangitam.desktop.model.Playlist;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.SmartPlaylistRule;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static sangitam.desktop.model.LibraryCollections.Kind.*;

/** Context actions shared by General and Locations. */
final class LibraryMenus {
    private record Destination(long id, String label) { @Override public String toString() { return label; } }
    private final LibraryPanel panel;
    private final LibraryPanel.Actions actions;

    LibraryMenus(LibraryPanel panel, LibraryPanel.Actions actions) {
        this.panel = panel;
        this.actions = actions;
    }

    JPopupMenu addMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Import music…", () -> actions.importMusic(panel.currentFolder())));
        menu.add(item("Create playlist…", () -> createPlaylist(panel.currentFolder(), List.of())));
        menu.add(item("Create smart playlist…", () -> editSmartPlaylist(null, panel.currentFolder())));
        menu.add(item("Create folder…", () -> createFolder(panel.currentFolder())));
        return menu;
    }

    JPopupMenu libraryMenu() {
        JPopupMenu menu = addMenu();
        menu.addSeparator();
        menu.add(item("Rescan library…", actions::rescan));
        return menu;
    }

    JPopupMenu locationMenu(Path folder) {
        JPopupMenu menu = new JPopupMenu();
        List<Path> paths = panel.tracks().stream().map(Track::path).filter(path -> path.startsWith(folder)).toList();
        menu.add(addToPlaylist(paths, "Add to playlist", 0));
        menu.add(item("Rescan library…", actions::rescan));
        menu.add(item("Remove from library…", () -> {
            if (confirm("Remove all indexed tracks under “" + folder + "”? Their files stay on disk.")) {
                actions.remove(List.of(), folder);
            }
        }));
        return menu;
    }

    JPopupMenu entryMenu(LibraryEntry entry) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(playable("Play now", entry, () -> actions.play(entry.tracks())));
        switch (entry.kind()) {
            case SONG, ALBUM, ARTIST -> {
                menu.add(addToPlaylist(entry, "Add to playlist"));
                JMenu taste = new JMenu("Taste profile");
                String[] labels = {"Avoid in automatic sessions", "Less like this", "Neutral / reset", "More like this", "Love / favour"};
                Integer selected = actions.tasteRating(entry.tracks()); ButtonGroup ratings = new ButtonGroup();
                for (int i=0;i<labels.length;i++) {
                    int rating=i-2; var item = new JRadioButtonMenuItem(labels[i], selected != null && selected == rating);
                    item.addActionListener(event -> actions.taste(entry.tracks(),rating)); ratings.add(item); taste.add(item);
                }
                menu.add(taste);
                if (entry.kind() != ARTIST) menu.add(item("Edit details…",
                    () -> actions.editDetails(entry.tracks(), entry.kind() == ALBUM)));
                if (entry.kind() != ARTIST) menu.add(playable("Restore file details…", entry, () -> actions.restoreDetails(entry.tracks())));
                menu.add(playable("Add to queue", entry, () -> actions.queue(entry.tracks())));
                if (entry.kind() != ARTIST) {
                    menu.addSeparator();
                    menu.add(item("Remove from library…", () -> {
                        if (confirm("Remove “" + entry.name() + "” from the library? Its files stay on disk.")) {
                            actions.remove(entry.paths(), null);
                        }
                    }));
                }
            }
            case PLAYLIST -> {
                Playlist playlist = panel.collections().playlists().stream().filter(value -> value.id() == entry.id()).findFirst().orElse(null);
                if (playlist != null && playlist.likedSongs()) {
                    menu.add(addToPlaylist(entry, "Copy songs to playlist"));
                    menu.add(playable("Add to queue", entry, () -> actions.queue(entry.tracks())));
                    break;
                }
                boolean smart = playlist != null && playlist.smart();
                if (smart) {
                    menu.add(item("Edit rules…", () -> editSmartPlaylist(playlist, playlist.folderId())));
                    menu.add(item("Refresh matches", () -> actions.organize("Smart playlist refreshed", ignored -> { })));
                    menu.add(item("Save as regular playlist…", () -> createPlaylist(playlist.folderId(), entry.paths())));
                } else menu.add(item("Add songs…", () -> chooseSongs(paths -> addToPlaylist(entry.id(), paths))));
                menu.add(addToPlaylist(entry, "Add to other playlist"));
                menu.add(item("Edit details…", () -> {
                    if (smart) editSmartPlaylist(playlist, playlist.folderId()); else editPlaylist(entry);
                }));
                menu.add(playable("Add to queue", entry, () -> actions.queue(entry.tracks())));
                menu.add(item("Move to folder…", () -> move(entry)));
                menu.addSeparator();
                menu.add(item("Delete playlist…", () -> {
                    if (confirm("Delete playlist “" + entry.name() + "”?")) actions.deletePlaylist(entry.id());
                }));
            }
            case FOLDER -> {
                menu.add(item("Add songs…", () -> chooseSongs(paths -> addToFolder(entry.id(), paths))));
                menu.add(addToPlaylist(entry, "Add to playlist"));
                JMenu create = new JMenu("Create");
                create.add(item("Playlist…", () -> createPlaylist(entry.id(), List.of())));
                create.add(item("Smart playlist…", () -> editSmartPlaylist(null, entry.id())));
                create.add(item("Folder…", () -> createFolder(entry.id())));
                menu.add(create);
                menu.add(item("Import music…", () -> actions.importMusic(entry.id())));
                menu.add(playable("Add to queue", entry, () -> actions.queue(entry.tracks())));
                menu.add(item("Move folder…", () -> move(entry)));
                menu.addSeparator();
                menu.add(item("Delete folder…", () -> {
                    if (confirm("Delete “" + entry.name() + "” and its nested folders and playlists? The tracks stay in your library.")) {
                        actions.organize("Folder deleted", store -> store.deleteFolder(entry.id()));
                    }
                }));
            }
        }
        if (entry.kind() != ARTIST) {
            menu.addSeparator();
            boolean pinned = panel.collections().pins().contains(entry.key());
            String label = entry.kind() == SONG ? (pinned ? "Unlike song" : "Like song · add to Liked Songs")
                : (pinned ? "Unpin" : "Pin to library");
            menu.add(item(label, () -> actions.organize(
                pinned ? "Unpinned" : "Pinned to library", store -> store.setPinned(entry.key(), !pinned))));
        }
        return menu;
    }

    private JMenu addToPlaylist(LibraryEntry entry, String label) {
        return addToPlaylist(entry.paths(), label, entry.kind() == PLAYLIST ? entry.id() : 0);
    }

    private JMenu addToPlaylist(List<Path> paths, String label, long sourcePlaylist) {
        JMenu menu = new JMenu(label);
        menu.setEnabled(!paths.isEmpty());
        long folder = panel.currentFolder();
        menu.add(item("+ Create a new playlist…", () -> createPlaylist(folder, paths)));
        List<Playlist> choices = panel.collections().playlists().stream()
            .filter(playlist -> playlist.id() != sourcePlaylist && playlist.acceptsManualEdits()).toList();
        JMenu existing = new JMenu("Add to existing playlist");
        existing.setEnabled(!choices.isEmpty());
        existing.getPopupMenu().setFocusable(true);
        JTextField search = new JTextField(20);
        search.getAccessibleContext().setAccessibleName("Search playlists");
        search.setToolTipText("Search playlists");
        DefaultListModel<Playlist> model = new DefaultListModel<>();
        JList<Playlist> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((source, value, index, selected, focus) -> {
            JLabel row = (JLabel) new DefaultListCellRenderer().getListCellRendererComponent(source, value, index, selected, focus);
            row.setText(value.name() + (value.folderId() == 0 ? "" : " · " + folderPath(value.folderId())));
            return row;
        });
        Runnable filter = () -> {
            String query = LibraryEntry.lower(search.getText().strip());
            model.clear();
            model.addAll(choices.stream().filter(playlist ->
                LibraryEntry.lower(playlist.name() + " " + folderPath(playlist.folderId())).contains(query)).toList());
            if (!model.isEmpty()) list.setSelectedIndex(0);
        };
        LibraryPanel.listen(search, filter);
        filter.run();
        JPanel picker = new JPanel(new BorderLayout(0, 7));
        picker.setBorder(Theme.padding(8, 8, 8, 8));
        picker.add(search, BorderLayout.NORTH);
        picker.add(new JScrollPane(list), BorderLayout.CENTER);
        picker.setPreferredSize(new Dimension(280, 215));
        Runnable add = () -> {
            Playlist target = list.getSelectedValue();
            if (target == null) return;
            MenuSelectionManager.defaultManager().clearSelectedPath();
            addToPlaylist(target.id(), paths);
        };
        JButton addButton = UiFactory.button("Add to selected playlist", null, true);
        addButton.addActionListener(event -> add.run());
        addButton.setEnabled(!model.isEmpty());
        list.addListSelectionListener(event -> addButton.setEnabled(list.getSelectedValue() != null));
        search.addActionListener(event -> add.run());
        LibraryPanel.bind(list, KeyStroke.getKeyStroke("ENTER"), "add", add);
        picker.add(addButton, BorderLayout.SOUTH);
        existing.add(picker);
        existing.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent event) { SwingUtilities.invokeLater(search::requestFocusInWindow); }
            @Override public void menuDeselected(MenuEvent event) { }
            @Override public void menuCanceled(MenuEvent event) { }
        });
        menu.add(existing);
        return menu;
    }

    private void addToPlaylist(long id, List<Path> paths) {
        actions.organize("Playlist updated", store -> store.addPlaylistTracks(id, paths));
    }

    private void addToFolder(long id, List<Path> paths) {
        if (!paths.isEmpty()) actions.organize("Folder updated", store -> store.addFolderTracks(id, paths));
    }

    private void createPlaylist(long parent, List<Path> paths) {
        String name = name("Create playlist");
        if (name != null) actions.organize("Playlist created", store -> store.createPlaylist(name, parent, paths, false));
    }

    private void createFolder(long parent) {
        String name = name("Create folder");
        if (name != null) actions.organize("Folder created", store -> store.createFolder(name, parent));
    }

    private void editSmartPlaylist(Playlist playlist, long folder) {
        try (var editor = new SmartPlaylistPanel(playlist == null ? "" : playlist.name(),
            playlist == null ? "" : playlist.description(), playlist == null ? SmartPlaylistRule.defaults() : playlist.rule(), actions::previewSmart)) {
            while (JOptionPane.showConfirmDialog(panel, editor, playlist == null ? "Create smart playlist" : "Edit smart playlist",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                try {
                    String name = editor.playlistName(); String description = editor.description(); SmartPlaylistRule rule = editor.rule();
                    actions.organize("Smart playlist saved", store -> {
                        if (playlist == null) store.createSmartPlaylist(name, description, folder, rule);
                        else store.editSmartPlaylist(playlist.id(), name, description, rule);
                    });
                    return;
                } catch (IllegalArgumentException error) {
                    JOptionPane.showMessageDialog(panel, error.getMessage(), "Check playlist rules", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void editPlaylist(LibraryEntry entry) {
        Playlist playlist = panel.collections().playlists().stream().filter(value -> value.id() == entry.id())
            .findFirst().orElse(null);
        if (playlist == null) return;
        JTextField name = new JTextField(playlist.name(), 26);
        JTextField description = new JTextField(playlist.description(), 26);
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 6));
        form.add(new JLabel("Playlist name"));
        form.add(name);
        form.add(new JLabel("Description"));
        form.add(description);
        if (JOptionPane.showConfirmDialog(panel, form, "Edit playlist", JOptionPane.OK_CANCEL_OPTION)
            == JOptionPane.OK_OPTION && !name.getText().isBlank()) {
            String title = name.getText().strip();
            String note = description.getText().strip();
            actions.organize("Playlist details saved", store -> store.editPlaylist(entry.id(), title, note));
        }
    }

    private void chooseSongs(Consumer<List<Path>> accept) {
        JTextField search = new JTextField();
        search.getAccessibleContext().setAccessibleName("Search songs");
        DefaultListModel<Track> model = new DefaultListModel<>();
        JList<Track> songs = new JList<>(model);
        songs.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        List<Track> tracks = panel.tracks();
        Runnable filter = () -> {
            String query = LibraryEntry.lower(search.getText().strip());
            model.clear();
            model.addAll(tracks.stream().filter(track ->
                LibraryEntry.lower(track + " " + track.album()).contains(query)).toList());
        };
        LibraryPanel.listen(search, filter);
        filter.run();
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(search, BorderLayout.NORTH);
        content.add(new JScrollPane(songs), BorderLayout.CENTER);
        content.setPreferredSize(new Dimension(510, 310));
        if (JOptionPane.showConfirmDialog(panel, content, "Choose songs", JOptionPane.OK_CANCEL_OPTION)
            == JOptionPane.OK_OPTION && !songs.isSelectionEmpty()) {
            accept.accept(songs.getSelectedValuesList().stream().map(Track::path).toList());
        }
    }

    private void move(LibraryEntry entry) {
        var destinations = new java.util.ArrayList<Destination>();
        destinations.add(new Destination(0, "Library root"));
        for (Folder folder : panel.collections().folders()) {
            if (entry.kind() == FOLDER && isInside(folder, entry.id())) continue;
            destinations.add(new Destination(folder.id(), folderPath(folder.id())));
        }
        Destination selected = (Destination) JOptionPane.showInputDialog(panel, "Move “" + entry.name() + "” to",
            "Choose folder", JOptionPane.PLAIN_MESSAGE, null, destinations.toArray(), destinations.getFirst());
        if (selected != null && selected.id() != entry.parent()) moveTo(entry, selected.id());
    }

    private boolean isInside(Folder folder, long id) {
        while (folder != null) {
            if (folder.id() == id) return true;
            folder = findFolder(folder.parentId());
        }
        return false;
    }

    private Folder findFolder(long id) {
        return panel.collections().folders().stream().filter(folder -> folder.id() == id).findFirst().orElse(null);
    }

    private String folderPath(long id) {
        var parts = new java.util.ArrayList<String>();
        for (Folder folder = findFolder(id); folder != null; folder = findFolder(folder.parentId())) parts.add(folder.name());
        return String.join(" / ", parts.reversed());
    }

    void drop(LibraryEntry source, LibraryEntry target) {
        if (!LibraryPanel.acceptsDrop(source, target, panel.collections())) return;
        if (target.kind() == PLAYLIST) {
            addToPlaylist(target.id(), source.paths());
            return;
        }
        switch (source.kind()) {
            case FOLDER, PLAYLIST -> moveTo(source, target.id());
            case ALBUM -> actions.organize("Album added as a playlist",
                store -> store.createPlaylist(source.name(), target.id(), source.paths(), true));
            case SONG -> addToFolder(target.id(), source.paths());
            case ARTIST -> { }
        }
    }

    private void moveTo(LibraryEntry source, long destination) {
        if (source.kind() == FOLDER) {
            actions.organize("Folder moved", store -> store.moveFolder(source.id(), destination));
        } else actions.organize("Playlist moved", store -> store.movePlaylist(source.id(), destination));
    }

    private String name(String title) {
        String name = JOptionPane.showInputDialog(panel, "Name", title, JOptionPane.PLAIN_MESSAGE);
        return name == null || name.isBlank() ? null : name.strip();
    }

    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(panel, message, "Library", JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private JMenuItem playable(String text, LibraryEntry entry, Runnable action) {
        JMenuItem item = item(text, action);
        item.setEnabled(!entry.tracks().isEmpty());
        return item;
    }

    private JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(event -> action.run());
        return item;
    }
}
