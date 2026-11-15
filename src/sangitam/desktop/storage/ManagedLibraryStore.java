package sangitam.desktop.storage;

import com.google.gson.Gson;
import sangitam.desktop.library.ManagedLibraryCopy;
import sangitam.desktop.model.FileStamp;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists the active configuration and the relationship between original and catalog paths. */
public final class ManagedLibraryStore {
    private static final Gson JSON = new Gson();
    private final LibraryStore library;
    private record Configuration(String root, ManagedLibraryCopy.Policy policy,
                                 ManagedLibraryCopy.Options options, boolean writeTags) { }
    public record Placement(Path source, Path target, FileStamp stamp, FileStamp sourceStamp) { }
    public record Batch(ManagedLibraryCopy.Settings activation, List<Placement> placements) { }
    public record Cleanup(Path previous, Path current) { }
    public record Original(Path source, Path target, FileStamp stamp) { }
    public record PlaylistReference(long playlist, String path) { }
    public record Duplicate(Path previous, Path target) { }
    public record Repair(Map<Path, FileStamp> restored, Map<String, Path> references) {
        public static final Repair EMPTY = new Repair(Map.of(), Map.of());
    }

    public ManagedLibraryStore(LibraryStore library) { this.library = library; }

    static void initialize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS managed_files (source TEXT PRIMARY KEY, managed TEXT NOT NULL UNIQUE,
                  source_size INTEGER, source_modified INTEGER)
                """);
            boolean hasStamp = false;
            try (var rows = statement.executeQuery("PRAGMA table_info(managed_files)")) {
                while (rows.next()) if (rows.getString("name").equals("source_size")) hasStamp = true;
            }
            if (!hasStamp) {
                statement.executeUpdate("ALTER TABLE managed_files ADD COLUMN source_size INTEGER");
                statement.executeUpdate("ALTER TABLE managed_files ADD COLUMN source_modified INTEGER");
            }
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS managed_cleanup (previous TEXT PRIMARY KEY, current TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS managed_aliases (previous TEXT PRIMARY KEY, current TEXT NOT NULL)");
        }
    }

    public ManagedLibraryCopy.Settings settings() throws SQLException {
        try (var connection = library.connect(); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT value FROM app_state WHERE key='active_managed_library'")) {
            if (!row.next()) return null;
            var config = JSON.fromJson(row.getString(1), Configuration.class);
            return new ManagedLibraryCopy.Settings(Path.of(config.root()), config.policy(), config.options(), config.writeTags());
        }
    }

    public Map<Path, Path> paths() throws SQLException {
        return readPaths("SELECT source, managed FROM managed_files ORDER BY source");
    }

    public Map<Path, Path> aliases() throws SQLException {
        return readPaths("SELECT previous, current FROM managed_aliases");
    }

    public List<Original> originals() throws SQLException {
        var items = new java.util.ArrayList<Original>();
        try (var connection = library.connect(); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT source, managed, source_size, source_modified FROM managed_files")) {
            while (rows.next()) items.add(new Original(Path.of(rows.getString(1)), Path.of(rows.getString(2)),
                rows.getObject(3) == null ? null : new FileStamp(rows.getLong(3), rows.getLong(4))));
        }
        return items;
    }

    public List<PlaylistReference> danglingPlaylistReferences() throws SQLException {
        var items = new java.util.ArrayList<PlaylistReference>();
        try (var connection = library.connect(); var sql = connection.createStatement();
             var rows = sql.executeQuery("""
                SELECT DISTINCT p.playlist_id, p.path FROM playlist_items p
                LEFT JOIN tracks t ON t.path=p.path WHERE t.path IS NULL
                """)) {
            while (rows.next()) items.add(new PlaylistReference(rows.getLong(1), rows.getString(2)));
        }
        return items;
    }

    private Map<Path, Path> readPaths(String query) throws SQLException {
        var paths = new LinkedHashMap<Path, Path>();
        try (var connection = library.connect(); var sql = connection.createStatement();
             var rows = sql.executeQuery(query)) {
            while (rows.next()) paths.put(Path.of(rows.getString(1)), Path.of(rows.getString(2)));
        }
        return Map.copyOf(paths);
    }

    static void apply(Connection connection, Batch batch) throws SQLException {
        if (batch == null) return;
        for (Placement placement : batch.placements()) {
            remap(connection, placement.source(), placement.target(), placement.stamp());
            execute(connection, """
                INSERT INTO managed_files(source, managed, source_size, source_modified) VALUES(?, ?, ?, ?)
                ON CONFLICT(source) DO UPDATE SET managed=excluded.managed,
                    source_size=excluded.source_size, source_modified=excluded.source_modified
                """, placement.source(), placement.target(), placement.sourceStamp().size(), placement.sourceStamp().modifiedMillis());
        }
        if (batch.activation() != null) {
            var s = batch.activation();
            execute(connection, """
                INSERT INTO app_state(key,value) VALUES('active_managed_library',?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """, JSON.toJson(new Configuration(s.root().toString(), s.policy(), s.options(), s.writeTags())));
        }
    }

    static void applyRepair(Connection connection, Repair repair) throws SQLException {
        for (var restored : repair.restored().entrySet()) updateStamp(connection, restored.getKey(), restored.getValue());
        for (var link : repair.references().entrySet()) {
            String previous = link.getKey();
            Path target = link.getValue();
            moveReferences(connection, previous, target);
            saveAlias(connection, previous, target);
        }
    }

    public void mergeDuplicates(List<Duplicate> duplicates) throws SQLException {
        if (duplicates.isEmpty()) return;
        try (var connection = library.connect()) {
            connection.setAutoCommit(false);
            try {
                for (var duplicate : duplicates) mergeDuplicate(connection, duplicate.previous(), duplicate.target());
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void mergeDuplicate(Connection connection, Path previous, Path target) throws SQLException {
        for (String field : List.of("title", "artist", "album", "genre")) {
            String override = field + "_override";
            execute(connection, "UPDATE tracks SET " + field + "=old." + field + ", " + override + "=1 "
                + "FROM tracks old WHERE tracks.path=? AND old.path=? AND tracks." + override + "=0 AND old." + override + "=1",
                target, previous);
        }
        execute(connection, """
            UPDATE tracks SET play_count=tracks.play_count+old.play_count,
                added_at=CASE WHEN tracks.added_at=0 THEN old.added_at WHEN old.added_at=0 THEN tracks.added_at
                    ELSE MIN(tracks.added_at,old.added_at) END,
                last_played_at=MAX(tracks.last_played_at,old.last_played_at),
                energy=CASE WHEN tracks.tags_override=0 AND old.tags_override=1 THEN old.energy ELSE tracks.energy END,
                activity=CASE WHEN tracks.tags_override=0 AND old.tags_override=1 THEN old.activity ELSE tracks.activity END,
                analyzed=MAX(tracks.analyzed,old.analyzed),
                tags_override=MAX(tracks.tags_override,old.tags_override)
            FROM tracks old WHERE tracks.path=? AND old.path=?
            """, target, previous);
        MetadataStore.mergeDuplicate(connection, previous, target);
        for (String table : List.of("audio_features", "background_metadata", "recording_matches")) {
            execute(connection, "UPDATE OR IGNORE " + table + " SET path=? WHERE path=?", target, previous);
        }
        moveReferences(connection, previous.toString(), target);
        saveAlias(connection, previous.toString(), target);
        execute(connection, "UPDATE managed_cleanup SET current=? WHERE current=?", target, previous);
        execute(connection, "DELETE FROM tracks WHERE path=?", previous);
    }

    public void relocate(Path previous, Path target, FileStamp stamp) throws SQLException {
        try (var connection = library.connect()) {
            connection.setAutoCommit(false);
            try {
                remap(connection, previous, target, stamp);
                execute(connection, "UPDATE managed_files SET managed=? WHERE managed=?", target, previous);
                execute(connection, "DELETE FROM managed_cleanup WHERE previous=?", target);
                execute(connection, "UPDATE managed_cleanup SET current=? WHERE current=?", target, previous);
                execute(connection, "INSERT INTO managed_cleanup(previous,current) VALUES(?,?)", previous, target);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    public List<Cleanup> cleanup() throws SQLException {
        var pending = new java.util.ArrayList<Cleanup>();
        try (var connection = library.connect(); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT previous,current FROM managed_cleanup")) {
            while (rows.next()) pending.add(new Cleanup(Path.of(rows.getString(1)), Path.of(rows.getString(2))));
        }
        return pending;
    }

    public void finishCleanup(Path previous) throws SQLException {
        try (var connection = library.connect()) {
            execute(connection, "DELETE FROM managed_cleanup WHERE previous=?", previous);
        }
    }

    public void updateStamp(Path path, FileStamp stamp) throws SQLException {
        try (var connection = library.connect()) {
            connection.setAutoCommit(false);
            try {
                updateStamp(connection, path, stamp);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static void updateStamp(Connection connection, Path path, FileStamp stamp) throws SQLException {
        for (String table : List.of("tracks", "background_metadata", "recording_matches")) {
            execute(connection, "UPDATE " + table + " SET metadata_size=?, metadata_modified=? WHERE path=?",
                stamp.size(), stamp.modifiedMillis(), path);
        }
    }

    private static void remap(Connection connection, Path previous, Path target, FileStamp stamp) throws SQLException {
        if (previous.equals(target)) return;
        for (String table : List.of("tracks", "track_metadata", "audio_features", "background_metadata", "recording_matches")) {
            execute(connection, "UPDATE " + table + " SET path=? WHERE path=?", target, previous);
        }
        moveReferences(connection, previous.toString(), target);
        saveAlias(connection, previous.toString(), target);
        updateStamp(connection, target, stamp);
    }

    private static void moveReferences(Connection connection, String previous, Path target) throws SQLException {
        if (previous.equals(target.toString())) return;
        for (String table : List.of("playlist_items", "listening_windows")) {
            execute(connection, "UPDATE " + table + " SET path=? WHERE path=?", target, previous);
        }
        for (String table : List.of("folder_tracks", "song_likes", "former_song_pins", "taste_feedback", "listening_credits")) {
            execute(connection, "UPDATE OR IGNORE " + table + " SET path=? WHERE path=?", target, previous);
            execute(connection, "DELETE FROM " + table + " WHERE path=?", previous);
        }
        // Preserve both editions when two catalog rows point to the same managed file.
        execute(connection, "UPDATE sheet_music SET path=? WHERE path=?", target, previous);
        execute(connection, "UPDATE managed_scores SET path=? WHERE path=?", target, previous);
        execute(connection, "UPDATE OR IGNORE score_recordings SET path=? WHERE path=?", target, previous);
        execute(connection, "DELETE FROM score_recordings WHERE path=?", previous);
        execute(connection, """
            INSERT INTO listening_seconds(path,seconds) SELECT ?, seconds FROM listening_seconds WHERE path=?
            ON CONFLICT(path) DO UPDATE SET seconds=listening_seconds.seconds+excluded.seconds
            """, target, previous);
        execute(connection, "DELETE FROM listening_seconds WHERE path=?", previous);
        execute(connection, "UPDATE listens SET local_path=? WHERE local_path=?", target, previous);
        execute(connection, "UPDATE OR IGNORE library_pins SET item=? WHERE kind='SONG' AND item=?", target, previous);
        execute(connection, "DELETE FROM library_pins WHERE kind='SONG' AND item=?", previous);
        execute(connection, "UPDATE app_state SET value=? WHERE key='last_track' AND value=?", target, previous);
    }

    private static void saveAlias(Connection connection, String previous, Path target) throws SQLException {
        if (previous.equals(target.toString())) return;
        execute(connection, "DELETE FROM managed_aliases WHERE previous=?", target);
        execute(connection, "UPDATE managed_aliases SET current=? WHERE current=?", target, previous);
        execute(connection, """
            INSERT INTO managed_aliases(previous,current) VALUES(?,?)
            ON CONFLICT(previous) DO UPDATE SET current=excluded.current
            """, previous, target);
    }

    private static void execute(Connection connection, String command, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(command)) {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                statement.setObject(i + 1, value instanceof Path path ? path.toString() : value);
            }
            statement.executeUpdate();
        }
    }
}
