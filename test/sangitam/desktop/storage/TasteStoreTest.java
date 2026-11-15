package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TasteStoreTest {
    @TempDir Path folder;

    @Test void contextKeepsFreshWindowsAndAggregatesOlderListeningWithoutFutureOrExpiredRows() throws Exception {
        var library=new LibraryStore(folder.resolve("library.db")); library.initialize();
        var now=ZonedDateTime.of(2026,11,6,20,0,0,0,ZoneId.of("Asia/Kolkata"));
        Path recent=folder.resolve("recent.mp3"),old=folder.resolve("old.mp3");
        var store=new TasteStore(library); store.rate(List.of(recent),2); store.rate(List.of(old),-1);
        try(var c=library.connect();var q=c.prepareStatement("INSERT INTO listening_windows(path,observed_at,seconds) VALUES(?,?,?)")) {
            for(int minutes:new int[]{2,1441,1442,1443,-1,181*1440}) {
                q.setString(1,(minutes==2?recent:old).toString());q.setLong(2,now.minusMinutes(minutes).toInstant().toEpochMilli());q.setDouble(3,10);q.addBatch();
            }
            q.executeBatch();
        }
        var reopened=new TasteStore(new LibraryStore(folder.resolve("library.db")));
        var context=reopened.context(now);assertEquals(2,context.history().size());
        assertEquals(40,context.history().stream().mapToDouble(w->w.seconds()).sum());
        assertEquals(2,context.taste().get(recent));assertEquals(-1,context.taste().get(old));
        reopened.rate(List.of(recent),0);assertEquals(0,reopened.ratings().get(recent));
        assertThrows(IllegalArgumentException.class,()->reopened.rate(List.of(recent),3));
    }

    @Test void actualListeningWritesTimestampedContextAlongsideArtistTotals() throws Exception {
        var library=new LibraryStore(folder.resolve("library.db"));library.initialize();Path path=folder.resolve("music.mp3");
        new ArtistStore(library).addListening(Map.of(path,12d,folder.resolve("invalid"),Double.NaN));
        var context=new TasteStore(library).context(ZonedDateTime.now());
        assertEquals(1,context.history().size());assertEquals(path,context.history().getFirst().path());assertEquals(12,context.history().getFirst().seconds());
    }
}
