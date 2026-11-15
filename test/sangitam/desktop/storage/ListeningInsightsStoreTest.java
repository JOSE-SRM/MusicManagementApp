package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.history.ListenEvent;
import sangitam.desktop.model.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ListeningInsightsStoreTest {
    @TempDir Path folder;

    @Test void measuredTimeKeepsRemovedRecordingsAndNeverAddsProviderPlaysAsMinutes() throws Exception {
        var library = new LibraryStore(folder.resolve("library.db"));
        library.initialize();
        Path sonata = folder.resolve("sonata.mp3"), removed = folder.resolve("removed.mp3");
        var metadata = TrackMetadata.empty().edit(Map.of(TrackMetadata.Field.PERIOD, "Romantic"), null);
        Track track = new Track(sonata, "Sonata", "Composer", "Recital", 180, "MP3", "Classical", metadata);
        library.saveScan(List.of(track), Map.of(sonata, new FileStamp(100, 100)), Set.of(), List.of(), Map.of());
        var features = new AudioFeatures(AudioFeatures.VERSION, 100, 100, 90, -30, -15, 8, 500, 80, .6, "C", "minor", .7, 20, 30, "Calm", .6, List.of());
        library.saveTags(sonata, new TrackTags(20, 12, true, 100, 100, features));
        new ArtistStore(library).addListening(Map.of(sonata, 60d, removed, 20d));
        library.recordPlay(sonata);
        library.saveListens(List.of(new ListenEvent("Spotify", "example", "Sonata", "Composer", "Recital", 1, null, null)));

        var store = new ListeningInsightsStore(library);
        var snapshot = store.load();
        assertEquals(80, snapshot.seconds());
        assertEquals(2, snapshot.recordings());
        assertEquals(Map.of("Sangitam", 1L, "Spotify", 1L), snapshot.sourcePlays());
        assertEquals(new ListeningInsightsStore.Bucket("Classical", 60), snapshot.genres().getFirst());
        assertEquals(new ListeningInsightsStore.Bucket("Romantic", 60), snapshot.periods().getFirst());
        assertEquals(new ListeningInsightsStore.Bucket("Calm", 60), snapshot.moods().getFirst());
        assertEquals(new ListeningInsightsStore.Bucket("Removed recordings", 20), snapshot.genres().getLast());

        library.saveScan(List.of(track), Map.of(sonata, new FileStamp(101, 101)), Set.of(), List.of(), Map.of());
        assertEquals("Analysis pending", store.load().moods().getFirst().label());
    }

    @Test void dateRangeUsesLocalDayBoundariesAndKeepsProviderPlaysSeparate() throws Exception {
        var library = new LibraryStore(folder.resolve("dated.db"));
        library.initialize();
        var now = ZonedDateTime.of(2026, 3, 9, 12, 0, 0, 0, ZoneId.of("America/New_York"));
        long first = now.toLocalDate().minusDays(6).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
        long sundayLate = now.minusDays(1).withHour(23).toInstant().toEpochMilli();
        long future = now.plusHours(1).toInstant().toEpochMilli();
        Path removed = folder.resolve("old.mp3");
        try (var connection = library.connect();
             var insert = connection.prepareStatement("INSERT INTO listening_windows(path,observed_at,seconds) VALUES(?,?,?)")) {
            insert.setString(1, removed.toString());
            for (long time : new long[] { first - 1, first, sundayLate, future }) {
                insert.setLong(2, time);
                insert.setDouble(3, 30);
                insert.executeUpdate();
            }
        }
        library.saveListens(List.of(
            new ListenEvent("Spotify", "before", "Song", "Artist", "Album", first - 1, null, null),
            new ListenEvent("Spotify", "sunday", "Song", "Artist", "Album", sundayLate, null, null),
            new ListenEvent("Spotify", "future", "Song", "Artist", "Album", future, null, null)));
        var store = new ListeningInsightsStore(library);
        var week = store.load(ListeningInsightsStore.Range.WEEK, now);
        assertEquals(60, week.seconds());
        assertEquals(1, week.recordings());
        assertEquals(Map.of("Spotify", 1L), week.sourcePlays());
        assertEquals(7, week.trend().size());
        assertEquals(new ListeningInsightsStore.Day(LocalDate.of(2026, 3, 3), 30, 0), week.trend().getFirst());
        assertEquals(new ListeningInsightsStore.Day(LocalDate.of(2026, 3, 8), 30, 1), week.trend().get(5));
        assertEquals(new ListeningInsightsStore.Day(LocalDate.of(2026, 3, 9), 0, 0), week.trend().getLast());
        assertEquals(30, store.load(ListeningInsightsStore.Range.ALL, now).trend().size());
    }
}
