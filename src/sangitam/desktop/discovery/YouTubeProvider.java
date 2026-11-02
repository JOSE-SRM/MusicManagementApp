package sangitam.desktop.discovery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** YouTube Data API search. The result links to YouTube; no audio is extracted. */
public final class YouTubeProvider implements MusicProvider {
    private final String apiKey;

    public YouTubeProvider() { this(System.getenv("SANGITAM_YOUTUBE_API_KEY")); }
    YouTubeProvider(String apiKey) { this.apiKey = apiKey; }

    @Override public String name() { return "YouTube"; }
    @Override public String setupHint() {
        return available() ? "Video search · opens results on YouTube"
            : "Set SANGITAM_YOUTUBE_API_KEY, then restart Sangitam";
    }
    @Override public boolean available() { return apiKey != null && !apiKey.isBlank(); }

    @Override public List<CatalogTrack> search(String query) throws Exception {
        if (!available()) throw new IllegalStateException(setupHint());
        String url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video"
            + "&videoCategoryId=10&maxResults=10&q=" + CatalogHttp.encode(query)
            + "&key=" + CatalogHttp.encode(apiKey);
        return parse(CatalogHttp.get(url, null, null));
    }

    static List<CatalogTrack> parse(JsonObject body) {
        List<CatalogTrack> results = new ArrayList<>();
        JsonElement items = body.get("items");
        if (items == null || !items.isJsonArray()) return results;
        for (JsonElement element : items.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = CatalogHttp.string(CatalogHttp.object(item, "id"), "videoId");
            if (!id.matches("[A-Za-z0-9_-]{11}")) continue;
            JsonObject snippet = CatalogHttp.object(item, "snippet");
            results.add(new CatalogTrack(CatalogHttp.string(snippet, "title"),
                CatalogHttp.string(snippet, "channelTitle"), "", "YouTube",
                URI.create("https://www.youtube.com/watch?v=" + id)));
        }
        return results;
    }
}
