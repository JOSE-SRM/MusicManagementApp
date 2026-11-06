package sangitam.desktop.storage;

import sangitam.desktop.model.Track;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** A bounded local-history query. Imports alone never count as recent listening. */
public final class LibraryDashboardStore {
    private final LibraryStore library;
    public LibraryDashboardStore(LibraryStore library) { this.library = library; }
    public List<Track> recent(int limit) throws SQLException {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Recent tracks limit must be within 1–50.");
        try (var connection = library.connect(); var query = connection.prepareStatement("""
            SELECT t.*,m.payload FROM tracks t LEFT JOIN track_metadata m ON m.path=t.path
            WHERE t.last_played_at>0 AND t.unavailable=0 ORDER BY t.last_played_at DESC,t.path LIMIT ?
            """)) {
            query.setInt(1, limit); var result = new ArrayList<Track>();
            try (var row = query.executeQuery()) {
                while (row.next()) result.add(new Track(Path.of(row.getString("path")), row.getString("title"), row.getString("artist"),
                    row.getString("album"), row.getDouble("duration_seconds"), row.getString("format"), row.getString("genre"), MetadataStore.decode(row.getString("payload"))));
            }
            return List.copyOf(result);
        }
    }
}
