package sangitam.desktop.history;

import sangitam.desktop.model.Track;

import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Explainable grouping rules. Nearby reports of the same song count as one listen. */
public final class FavoritesAnalyzer {
    private static final long SAME_PLAY_WINDOW = Duration.ofMinutes(2).toMillis();
    private static final long FORGOTTEN_AFTER = Duration.ofDays(30).toMillis();
    private FavoritesAnalyzer() { }

    public static List<Favorite> analyze(List<Track> tracks, Map<Path, Integer> localCounts,
                                          List<ListenEvent> events) {
        Map<String, Group> groups = new HashMap<>();
        Map<Path, Group> localGroups = new HashMap<>();
        Map<Path, Integer> recordedLocal = new HashMap<>();
        for (Track track : tracks) {
            Group group = groups.computeIfAbsent(key(track.title(), track.artist()), ignored -> new Group());
            group.title = track.title();
            group.artist = track.artist();
            group.album = track.album();
            group.localPath = track.path();
            localGroups.put(track.path(), group);
        }
        for (ListenEvent event : events) {
            if (event.localPath() != null) recordedLocal.merge(event.localPath(), 1, Integer::sum);
            Group group = event.localPath() == null ? null : localGroups.get(event.localPath());
            if (group == null)
                group = groups.computeIfAbsent(key(event.title(), event.artist()), ignored -> new Group());
            if (group.title == null) {
                group.title = event.title();
                group.artist = event.artist();
                group.album = event.album();
            }
            group.events.add(event);
            group.sources.add(event.source());
            if (event.link() != null && (group.link == null
                || ("listenbrainz.org".equalsIgnoreCase(group.link.getHost())
                    && !"ListenBrainz".equals(event.source())))) group.link = event.link();
            if (group.localPath == null && event.localPath() != null) group.localPath = event.localPath();
        }
        for (Track track : tracks) {
            int legacy = Math.max(0, localCounts.getOrDefault(track.path(), 0)
                - recordedLocal.getOrDefault(track.path(), 0));
            if (legacy > 0) {
                Group group = groups.get(key(track.title(), track.artist()));
                group.legacyPlays += legacy;
                group.sources.add("Sangitam");
            }
        }
        List<Favorite> favorites = new ArrayList<>();
        for (Group group : groups.values()) {
            group.events.sort(Comparator.comparingLong(ListenEvent::playedAtMillis));
            List<Cluster> clusters = new ArrayList<>();
            long last = 0;
            for (ListenEvent event : group.events) {
                Cluster match = null;
                for (int i = clusters.size() - 1; i >= 0; i--) {
                    Cluster candidate = clusters.get(i);
                    if (event.playedAtMillis() - candidate.lastAt > SAME_PLAY_WINDOW) break;
                    // Two short repeats reported by the same source remain two plays.
                    if (!candidate.sources.contains(event.source())) { match = candidate; break; }
                }
                if (match == null) clusters.add(new Cluster(event.playedAtMillis(), event.source()));
                else { match.sources.add(event.source()); match.lastAt = event.playedAtMillis(); }
                last = Math.max(last, event.playedAtMillis());
            }
            int count = clusters.size() + group.legacyPlays;
            if (count > 0) favorites.add(new Favorite(group.title, group.artist, group.album,
                count, last, group.sources, group.localPath, group.link));
        }
        favorites.sort(Comparator.comparingInt(Favorite::knownPlays).reversed()
            .thenComparing(Favorite::title, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(favorites);
    }

    /** A favorite is forgotten only when its last observed play is old enough. */
    public static List<Favorite> forgotten(List<Favorite> favorites, long nowMillis) {
        return favorites.stream().filter(favorite -> favorite.knownPlays() >= 3)
            .filter(favorite -> favorite.lastHeardMillis() > 0)
            .filter(favorite -> nowMillis - favorite.lastHeardMillis() >= FORGOTTEN_AFTER)
            .sorted(Comparator.comparingLong(Favorite::lastHeardMillis)
                .thenComparing(Comparator.comparingInt(Favorite::knownPlays).reversed()))
            .toList();
    }

    private static String key(String title, String artist) {
        return normalize(artist) + "\u0000" + normalize(title);
    }

    private static String normalize(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return plain.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll(" +", " ");
    }

    private static final class Group {
        private String title;
        private String artist;
        private String album;
        private Path localPath;
        private java.net.URI link;
        private int legacyPlays;
        private final List<ListenEvent> events = new ArrayList<>();
        private final Set<String> sources = new HashSet<>();
    }

    private static final class Cluster {
        private long lastAt;
        private final Set<String> sources = new HashSet<>();
        private Cluster(long at, String source) { lastAt = at; sources.add(source); }
    }
}
