package sangitam.desktop.model;

import java.nio.file.Path;
import java.util.Objects;

/** One indexed audio file. Its normalized path is the stable identity within the local library. */
public record Track(Path path, String title, String artist, String album, double durationSeconds, String format) {
    public Track {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        title = Objects.requireNonNull(title, "title");
        artist = Objects.requireNonNull(artist, "artist");
        album = Objects.requireNonNull(album, "album");
        format = Objects.requireNonNull(format, "format");
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0) durationSeconds = 0;
    }

    public String durationLabel() {
        long seconds = Math.round(durationSeconds);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    @Override public String toString() { return title + " — " + artist; }
}
