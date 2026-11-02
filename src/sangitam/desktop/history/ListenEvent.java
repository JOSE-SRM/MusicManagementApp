package sangitam.desktop.history;

import java.net.URI;
import java.nio.file.Path;

/** One observed play. The source key makes repeated syncs idempotent. */
public record ListenEvent(String source, String sourceKey, String title, String artist,
                          String album, long playedAtMillis, URI link, Path localPath) {
    public ListenEvent {
        if (source == null || source.isBlank() || sourceKey == null || sourceKey.isBlank())
            throw new IllegalArgumentException("A listen needs a source and unique key");
        if (title == null || title.isBlank() || artist == null || artist.isBlank())
            throw new IllegalArgumentException("A listen needs a title and artist");
        if (playedAtMillis <= 0) throw new IllegalArgumentException("A listen needs a timestamp");
        album = album == null ? "" : album;
    }
}
