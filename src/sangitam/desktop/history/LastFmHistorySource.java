package sangitam.desktop.history;

import sangitam.desktop.storage.ProviderCredentials;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads scrobbles from Last.fm; the API key is supplied outside the repository. */
public final class LastFmHistorySource implements HistorySource {
    private final String username;
    private final String apiKey;

    public LastFmHistorySource(String username) {
        this(username, ProviderCredentials.get(ProviderCredentials.LASTFM_KEY, "SANGITAM_LASTFM_API_KEY"));
    }
    LastFmHistorySource(String username, String apiKey) {
        this.username = username == null ? "" : username.strip();
        this.apiKey = apiKey;
    }
    @Override public String name() { return "Last.fm"; }
    @Override public boolean available() { return !username.isBlank() && apiKey != null && !apiKey.isBlank(); }
    @Override public String setupHint() {
        return apiKey == null || apiKey.isBlank() ? "Add a Last.fm API key in Settings and enter your username."
            : "Enter your Last.fm username.";
    }

    @Override public List<ListenEvent> recent() throws Exception {
        if (!available()) throw new IllegalStateException(setupHint());
        String url = "https://ws.audioscrobbler.com/2.0/?method=user.getrecenttracks&user="
            + HistoryHttp.encode(username) + "&api_key=" + HistoryHttp.encode(apiKey)
            + "&format=json&limit=200";
        return parse(HistoryHttp.get(url, null), username);
    }

    static List<ListenEvent> parse(JsonObject body, String username) {
        if (body.has("error")) throw new IllegalStateException("Last.fm rejected the username or API key.");
        List<ListenEvent> result = new ArrayList<>();
        JsonElement items = HistoryHttp.object(body, "recenttracks").get("track");
        if (items == null || !items.isJsonArray()) return result;
        for (JsonElement element : items.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String title = HistoryHttp.string(item, "name");
            String artist = HistoryHttp.string(HistoryHttp.object(item, "artist"), "#text");
            String album = HistoryHttp.string(HistoryHttp.object(item, "album"), "#text");
            long at = HistoryHttp.number(HistoryHttp.object(item, "date"), "uts") * 1000;
            if (title.isBlank() || artist.isBlank() || at <= 0) continue; // Skip currently playing.
            String key = username.toLowerCase(Locale.ROOT) + ":" + at + ":" + artist + ":" + title;
            result.add(new ListenEvent("Last.fm", key, title, artist, album, at,
                HistoryHttp.safeLink(HistoryHttp.string(item, "url")), null));
        }
        return List.copyOf(result);
    }
}
