package sangitam.desktop.storage;

import sangitam.desktop.model.LibraryCollections;
import sangitam.desktop.model.LibraryCollections.Folder;
import sangitam.desktop.model.LibraryCollections.Key;
import sangitam.desktop.model.LibraryCollections.Kind;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** SQLite operations for virtual folders, playlist membership, and pinned library items. */
public final class CollectionStore {
    private static final String PLAYLISTS = """
        CREATE TABLE IF NOT EXISTS playlists (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE,
            folder_id INTEGER NOT NULL DEFAULT 0, description TEXT NOT NULL DEFAULT '',
            updated_at INTEGER NOT NULL DEFAULT 0, UNIQUE(folder_id, name)
        )
        """;
    private static final String DESCENDANTS = """
        WITH RECURSIVE branch(id) AS (
            SELECT id FROM virtual_folders WHERE id=?
            UNION
            SELECT f.id FROM virtual_folders f JOIN branch ON f.parent_id=branch.id
        ) SELECT id FROM branch
        """;
    private final LibraryStore library;

    public CollectionStore(LibraryStore library) { this.library = library; }

    static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            boolean old = false;
            boolean migrated = false;
            try (ResultSet columns = statement.executeQuery("PRAGMA table_info(playlists)")) {
                while (columns.next()) {
                    old = true;
                    migrated |= "folder_id".equals(columns.getString("name"));
                }
            }
            if (old && !migrated) {
                connection.setAutoCommit(false);
                try {
                    statement.executeUpdate("ALTER TABLE playlists RENAME TO playlists_legacy");
                    statement.executeUpdate(PLAYLISTS);
                    statement.executeUpdate("INSERT INTO playlists(id,name,updated_at) SELECT id,name,id FROM playlists_legacy");
                    statement.executeUpdate("DROP TABLE playlists_legacy");
                    connection.commit();
                } catch (SQLException error) {
                    connection.rollback();
                    throw error;
                } finally {
                    connection.setAutoCommit(true);
                }
            } else statement.executeUpdate(PLAYLISTS);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS virtual_folders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, parent_id INTEGER NOT NULL DEFAULT 0,
                    name TEXT NOT NULL COLLATE NOCASE, updated_at INTEGER NOT NULL, UNIQUE(parent_id,name)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS folder_tracks (
                    folder_id INTEGER NOT NULL, path TEXT NOT NULL, PRIMARY KEY(folder_id,path)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS library_pins (
                    kind TEXT NOT NULL, item TEXT NOT NULL, PRIMARY KEY(kind,item)
                )
                """);
        }
    }

    public LibraryCollections load() throws SQLException {
        List<Folder> folders = new ArrayList<>();
        var pins = new HashSet<Key>();
        try (Connection connection = library.connect();
             Statement query = connection.createStatement();
             PreparedStatement members = connection.prepareStatement(
                "SELECT path FROM folder_tracks WHERE folder_id=? ORDER BY rowid")) {
            try (ResultSet rows = query.executeQuery("SELECT id,parent_id,name,updated_at FROM virtual_folders ORDER BY id")) {
                while (rows.next()) {
                    List<Path> paths = new ArrayList<>();
                    long id = rows.getLong(1);
                    members.setLong(1, id);
                    try (ResultSet tracks = members.executeQuery()) {
                        while (tracks.next()) paths.add(Path.of(tracks.getString(1)));
                    }
                    folders.add(new Folder(id, rows.getLong(2), rows.getString(3), paths, rows.getLong(4)));
                }
            }
            try (ResultSet rows = query.executeQuery("SELECT kind,item FROM library_pins")) {
                while (rows.next()) pins.add(new Key(Kind.valueOf(rows.getString(1)), rows.getString(2)));
            }
        }
        return new LibraryCollections(library.loadPlaylists(), folders, pins);
    }

    public void createFolder(String name, long parent) throws SQLException {
        transact(connection -> {
            requireFolder(connection, parent);
            execute(connection, "INSERT INTO virtual_folders(name,parent_id,updated_at) VALUES (?,?,?)",
                name.strip(), parent, now());
        });
    }

    public void moveFolder(long id, long parent) throws SQLException {
        transact(connection -> {
            requireFolder(connection, id);
            requireFolder(connection, parent);
            if (branch(connection, id).contains(parent)) throw new SQLException("A folder cannot be moved inside itself.");
            execute(connection, "UPDATE virtual_folders SET parent_id=?,updated_at=? WHERE id=?", parent, now(), id);
        });
    }

    public void deleteFolder(long id) throws SQLException {
        transact(connection -> {
            for (long child : branch(connection, id).reversed()) {
                execute(connection, """
                    DELETE FROM library_pins WHERE kind='PLAYLIST' AND item IN
                        (SELECT CAST(id AS TEXT) FROM playlists WHERE folder_id=?)
                    """, child);
                execute(connection, "DELETE FROM playlist_items WHERE playlist_id IN (SELECT id FROM playlists WHERE folder_id=?)",
                    child);
                execute(connection, "DELETE FROM playlists WHERE folder_id=?", child);
                execute(connection, "DELETE FROM folder_tracks WHERE folder_id=?", child);
                execute(connection, "DELETE FROM library_pins WHERE kind='FOLDER' AND item=?", Long.toString(child));
                execute(connection, "DELETE FROM virtual_folders WHERE id=?", child);
            }
        });
    }

    public void addFolderTracks(long id, List<Path> tracks) throws SQLException {
        transact(connection -> {
            requireFolder(connection, id);
            try (PreparedStatement add = connection.prepareStatement("INSERT OR IGNORE INTO folder_tracks VALUES (?,?)")) {
                for (Path path : tracks) {
                    add.setLong(1, id);
                    add.setString(2, path.toString());
                    add.addBatch();
                }
                add.executeBatch();
            }
            execute(connection, "UPDATE virtual_folders SET updated_at=? WHERE id=?", now(), id);
        });
    }

    public void createPlaylist(String name, long folder, List<Path> tracks, boolean mergeExisting) throws SQLException {
        transact(connection -> {
            requireFolder(connection, folder);
            String conflict = mergeExisting ? " ON CONFLICT(folder_id,name) DO NOTHING" : "";
            execute(connection, "INSERT INTO playlists(name,folder_id,updated_at) VALUES (?,?,?)" + conflict,
                name.strip(), folder, now());
            try (PreparedStatement query = connection.prepareStatement("SELECT id FROM playlists WHERE folder_id=? AND name=?")) {
                query.setLong(1, folder);
                query.setString(2, name.strip());
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) throw new SQLException("The playlist could not be created.");
                    append(connection, rows.getLong(1), tracks);
                }
            }
        });
    }

    public void addPlaylistTracks(long id, List<Path> tracks) throws SQLException {
        transact(connection -> append(connection, id, tracks));
    }

    private void append(Connection connection, long id, List<Path> tracks) throws SQLException {
        int position = 0;
        var existing = new HashSet<String>();
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT path,position FROM playlist_items WHERE playlist_id=?")) {
            query.setLong(1, id);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    existing.add(rows.getString(1));
                    position = Math.max(position, rows.getInt(2) + 1);
                }
            }
        }
        if (execute(connection, "UPDATE playlists SET updated_at=? WHERE id=?", now(), id) == 0) {
            throw new SQLException("The playlist no longer exists.");
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO playlist_items VALUES (?,?,?)")) {
            for (Path path : tracks) {
                if (!existing.add(path.toString())) continue;
                insert.setLong(1, id);
                insert.setInt(2, position++);
                insert.setString(3, path.toString());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    public void editPlaylist(long id, String name, String description) throws SQLException {
        transact(connection -> execute(connection, "UPDATE playlists SET name=?,description=?,updated_at=? WHERE id=?",
            name.strip(), description.strip(), now(), id));
    }

    public void movePlaylist(long id, long folder) throws SQLException {
        transact(connection -> {
            requireFolder(connection, folder);
            execute(connection, "UPDATE playlists SET folder_id=?,updated_at=? WHERE id=?", folder, now(), id);
        });
    }

    public void setPinned(Key key, boolean pinned) throws SQLException {
        transact(connection -> execute(connection, pinned ? "INSERT OR IGNORE INTO library_pins VALUES (?,?)"
            : "DELETE FROM library_pins WHERE kind=? AND item=?", key.kind().name(), key.id()));
    }

    private List<Long> branch(Connection connection, long id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(DESCENDANTS)) {
            query.setLong(1, id);
            try (ResultSet rows = query.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rows.next()) ids.add(rows.getLong(1));
                return ids;
            }
        }
    }

    private void requireFolder(Connection connection, long id) throws SQLException {
        if (id == 0) return;
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM virtual_folders WHERE id=?")) {
            query.setLong(1, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("The destination folder no longer exists.");
            }
        }
    }

    @FunctionalInterface
    private interface Transaction { void run(Connection connection) throws SQLException; }

    private void transact(Transaction work) throws SQLException {
        synchronized (library) {
            try (Connection connection = library.connect()) {
                connection.setAutoCommit(false);
                try {
                    work.run(connection);
                    connection.commit();
                } catch (SQLException error) {
                    connection.rollback();
                    throw error;
                }
            }
        }
    }

    private int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            return statement.executeUpdate();
        }
    }

    private long now() { return System.currentTimeMillis(); }
}
