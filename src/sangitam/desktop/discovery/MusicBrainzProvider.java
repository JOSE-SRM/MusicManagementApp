package sangitam.desktop.discovery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import sangitam.desktop.model.RecordingCandidate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Public recording metadata. MusicBrainz supplies information, not audio. */
public final class MusicBrainzProvider implements MusicProvider {
    private static long nextRequestAt;
    private final URI endpoint;
    private record Cached(long expiresAt, List<RecordingCandidate> results) { }
    private final LinkedHashMap<String, Cached> lookupCache = new LinkedHashMap<>(16, .75f, true);

    public MusicBrainzProvider() { this(URI.create("https://musicbrainz.org/ws/2/recording/")); }

    MusicBrainzProvider(URI endpoint) { this.endpoint = endpoint; }

    @Override public String name() { return "MusicBrainz"; }
    @Override public String setupHint() { return "No key needed · recording metadata only"; }
    @Override public boolean available() { return true; }

    @Override public List<CatalogTrack> search(String query) throws Exception {
        return parse(request(query, 10));
    }

    public List<RecordingCandidate> lookupRecordings(String query, boolean refresh) throws Exception {
        synchronized (lookupCache) {
            Cached cached = lookupCache.get(query);
            if (!refresh && cached != null && System.nanoTime() - cached.expiresAt() < 0) return cached.results();
        }
        List<RecordingCandidate> results = parseRecordings(request(query, 20));
        synchronized (lookupCache) {
            lookupCache.put(query, new Cached(System.nanoTime() + java.time.Duration.ofMinutes(30).toNanos(), results));
            if (lookupCache.size() > 64) lookupCache.pollFirstEntry();
        }
        return results;
    }

    private JsonObject request(String query, int limit) throws Exception {
        return fetch(endpoint + "?query=" + CatalogHttp.encode(query) + "&fmt=json&limit=" + limit);
    }

    JsonObject workSearch(String query) throws Exception {
        return fetch(endpoint.resolve("../work/") + "?query=" + CatalogHttp.encode(query) + "&fmt=json&limit=10");
    }

    JsonObject recordingDetails(UUID id) throws Exception {
        return fetch(endpoint.resolve(id.toString()) + "?fmt=json&inc=artist-credits+releases+artist-rels+work-rels+work-level-rels+place-rels+area-rels");
    }

    JsonObject workDetails(UUID id) throws Exception {
        return fetch(endpoint.resolve("../work/" + id) + "?fmt=json&inc=artist-rels+work-rels");
    }

    private static JsonObject fetch(String url) throws Exception {
        // MusicBrainz asks clients to stay at or below one request per second.
        synchronized (MusicBrainzProvider.class) {
            long wait = nextRequestAt - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            nextRequestAt = System.currentTimeMillis() + 1100;
        }
        return CatalogHttp.get(url, null, null);
    }

    static List<CatalogTrack> parse(JsonObject body) {
        return parseRecordings(body).stream().map(candidate -> new CatalogTrack(candidate.title(), candidate.credit(),
            candidate.releases().isEmpty() ? "" : candidate.releases().getFirst().title(), "MusicBrainz", candidate.link())).toList();
    }

    static List<RecordingCandidate> parseRecordings(JsonObject body) {
        List<RecordingCandidate> results = new ArrayList<>();
        JsonElement recordings = body.get("recordings");
        if (recordings == null || !recordings.isJsonArray()) return List.of();
        for (JsonElement element : recordings.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            UUID id = id(item);
            String title = CatalogHttp.string(item, "title");
            if (id == null || title.isBlank()) continue;
            var artist = new StringBuilder();
            JsonElement credits = item.get("artist-credit");
            if (credits != null && credits.isJsonArray()) {
                for (JsonElement credit : credits.getAsJsonArray()) {
                    if (credit.isJsonObject()) {
                        var person = credit.getAsJsonObject();
                        artist.append(CatalogHttp.string(person, "name"));
                        artist.append(CatalogHttp.string(person, "joinphrase"));
                    }
                }
            }
            var editions = new ArrayList<RecordingCandidate.Release>();
            JsonElement releases = item.get("releases");
            if (releases != null && releases.isJsonArray()) {
                for (var release : releases.getAsJsonArray()) {
                    if (!release.isJsonObject()) continue;
                    var edition = release.getAsJsonObject();
                    UUID releaseId = id(edition);
                    String name = CatalogHttp.string(edition, "title");
                    if (releaseId != null && !name.isBlank())
                        editions.add(new RecordingCandidate.Release(releaseId, name, CatalogHttp.string(edition, "date")));
                }
            }
            long duration;
            try { duration = Math.max(0, Long.parseLong(CatalogHttp.string(item, "length"))); }
            catch (NumberFormatException ignored) { duration = 0; }
            results.add(new RecordingCandidate(id, title, artist.toString().strip(),
                CatalogHttp.string(item, "disambiguation"), duration, editions));
        }
        return List.copyOf(results);
    }

    private static UUID id(JsonObject object) {
        String value = CatalogHttp.string(object, "id");
        if (value.length() != 36) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
