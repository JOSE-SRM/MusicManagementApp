package sangitam.desktop.model;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** A MusicBrainz recording and the releases on which it appears. */
public record RecordingCandidate(UUID id, String title, String credit, String disambiguation,
                                 long durationMillis, List<Release> releases) {
    public RecordingCandidate { releases = List.copyOf(releases); }

    public URI link() { return URI.create("https://musicbrainz.org/recording/" + id); }

    public record Release(UUID id, String title, String date) {
        @Override public String toString() { return date.isBlank() ? title : title + " (" + date + ")"; }
    }
}
