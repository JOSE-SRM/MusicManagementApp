package sangitam.desktop.discovery;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import sangitam.desktop.model.RecordingCandidate;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata;
import sangitam.desktop.model.TrackMetadata.Field;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecordingLookupTest {
    @Test void comparesPerformancesAndSendsOnlyTheSelectedTextFields() {
        var track = new Track(Path.of("private", "local.wav"), "Piano Sonata No. 8: Allegro", "Beethoven",
            "Unknown album", 180, "WAV", "Classical",
            TrackMetadata.empty().edit(Map.of(Field.COMPOSER, "Beethoven", Field.SOLOIST, "Áster A"),
                null));
        var first = new RecordingCandidate(UUID.randomUUID(), track.title(), "Aster A",
            "Studio recording", 181000, List.of());
        var other = new RecordingCandidate(UUID.randomUUID(), track.title(), "Player B",
            "Live recording of the same work", 181000, List.of());
        assertTrue(RecordingLookup.compare(track, first).corroborated());
        assertFalse(RecordingLookup.compare(track, other).corroborated());
        assertTrue(RecordingLookup.compare(track, first).rank() > RecordingLookup.compare(track, other).rank());
        String titleOnly = RecordingLookup.query("Piano \"Sonata\"", track, false);
        assertEquals("recording:\"Piano \\\"Sonata\\\"\"", titleOnly);
        String enriched = RecordingLookup.query(track.title(), track, true);
        assertTrue(enriched.contains("artist:\"Áster A\""));
        assertTrue(enriched.contains("qdur:[87 TO 93]"));
        assertFalse(enriched.contains("local.wav"));
    }

    @Test void cachesSuccessfulSearchesAndKeepsServiceFailureSeparateFromEmptyResults() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/recording/", exchange -> {
            int count = requests.incrementAndGet();
            byte[] body = "{\"recordings\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(count == 2 ? 503 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var provider = new MusicBrainzProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/recording/"));
            assertTrue(provider.lookupRecordings("test", false).isEmpty());
            assertTrue(provider.lookupRecordings("test", false).isEmpty());
            assertEquals(1, requests.get());
            assertThrows(IOException.class, () -> provider.lookupRecordings("test", true));
            assertEquals(2, requests.get());
            assertTrue(provider.lookupRecordings("test", false).isEmpty());
            assertEquals(2, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
