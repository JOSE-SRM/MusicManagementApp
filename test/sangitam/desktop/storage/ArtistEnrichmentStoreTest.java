package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.*;
import sangitam.desktop.discovery.ArtistEnrichmentClient.Result;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtistEnrichmentStoreTest {
    @TempDir Path folder;
    @Test void confirmedAliasUnifiesCreditsPersistsAcrossReimportAndKeepsManualProfilesAndAlbumPins() throws Exception {
        var library=new LibraryStore(folder.resolve("library.db"));library.initialize();Path a=folder.resolve("a.mp3"),b=folder.resolve("b.mp3");
        var metadata=TrackMetadata.empty().edit(Map.of(TrackMetadata.Field.COMPOSER,"Beethoven"),Set.of("Focus"));
        var track=new Track(a,"Sonata","Beethoven","Sonatas",60,"MP3","Classical",metadata);
        library.saveAll(List.of(track,new Track(b,"Trio","Ludwig van Beethoven","Sonatas",90,"MP3")));
        var profiles=new ArtistStore(library);profiles.save("Beethoven",new ArtistStore.Profile("My notes",null));profiles.addListening(Map.of(a,22d));
        new CollectionStore(library).setPinned(new LibraryCollections.Key(LibraryCollections.Kind.ALBUM,"sonatas\u001fbeethoven"),true);
        var enrichment=new ArtistEnrichmentStore(library);enrichment.save("Beethoven",new Result("Ludwig van Beethoven","Sourced description",null,"https://en.wikipedia.org/wiki/Ludwig_van_Beethoven","",""));
        assertEquals(1,library.loadAll().stream().map(Track::artist).distinct().count());assertEquals("Ludwig van Beethoven",library.loadAll().getFirst().metadata().text(TrackMetadata.Field.COMPOSER));
        assertEquals("My notes",profiles.load("Ludwig van Beethoven").description());assertEquals(22d,profiles.artistSeconds("Ludwig van Beethoven"));
        assertTrue(new CollectionStore(library).load().pins().contains(new LibraryCollections.Key(LibraryCollections.Kind.ALBUM,"sonatas\u001fludwig van beethoven")));
        library.saveAll(List.of(track));assertEquals("Ludwig van Beethoven",library.loadAll().stream().filter(t->t.path().equals(a)).findFirst().orElseThrow().artist());
        library.updateDetails(List.of(a),new LibraryStore.DetailChanges(null,"Beethoven",null,null,null,null,Map.of(TrackMetadata.Field.COMPOSER,"Beethoven"),null));
        try(var c=library.connect();var q=c.createStatement();var row=q.executeQuery("SELECT t.artist,m.search_text FROM tracks t JOIN track_metadata m ON t.path=m.path WHERE t.path='"+a.toString().replace("'","''")+"'")) {
            assertTrue(row.next());assertEquals("Ludwig van Beethoven",row.getString(1));assertTrue(row.getString(2).contains("ludwig van beethoven"));
        }
        enrichment.setOnlineEnabled(false);assertFalse(new ArtistEnrichmentStore(new LibraryStore(folder.resolve("library.db"))).onlineEnabled());
        assertEquals("Ludwig van Beethoven",new ArtistEnrichmentStore(new LibraryStore(folder.resolve("library.db"))).load("Beethoven").name());
    }
}
