package sangitam.desktop.history;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HistorySourceTest {
    @Test void parsesRealPlayTimestampsAndSkipsCurrentlyPlaying() {
        var spotify = JsonParser.parseString("""
            {"items":[{"played_at":"2026-09-01T12:00:00Z","track":{"id":"1234567890123456789012",
            "name":"Blue Hour","artists":[{"name":"Aster"}],"album":{"name":"Album"}}}]}
            """).getAsJsonObject();
        assertEquals(1, SpotifyHistorySource.parse(spotify).size());
        assertEquals("Spotify", SpotifyHistorySource.parse(spotify).get(0).source());

        var listenBrainz = JsonParser.parseString("""
            {"payload":{"listens":[{"listened_at":1788264000,"track_metadata":
            {"track_name":"Blue Hour","artist_name":"Aster","release_name":"Album"}}]}}
            """).getAsJsonObject();
        assertEquals(1, ListenBrainzHistorySource.parse(listenBrainz, "tester").size());

        var lastFm = JsonParser.parseString("""
            {"recenttracks":{"track":[{"name":"Blue Hour","artist":{"#text":"Aster"},
            "album":{"#text":"Album"},"date":{"uts":"1788264000"},"url":"https://last.fm/test"},
            {"name":"Now playing","artist":{"#text":"Aster"}}]}}
            """).getAsJsonObject();
        assertEquals(1, LastFmHistorySource.parse(lastFm, "tester").size());
    }
}
