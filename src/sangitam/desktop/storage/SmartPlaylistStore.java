package sangitam.desktop.storage;

import com.google.gson.Gson;
import org.sqlite.Function;
import org.sqlite.ProgressHandler;
import sangitam.desktop.model.SmartPlaylistRule;
import sangitam.desktop.model.Track;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Rule persistence and evaluation. Mutations share CollectionStore's transaction; reads never run on EDT. */
public final class SmartPlaylistStore {
    private static final Gson JSON = new Gson();
    private record Saved(int version, SmartPlaylistRule rule) { }
    public record Preview(long matching, long included, List<Track> examples) {
        public Preview { examples = List.copyOf(examples); }
    }
    private record Filter(String sql, List<Object> parameters) { }
    private final LibraryStore library;
    private boolean initialized;
    public SmartPlaylistStore(LibraryStore library) { this.library = library; }

    static void initialize(Connection connection) throws SQLException {
        try (var sql = connection.createStatement()) {
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS smart_playlist_rules (playlist_id INTEGER PRIMARY KEY, rule_json TEXT NOT NULL)");
            sql.executeUpdate("CREATE TRIGGER IF NOT EXISTS remove_smart_playlist_rule AFTER DELETE ON playlists BEGIN DELETE FROM smart_playlist_rules WHERE playlist_id=OLD.id; END");
        }
    }
    static SmartPlaylistRule decode(String json) throws SQLException {
        if (json == null) return null;
        try {
            Saved saved = JSON.fromJson(json, Saved.class);
            if (saved == null || saved.version() != 1 || saved.rule() == null) throw new IllegalArgumentException();
            return saved.rule();
        } catch (RuntimeException error) { throw new SQLException("A saved smart playlist rule is invalid or from an unsupported version.", error); }
    }
    static void save(Connection connection, long id, SmartPlaylistRule rule) throws SQLException {
        Objects.requireNonNull(rule);
        try (var sql = connection.prepareStatement("INSERT INTO smart_playlist_rules VALUES (?,?) ON CONFLICT(playlist_id) DO UPDATE SET rule_json=excluded.rule_json")) {
            sql.setLong(1, id); sql.setString(2, JSON.toJson(new Saved(1, rule))); sql.executeUpdate();
        }
    }
    static void requireManual(Connection connection, long id) throws SQLException {
        try (var sql = connection.prepareStatement("SELECT 1 FROM smart_playlist_rules WHERE playlist_id=?")) {
            sql.setLong(1, id);
            try (var row = sql.executeQuery()) {
                if (row.next()) throw new SQLException("This is a smart playlist. Edit its rules or save a regular copy to change individual songs.");
            }
        }
    }
    static void prepare(Connection connection) throws SQLException {
        // SQLite's built-in lower()/NOCASE only fold ASCII. Tags must also work for Unicode user labels.
        Function.create(connection, "sangitam_fold", new Function() {
            @Override protected void xFunc() throws SQLException {
                String value = value_text(0); result(value == null ? "" : value.toLowerCase(Locale.ROOT));
            }
        }, 1);
        ProgressHandler.setHandler(connection, 1000, new ProgressHandler() {
            @Override protected int progress() { return Thread.currentThread().isInterrupted() ? 1 : 0; }
        });
    }
    static List<Path> paths(Connection connection, SmartPlaylistRule rule) throws SQLException {
        Filter filter = filter(rule);
        try (var query = connection.prepareStatement("SELECT t.path" + filter.sql() + order(rule) + " LIMIT ?")) {
            int next = bind(query, filter); query.setInt(next, rule.limit());
            var paths = new ArrayList<Path>();
            try (var rows = query.executeQuery()) { while (rows.next()) paths.add(Path.of(rows.getString(1))); }
            return List.copyOf(paths);
        }
    }
    public Preview preview(SmartPlaylistRule rule) throws Exception {
        synchronized (library) { if (!initialized) { library.initialize(); initialized = true; } }
        try (Connection connection = library.connect()) {
            prepare(connection); connection.setAutoCommit(false);
            Filter filter = filter(rule);
            long count;
            try (var query = connection.prepareStatement("SELECT COUNT(*)" + filter.sql())) {
                bind(query, filter);
                try (var row = query.executeQuery()) { row.next(); count = row.getLong(1); }
            }
            var examples = new ArrayList<Track>();
            try (var query = connection.prepareStatement("SELECT t.*,m.payload" + filter.sql() + order(rule) + " LIMIT ?")) {
                int next = bind(query, filter); query.setInt(next, Math.min(rule.limit(), 20));
                try (var row = query.executeQuery()) {
                    while (row.next()) examples.add(new Track(Path.of(row.getString("path")), row.getString("title"), row.getString("artist"),
                        row.getString("album"), row.getDouble("duration_seconds"), row.getString("format"), row.getString("genre"), MetadataStore.decode(row.getString("payload"))));
                }
            }
            connection.commit();
            return new Preview(count, Math.min(count, rule.limit()), examples);
        }
    }
    private static Filter filter(SmartPlaylistRule rule) {
        StringBuilder sql = new StringBuilder(" FROM tracks t LEFT JOIN track_metadata m ON m.path=t.path WHERE 1=1");
        var values = new ArrayList<Object>();
        if (rule.availableOnly()) sql.append(" AND t.unavailable=0");
        if (!rule.text().isEmpty()) {
            sql.append(" AND COALESCE(m.search_text,sangitam_fold(t.title || ' ' || t.artist || ' ' || t.album || ' ' || t.genre)) LIKE ? ESCAPE '\\'");
            values.add("%" + rule.text().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (!rule.genre().isEmpty()) { sql.append(" AND sangitam_fold(t.genre)=?"); values.add(rule.genre().toLowerCase(Locale.ROOT)); }
        for (String tag : rule.tags()) {
            sql.append(" AND EXISTS (SELECT 1 FROM json_each(m.payload,'$.metadata.tags') tag WHERE sangitam_fold(tag.value)=?)");
            values.add(tag.toLowerCase(Locale.ROOT));
        }
        if (rule.energy() != null || rule.activity() != null)
            sql.append(" AND t.analyzed=1 AND (t.tags_override=1 OR t.analysis_version>=2)");
        if (rule.energy() != null) { sql.append(" AND t.energy BETWEEN ? AND ?"); values.add(rule.energy().minimum()); values.add(rule.energy().maximum()); }
        if (rule.activity() != null) { sql.append(" AND t.activity BETWEEN ? AND ?"); values.add(rule.activity().minimum()); values.add(rule.activity().maximum()); }
        sql.append(" AND t.play_count>=?"); values.add(rule.minimumPlays());
        return new Filter(sql.toString(), List.copyOf(values));
    }
    private static int bind(PreparedStatement query, Filter filter) throws SQLException {
        int index = 1; for (Object value : filter.parameters()) query.setObject(index++, value); return index;
    }
    private static String order(SmartPlaylistRule rule) {
        String tie = "t.title COLLATE NOCASE,t.artist COLLATE NOCASE,t.path";
        return " ORDER BY " + switch (rule.order()) {
            case RECENT -> "MAX(t.added_at,t.last_played_at) DESC," + tie;
            case MOST_PLAYED -> "t.play_count DESC," + tie;
            case LEAST_PLAYED -> "t.play_count," + tie;
            case ALPHABETICAL -> tie;
        };
    }
}
