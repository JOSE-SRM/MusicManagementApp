package sangitam.desktop.storage;

import sangitam.desktop.model.Track;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Small SQLite boundary: UI panels never know SQL or the database location. */
public final class LibraryStore {
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
                    format TEXT NOT NULL
                )
                """);
        }
    }

    public List<Track> loadAll() throws SQLException {
        List<Track> tracks = new ArrayList<>();
        try (Connection connection = connect();
             PreparedStatement query = connection.prepareStatement(
                 "SELECT path, title, artist, album, duration_seconds, format FROM tracks " +
                 "ORDER BY title COLLATE NOCASE, artist COLLATE NOCASE");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                tracks.add(new Track(Path.of(rows.getString(1)), rows.getString(2), rows.getString(3),
                    rows.getString(4), rows.getDouble(5), rows.getString(6)));
            }
        }
        return tracks;
    }

    public void saveAll(List<Track> tracks) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO tracks(path, title, artist, album, duration_seconds, format)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET
                    title=excluded.title, artist=excluded.artist, album=excluded.album,
                    duration_seconds=excluded.duration_seconds, format=excluded.format
                """)) {
                for (Track track : tracks) {
                    upsert.setString(1, track.path().toString());
                    upsert.setString(2, track.title());
                    upsert.setString(3, track.artist());
                    upsert.setString(4, track.album());
                    upsert.setDouble(5, track.durationSeconds());
                    upsert.setString(6, track.format());
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

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
}
