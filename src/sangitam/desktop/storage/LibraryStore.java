package sangitam.desktop.storage;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;
import sangitam.desktop.model.TrackMetadata;
import sangitam.desktop.model.DetailReset;
import sangitam.desktop.model.FileStamp;
import sangitam.desktop.model.LibrarySource;
import sangitam.desktop.model.Playlist;
import sangitam.desktop.model.PlaybackMemory;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Small SQLite boundary: UI panels never know SQL or the database location. */
public final class LibraryStore {
    private static final String SAVE_TAGS = """
        UPDATE tracks SET energy=?, activity=?, analyzed=?, file_size=?, file_modified=?, analysis_version=2
        WHERE path=? AND tags_override=0 AND (metadata_size<0 OR (metadata_size=? AND metadata_modified=?))
        """;
    public record CatalogEntry(Track track, TrackTags tags, int playCount, boolean manualTags,
                               FileStamp stamp, boolean unavailable, long recentAt, boolean titleOverride,
                               boolean artistOverride) { }
    private final Path database;
    private boolean initialized;

    public LibraryStore(Path database) { this.database = database.toAbsolutePath().normalize(); }

    public synchronized void initialize() throws Exception {
        if (initialized) return;
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
            addColumn(statement, columns, "genre_override", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, columns, "tags_override", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, columns, "analysis_version", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, columns, "metadata_size", "INTEGER NOT NULL DEFAULT -1");
            addColumn(statement, columns, "metadata_modified", "INTEGER NOT NULL DEFAULT -1");
            addColumn(statement, columns, "unavailable", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, columns, "title_override", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, columns, "artist_override", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, columns, "album_override", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, columns, "added_at", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, columns, "last_played_at", "INTEGER NOT NULL DEFAULT 0");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS library_sources (
                    path TEXT PRIMARY KEY,
                    recursive INTEGER NOT NULL
                )
                """);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS app_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            if (!columns.contains("added_at")) statement.executeUpdate("UPDATE tracks SET added_at=rowid");
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS playlist_items (
                    playlist_id INTEGER NOT NULL,
                    position INTEGER NOT NULL,
                    path TEXT NOT NULL,
                    PRIMARY KEY (playlist_id, position)
                )
                """);
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
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS listens_local_time ON listens(local_path,played_at_ms)");
            if (!columns.contains("last_played_at")) statement.executeUpdate("""
                UPDATE tracks SET last_played_at=COALESCE(
                    (SELECT MAX(played_at_ms) FROM listens WHERE local_path=tracks.path),0)
                """);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS library_exclusions (path TEXT PRIMARY KEY)");
            CollectionStore.initialize(connection);
            MetadataStore.initialize(connection);
            TasteStore.initialize(connection);
            AudioFeatureStore.initialize(connection);
            ArtistStore.initialize(connection);
            ArtistEnrichmentStore.initialize(connection);
            SmartPlaylistStore.initialize(connection);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS tracks_title ON tracks(title COLLATE NOCASE,artist COLLATE NOCASE,path)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS tracks_recent ON tracks(MAX(added_at,last_played_at) DESC,path)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS tracks_plays ON tracks(play_count DESC,path)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS tracks_last_played ON tracks(last_played_at DESC,path)");
        }
        initialized = true;
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
        Map<String,String> aliases = new ArtistEnrichmentStore(this).aliases();
        try (Connection connection = connect();
             PreparedStatement query = connection.prepareStatement(
                 "SELECT path, title, artist, album, duration_seconds, format, genre, " +
                 "energy, activity, analyzed, file_size, file_modified, play_count, tags_override, analysis_version, " +
                 "metadata_size, metadata_modified, unavailable, MAX(added_at,last_played_at), title_override, " +
                 "artist_override, m.payload, f.payload FROM tracks LEFT JOIN track_metadata m USING(path) LEFT JOIN audio_features f USING(path) " +
                 "ORDER BY title COLLATE NOCASE, artist COLLATE NOCASE");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                Track track = new Track(Path.of(rows.getString(1)), rows.getString(2), rows.getString(3),
                    rows.getString(4), rows.getDouble(5), rows.getString(6), rows.getString(7), MetadataStore.decode(rows.getString(22)));
                track = ArtistEnrichmentStore.canonical(track,aliases);
                boolean manual = rows.getInt(14) == 1;
                boolean oldAnalysis = !manual && rows.getInt(15) < 2;
                TrackTags tags = oldAnalysis ? TrackTags.pending()
                    : new TrackTags(rows.getInt(8), rows.getInt(9), rows.getInt(10) == 1,
                        rows.getLong(11), rows.getLong(12), AudioFeatureStore.decode(rows.getString(23)));
                FileStamp stamp = new FileStamp(rows.getLong(16), rows.getLong(17));
                tracks.add(new CatalogEntry(track, tags, rows.getInt(13), manual, stamp, rows.getInt(18) == 1,
                    rows.getLong(19), rows.getBoolean(20), rows.getBoolean(21)));
            }
        }
        return tracks;
    }

    public void saveAll(List<Track> tracks) throws SQLException {
        saveScan(tracks, Map.of(), Set.of(), List.of(), Map.of());
    }

    public List<LibrarySource> loadSources() throws SQLException {
        List<LibrarySource> sources = new ArrayList<>();
        try (Connection connection = connect(); Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT path, recursive FROM library_sources ORDER BY path")) {
            while (rows.next()) sources.add(new LibrarySource(Path.of(rows.getString(1)), rows.getBoolean(2)));
        }
        return sources;
    }

    /** Saves metadata, source choices and availability together. Missing files keep their indexed rows. */
    public void saveScan(List<Track> changed, Map<Path, FileStamp> available, Set<Path> unavailable,
                         List<LibrarySource> sources, Map<Path, TrackTags> measured) throws SQLException {
        saveScan(changed, available, unavailable, sources, measured, List.of());
    }

    public void saveScan(List<Track> changed, Map<Path, FileStamp> available, Set<Path> unavailable,
                         List<LibrarySource> sources, Map<Path, TrackTags> measured,
                         List<Path> restoredPaths) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            var aliases = ArtistEnrichmentStore.aliases(connection);
            List<Track> normalized = changed.stream().map(track -> ArtistEnrichmentStore.canonical(track,aliases)).toList();
            try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO tracks(path, title, artist, album, duration_seconds, format, genre,
                    metadata_size, metadata_modified, added_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(path) DO UPDATE SET
                    title=CASE WHEN tracks.title_override=1 THEN tracks.title ELSE excluded.title END,
                    artist=CASE WHEN tracks.artist_override=1 THEN tracks.artist ELSE excluded.artist END,
                    album=CASE WHEN tracks.album_override=1 THEN tracks.album ELSE excluded.album END,
                    duration_seconds=excluded.duration_seconds, format=excluded.format,
                    metadata_size=excluded.metadata_size, metadata_modified=excluded.metadata_modified, unavailable=0,
                    genre=CASE WHEN tracks.genre_override=1 THEN tracks.genre ELSE excluded.genre END
                """);
                 PreparedStatement saveSource = connection.prepareStatement("""
                    INSERT INTO library_sources(path, recursive) VALUES (?, ?)
                    ON CONFLICT(path) DO UPDATE SET recursive=excluded.recursive
                    """);
                 PreparedStatement online = connection.prepareStatement(
                    "UPDATE tracks SET unavailable=0 WHERE path=? AND unavailable=1");
                 PreparedStatement offline = connection.prepareStatement(
                    "UPDATE tracks SET unavailable=1 WHERE path=? AND unavailable=0");
                 PreparedStatement saveTags = connection.prepareStatement(SAVE_TAGS);
                 PreparedStatement restore = connection.prepareStatement("DELETE FROM library_exclusions WHERE path=?")) {
                long addedAt = System.currentTimeMillis();
                for (Track track : normalized) {
                    FileStamp stamp = available.get(track.path());
                    upsert.setString(1, track.path().toString());
                    upsert.setString(2, track.title());
                    upsert.setString(3, track.artist());
                    upsert.setString(4, track.album());
                    upsert.setDouble(5, track.durationSeconds());
                    upsert.setString(6, track.format());
                    upsert.setString(7, track.genre());
                    upsert.setLong(8, stamp == null ? -1 : stamp.size());
                    upsert.setLong(9, stamp == null ? -1 : stamp.modifiedMillis());
                    upsert.setLong(10, addedAt);
                    upsert.addBatch();
                }
                upsert.executeBatch();
                for (Track track : normalized) MetadataStore.scan(connection, track);
                for (var entry : measured.entrySet()) AudioFeatureStore.save(connection,entry.getKey(),entry.getValue());
                for (LibrarySource source : sources) {
                    saveSource.setString(1, source.path().toString());
                    saveSource.setBoolean(2, source.recursive());
                    saveSource.addBatch();
                }
                saveSource.executeBatch();
                updateAvailability(online, available.keySet());
                updateAvailability(offline, unavailable);
                for (var entry : measured.entrySet()) {
                    bindTags(saveTags, entry.getKey(), entry.getValue());
                    saveTags.addBatch();
                }
                saveTags.executeBatch();
                for (Path path : restoredPaths) {
                    restore.setString(1, path.toString());
                    restore.addBatch();
                }
                restore.executeBatch();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public record DetailChanges(String title, String artist, String album, String genre, Integer energy, Integer movement,
                                Map<TrackMetadata.Field, String> metadata, Set<String> userTags) {
        public DetailChanges {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            userTags = userTags == null ? null : TrackMetadata.normalizeTags(userTags);
        }
        public DetailChanges(String title, String artist, String album, String genre, Integer energy, Integer movement) {
            this(title, artist, album, genre, energy, movement, Map.of(), null);
        }
    }

    public void updateDetails(List<Path> paths, DetailChanges edit) throws SQLException {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement("""
            UPDATE tracks SET title=COALESCE(?1,title), title_override=MAX(title_override,?1 IS NOT NULL),
                artist=COALESCE(?2,artist), artist_override=MAX(artist_override,?2 IS NOT NULL),
                album=COALESCE(?3,album), album_override=MAX(album_override,?3 IS NOT NULL),
                genre=COALESCE(?4,genre), genre_override=MAX(genre_override,?4 IS NOT NULL),
                energy=COALESCE(?5,energy), activity=COALESCE(?6,activity),
                tags_override=MAX(tags_override,?5 IS NOT NULL), analyzed=MAX(analyzed,?5 IS NOT NULL)
            WHERE path=?7
            """)) {
            connection.setAutoCommit(false);
            var aliases = ArtistEnrichmentStore.aliases(connection);
            update.setString(1, edit.title());
            update.setString(2, edit.artist() == null ? null : ArtistEnrichmentStore.canonical(edit.artist(), aliases));
            update.setString(3, edit.album());
            update.setString(4, edit.genre());
            update.setObject(5, edit.energy());
            update.setObject(6, edit.movement());
            try {
                for (Path path : paths) {
                    update.setString(7, path.toString());
                    update.addBatch();
                }
                update.executeBatch();
                for (Path path : paths) {
                    MetadataStore.edit(connection, path, edit.metadata(), edit.userTags());
                    MetadataStore.canonicalize(connection, path, aliases);
                }
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    public List<Path> loadExclusions() throws SQLException {
        List<Path> exclusions = new ArrayList<>();
        try (Connection connection = connect(); Statement query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT path FROM library_exclusions")) {
            while (rows.next()) exclusions.add(Path.of(rows.getString(1)));
        }
        return exclusions;
    }

    /** Restores only successfully read, still-indexed files. Other overrides and mood ratings stay intact. */
    public int restoreDetails(List<Track> source, DetailReset reset) throws SQLException {
        if (reset.empty()) return 0;
        int restored = 0;
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement("""
            UPDATE tracks SET
                title=CASE WHEN ?1 THEN ?2 ELSE title END, title_override=CASE WHEN ?1 THEN 0 ELSE title_override END,
                artist=CASE WHEN ?3 THEN ?4 ELSE artist END, artist_override=CASE WHEN ?3 THEN 0 ELSE artist_override END,
                album=CASE WHEN ?5 THEN ?6 ELSE album END, album_override=CASE WHEN ?5 THEN 0 ELSE album_override END,
                genre=CASE WHEN ?7 THEN ?8 ELSE genre END, genre_override=CASE WHEN ?7 THEN 0 ELSE genre_override END,
                unavailable=0 WHERE path=?9
            """)) {
            connection.setAutoCommit(false);
            try {
                var aliases = ArtistEnrichmentStore.aliases(connection);
                for (Track raw : source) {
                    Track track = ArtistEnrichmentStore.canonical(raw, aliases);
                    update.setBoolean(1, reset.basic().contains(DetailReset.Basic.TITLE)); update.setString(2, track.title());
                    update.setBoolean(3, reset.basic().contains(DetailReset.Basic.ARTIST)); update.setString(4, track.artist());
                    update.setBoolean(5, reset.basic().contains(DetailReset.Basic.ALBUM)); update.setString(6, track.album());
                    update.setBoolean(7, reset.basic().contains(DetailReset.Basic.GENRE)); update.setString(8, track.genre());
                    update.setString(9, track.path().toString());
                    if (update.executeUpdate() == 0) continue;
                    MetadataStore.restore(connection, track, reset); restored++;
                }
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
        return restored;
    }

    /** Removes catalog entries only. The paths in playlists and listening history survive. */
    public void removeFromLibrary(List<Path> paths, Path scope) throws SQLException {
        try (Connection connection = connect(); PreparedStatement remove = connection.prepareStatement(
                 "DELETE FROM tracks WHERE path=?");
             PreparedStatement exclude = connection.prepareStatement("INSERT OR IGNORE INTO library_exclusions VALUES (?)")) {
            connection.setAutoCommit(false);
            try {
                for (Path path : paths) {
                    remove.setString(1, path.toString());
                    remove.addBatch();
                    if (scope == null) {
                        exclude.setString(1, path.toString());
                        exclude.addBatch();
                    }
                }
                if (scope != null) {
                    exclude.setString(1, scope.toString());
                    exclude.addBatch();
                }
                remove.executeBatch();
                exclude.executeBatch();
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    private void updateAvailability(PreparedStatement update, Set<Path> paths) throws SQLException {
        for (Path path : paths) {
            update.setString(1, path.toString());
            update.addBatch();
        }
        update.executeBatch();
    }

    public void saveTags(Path path, TrackTags tags) throws SQLException {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(SAVE_TAGS)) {
            connection.setAutoCommit(false);
            try { bindTags(update,path,tags); update.executeUpdate(); AudioFeatureStore.save(connection,path,tags); connection.commit(); }
            catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    private void bindTags(PreparedStatement update, Path path, TrackTags tags) throws SQLException {
        update.setInt(1, tags.energy());
        update.setInt(2, tags.activity());
        update.setInt(3, tags.analyzed() ? 1 : 0);
        update.setLong(4, tags.fileSize());
        update.setLong(5, tags.modifiedMillis());
        update.setString(6, path.toString());
        update.setLong(7, tags.fileSize());
        update.setLong(8, tags.modifiedMillis());
    }

    /** Manual corrections take priority over future scans of the original file. */
    public void updateTags(Path path, String genre, int energy, int activity, long size, long modified)
        throws SQLException {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement("""
            UPDATE tracks SET genre=?, genre_override=1, energy=?, activity=?, analyzed=1,
                file_size=?, file_modified=?, tags_override=1, analysis_version=2 WHERE path=?
            """)) {
            connection.setAutoCommit(false);
            update.setString(1, genre);
            update.setInt(2, energy);
            update.setInt(3, activity);
            update.setLong(4, size);
            update.setLong(5, modified);
            update.setString(6, path.toString());
            try {
                update.executeUpdate();
                MetadataStore.edit(connection, path, Map.of(), null);
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    public void savePlaylist(String name, List<Path> paths) throws SQLException {
        if (name == null || name.isBlank() || paths.isEmpty())
            throw new IllegalArgumentException("A playlist needs a name and at least one track");
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO playlists(name,updated_at) VALUES (?,?) " +
                    "ON CONFLICT(folder_id,name) DO UPDATE SET updated_at=excluded.updated_at");
                 PreparedStatement idQuery = connection.prepareStatement("SELECT id FROM playlists WHERE folder_id=0 AND name=?");
                 PreparedStatement clear = connection.prepareStatement("DELETE FROM playlist_items WHERE playlist_id=?");
                 PreparedStatement add = connection.prepareStatement(
                    "INSERT INTO playlist_items(playlist_id, position, path) VALUES (?, ?, ?)")) {
                upsert.setString(1, name.strip());
                upsert.setLong(2, System.currentTimeMillis());
                upsert.executeUpdate();
                idQuery.setString(1, name.strip());
                long id;
                try (ResultSet row = idQuery.executeQuery()) {
                    if (!row.next()) throw new SQLException("Could not find the playlist");
                    id = row.getLong(1);
                }
                SmartPlaylistStore.requireManual(connection, id);
                clear.setLong(1, id);
                clear.executeUpdate();
                for (int i = 0; i < paths.size(); i++) {
                    add.setLong(1, id);
                    add.setInt(2, i);
                    add.setString(3, paths.get(i).toString());
                    add.addBatch();
                }
                add.executeBatch();
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    public List<Playlist> loadPlaylists() throws SQLException {
        List<Playlist> result = new ArrayList<>();
        try (Connection connection = connect()) {
            SmartPlaylistStore.prepare(connection);
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement(
                "SELECT p.id, p.name, p.folder_id, p.description, p.updated_at,r.rule_json FROM playlists p " +
                "LEFT JOIN smart_playlist_rules r ON p.id=r.playlist_id ORDER BY p.name COLLATE NOCASE,p.id");
                 PreparedStatement items = connection.prepareStatement(
                    "SELECT path FROM playlist_items WHERE playlist_id=? ORDER BY position");
                 ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    var rule = SmartPlaylistStore.decode(rows.getString(6));
                    List<Path> paths;
                    if (rule != null) paths = SmartPlaylistStore.paths(connection, rule);
                    else {
                        paths = new ArrayList<>();
                        items.setLong(1, id);
                        try (ResultSet files = items.executeQuery()) {
                            while (files.next()) paths.add(Path.of(files.getString(1)));
                        }
                    }
                    result.add(new Playlist(id, rows.getString(2), paths, rows.getLong(3), rows.getString(4), rows.getLong(5), rule));
                }
            }
            connection.commit();
        }
        return result;
    }

    public void deletePlaylist(long id) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement items = connection.prepareStatement("DELETE FROM playlist_items WHERE playlist_id=?");
                 PreparedStatement playlist = connection.prepareStatement("DELETE FROM playlists WHERE id=?");
                 PreparedStatement pin = connection.prepareStatement(
                     "DELETE FROM library_pins WHERE kind='PLAYLIST' AND item=?")) {
                items.setLong(1, id); items.executeUpdate();
                playlist.setLong(1, id); playlist.executeUpdate();
                pin.setString(1, Long.toString(id)); pin.executeUpdate();
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    public void savePlaybackMemory(PlaybackMemory memory) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO app_state(key,value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                upsert.setString(1, "last_track"); upsert.setString(2, memory.path().toString()); upsert.addBatch();
                upsert.setString(1, "last_position");
                upsert.setString(2, Double.toString(Math.max(0, memory.positionSeconds()))); upsert.addBatch();
                upsert.executeBatch();
                connection.commit();
            } catch (SQLException error) { connection.rollback(); throw error; }
        }
    }

    public PlaybackMemory loadPlaybackMemory() throws SQLException {
        String path = null;
        double position = 0;
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
                "SELECT key, value FROM app_state WHERE key IN ('last_track','last_position')");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                if (rows.getString(1).equals("last_track")) path = rows.getString(2);
                else try { position = Double.parseDouble(rows.getString(2)); }
                catch (NumberFormatException ignored) { position = 0; }
            }
        }
        return path == null ? null : new PlaybackMemory(Path.of(path), position);
    }

    public double loadVolume() throws SQLException {
        try (var connection = connect(); var query = connection.createStatement(); var row = query.executeQuery("SELECT value FROM app_state WHERE key='volume'")) {
            if (row.next()) try { return sangitam.desktop.playback.AudioGain.clamp(Double.parseDouble(row.getString(1))); } catch (NumberFormatException ignored) { }
            return .72;
        }
    }
    public void saveVolume(double volume) throws SQLException {
        try (var connection = connect(); var query = connection.prepareStatement("INSERT INTO app_state VALUES('volume',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            query.setString(1, Double.toString(sangitam.desktop.playback.AudioGain.clamp(volume))); query.executeUpdate();
        }
    }

    public record PlayUpdate(int plays, long recentAt) { }

    public PlayUpdate recordPlay(Path path) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lookup = connection.prepareStatement(
                    "SELECT title, artist, album, play_count, added_at FROM tracks WHERE path=?");
                 PreparedStatement update = connection.prepareStatement(
                    "UPDATE tracks SET play_count=play_count+1,last_played_at=? WHERE path=?");
                 PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO listens(source, source_key, title, artist, album, played_at_ms, link, local_path)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                lookup.setString(1, path.toString());
                long now = System.currentTimeMillis();
                PlayUpdate result;
                try (ResultSet row = lookup.executeQuery()) {
                    if (!row.next()) { connection.rollback(); return null; }
                    result = new PlayUpdate(row.getInt(4) + 1, Math.max(now, row.getLong(5)));
                    insert.setString(1, "Sangitam");
                    insert.setString(2, UUID.randomUUID().toString());
                    insert.setString(3, row.getString(1));
                    insert.setString(4, row.getString(2));
                    insert.setString(5, row.getString(3));
                }
                update.setLong(1, now);
                update.setString(2, path.toString());
                update.executeUpdate();
                insert.setLong(6, now);
                insert.setString(7, null);
                insert.setString(8, path.toString());
                insert.executeUpdate();
                connection.commit();
                return result;
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

    Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }
}
