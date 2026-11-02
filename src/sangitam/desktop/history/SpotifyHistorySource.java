package sangitam.desktop.history;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Reads up to 50 recent Spotify plays using an OAuth token with history permission. */
public final class SpotifyHistorySource implements HistorySource {
    private final String token;

    public SpotifyHistorySource() { this(System.getenv("SANGITAM_SPOTIFY_ACCESS_TOKEN")); }
    SpotifyHistorySource(String token) { this.token = token; }

    @Override public String name() { return "Spotify"; }
    @Override public boolean available() { return token != null && !token.isBlank(); }
    @Override public String setupHint() {
        return available() ? "Connected by access token; requires user-read-recently-played."
            : "Set SANGITAM_SPOTIFY_ACCESS_TOKEN with user-read-recently-played permission.";
    }

    @Override public List<ListenEvent> recent() throws Exception {
        if (!available()) throw new IllegalStateException(setupHint());
        return parse(HistoryHttp.get("https://api.spotify.com/v1/me/player/recently-played?limit=50",
            "Bearer " + token));
    }

    static List<ListenEvent> parse(JsonObject body) {
        List<ListenEvent> result = new ArrayList<>();
        JsonElement items = body.get("items");
        if (items == null || !items.isJsonArray()) return result;
        for (JsonElement element : items.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            JsonObject track = HistoryHttp.object(item, "track");
            String id = HistoryHttp.string(track, "id");
            String title = HistoryHttp.string(track, "name");
            String artist = "";
            JsonElement artists = track.get("artists");
            if (artists != null && artists.isJsonArray() && !artists.getAsJsonArray().isEmpty()
                && artists.getAsJsonArray().get(0).isJsonObject())
                artist = HistoryHttp.string(artists.getAsJsonArray().get(0).getAsJsonObject(), "name");
            try {
                long at = Instant.parse(HistoryHttp.string(item, "played_at")).toEpochMilli();
                if (!id.matches("[A-Za-z0-9]{22}") || title.isBlank() || artist.isBlank()) continue;
                URI link = URI.create("https://open.spotify.com/track/" + id);
                result.add(new ListenEvent("Spotify", id + ":" + at, title, artist,
                    HistoryHttp.string(HistoryHttp.object(track, "album"), "name"), at, link, null));
            } catch (RuntimeException invalid) { /* Ignore incomplete provider items. */ }
        }
        return List.copyOf(result);
    }
}
