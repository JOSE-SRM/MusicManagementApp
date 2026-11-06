package sangitam.desktop.session;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.*;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionContextTest {
    @Test void yesterdayIsNotFreshMoodAndNewSessionsExcludeLongGaps() {
        var now=ZonedDateTime.of(2026,11,6,20,0,0,0,ZoneId.of("Asia/Kolkata"));Path calm=Path.of("calm"),loud=Path.of("loud");
        var tags=Map.of(calm,new TrackTags(20,20,true,1,1),loud,new TrackTags(90,90,true,1,1));
        var context=new SessionContext(now,Map.of(),List.of(new SessionContext.Listen(calm,now.minusMinutes(2).toInstant().toEpochMilli(),120),new SessionContext.Listen(loud,now.minusMinutes(60).toInstant().toEpochMilli(),120),new SessionContext.Listen(loud,now.minusDays(1).toInstant().toEpochMilli(),120)));
        assertEquals(20,context.hint(tags).energy());assertTrue(context.hint(tags).explanation().contains("Recent listening"));
        var yesterday=new SessionContext(now,Map.of(),List.of(new SessionContext.Listen(loud,now.minusDays(1).toInstant().toEpochMilli(),120)));
        assertFalse(yesterday.hint(tags).explanation().contains("Recent listening"));assertTrue(yesterday.hint(tags).explanation().contains("similar local"));
    }
    @Test void avoidedSongsStayOutEvenWhenOnlyLongCandidateAndFavouritesInfluenceSelection() {
        var a=new Track(Path.of("a.mp3"),"A","One","Album",180,"MP3");var b=new Track(Path.of("b.mp3"),"B","Two","Album",180,"MP3");var tags=Map.of(a.path(),new TrackTags(45,25,true,1,1),b.path(),new TrackTags(45,25,true,1,1));
        var context=new SessionContext(ZonedDateTime.now(),Map.of(a.path(),-2,b.path(),2),List.of());var request=new SessionPlanner.Request(SessionPlanner.Intent.FOCUS,45,3,false);
        var plan=new SessionPlanner().build(request,List.of(a,b),tags,Map.of(),context);assertEquals(List.of(b),plan.choices().stream().map(SessionPlanner.Choice::track).toList());
        assertTrue(new SessionPlanner().build(request,List.of(a),tags,Map.of(),context).choices().isEmpty());
    }
}
