package sangitam.desktop.discovery;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Apple Music catalog search. A developer token is needed; playback remains with Apple. */
public final class AppleMusicProvider implements MusicProvider {
    private final String token;
    private final String storefront;

    public AppleMusicProvider() {
        this(System.getenv("SANGITAM_APPLE_MUSIC_DEVELOPER_TOKEN"),
            System.getenv("SANGITAM_APPLE_MUSIC_STOREFRONT"));
    }
    AppleMusicProvider(String token, String storefront) {
        this.token = token;
        this.storefront = storefront != null && storefront.matches("[a-zA-Z]{2}")
            ? storefront.toLowerCase() : "in";
    }

    @Override public String name() { return "Apple Music"; }
    @Override public String setupHint() {
        return available() ? "Catalog search · opens songs in Apple Music"
            : "Set SANGITAM_APPLE_MUSIC_DEVELOPER_TOKEN, then restart Sangitam";
    }
    @Override public boolean available() { return token != null && !token.isBlank(); }

    @Override public List<CatalogTrack> search(String query) throws Exception {
        if (!available()) throw new IllegalStateException(setupHint());
        String url = "https://api.music.apple.com/v1/catalog/" + storefront
            + "/search?term=" + CatalogHttp.encode(query) + "&types=songs&limit=10";
        return parse(CatalogHttp.get(url, "Authorization", "Bearer " + token));
    }

    static List<CatalogTrack> parse(JsonObject body) {
        List<CatalogTrack> results = new ArrayList<>();
        JsonElement data = CatalogHttp.object(CatalogHttp.object(body, "results"), "songs").get("data");
        if (data == null || !data.isJsonArray()) return results;
        for (JsonElement element : data.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject attributes = CatalogHttp.object(element.getAsJsonObject(), "attributes");
            String rawUrl = CatalogHttp.string(attributes, "url");
            URI link;
            try { link = URI.create(rawUrl); }
            catch (IllegalArgumentException invalid) { continue; }
            if (!"https".equalsIgnoreCase(link.getScheme())
                || !"music.apple.com".equalsIgnoreCase(link.getHost())) continue;
            results.add(new CatalogTrack(CatalogHttp.string(attributes, "name"),
                CatalogHttp.string(attributes, "artistName"),
                CatalogHttp.string(attributes, "albumName"), "Apple Music", link));
        }
        return results;
    }
}
