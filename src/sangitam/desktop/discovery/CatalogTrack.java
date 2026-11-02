package sangitam.desktop.discovery;

import java.net.URI;

/** One catalog result. A provider link is not a locally playable audio file. */
public record CatalogTrack(String title, String artist, String album, String source, URI link) {
    public CatalogTrack {
        title = tidy(title, "Untitled");
        artist = tidy(artist, "Unknown artist");
        album = tidy(album, "");
    }

    private static String tidy(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        // YouTube titles may contain HTML entities; angle brackets are made inert for Swing labels.
        return value.strip().replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&amp;", "&").replace("&lt;", "‹").replace("&gt;", "›")
            .replace('<', '‹').replace('>', '›');
    }
}
