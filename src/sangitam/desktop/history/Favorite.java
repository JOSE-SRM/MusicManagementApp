package sangitam.desktop.history;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

/** A song grouped across sources; links remain provider pages, never downloaded audio. */
public record Favorite(String title, String artist, String album, int knownPlays,
                       long lastHeardMillis, Set<String> sources, Path localPath, URI link) {
    public Favorite {
        sources = Set.copyOf(sources);
    }
}
