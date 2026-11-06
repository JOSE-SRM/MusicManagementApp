package sangitam.desktop.storage;

import sangitam.desktop.model.Track;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Bounded SQLite result pages. Searches extended metadata without decoding files or loading the catalog. */
public final class LibrarySearchStore {
    public enum Category { SONGS, ALBUMS, ARTISTS }
    public enum Order {
        RECENT("Recent"), ALPHABETICAL("Alphabetical"), MOST_PLAYED("Most played");
        private final String label;
        Order(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public record Request(String text, Category category, boolean availableOnly, Order order, int offset, int limit) {
        public Request {
            text = Objects.requireNonNull(text).strip().toLowerCase(Locale.ROOT);
            Objects.requireNonNull(category); Objects.requireNonNull(order);
            if (offset < 0 || limit < 1 || limit > 250) throw new IllegalArgumentException("Invalid search page");
        }
    }
    public record Hit(Track track, String title, String artist, int trackCount) {
        @Override public String toString() { return track == null ? title + (artist.isBlank() ? "" : " — " + artist) + " · " + trackCount + " tracks" : track.toString(); }
    }
    public record Page(List<Hit> hits, long total, Request request) {
        public Page { hits = List.copyOf(hits); }
        public boolean hasNext() { return request.offset() + hits.size() < total; }
    }
    private final LibraryStore library;
    private boolean initialized;
    public LibrarySearchStore(LibraryStore library) { this.library = library; }

    public Page search(Request request) throws Exception {
        synchronized (library) {
            if (!initialized) { library.initialize(); initialized = true; }
        }
        String from = " FROM tracks t LEFT JOIN track_metadata m ON t.path=m.path WHERE 1=1";
        if (request.availableOnly()) from += " AND t.unavailable=0";
        if (request.category() == Category.ALBUMS) from += " AND trim(t.album)<>'' AND lower(t.album)<>'unknown album'";
        if (request.category() == Category.ARTISTS) from += " AND trim(t.artist)<>'' AND lower(t.artist)<>'unknown artist'";
        if (!request.text().isEmpty()) from += " AND (m.search_text LIKE ? ESCAPE '\\' OR (m.path IS NULL AND lower(t.title || ' ' || t.artist || ' ' || t.album || ' ' || t.genre) LIKE ? ESCAPE '\\'))";
        String grouping = switch (request.category()) {
            case SONGS -> "";
            case ALBUMS -> " GROUP BY t.album COLLATE NOCASE,t.artist COLLATE NOCASE";
            case ARTISTS -> " GROUP BY t.artist COLLATE NOCASE";
        };
        String recent = request.category() == Category.SONGS ? "MAX(t.added_at,t.last_played_at)" : "MAX(MAX(t.added_at,t.last_played_at))";
        String plays = request.category() == Category.SONGS ? "t.play_count" : "SUM(t.play_count)";
        String title = request.category() == Category.SONGS ? "t.title" : request.category() == Category.ALBUMS ? "t.album" : "t.artist";
        String tie = title + " COLLATE NOCASE,t.artist COLLATE NOCASE," + (request.category() == Category.SONGS ? "t.path" : "MIN(t.path)");
        String sort = switch (request.order()) {
            case RECENT -> recent + " DESC," + tie;
            case ALPHABETICAL -> tie;
            case MOST_PLAYED -> plays + " DESC," + tie;
        };
        String select = request.category() == Category.SONGS ? "SELECT t.*,m.payload" : "SELECT " + title + " AS title,t.artist,COUNT(*) AS members";
        try (Connection connection = library.connect()) {
            org.sqlite.ProgressHandler.setHandler(connection, 1000, new org.sqlite.ProgressHandler() {
                @Override protected int progress() { return Thread.currentThread().isInterrupted() ? 1 : 0; }
            });
            connection.setAutoCommit(false); // count and rows refer to the same read snapshot
            long total;
            try (var count = connection.prepareStatement("SELECT COUNT(*) FROM (SELECT 1" + from + grouping + ")")) {
                bindQuery(count, request);
                try (var rows = count.executeQuery()) { rows.next(); total = rows.getLong(1); }
            }
            var hits = new ArrayList<Hit>();
            try (var query = connection.prepareStatement(select + from + grouping + " ORDER BY " + sort + " LIMIT ? OFFSET ?")) {
                int index = bindQuery(query, request);
                query.setInt(index++, request.limit()); query.setInt(index, request.offset());
                query.setQueryTimeout(5);
                try (var row = query.executeQuery()) {
                    while (row.next()) {
                        if (request.category() == Category.SONGS) {
                            Track track = new Track(Path.of(row.getString("path")), row.getString("title"), row.getString("artist"),
                                row.getString("album"), row.getDouble("duration_seconds"), row.getString("format"), row.getString("genre"), MetadataStore.decode(row.getString("payload")));
                            hits.add(new Hit(track, track.title(), track.artist(), 1));
                        } else hits.add(new Hit(null, row.getString("title"), request.category() == Category.ARTISTS ? "" : row.getString("artist"), row.getInt("members")));
                    }
                }
            }
            connection.commit();
            return new Page(hits, total, request);
        }
    }
    private static int bindQuery(PreparedStatement query, Request request) throws SQLException {
        if (request.text().isEmpty()) return 1;
        String literal = "%" + request.text().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        query.setString(1, literal); query.setString(2, literal);
        return 3;
    }
}
