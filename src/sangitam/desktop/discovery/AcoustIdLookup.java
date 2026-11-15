package sangitam.desktop.discovery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sangitam.desktop.model.RecordingCandidate;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Local Chromaprint, with a text-only fingerprint request to AcoustID. */
public final class AcoustIdLookup {
    public record Match(UUID id, RecordingCandidate recording) { }
    private static final class Client {
        static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    public Match identify(Path path, String clientKey) throws Exception {
        JsonObject fingerprint = fingerprint(path);
        String value = CatalogHttp.string(fingerprint, "fingerprint");
        int duration = (int) Math.round(Double.parseDouble(CatalogHttp.string(fingerprint, "duration")));
        if (value.isBlank() || duration <= 0) return null;
        var request = HttpRequest.newBuilder(URI.create("https://api.acoustid.org/v2/lookup"))
            .timeout(Duration.ofSeconds(14)).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("client=" + CatalogHttp.encode(clientKey)
                + "&meta=recordings&format=json&duration=" + duration + "&fingerprint=" + CatalogHttp.encode(value))).build();
        HttpResponse<String> response;
        try { response = Client.HTTP.send(request, HttpResponse.BodyHandlers.ofString()); }
        catch (IOException error) { throw new IOException("AcoustID is unavailable."); }
        if (response.statusCode() != 200) throw new IOException("AcoustID returned HTTP " + response.statusCode() + ".");
        var json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!"ok".equals(CatalogHttp.string(json, "status"))) throw new IOException("AcoustID rejected the lookup. Check the application key.");
        UUID id = confidentRecording(json);
        if (id == null) return null;
        for (var result : MusicBrainzMetadata.objects(json, "results")) {
            for (var recording : MusicBrainzMetadata.objects(result, "recordings")) {
                String title = CatalogHttp.string(recording, "title");
                if (!id.toString().equals(CatalogHttp.string(recording, "id")) || title.isBlank()) continue;
                String credits = MusicBrainzMetadata.objects(recording, "artists").stream()
                    .map(artist -> CatalogHttp.string(artist, "name")).filter(name -> !name.isBlank())
                    .collect(java.util.stream.Collectors.joining("; "));
                long length = 0;
                try { length = Math.max(0, Math.round(Double.parseDouble(CatalogHttp.string(recording, "duration")) * 1000)); }
                catch (NumberFormatException ignored) { }
                return new Match(id, new RecordingCandidate(id, title, credits, "", length, java.util.List.of()));
            }
        }
        return new Match(id, null);
    }

    private static JsonObject fingerprint(Path path) throws Exception {
        String configured = System.getenv("SANGITAM_FPCALC");
        String command = configured == null || configured.isBlank() ? "fpcalc" : configured;
        Path output = Files.createTempFile("sangitam-fingerprint-", ".json");
        Process process = null;
        try {
            try {
                process = new ProcessBuilder(command, "-json", "-length", "120", path.toString())
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException error) {
                throw new IOException("Chromaprint fpcalc is unavailable. Install it or set SANGITAM_FPCALC.");
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("Audio fingerprint timed out.");
            if (Files.size(output) == 0 || Files.size(output) > 131_072)
                throw new IOException("Chromaprint could not fingerprint this audio format.");
            return JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output);
        }
    }

    static UUID confidentRecording(JsonObject response) {
        var scores = new HashMap<UUID,Double>();
        for (var result : MusicBrainzMetadata.objects(response, "results")) {
            double score;
            try { score = Double.parseDouble(CatalogHttp.string(result, "score")); }
            catch (NumberFormatException invalid) { continue; }
            if (!Double.isFinite(score) || score < 0 || score > 1) continue;
            for (var recording : MusicBrainzMetadata.objects(result, "recordings")) {
                try { scores.merge(UUID.fromString(CatalogHttp.string(recording, "id")), score, Math::max); }
                catch (IllegalArgumentException invalid) { /* Ignore malformed IDs. */ }
            }
        }
        var ranked = scores.entrySet().stream().sorted(java.util.Map.Entry.<UUID,Double>comparingByValue().reversed()).toList();
        if (ranked.isEmpty() || ranked.getFirst().getValue() < .92) return null;
        if (ranked.size() > 1 && ranked.getFirst().getValue() - ranked.get(1).getValue() < .06) return null;
        return ranked.getFirst().getKey();
    }
}
