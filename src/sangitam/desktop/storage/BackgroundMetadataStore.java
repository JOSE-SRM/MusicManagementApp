package sangitam.desktop.storage;

import com.google.gson.Gson;
import sangitam.desktop.model.FileStamp;
import sangitam.desktop.model.TrackMetadata.Value;
import sangitam.desktop.model.TrackMetadata.Field;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** Revision-bound metadata overlay. The base tags and manual overrides stay in their original tables. */
public final class BackgroundMetadataStore {
    private static final Gson JSON = new Gson();
    private final LibraryStore library;

    public BackgroundMetadataStore(LibraryStore library) { this.library = library; }

    static void initialize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS background_metadata (
                    path TEXT PRIMARY KEY, metadata_size INTEGER NOT NULL, metadata_modified INTEGER NOT NULL,
                    input TEXT NOT NULL, payload TEXT NOT NULL, sources TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS remove_background_metadata AFTER DELETE ON tracks
                BEGIN DELETE FROM background_metadata WHERE path=OLD.path; END
                """);
        }
    }

    public boolean checked(Path path, FileStamp stamp, String input) throws SQLException {
        try (var connection = library.connect(); var query = connection.prepareStatement("""
            SELECT 1 FROM background_metadata WHERE path=? AND metadata_size=? AND metadata_modified=? AND input=?
            """)) {
            query.setString(1, path.toString());
            query.setLong(2, stamp.size());
            query.setLong(3, stamp.modifiedMillis());
            query.setString(4, input);
            try (var row = query.executeQuery()) { return row.next(); }
        }
    }

    public void save(Path path, FileStamp stamp, String input, Map<Field, Value> fields, String sources) throws SQLException {
        try (var connection = library.connect(); var query = connection.prepareStatement("""
            INSERT INTO background_metadata
            SELECT path, metadata_size, metadata_modified, ?, ?, ? FROM tracks
            WHERE path=? AND metadata_size=? AND metadata_modified=?
            ON CONFLICT(path) DO UPDATE SET metadata_size=excluded.metadata_size,
              metadata_modified=excluded.metadata_modified, input=excluded.input,
              payload=CASE WHEN background_metadata.metadata_size=excluded.metadata_size
                   AND background_metadata.metadata_modified=excluded.metadata_modified
                   AND substr(background_metadata.input,1,instr(background_metadata.input,'|'))
                     =substr(excluded.input,1,instr(excluded.input,'|'))
                   THEN json_patch(background_metadata.payload,excluded.payload) ELSE excluded.payload END,
              sources=CASE WHEN excluded.sources='' THEN background_metadata.sources ELSE excluded.sources END
            """)) {
            query.setString(1, input);
            query.setString(2, JSON.toJson(fields));
            query.setString(3, sources);
            query.setString(4, path.toString());
            query.setLong(5, stamp.size());
            query.setLong(6, stamp.modifiedMillis());
            query.executeUpdate();
        }
    }

    static void restoreFields(Connection connection, Path path, java.util.Set<Field> fields) throws SQLException {
        if (fields.isEmpty()) return;
        try (var query = connection.prepareStatement("UPDATE background_metadata SET payload=json_remove(payload,?) WHERE path=?")) {
            for (Field field : fields) {
                query.setString(1, "$." + field.name());
                query.setString(2, path.toString());
                query.addBatch();
            }
            query.executeBatch();
        }
    }
}
