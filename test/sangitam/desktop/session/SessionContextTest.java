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

    @Test void aLikeChangesSessionRankingAndFractionalPlayEvidenceBreaksEqualRatings() {
        var a = new Track(Path.of("a.mp3"), "Same", "Same", "Album", 180, "MP3");
        var b = new Track(Path.of("b.mp3"), "Same", "Same", "Album", 180, "MP3");
        var request = new SessionPlanner.Request(SessionPlanner.Intent.FOCUS, 45, 3, false);
        var planner = new SessionPlanner();
        var now = ZonedDateTime.now();
        var ratings = Map.of(a.path(), 1, b.path(), 1);
        var neither = new SessionContext(now, ratings, List.of());
        var liked = new SessionContext(now, ratings, List.of(), Set.of(b.path()));
        assertEquals(a, planner.build(request, List.of(b, a), Map.of(), Map.of(), neither).choices().getFirst().track());
        assertEquals(b, planner.build(request, List.of(a, b), Map.of(), Map.of(), liked).choices().getFirst().track());
        var bothLiked = new SessionContext(now, ratings, List.of(), Set.of(a.path(), b.path()));
        assertEquals(b, planner.build(request, List.of(a, b), Map.of(),
            Map.of(a.path(), 20, b.path(), 21), bothLiked).choices().getFirst().track());
        assertTrue(bothLiked.preference(b.path(), 21) > bothLiked.preference(a.path(), 20));
    }
}
