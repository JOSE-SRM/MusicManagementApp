package sangitam.desktop.discovery;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata;
import sangitam.desktop.model.TrackMetadata.Field;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundProviderTest {
    @Test void alternatePerformanceUsesTheWorkEndpointWithoutAdoptingItsCreditsOrDate() throws Exception {
        UUID recordingId = UUID.fromString("7e582443-068f-46bb-bee0-c72f50320ba5");
        UUID workId = UUID.fromString("2e582443-068f-46bb-bee0-c72f50320ba5");
        String work = """
            {"id":"%s","title":"Piano Sonata in B-flat major, Op. 106","relations":[
                {"type":"composer","artist":{"name":"Ludwig van Beethoven"},"begin":"1817","end":"1818"},
                {"type":"composer","artist":{"name":"Additional contributor"},"begin":"1902","attributes":["additional"]}]}
            """.formatted(workId);
        String recording = """
            {"relations":[{"type":"performance","work":{"id":"%s","title":"Piano Sonata in B-flat major, Op. 106"}},
              {"type":"instrument","artist":{"name":"Other Pianist"}},{"type":"recorded at","begin":"1999"}]}
            """.formatted(workId);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ws/2/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String payload = path.endsWith(recordingId.toString()) ? recording : work;
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var provider = new MusicBrainzProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ws/2/recording/"));
            var local = new Track(Path.of("local.flac"), "Pianist – Piano Sonata in B-flat major, Op. 106: Allegro",
                "Local Pianist", "Local release", 180, "FLAC", "Classical",
                TrackMetadata.empty().edit(Map.of(Field.COMPOSER, "Beethoven, Ludwig van"), null));
            var resolver = new MusicBrainzMetadata(provider);
            var composition = resolver.resolve(local, recordingId, false);
            assertEquals("Op. 106", composition.fields().get(Field.OPUS));
            assertEquals("B-flat major", composition.fields().get(Field.WORK_KEY));
            assertEquals("1817–1818", composition.fields().get(Field.COMPOSITION_DATE));
            assertFalse(composition.fields().containsKey(Field.PERFORMERS));
            assertFalse(composition.fields().containsKey(Field.RECORDING_DATE));
            assertEquals("1999", resolver.resolve(local, recordingId, true).fields().get(Field.RECORDING_DATE));
        } finally { server.stop(0); }
    }

    @Test void compositionAndRecordingEvidenceStaySeparate() {
        var work = JsonParser.parseString("""
            {"title":"Piano Sonata in B-flat major, Op. 106","relations":[
                {"type":"composer","artist":{"name":"Ludwig van Beethoven"}}]}
            """).getAsJsonObject();
        var local = new Track(Path.of("test.flac"), "Movement I", "Local performer", "Local release", 180, "FLAC",
            "Classical", TrackMetadata.empty().edit(Map.of(Field.COMPOSER, "Beethoven",
                Field.WORK, "Piano Sonata in B-flat major, Op. 106"), null));
        assertTrue(MusicBrainzMetadata.workAgrees(local, work));
        var details = MusicBrainzMetadata.composition(work);
        assertEquals("Op. 106", details.get(Field.OPUS));
        assertEquals("B-flat major", details.get(Field.WORK_KEY));
        assertFalse(details.containsKey(Field.KEY));
        assertFalse(details.containsKey(Field.PERFORMERS));
        assertFalse(details.containsKey(Field.RECORDING_DATE));
        var recording = JsonParser.parseString("""
            {"first-release-date":"2020","relations":[{"type":"conductor","artist":{"name":"Other performer"}},
                {"type":"recorded at","begin":"2003-01","end":"2003-01"}]}
            """).getAsJsonObject();
        assertEquals("2003-01", MusicBrainzMetadata.performance(recording).get(Field.RECORDING_DATE));
        assertEquals("Other performer", MusicBrainzMetadata.performance(recording).get(Field.CONDUCTOR));
    }

    @Test void rejectsAmbiguousFingerprintRecordingIds() {
        var result = JsonParser.parseString("""
            {"results":[{"score":0.99,"recordings":[{"id":"7e582443-068f-46bb-bee0-c72f50320ba5"},
                {"id":"2e582443-068f-46bb-bee0-c72f50320ba5"}]}]}
            """).getAsJsonObject();
        assertNull(AcoustIdLookup.confidentRecording(result));
        result.getAsJsonArray("results").get(0).getAsJsonObject().getAsJsonArray("recordings").remove(1);
        assertEquals(UUID.fromString("7e582443-068f-46bb-bee0-c72f50320ba5"),
            AcoustIdLookup.confidentRecording(result));
    }
}
