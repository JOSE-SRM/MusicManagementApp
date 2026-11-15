package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.*;
import sangitam.desktop.model.LibraryCollections.*;
import sangitam.desktop.storage.SmartPlaylistStore.Preview;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SmartPlaylistLibraryTest {
    @Test void smartMenusAndDropPolicyPreserveRulesWhileAllowingSnapshotCopies() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Track track = new Track(Path.of("song.mp3"), "Song", "Artist", "Album", 60, "MP3");
            var smart = new Playlist(1, "Smart", List.of(track.path()), 0, "A rule", 1, SmartPlaylistRule.defaults());
            var regular = new Playlist(2, "Regular", List.of(track.path()));
            var collections = new LibraryCollections(List.of(smart, regular), List.of(), Set.of());
            AtomicInteger writes = new AtomicInteger();
            LibraryPanel.Actions actions = new LibraryPanel.Actions() {
                public void importMusic(long folder) { } public void rescan() { } public void play(List<Track> tracks) { }
                public void queue(List<Track> tracks) { } public void editDetails(List<Track> tracks, boolean group) { }
                public void restoreDetails(List<Track> tracks) { }
                public void remove(List<Path> paths, Path scope) { } public void open(LibraryEntry entry) { }
                public void organize(String message, LibraryPanel.CollectionEdit edit) { writes.incrementAndGet(); }
                public void deletePlaylist(long id) { } public void entriesChanged() { }
                public Preview previewSmart(SmartPlaylistRule rule) { return new Preview(0, 0, List.of()); }
            };
            LibraryPanel panel = new LibraryPanel(actions);
            try {
                panel.setCollections(collections);
                LibraryMenus menus = new LibraryMenus(panel, actions);
                LibraryEntry smartEntry = entry(Kind.PLAYLIST, "1", List.of(track));
                LibraryEntry ordinary = entry(Kind.PLAYLIST, "2", List.of(track));
                LibraryEntry song = entry(Kind.SONG, track.path().toString(), List.of(track));
                LibraryEntry folder = entry(Kind.FOLDER, "10", List.of(track));
                JPopupMenu smartMenu = menus.entryMenu(smartEntry);
                assertTrue(has(smartMenu, "Edit rules…")); assertFalse(has(smartMenu, "Add songs…"));
                assertTrue(has(smartMenu, "Save as regular playlist…"));
                assertTrue(has(menus.entryMenu(ordinary), "Add songs…"));
                assertTrue(has(menus.entryMenu(song), "Restore file details…"));
                assertTrue(has(menus.entryMenu(entry(Kind.ALBUM, "album", List.of(track))), "Restore file details…"));
                JList<?> choices = playlistChoices(smartMenu);
                assertNotNull(choices); assertEquals(1, choices.getModel().getSize());
                assertEquals(regular, choices.getModel().getElementAt(0));
                assertFalse(LibraryPanel.acceptsDrop(song, smartEntry, collections));
                assertTrue(LibraryPanel.acceptsDrop(smartEntry, ordinary, collections));
                assertTrue(LibraryPanel.acceptsDrop(smartEntry, folder, collections));
                menus.drop(song, smartEntry); assertEquals(0, writes.get());
                menus.drop(smartEntry, ordinary); assertEquals(1, writes.get());
                var catalog = new LibraryService.Catalog(List.of(track), java.util.Map.of(), java.util.Map.of());
                assertTrue(LibraryEntry.build(catalog, collections).stream().filter(row -> row.key().equals(smartEntry.key())).findFirst().orElseThrow().detail().startsWith("Smart"));
            } finally { panel.close(); }
        });
    }
    private static LibraryEntry entry(Kind kind, String id, List<Track> tracks) { return new LibraryEntry(new Key(kind, id), kind.toString(), "", tracks, 0, 0, 0); }
    private static boolean has(JPopupMenu menu, String text) { for (Component item : menu.getComponents()) if (item instanceof JMenuItem entry && text.equals(entry.getText())) return true; return false; }
    private static JList<?> playlistChoices(Container root) {
        Component[] items = root instanceof JMenu menu ? menu.getMenuComponents() : root.getComponents();
        for (Component item : items) {
            if (item instanceof JList<?> list && list.getModel().getSize() > 0 && list.getModel().getElementAt(0) instanceof Playlist) return list;
            if (item instanceof Container nested) { var found = playlistChoices(nested); if (found != null) return found; }
        }
        return null;
    }
}
