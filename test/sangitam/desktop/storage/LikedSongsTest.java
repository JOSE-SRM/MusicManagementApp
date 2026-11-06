package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.LibraryCollections.*;
import sangitam.desktop.model.Playlist;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LikedSongsTest {
    @TempDir Path folder;

    @Test void oldSongPinsAndNewHeartsShareOnePersistentManagedPlaylist() throws Exception {
        var store = new LibraryStore(folder.resolve("library.db")); store.initialize();
        var collections = new CollectionStore(store);
        Path song = folder.resolve("liked.wav"), other = folder.resolve("other.wav");
        var oldHeart = new Key(Kind.SONG, song.toString());
        var newHeart = new Key(Kind.SONG, other.toString());
        collections.setPinned(oldHeart, true);
        collections.setPinned(new Key(Kind.ALBUM, "Pinned album"), true);
        collections.togglePinned(newHeart);
        Playlist liked = new CollectionStore(new LibraryStore(folder.resolve("library.db"))).load().withLikedSongs().playlists().getFirst();
        assertEquals("Liked Songs", liked.name());
        assertFalse(liked.acceptsManualEdits());
        assertEquals(List.of(song, other), liked.tracks());
        collections.togglePinned(oldHeart);
        assertEquals(List.of(other), collections.load().withLikedSongs().playlists().getFirst().tracks());
    }
}
