package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.*;
import sangitam.desktop.model.LibraryCollections.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GeneralLibraryPagesTest {
    static List<Track> songs(int count) { return java.util.stream.IntStream.range(0, count).mapToObj(i -> new Track(Path.of("music", "%04d.mp3".formatted(i)), "Song %04d".formatted(i), "Unknown artist", "Unknown album", 60, "MP3")).toList(); }
    @Test void stablePagesRespectPinsTiesAndClampAfterRemoval() {
        var songs = songs(205); var catalog = new LibraryService.Catalog(songs, Map.of(), Map.of());
        var pin = new Key(Kind.SONG, songs.getLast().path().toString());
        var organization = new LibraryCollections(List.of(), List.of(), Set.of(pin));
        var entries = LibraryEntry.build(catalog, organization); var seen = new HashSet<Key>();
        for (int offset : new int[]{0,100,200}) {
            var page = GeneralLibraryPages.query(entries, organization, new GeneralLibraryPages.Request("", 0, GeneralLibraryPages.Order.ALPHABETICAL, offset));
            assertEquals(205, page.total()); assertTrue(page.entries().size() <= 100); assertEquals(offset, page.offset());
            if (offset == 0) assertEquals(pin, page.entries().getFirst().key());
            for (var entry : page.entries()) assertTrue(seen.add(entry.key()));
            assertEquals(offset < 200, page.hasNext());
        }
        assertEquals(205, seen.size());
        assertEquals(0, GeneralLibraryPages.query(entries.subList(0, 3), organization, new GeneralLibraryPages.Request("",0,GeneralLibraryPages.Order.RECENT,200)).offset());
    }
    @Test void folderScopeLiteralMetadataSearchAndCompleteGroupMembershipArePreserved() {
        var songs = songs(130);
        Track tagged = new Track(songs.getFirst().path(), "100%_Étude", "Artist", "Album", 60,"MP3","Classical", TrackMetadata.empty().edit(Map.of(TrackMetadata.Field.COMPOSER,"Frédéric Chopin"),Set.of("Focus")));
        var all = new ArrayList<>(songs); all.set(0, tagged);
        var playlist = new Playlist(1,"Nested playlist",songs.stream().map(Track::path).toList(),7,"",0);
        var folder = new LibraryCollections.Folder(7,0,"Folder",List.of(tagged.path()),0);
        var organization = new LibraryCollections(List.of(playlist),List.of(folder),Set.of());
        var catalog = new LibraryService.Catalog(all,Map.of(),Map.of()); var entries = LibraryEntry.build(catalog,organization);
        var root = GeneralLibraryPages.query(entries,organization,new GeneralLibraryPages.Request("",0,GeneralLibraryPages.Order.RECENT,0));
        assertFalse(root.entries().stream().anyMatch(entry -> entry.key().equals(new Key(Kind.PLAYLIST,"1"))));
        var inside = GeneralLibraryPages.query(entries,organization,new GeneralLibraryPages.Request("",7,GeneralLibraryPages.Order.ALPHABETICAL,0));
        assertEquals(2,inside.total()); assertEquals(130,inside.entries().stream().filter(entry -> entry.kind()==Kind.PLAYLIST).findFirst().orElseThrow().tracks().size());
        var literal = GeneralLibraryPages.query(entries,organization,new GeneralLibraryPages.Request("%_",0,GeneralLibraryPages.Order.RECENT,0)); assertEquals(1,literal.total());
        var composer = GeneralLibraryPages.query(entries,organization,new GeneralLibraryPages.Request("FRÉDÉRIC",0,GeneralLibraryPages.Order.RECENT,0)); assertEquals(tagged,composer.entries().getFirst().tracks().getFirst());
    }
    @Test void recentAndPlayCountOrderingKeepPinsFirst() {
        var songs = songs(4); var catalog = new LibraryService.Catalog(songs, Map.of(), Map.of(songs.get(2).path(), 20), Set.of(), Set.of(), Map.of(songs.get(1).path(), 100L), Set.of());
        var pinned = new Key(Kind.SONG, songs.get(3).path().toString()); var organization = new LibraryCollections(List.of(), List.of(), Set.of(pinned)); var entries = LibraryEntry.build(catalog, organization);
        var recent = GeneralLibraryPages.query(entries, organization, new GeneralLibraryPages.Request("", 0, GeneralLibraryPages.Order.RECENT, 0));
        var played = GeneralLibraryPages.query(entries, organization, new GeneralLibraryPages.Request("", 0, GeneralLibraryPages.Order.PLAYED, 0));
        assertEquals(pinned, recent.entries().getFirst().key()); assertEquals(pinned, played.entries().getFirst().key());
        assertEquals(songs.get(1), recent.entries().get(1).tracks().getFirst()); assertEquals(songs.get(2), played.entries().get(1).tracks().getFirst());
    }
}
