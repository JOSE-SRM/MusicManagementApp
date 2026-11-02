package sangitam.desktop.history;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A public ListenBrainz username can aggregate scrobbles from several music players. */
public final class ListenBrainzHistorySource implements HistorySource {
    private final String username;

    public ListenBrainzHistorySource(String username) { this.username = username == null ? "" : username.strip(); }
    @Override public String name() { return "ListenBrainz"; }
    @Override public boolean available() { return !username.isBlank(); }
    @Override public String setupHint() { return "Enter your public ListenBrainz username."; }

    @Override public List<ListenEvent> recent() throws Exception {
        if (!available()) throw new IllegalStateException(setupHint());
        return parse(HistoryHttp.get("https://api.listenbrainz.org/1/user/"
            + HistoryHttp.encode(username) + "/listens?count=500", null), username);
    }

    static List<ListenEvent> parse(JsonObject body, String username) {
        List<ListenEvent> result = new ArrayList<>();
        JsonElement items = HistoryHttp.object(body, "payload").get("listens");
        if (items == null || !items.isJsonArray()) return result;
        for (JsonElement element : items.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            JsonObject metadata = HistoryHttp.object(item, "track_metadata");
            String title = HistoryHttp.string(metadata, "track_name");
            String artist = HistoryHttp.string(metadata, "artist_name");
            long at = HistoryHttp.number(item, "listened_at") * 1000;
            if (title.isBlank() || artist.isBlank() || at <= 0) continue;
            // Timestamp and metadata identify a scrobble across repeated bounded syncs.
            String key = username.toLowerCase(Locale.ROOT) + ":" + at + ":" + artist + ":" + title;
            URI link = URI.create("https://listenbrainz.org/user/" + HistoryHttp.encode(username) + "/");
            result.add(new ListenEvent("ListenBrainz", key, title, artist,
                HistoryHttp.string(metadata, "release_name"), at, link, null));
        }
        return List.copyOf(result);
    }
}
