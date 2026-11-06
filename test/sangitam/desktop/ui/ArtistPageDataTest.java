package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.*;
import sangitam.desktop.model.LibraryCollections.*;
import sangitam.desktop.model.TrackMetadata.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtistPageDataTest {
    private Track song(int i) {
        return new Track(Path.of("song-" + i + ".mp3"), "Song " + i, "Soloist", "Album " + i / 3, 120, "MP3", "Classical",
            TrackMetadata.empty().edit(Map.of(Field.COMPOSER, "Bach, Johann Sebastian", Field.CONDUCTOR, "Conductor", Field.SOLOIST, "Soloist", Field.ORCHESTRA, "Ensemble"), null));
    }
    @Test void composerConductorSoloistLeadAndExactNamesMatchAllFeaturedRecordings() {
        var songs = java.util.stream.IntStream.range(0, 15).mapToObj(this::song).toList();
        assertEquals(List.of("Bach, Johann Sebastian", "Conductor", "Soloist", "Ensemble"), ArtistCredits.of(songs.getFirst()).stream().map(ArtistCredits.Credit::name).toList());
        assertTrue(ArtistCredits.features(songs.getFirst(), " bach, JOHANN SEBASTIAN ")); assertFalse(ArtistCredits.features(songs.getFirst(), "Bach"));
        var counts = new HashMap<Path, Integer>(); for (int i = 0; i < songs.size(); i++) counts.put(songs.get(i).path(), i);
        var catalog = new LibraryService.Catalog(songs, Map.of(), counts);
        var organization = new LibraryCollections(List.of(), List.of(), Set.of(new Key(Kind.SONG, songs.get(2).path().toString())));
        var result = ArtistPageData.build("Conductor", catalog, organization, Map.of(songs.getFirst().path(), 92d, songs.get(1).path(), 8d));
        assertEquals(15, result.tracks().size()); assertEquals(10, result.popular().size()); assertEquals(songs.getLast(), result.popular().getFirst());
        assertEquals(List.of(songs.get(2)), result.liked()); assertEquals(5, result.albums().size()); assertEquals(3, result.albums().getFirst().tracks().size());
        assertEquals(100, result.seconds()); assertEquals(3, result.collaborators().size());
    }
}
