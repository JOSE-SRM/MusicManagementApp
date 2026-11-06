package sangitam.desktop.library;

import sangitam.desktop.model.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Pure search logic, kept outside Swing so it can be tested with large libraries. */
public final class TrackSearch {
    public record Results(List<Track> songs, List<String> albums, List<String> artists) { }

    private TrackSearch() { }

    public static Results find(List<Track> tracks, String text) {
        String query = text.trim().toLowerCase(Locale.ROOT);
        List<Track> songs = new ArrayList<>();
        Set<String> albums = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> artists = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Track track : tracks) {
            if (Thread.currentThread().isInterrupted()) break;
            if (!matches(track, query)) continue;
            songs.add(track);
            albums.add(track.album());
            artists.add(track.artist());
        }
        return new Results(List.copyOf(songs), List.copyOf(albums), List.copyOf(artists));
    }

    private static boolean matches(Track track, String query) {
        return query.isEmpty() || track.title().toLowerCase(Locale.ROOT).contains(query)
            || track.artist().toLowerCase(Locale.ROOT).contains(query)
            || track.album().toLowerCase(Locale.ROOT).contains(query)
            || track.genre().toLowerCase(Locale.ROOT).contains(query)
            || track.metadata().searchableText().contains(query);
    }
}
