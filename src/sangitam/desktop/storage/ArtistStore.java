package sangitam.desktop.storage;

import sangitam.desktop.model.ArtistCredits;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** User-owned offline biographies/portraits and measured device-local listening seconds. */
public final class ArtistStore {
    public record Profile(String description, byte[] portrait) {
        public Profile { description = description == null ? "" : description; portrait = portrait == null ? null : portrait.clone(); }
        @Override public byte[] portrait() { return portrait == null ? null : portrait.clone(); }
    }
    private final LibraryStore library;
    public ArtistStore(LibraryStore library) { this.library = library; }
    public void initialize() throws SQLException {
        try (var connection = library.connect()) { initialize(connection); }
    }
    static void initialize(Connection connection) throws SQLException {
        try (var sql = connection.createStatement()) {
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS artist_profiles (name_key TEXT PRIMARY KEY, description TEXT NOT NULL, portrait BLOB)");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS listening_seconds (path TEXT PRIMARY KEY, seconds REAL NOT NULL DEFAULT 0)");
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS listening_credits (path TEXT NOT NULL, name_key TEXT NOT NULL, PRIMARY KEY(path,name_key))");
            sql.executeUpdate("CREATE INDEX IF NOT EXISTS listening_by_artist ON listening_credits(name_key,path)");
        }
    }
    public Profile load(String name) throws SQLException {
        try (var connection = library.connect(); var query = connection.prepareStatement("SELECT description,portrait FROM artist_profiles WHERE name_key=?")) {
            query.setString(1, ArtistCredits.key(name));
            try (var row = query.executeQuery()) { return row.next() ? new Profile(row.getString(1), row.getBytes(2)) : new Profile("", null); }
        }
    }
    public void save(String name, Profile profile) throws SQLException {
        if (name.isBlank() || profile.description().length() > 20_000 || (profile.portrait() != null && profile.portrait().length > 2_000_000)) throw new IllegalArgumentException("Artist profile is too large or has no name.");
        try (var connection = library.connect(); var update = connection.prepareStatement("INSERT INTO artist_profiles VALUES(?,?,?) ON CONFLICT(name_key) DO UPDATE SET description=excluded.description,portrait=excluded.portrait")) {
            update.setString(1, ArtistCredits.key(name)); update.setString(2, profile.description()); update.setBytes(3, profile.portrait()); update.executeUpdate();
        }
    }
    public void addListening(Map<Path, Double> seconds) throws SQLException {
        try (var connection = library.connect(); var update = connection.prepareStatement("INSERT INTO listening_seconds VALUES(?,?) ON CONFLICT(path) DO UPDATE SET seconds=seconds+excluded.seconds")) {
            connection.setAutoCommit(false);
            try {
                for (var entry : seconds.entrySet()) {
                    if (!Double.isFinite(entry.getValue()) || entry.getValue() <= 0) continue;
                    update.setString(1, entry.getKey().toString()); update.setDouble(2, entry.getValue()); update.addBatch();
                    updateCredits(connection, entry.getKey());
                }
                update.executeBatch(); TasteStore.record(connection,seconds); connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }
    private void updateCredits(Connection connection, Path path) throws SQLException {
        sangitam.desktop.model.Track track;
        try (var query = connection.prepareStatement("SELECT t.*,m.payload FROM tracks t LEFT JOIN track_metadata m ON t.path=m.path WHERE t.path=?")) {
            query.setString(1, path.toString());
            try (var row = query.executeQuery()) {
                if (!row.next()) return; // retain the historical credit mapping if the track was removed
                track = new sangitam.desktop.model.Track(path, row.getString("title"), row.getString("artist"), row.getString("album"), row.getDouble("duration_seconds"), row.getString("format"), row.getString("genre"), MetadataStore.decode(row.getString("payload")));
            }
        }
        try (var clear = connection.prepareStatement("DELETE FROM listening_credits WHERE path=?"); var insert = connection.prepareStatement("INSERT INTO listening_credits VALUES(?,?)")) {
            clear.setString(1, path.toString()); clear.executeUpdate();
            for (var credit : ArtistCredits.of(track)) { insert.setString(1, path.toString()); insert.setString(2, ArtistCredits.key(credit.name())); insert.addBatch(); } insert.executeBatch();
        }
    }
    public double artistSeconds(String name) throws SQLException {
        try (var connection = library.connect(); var query = connection.prepareStatement("SELECT COALESCE(SUM(s.seconds),0) FROM listening_seconds s JOIN listening_credits c ON s.path=c.path WHERE c.name_key=?")) {
            query.setString(1, ArtistCredits.key(name)); try (var row = query.executeQuery()) { return row.next() ? row.getDouble(1) : 0; }
        }
    }
    public Map<Path, Double> listening() throws SQLException {
        var result = new HashMap<Path, Double>();
        try (var connection = library.connect(); var query = connection.createStatement(); var row = query.executeQuery("SELECT path,seconds FROM listening_seconds")) {
            while (row.next()) result.put(Path.of(row.getString(1)), row.getDouble(2));
        }
        return Map.copyOf(result);
    }
}
