package sangitam.desktop.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Public recording metadata. MusicBrainz supplies information, not audio. */
public final class MusicBrainzProvider implements MusicProvider {
    private static long nextRequestAt;

    @Override public String name() { return "MusicBrainz"; }
    @Override public String setupHint() { return "No key needed · recording metadata only"; }
    @Override public boolean available() { return true; }

    @Override public List<CatalogTrack> search(String query) throws Exception {
        // MusicBrainz asks clients to stay at or below one request per second.
        synchronized (MusicBrainzProvider.class) {
            long wait = nextRequestAt - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            nextRequestAt = System.currentTimeMillis() + 1100;
        }
        String url = "https://musicbrainz.org/ws/2/recording/?query="
            + CatalogHttp.encode(query) + "&fmt=json&limit=10";
        return parse(CatalogHttp.get(url, null, null));
    }

    static List<CatalogTrack> parse(JsonObject body) {
        List<CatalogTrack> results = new ArrayList<>();
        JsonElement recordings = body.get("recordings");
        if (recordings == null || !recordings.isJsonArray()) return results;
        for (JsonElement element : recordings.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = CatalogHttp.string(item, "id");
            if (!id.matches("[0-9a-fA-F-]{36}")) continue;
            String artist = "";
            JsonElement credits = item.get("artist-credit");
            if (credits != null && credits.isJsonArray()) {
                for (JsonElement credit : credits.getAsJsonArray()) {
                    if (credit.isJsonObject()) { artist = CatalogHttp.string(credit.getAsJsonObject(), "name"); break; }
                }
            }
            String album = "";
            JsonElement releases = item.get("releases");
            if (releases != null && releases.isJsonArray() && !releases.getAsJsonArray().isEmpty()) {
                JsonArray array = releases.getAsJsonArray();
                if (array.get(0).isJsonObject()) album = CatalogHttp.string(array.get(0).getAsJsonObject(), "title");
            }
            results.add(new CatalogTrack(CatalogHttp.string(item, "title"), artist, album,
                "MusicBrainz", URI.create("https://musicbrainz.org/recording/" + id)));
        }
        return results;
    }
}
