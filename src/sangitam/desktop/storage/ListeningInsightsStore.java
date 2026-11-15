package sangitam.desktop.storage;

import sangitam.desktop.model.AudioFeatures;
import sangitam.desktop.model.TrackMetadata;

import java.sql.SQLException;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Aggregates measured local seconds separately from each source's recorded play events. */
public final class ListeningInsightsStore {
    public record Bucket(String label, double seconds) { }
    public record Day(LocalDate date, double seconds, long plays) { }
    public enum Range {
        ALL("All time", 0), WEEK("Last 7 days", 7), MONTH("Last 30 days", 30), QUARTER("Last 90 days", 90);
        private final String label;
        private final int days;
        Range(String label, int days) { this.label = label; this.days = days; }
        @Override public String toString() { return label; }
    }
    public record Snapshot(double seconds, int recordings, List<Bucket> genres, List<Bucket> periods,
                           List<Bucket> moods, Map<String, Long> sourcePlays, List<Day> trend) { }

    private final LibraryStore library;

    public ListeningInsightsStore(LibraryStore library) { this.library = library; }

    public Snapshot load() throws SQLException { return load(Range.ALL, ZonedDateTime.now()); }

    public Snapshot load(Range range, ZonedDateTime now) throws SQLException {
        Map<String, Double> genres = buckets(), periods = buckets(), moods = buckets();
        Map<String, Long> sourcePlays = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        double total = 0;
        int recordings = 0;
        LocalDate last = now.toLocalDate();
        LocalDate first = last.minusDays((range == Range.ALL ? 30 : range.days) - 1L);
        long since = midnight(first, now);
        long until = now.toInstant().toEpochMilli() + 1;
        List<Day> trend;
        try (var connection = library.connect()) {
            connection.setAutoCommit(false);
            String listening = range == Range.ALL ? "listening_seconds" : """
                (SELECT path, SUM(seconds) AS seconds FROM listening_windows
                 WHERE observed_at >= ? AND observed_at < ? GROUP BY path)
                """;
            try (var details = connection.prepareStatement("""
                SELECT l.seconds,t.path,t.genre,t.metadata_size,t.metadata_modified,m.payload AS metadata,f.payload AS features
                FROM %s l LEFT JOIN tracks t ON l.path=t.path
                LEFT JOIN track_metadata m ON l.path=m.path LEFT JOIN audio_features f ON l.path=f.path
                """.formatted(listening))) {
                if (range != Range.ALL) {
                    details.setLong(1, since);
                    details.setLong(2, until);
                }
                try (var rows = details.executeQuery()) {
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
            }
            String filter = range == Range.ALL ? "" : " WHERE played_at_ms >= ? AND played_at_ms < ?";
            try (var counts = connection.prepareStatement("SELECT source,COUNT(*) FROM listens" + filter + " GROUP BY source")) {
                if (range != Range.ALL) {
                    counts.setLong(1, since);
                    counts.setLong(2, until);
                }
                try (var rows = counts.executeQuery()) {
                    while (rows.next()) sourcePlays.merge(rows.getString(1), rows.getLong(2), Long::sum);
                }
            }
            trend = trend(connection, first, last, now, until);
            connection.commit();
        }
        return new Snapshot(total, recordings, ranked(genres), ranked(periods), ranked(moods),
            Collections.unmodifiableMap(sourcePlays), trend);
    }

    private static List<Day> trend(Connection connection, LocalDate first, LocalDate last,
                                   ZonedDateTime now, long until) throws SQLException {
        List<LocalDate> dates = first.datesUntil(last.plusDays(1)).toList();
        String boundaries = String.join(",", Collections.nCopies(dates.size(), "(?,?,?)"));
        try (var query = connection.prepareStatement("""
            WITH days(day, start_ms, end_ms) AS (VALUES %s)
            SELECT day,
                (SELECT COALESCE(SUM(seconds), 0) FROM listening_windows WHERE observed_at >= start_ms AND observed_at < end_ms),
                (SELECT COUNT(*) FROM listens WHERE played_at_ms >= start_ms AND played_at_ms < end_ms)
            FROM days ORDER BY day
            """.formatted(boundaries))) {
            int parameter = 1;
            for (LocalDate date : dates) {
                query.setString(parameter++, date.toString());
                query.setLong(parameter++, midnight(date, now));
                query.setLong(parameter++, Math.min(midnight(date.plusDays(1), now), until));
            }
            try (var rows = query.executeQuery()) {
                var days = new ArrayList<Day>();
                while (rows.next()) {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                    days.add(new Day(LocalDate.parse(rows.getString(1)), rows.getDouble(2), rows.getLong(3)));
                }
                return List.copyOf(days);
            }
        }
    }

    private static long midnight(LocalDate date, ZonedDateTime now) {
        return date.atStartOfDay(now.getZone()).toInstant().toEpochMilli();
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
