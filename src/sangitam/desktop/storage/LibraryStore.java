package sangitam.desktop.storage;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;
import sangitam.desktop.history.ListenEvent;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Small SQLite boundary: UI panels never know SQL or the database location. */
public final class LibraryStore {
    public record CatalogEntry(Track track, TrackTags tags, int playCount) { }
    private final Path database;

    public LibraryStore(Path database) { this.database = database.toAbsolutePath().normalize(); }

    public void initialize() throws Exception {
        Files.createDirectories(database.getParent());
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS tracks (
                    path TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    duration_seconds REAL NOT NULL,
                    format TEXT NOT NULL,
                    genre TEXT NOT NULL DEFAULT 'Unknown genre',
                    energy INTEGER,
                    activity INTEGER,
                    analyzed INTEGER NOT NULL DEFAULT 0,
                    file_size INTEGER NOT NULL DEFAULT -1,
                    file_modified INTEGER NOT NULL DEFAULT -1,
                    play_count INTEGER NOT NULL DEFAULT 0
                )
                """);
            // Existing users already have the six-column table from stage one.
            Set<String> columns = new HashSet<>();
            try (ResultSet rows = statement.executeQuery("PRAGMA table_info(tracks)")) {
                while (rows.next()) columns.add(rows.getString("name"));
            }
            addColumn(statement, columns, "genre", "TEXT NOT NULL DEFAULT 'Unknown genre'");
            addColumn(statement, columns, "energy", "INTEGER");
            addColumn(statement, columns, "activity", "INTEGER");
            addColumn(statement, columns, "analyzed", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, columns, "file_size", "INTEGER NOT NULL DEFAULT -1");
            addColumn(statement, columns, "file_modified", "INTEGER NOT NULL DEFAULT -1");
            addColumn(statement, columns, "play_count", "INTEGER NOT NULL DEFAULT 0");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS listens (
                    source TEXT NOT NULL,
                    source_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    played_at_ms INTEGER NOT NULL,
                    link TEXT,
                    local_path TEXT,
                    PRIMARY KEY (source, source_key)
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS listens_time ON listens(played_at_ms)");
        }
    }

    private void addColumn(Statement statement, Set<String> columns, String name, String definition)
        throws SQLException {
        if (!columns.contains(name)) statement.executeUpdate("ALTER TABLE tracks ADD COLUMN " + name + " " + definition);
    }

    public List<Track> loadAll() throws SQLException {
        return loadCatalog().stream().map(CatalogEntry::track).toList();
    }

    /** Loads metadata, measured tags, and familiarity together for session planning. */
    public List<CatalogEntry> loadCatalog() throws SQLException {
        List<CatalogEntry> tracks = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement query = connection.prepareStatement(
                 "SELECT path, title, artist, album, duration_seconds, format, genre, " +
                 "energy, activity, analyzed, file_size, file_modified, play_count FROM tracks " +
                 "ORDER BY title COLLATE NOCASE, artist COLLATE NOCASE");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                Track track = new Track(Path.of(rows.getString(1)), rows.getString(2), rows.getString(3),
                    rows.getString(4), rows.getDouble(5), rows.getString(6), rows.getString(7));
                TrackTags tags = new TrackTags(rows.getInt(8), rows.getInt(9), rows.getInt(10) == 1,
                    rows.getLong(11), rows.getLong(12));
                tracks.add(new CatalogEntry(track, tags, rows.getInt(13)));
            }
        }
        return tracks;
    }

    public void saveAll(List<Track> tracks) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO tracks(path, title, artist, album, duration_seconds, format, genre)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET
                    title=excluded.title, artist=excluded.artist, album=excluded.album,
                    duration_seconds=excluded.duration_seconds, format=excluded.format,
                    genre=excluded.genre
                """)) {
                for (Track track : tracks) {
                    upsert.setString(1, track.path().toString());
                    upsert.setString(2, track.title());
                    upsert.setString(3, track.artist());
                    upsert.setString(4, track.album());
                    upsert.setDouble(5, track.durationSeconds());
                    upsert.setString(6, track.format());
                    upsert.setString(7, track.genre());
                    upsert.addBatch();
                }
                upsert.executeBatch();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public void saveTags(Path path, TrackTags tags) throws SQLException {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
            "UPDATE tracks SET energy=?, activity=?, analyzed=?, file_size=?, file_modified=? WHERE path=?")) {
            update.setInt(1, tags.energy());
            update.setInt(2, tags.activity());
            update.setInt(3, tags.analyzed() ? 1 : 0);
            update.setLong(4, tags.fileSize());
            update.setLong(5, tags.modifiedMillis());
            update.setString(6, path.toString());
            update.executeUpdate();
        }
    }

    public void recordPlay(Path path) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lookup = connection.prepareStatement(
                    "SELECT title, artist, album FROM tracks WHERE path=?");
                 PreparedStatement update = connection.prepareStatement(
                    "UPDATE tracks SET play_count=play_count+1 WHERE path=?");
                 PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO listens(source, source_key, title, artist, album, played_at_ms, link, local_path)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                lookup.setString(1, path.toString());
                try (ResultSet row = lookup.executeQuery()) {
                    if (!row.next()) { connection.rollback(); return; }
                    insert.setString(1, "Sangitam");
                    insert.setString(2, UUID.randomUUID().toString());
                    insert.setString(3, row.getString(1));
                    insert.setString(4, row.getString(2));
                    insert.setString(5, row.getString(3));
                }
                update.setString(1, path.toString());
                update.executeUpdate();
                insert.setLong(6, System.currentTimeMillis());
                insert.setString(7, null);
                insert.setString(8, path.toString());
                insert.executeUpdate();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    /** Provider syncs can safely overlap: repeated source keys are ignored. */
    public int saveListens(List<ListenEvent> listens) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO listens(source, source_key, title, artist, album, played_at_ms, link, local_path)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                for (ListenEvent listen : listens) {
                    insert.setString(1, listen.source());
                    insert.setString(2, listen.sourceKey());
                    insert.setString(3, listen.title());
                    insert.setString(4, listen.artist());
                    insert.setString(5, listen.album());
                    insert.setLong(6, listen.playedAtMillis());
                    insert.setString(7, listen.link() == null ? null : listen.link().toString());
                    insert.setString(8, listen.localPath() == null ? null : listen.localPath().toString());
                    insert.addBatch();
                }
                int saved = 0;
                for (int result : insert.executeBatch()) if (result > 0) saved += result;
                connection.commit();
                return saved;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public List<ListenEvent> loadListens() throws SQLException {
        List<ListenEvent> listens = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                 "SELECT source, source_key, title, artist, album, played_at_ms, link, local_path " +
                 "FROM listens ORDER BY played_at_ms DESC");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String link = rows.getString(7);
                String local = rows.getString(8);
                listens.add(new ListenEvent(rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getString(4), rows.getString(5), rows.getLong(6),
                    link == null ? null : URI.create(link), local == null ? null : Path.of(local)));
            }
        }
        return listens;
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }
}
