package sangitam.desktop.storage;

import sangitam.desktop.discovery.RecordingLookup;
import sangitam.desktop.model.DetailReset.Basic;
import sangitam.desktop.model.FileStamp;
import sangitam.desktop.model.RecordingCandidate;
import sangitam.desktop.model.Track;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Stores a reviewed MusicBrainz association separately from embedded tags and manual edits. */
public final class RecordingMatchStore {
    private final LibraryStore library;

    public RecordingMatchStore(LibraryStore library) { this.library = library; }

    public record Saved(UUID id, String title, String credit, String release, String evidence,
                        FileStamp stamp, String query, long acceptedAt, String titleFill, String artistFill,
                        String albumFill) { }
    public record Context(Track track, FileStamp stamp, boolean titleOverride, boolean artistOverride,
                          boolean albumOverride, Saved saved) {
        public boolean missingTitle() { return !titleOverride && RecordingLookup.missingTitle(track.title()); }
        public boolean missingArtist() { return !artistOverride && RecordingLookup.missingLabel(track.artist()); }
        public boolean missingAlbum() { return !albumOverride && RecordingLookup.missingLabel(track.album()); }
    }

    static void initialize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS recording_matches (
                    path TEXT PRIMARY KEY, recording_id TEXT NOT NULL, recording_title TEXT NOT NULL,
                    recording_credit TEXT NOT NULL, release_id TEXT, release_title TEXT, evidence TEXT NOT NULL,
                    metadata_size INTEGER NOT NULL, metadata_modified INTEGER NOT NULL, query TEXT NOT NULL,
                    accepted_at INTEGER NOT NULL,
                    title_base TEXT NOT NULL, artist_base TEXT NOT NULL, album_base TEXT NOT NULL,
                    title_fill TEXT, artist_fill TEXT, album_fill TEXT
                )
                """);
            statement.executeUpdate("""
                CREATE TRIGGER IF NOT EXISTS remove_recording_match AFTER DELETE ON tracks
                BEGIN DELETE FROM recording_matches WHERE path=OLD.path; END
                """);
            statement.executeUpdate("""
                CREATE VIEW IF NOT EXISTS tracks_with_recording_matches AS
                SELECT t.*,
                    CASE WHEN t.title_override=0 AND t.title=r.title_base
                         THEN COALESCE(r.title_fill,t.title) ELSE t.title END AS display_title,
                    CASE WHEN t.artist_override=0 AND t.artist=r.artist_base
                         THEN COALESCE(r.artist_fill,t.artist) ELSE t.artist END AS display_artist,
                    CASE WHEN t.album_override=0 AND t.album=r.album_base
                         THEN COALESCE(r.album_fill,t.album) ELSE t.album END AS display_album,
                    (r.title_fill IS NOT NULL OR r.artist_fill IS NOT NULL OR r.album_fill IS NOT NULL) AS enriched
                FROM tracks t LEFT JOIN recording_matches r
                  ON r.path=t.path AND r.metadata_size=t.metadata_size AND r.metadata_modified=t.metadata_modified
                """);
        }
    }

    public Context load(Path path) throws Exception {
        library.initialize();
        try (var connection = library.connect()) { return read(connection, path); }
    }

    private static Context read(Connection connection, Path path) throws SQLException {
        try (var query = connection.prepareStatement("""
            SELECT t.*, m.payload, r.recording_id, r.recording_title, r.recording_credit, r.release_title, r.evidence,
                   r.metadata_size AS match_size, r.metadata_modified AS match_modified, r.query, r.accepted_at,
                   r.title_fill, r.artist_fill, r.album_fill
            FROM tracks t LEFT JOIN track_metadata m USING(path) LEFT JOIN recording_matches r USING(path)
            WHERE t.path=?
            """)) {
            query.setString(1, path.toString());
            try (var row = query.executeQuery()) {
                if (!row.next()) return null;
                Track track = new Track(path, row.getString("title"), row.getString("artist"), row.getString("album"),
                    row.getDouble("duration_seconds"), row.getString("format"), row.getString("genre"),
                    MetadataStore.decode(row.getString("payload")));
                String id = row.getString("recording_id");
                var saved = id == null ? null : new Saved(UUID.fromString(id), row.getString("recording_title"),
                    row.getString("recording_credit"), row.getString("release_title"), row.getString("evidence"),
                    new FileStamp(row.getLong("match_size"), row.getLong("match_modified")),
                    row.getString("query"), row.getLong("accepted_at"), row.getString("title_fill"),
                    row.getString("artist_fill"), row.getString("album_fill"));
                return new Context(track, new FileStamp(row.getLong("metadata_size"), row.getLong("metadata_modified")),
                    row.getBoolean("title_override"), row.getBoolean("artist_override"), row.getBoolean("album_override"), saved);
            }
        }
    }

    public void save(Context expected, RecordingCandidate recording, RecordingCandidate.Release release,
                     boolean fillMissing, String query) throws Exception {
        save(expected, recording, release, fillMissing, query, false);
    }

    public void saveFingerprintMatch(Context expected, RecordingCandidate recording) throws Exception {
        save(expected, recording, null, true, "AcoustID fingerprint", true);
    }

    private void save(Context expected, RecordingCandidate recording, RecordingCandidate.Release release,
                      boolean fillMissing, String query, boolean fingerprint) throws Exception {
        if (release != null && !recording.releases().contains(release))
            throw new IllegalArgumentException("Select a release that contains this recording.");
        Track track = expected.track();
        var attributes = Files.readAttributes(track.path(), BasicFileAttributes.class);
        if (!attributes.isRegularFile() || !expected.stamp().equals(new FileStamp(
                attributes.size(), attributes.lastModifiedTime().toMillis())))
            throw new IOException("The audio file changed. Rescan it before linking a recording.");
        var evidence = RecordingLookup.compare(track, recording);
        boolean fill = fillMissing && (evidence.corroborated() || fingerprint);
        try (var connection = library.connect()) {
            connection.setAutoCommit(false);
            requireCurrent(connection, expected);
            try (var save = connection.prepareStatement("""
                INSERT OR REPLACE INTO recording_matches VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
                save.setString(1, track.path().toString());
                save.setString(2, recording.id().toString());
                save.setString(3, recording.title());
                save.setString(4, recording.credit());
                save.setString(5, release == null ? null : release.id().toString());
                save.setString(6, release == null ? null : release.title());
                save.setString(7, fingerprint ? "Corroborated acoustic fingerprint from AcoustID" : evidence.summary());
                save.setLong(8, expected.stamp().size());
                save.setLong(9, expected.stamp().modifiedMillis());
                save.setString(10, query);
                save.setLong(11, System.currentTimeMillis());
                save.setString(12, track.title());
                save.setString(13, track.artist());
                save.setString(14, track.album());
                save.setString(15, fill && expected.missingTitle() ? recording.title() : null);
                save.setString(16, fill && expected.missingArtist() && !recording.credit().isBlank() ? recording.credit() : null);
                save.setString(17, fill && expected.missingAlbum() && release != null ? release.title() : null);
                save.executeUpdate();
            }
            connection.commit();
        }
    }

    public void remove(Context expected) throws SQLException {
        try (var connection = library.connect()) {
            connection.setAutoCommit(false);
            requireCurrent(connection, expected);
            try (var delete = connection.prepareStatement("DELETE FROM recording_matches WHERE path=?")) {
                delete.setString(1, expected.track().path().toString());
                delete.executeUpdate();
            }
            try (var delete = connection.prepareStatement("DELETE FROM background_metadata WHERE path=?")) {
                delete.setString(1, expected.track().path().toString());
                delete.executeUpdate();
            }
            connection.commit();
        }
    }

    static void restoreFileLabels(Connection connection, Path path, java.util.Set<Basic> fields) throws SQLException {
        if (!fields.contains(Basic.TITLE) && !fields.contains(Basic.ARTIST) && !fields.contains(Basic.ALBUM)) return;
        try (var update = connection.prepareStatement("""
            UPDATE recording_matches SET title_fill=CASE WHEN ? THEN NULL ELSE title_fill END,
                   artist_fill=CASE WHEN ? THEN NULL ELSE artist_fill END,
                   album_fill=CASE WHEN ? THEN NULL ELSE album_fill END WHERE path=?
            """)) {
            update.setBoolean(1, fields.contains(Basic.TITLE));
            update.setBoolean(2, fields.contains(Basic.ARTIST));
            update.setBoolean(3, fields.contains(Basic.ALBUM));
            update.setString(4, path.toString());
            update.executeUpdate();
        }
    }

    private static void requireCurrent(Connection connection, Context expected) throws SQLException {
        if (!Objects.equals(read(connection, expected.track().path()), expected))
            throw new SQLException("Track details changed while reviewing. Reopen the recording lookup.");
    }
}
