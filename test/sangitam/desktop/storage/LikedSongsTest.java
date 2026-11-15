package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.LibraryCollections.*;
import sangitam.desktop.model.Playlist;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LikedSongsTest {
    @TempDir Path folder;

    @Test void migrationKeepsLikesAndManualRatingsAndLetsTheUserRestoreOldPins() throws Exception {
        Path database = folder.resolve("library.db");
        var legacy = new LibraryStore(database);
        legacy.initialize();
        Path track = folder.resolve("legacy.wav");
        var songPin = new Key(Kind.SONG, track.toString());
        var albumPin = new Key(Kind.ALBUM, "Pinned album");
        var collections = new CollectionStore(legacy);
        collections.setPinned(songPin, true);
        collections.setPinned(albumPin, true);
        new TasteStore(legacy).rate(List.of(track), -2);
        try (var connection = legacy.connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM app_state WHERE key='independent_song_likes'");
            statement.executeUpdate("DROP TABLE song_likes");
            statement.executeUpdate("DROP TABLE former_song_pins");
        }
        var restarted = new LibraryStore(database);
        restarted.initialize();
        collections = new CollectionStore(restarted);
        var migrated = collections.load().withLikedSongs();
        assertEquals(List.of(track), migrated.playlists().getFirst().tracks());
        assertTrue(migrated.formerSongPins().contains(track));
        assertFalse(migrated.pins().contains(songPin));
        assertTrue(migrated.pins().contains(albumPin));
        assertEquals(-2, new TasteStore(restarted).ratings().get(track));

        collections.setPinned(songPin, true);
        collections.setLiked(track, false);
        var secondRestart = new LibraryStore(database);
        secondRestart.initialize();
        var restored = new CollectionStore(secondRestart).load().withLikedSongs();
        assertTrue(restored.pins().contains(songPin));
        assertFalse(restored.formerSongPins().contains(track));
        assertTrue(restored.playlists().getFirst().tracks().isEmpty());
    }

    @Test void likeAndPinAreIndependentAndDuplicateLikeRequestsApplyOneBonus() throws Exception {
        var store = new LibraryStore(folder.resolve("library.db"));
        store.initialize();
        var collections = new CollectionStore(store);
        var taste = new TasteStore(store);
        var now = ZonedDateTime.now();
        Path track = folder.resolve("liked.wav");
        var pin = new Key(Kind.SONG, track.toString());
        taste.rate(List.of(track), 1);
        double original = taste.context(now).preference(track, 3);
        collections.setPinned(pin, true);
        assertEquals(original, taste.context(now).preference(track, 3));
        assertFalse(collections.load().likes().contains(track));
        collections.setPinned(pin, false);
        collections.setLiked(track, true);
        collections.setLiked(track, true);
        assertEquals(original + 12d, taste.context(now).preference(track, 3));
        var organization = collections.load().withLikedSongs();
        assertFalse(organization.pins().contains(pin));
        Playlist liked = organization.playlists().getFirst();
        assertEquals("Liked Songs", liked.name());
        assertFalse(liked.acceptsManualEdits());
        assertEquals(List.of(track), liked.tracks());
        collections.setLiked(track, false);
        collections.setLiked(track, false);
        assertEquals(original, taste.context(now).preference(track, 3));
        assertEquals(1, taste.ratings().get(track));
    }
}
