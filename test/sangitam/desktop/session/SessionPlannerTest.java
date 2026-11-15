package sangitam.desktop.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the explainable rules instead of mirroring Swing widgets. */
class SessionPlannerTest {
    @TempDir Path folder;
    private final SessionPlanner planner = new SessionPlanner();

    @Test void focusPrefersSuitableMeasuredMusicAndExplainsWhy() {
        Track calm = track("calm.mp3", "Ambient", "A", "Ambient");
        Track charged = track("charged.mp3", "Rock", "B", "Rock");
        Map<Path, TrackTags> tags = Map.of(calm.path(), measured(20, 20), charged.path(), measured(90, 85));
        SessionPlanner.Plan plan = planner.build(new SessionPlanner.Request(
            SessionPlanner.Intent.FOCUS, 20, 3, false), List.of(charged, calm), tags, Map.of());
        assertEquals(1, plan.choices().size());
        assertEquals(calm, plan.choices().get(0).track());
        assertTrue(plan.choices().get(0).reason().contains("energy"));
        assertTrue(plan.choices().get(0).reason().contains("Ambient genre tag"));
    }

    @Test void familiarPreferenceUsesSavedPlayCountAndNeverRepeatsAFile() {
        Track familiar = track("played.mp3", "Played", "A", "Unknown genre");
        Track fresh = track("fresh.mp3", "Fresh", "B", "Unknown genre");
        List<Track> library = List.of(fresh, familiar);
        Map<Path, TrackTags> tags = Map.of(familiar.path(), measured(45, 25), fresh.path(), measured(45, 25));
        Map<Path, Integer> plays = Map.of(familiar.path(), 3, fresh.path(), 0);
        SessionPlanner.Plan known = planner.build(new SessionPlanner.Request(
            SessionPlanner.Intent.FOCUS, 45, 3, true), library, tags, plays);
        assertEquals(familiar, known.choices().get(0).track());
        assertTrue(known.choices().get(0).reason().contains("played 3 times"));
        SessionPlanner.Plan discovery = planner.build(new SessionPlanner.Request(
            SessionPlanner.Intent.EXPLORE, 45, 8, false), library, tags, plays);
        assertEquals(fresh, discovery.choices().get(0).track());
        assertEquals(2, discovery.choices().stream().map(choice -> choice.track().path()).distinct().count());
    }

    @Test void oneLongSongStillProducesAnEditableSession() {
        Track longSong = new Track(folder.resolve("long.mp3"), "Long song", "Artist", "Album", 2400, "MP3");
        SessionPlanner.Plan plan = planner.build(new SessionPlanner.Request(
            SessionPlanner.Intent.FOCUS, 40, 25, false), List.of(longSong), Map.of(), Map.of());
        assertEquals(1, plan.choices().size());
        assertTrue(plan.choices().get(0).reason().contains("longer than requested time"));
    }

    private Track track(String file, String title, String artist, String genre) {
        return new Track(folder.resolve(file), title, artist, "Album", 180, "MP3", genre);
    }

    private TrackTags measured(int energy, int activity) { return new TrackTags(energy, activity, true, 1, 1); }
}
