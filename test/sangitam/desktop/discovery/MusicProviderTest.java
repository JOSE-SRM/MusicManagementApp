package sangitam.desktop.discovery;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Checks provider-specific response shapes and safe links without spending API quota. */
class MusicProviderTest {
    @Test void spotifyReadsTrackAndUsesKnownLink() {
        var body = JsonParser.parseString("""
            {"tracks":{"items":[{"id":"2up3OPMp9Tb4dAKM2erWXQ","name":"Blue Hour",
            "artists":[{"name":"Aster Avenue"}],"album":{"name":"Night Drive"}}]}}
            """).getAsJsonObject();
        var track = SpotifyProvider.parse(body).getFirst();
        assertEquals("Blue Hour", track.title());
        assertEquals("Aster Avenue", track.artist());
        assertEquals("https://open.spotify.com/track/2up3OPMp9Tb4dAKM2erWXQ", track.link().toString());
    }

    @Test void youtubeReadsVideoAndRejectsInvalidIds() {
        var body = JsonParser.parseString("""
            {"items":[{"id":{"videoId":"dQw4w9WgXcQ"},"snippet":{"title":"A song","channelTitle":"Artist"}},
            {"id":{"videoId":"javascript:bad"},"snippet":{"title":"Bad"}}]}
            """).getAsJsonObject();
        var tracks = YouTubeProvider.parse(body);
        assertEquals(1, tracks.size());
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", tracks.getFirst().link().toString());
    }

    @Test void appleMusicOnlyAcceptsAppleLinks() {
        var body = JsonParser.parseString("""
            {"results":{"songs":{"data":[
              {"attributes":{"name":"Blue Hour","artistName":"Aster Avenue","albumName":"Night Drive",
                "url":"https://music.apple.com/in/album/night-drive/123?i=456"}},
              {"attributes":{"name":"Unsafe","url":"https://example.com/track"}}
            ]}}}
            """).getAsJsonObject();
        var tracks = AppleMusicProvider.parse(body);
        assertEquals(1, tracks.size());
        assertEquals("Night Drive", tracks.getFirst().album());
    }

    @Test void musicBrainzReadsRecordingWithoutAnAudioClaim() {
        var body = JsonParser.parseString("""
            {"recordings":[{"id":"12345678-1234-1234-1234-123456789abc","title":"Blue Hour",
              "artist-credit":[{"name":"Aster Avenue"}],"releases":[{"title":"Night Drive"}]}]}
            """).getAsJsonObject();
        var track = MusicBrainzProvider.parse(body).getFirst();
        assertEquals("MusicBrainz", track.source());
        assertEquals("Night Drive", track.album());
        assertEquals("https://musicbrainz.org/recording/12345678-1234-1234-1234-123456789abc",
            track.link().toString());
    }

    @Test void providersExplainMissingCredentials() {
        assertFalse(new SpotifyProvider(null).available());
        assertFalse(new YouTubeProvider(null).available());
        assertFalse(new AppleMusicProvider(null, "in").available());
        assertTrue(new MusicBrainzProvider().available());
    }
}
