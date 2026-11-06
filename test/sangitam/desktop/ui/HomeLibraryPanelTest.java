package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.*;
import sangitam.desktop.model.LibraryCollections.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HomeLibraryPanelTest {
    @Test void boundedShelvesResolvePinnedSmartAndRegularPlaylistsWithoutRetainingWholeCatalog() throws Exception {
        var tracks = new ArrayList<Track>(); var stamps = new HashMap<Path, FileStamp>();
        for (int i = 0; i < 40; i++) {
            var track = new Track(Path.of("song-" + i + ".mp3"), "Song " + i, "Artist", "Album", 60, "MP3");
            tracks.add(track); stamps.put(track.path(), new FileStamp(i, i));
        }
        var playlists = new ArrayList<Playlist>(); var pins = new HashSet<Key>();
        for (int i = 0; i < 12; i++) {
            playlists.add(new Playlist(i + 1, "Pinned " + String.format("%02d", i), List.of(tracks.get(20 + i).path(), Path.of("removed.mp3")), 0, "", i,
                i == 0 ? SmartPlaylistRule.defaults() : null));
            pins.add(new Key(Kind.PLAYLIST, Integer.toString(i + 1)));
        }
        playlists.add(new Playlist(99, "Unpinned", List.of(tracks.getLast().path())));
        pins.add(new Key(Kind.ALBUM, "album"));
        var catalog = new LibraryService.Catalog(tracks, Map.of(), Map.of(), Set.of(), Set.of(tracks.get(20).path()), Map.of(), Set.of(), stamps);
        var data = HomeLibraryData.build(catalog, new LibraryCollections(playlists, List.of(), pins), tracks);
        assertEquals(12, data.recent().size()); assertEquals(8, data.pinned().size());
        assertEquals("Pinned 00", data.pinned().getFirst().name()); assertEquals("Smart · 1 tracks", data.pinned().getFirst().detail());
        assertEquals(1, data.pinned().getFirst().tracks().size());
        assertEquals(20, data.stamps().size()); assertFalse(data.stamps().containsKey(tracks.getLast().path()));
        assertEquals(Set.of(tracks.get(20).path()), data.unavailable());
        AtomicReference<LibraryEntry> opened = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = new HomeLibraryPanel(opened::set, ignored -> new JPopupMenu());
            try {
                panel.setData(data);
                List<JList<?>> lists = lists(panel); assertEquals(2, lists.size());
                assertEquals("Recently played", lists.get(0).getAccessibleContext().getAccessibleName());
                assertEquals(12, lists.get(0).getModel().getSize()); assertEquals(8, lists.get(1).getModel().getSize());
                for (JList<?> list : lists) {
                    list.setSelectedIndex(0);
                    list.getActionMap().get("openEntry").actionPerformed(new ActionEvent(list, 0, ""));
                    assertEquals(list.getSelectedValue(), opened.get());
                    assertNotNull(list.getActionMap().get("entryMenu"));
                }
                panel.setData(HomeLibraryData.build(new LibraryService.Catalog(List.of(), Map.of(), Map.of()), LibraryCollections.empty(), List.of()));
                assertEquals(0, lists.get(0).getModel().getSize()); assertEquals(0, lists.get(1).getModel().getSize());
            } finally { panel.close(); }
        });
    }
    private static List<JList<?>> lists(Container parent) {
        var result = new ArrayList<JList<?>>();
        for (Component child : parent.getComponents()) {
            if (child instanceof JList<?> list) result.add(list);
            else if (child instanceof Container container) result.addAll(lists(container));
        }
        return result;
    }
}
