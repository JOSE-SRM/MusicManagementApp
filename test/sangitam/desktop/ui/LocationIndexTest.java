package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocationIndexTest {
    @Test void largeBranchesPageFoldersBeforeSongsAndLocateRestoredSelections() {
        Path root=Path.of("music").toAbsolutePath(); var songs=new ArrayList<Track>();
        for(int i=0;i<125;i++) songs.add(new Track(root.resolve("folder%03d".formatted(i)).resolve("song.mp3"),"Song","Artist","Album",60,"MP3"));
        for(int i=0;i<130;i++) songs.add(new Track(root.resolve("song%03d.mp3".formatted(i)),"Song %03d".formatted(i),"Artist","Album",60,"MP3"));
        var index=LocationIndex.build(songs,""); var first=index.page(root,0); var second=index.page(root,100); var last=index.page(root,200);
        assertEquals(255,first.total()); assertEquals(100,first.children().size()); assertTrue(first.children().stream().allMatch(Path.class::isInstance));
        assertEquals(100,second.children().size()); assertInstanceOf(Path.class,second.children().get(24)); assertInstanceOf(Track.class,second.children().get(25));
        assertEquals(55,last.children().size()); assertFalse(last.hasNext());
        var target=songs.getLast(); assertEquals(200,index.offsetToward(root,target.path())); assertTrue(last.children().contains(target));
        assertEquals(200,index.page(root,10000).offset());
    }
    @Test void indexedPathAndMetadataSearchAreLiteralAndNeverNeedAccessibleFiles() {
        Path path=Path.of("missing","Chopin","100%_Etude.mp3");
        var track=new Track(path,"Etude","Unknown artist","Unknown album",60,"MP3","Classical",TrackMetadata.empty().edit(Map.of(TrackMetadata.Field.COMPOSER,"Frédéric Chopin"),Set.of("Focus")));
        for(String query:List.of("%_","FRÉDÉRIC","focus","chopin")) assertEquals(1,LocationIndex.build(List.of(track),query).matches());
        assertEquals(0,LocationIndex.build(List.of(track),"no match").page(null,0).total());
    }
}
