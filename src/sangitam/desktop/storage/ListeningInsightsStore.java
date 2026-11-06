package sangitam.desktop.storage;

import sangitam.desktop.model.AudioFeatures;
import sangitam.desktop.model.TrackMetadata;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Aggregates measured local seconds separately from each source's recorded play events. */
public final class ListeningInsightsStore {
    public record Bucket(String label, double seconds) { }
    public record Snapshot(double seconds, int recordings, List<Bucket> genres, List<Bucket> periods,
                           List<Bucket> moods, Map<String, Long> sourcePlays) { }

    private final LibraryStore library;

    public ListeningInsightsStore(LibraryStore library) { this.library = library; }

    public Snapshot load() throws SQLException {
        Map<String, Double> genres = buckets(), periods = buckets(), moods = buckets();
        Map<String, Long> sourcePlays = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        double total = 0;
        int recordings = 0;
        try (var connection = library.connect(); var query = connection.createStatement()) {
            connection.setAutoCommit(false);
            try (var rows = query.executeQuery("""
                SELECT l.seconds,t.path,t.genre,t.metadata_size,t.metadata_modified,m.payload AS metadata,f.payload AS features
                FROM listening_seconds l LEFT JOIN tracks t ON l.path=t.path
                LEFT JOIN track_metadata m ON l.path=m.path LEFT JOIN audio_features f ON l.path=f.path
                """)) {
                while (rows.next()) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                    double seconds = rows.getDouble("seconds");
                    if (!Double.isFinite(seconds) || seconds <= 0) continue;
                    total += seconds;
                    recordings++;
                    if (rows.getString("path") == null) {
                        for (var dimension : List.of(genres, periods, moods)) dimension.merge("Removed recordings", seconds, Double::sum);
                        continue;
                    }
                    String period = MetadataStore.decode(rows.getString("metadata")).text(TrackMetadata.Field.PERIOD);
                    AudioFeatures features = AudioFeatureStore.decode(rows.getString("features"));
                    long size = rows.getLong("metadata_size"), modified = rows.getLong("metadata_modified");
                    String mood = features.current(size, modified) || size < 0 && features.version() == AudioFeatures.VERSION
                        ? features.mood() : "Analysis pending";
                    genres.merge(label(rows.getString("genre"), "Unknown genre"), seconds, Double::sum);
                    periods.merge(label(period, "Unknown period"), seconds, Double::sum);
                    moods.merge(label(mood, "Unknown mood"), seconds, Double::sum);
                }
            }
            try (var rows = query.executeQuery("SELECT source,COUNT(*) FROM listens GROUP BY source")) {
                while (rows.next()) sourcePlays.merge(rows.getString(1), rows.getLong(2), Long::sum);
            }
            connection.commit();
        }
        return new Snapshot(total, recordings, ranked(genres), ranked(periods), ranked(moods),
            Collections.unmodifiableMap(sourcePlays));
    }

    private static Map<String, Double> buckets() { return new TreeMap<>(String.CASE_INSENSITIVE_ORDER); }

    private static String label(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static List<Bucket> ranked(Map<String, Double> totals) {
        return totals.entrySet().stream().map(entry -> new Bucket(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingDouble(Bucket::seconds).reversed().thenComparing(Bucket::label)).toList();
    }
}
