package sangitam.desktop.discovery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Spotify catalog search using a user-supplied short-lived OAuth access token. */
public final class SpotifyProvider implements MusicProvider {
    private final String token;

    public SpotifyProvider() { this(System.getenv("SANGITAM_SPOTIFY_ACCESS_TOKEN")); }
    SpotifyProvider(String token) { this.token = token; }

    @Override public String name() { return "Spotify"; }
    @Override public String setupHint() {
        return available() ? "Catalog search · opens tracks in Spotify"
            : "Set SANGITAM_SPOTIFY_ACCESS_TOKEN, then restart Sangitam";
    }
    @Override public boolean available() { return token != null && !token.isBlank(); }

    @Override public List<CatalogTrack> search(String query) throws Exception {
        if (!available()) throw new IllegalStateException(setupHint());
        String url = "https://api.spotify.com/v1/search?q=" + CatalogHttp.encode(query)
            + "&type=track&limit=10";
        return parse(CatalogHttp.get(url, "Authorization", "Bearer " + token));
    }

    static List<CatalogTrack> parse(JsonObject body) {
        List<CatalogTrack> results = new ArrayList<>();
        JsonElement items = CatalogHttp.object(body, "tracks").get("items");
        if (items == null || !items.isJsonArray()) return results;
        for (JsonElement element : items.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = CatalogHttp.string(item, "id");
            if (!id.matches("[A-Za-z0-9]{22}")) continue;
            String artist = "";
            JsonElement artists = item.get("artists");
            if (artists != null && artists.isJsonArray() && !artists.getAsJsonArray().isEmpty()
                && artists.getAsJsonArray().get(0).isJsonObject())
                artist = CatalogHttp.string(artists.getAsJsonArray().get(0).getAsJsonObject(), "name");
            results.add(new CatalogTrack(CatalogHttp.string(item, "name"), artist,
                CatalogHttp.string(CatalogHttp.object(item, "album"), "name"), "Spotify",
                URI.create("https://open.spotify.com/track/" + id)));
        }
        return results;
    }
}
