package sangitam.desktop.storage;

import com.google.gson.Gson;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata;
import sangitam.desktop.model.TrackMetadata.Field;
import sangitam.desktop.model.DetailReset;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Shares the catalog transaction: scans refresh automatic fields, edits preserve per-field overrides. */
final class MetadataStore {
    private static final Gson JSON = new Gson();
    record Saved(TrackMetadata metadata, Set<Field> overrides, boolean tagsOverride) {
        Saved {
            metadata = metadata == null ? TrackMetadata.empty() : metadata;
            overrides = overrides == null ? Set.of() : Set.copyOf(overrides);
        }
    }
    private MetadataStore() { }
    static void initialize(Connection connection) throws SQLException {
        try (var sql = connection.createStatement()) {
            sql.executeUpdate("CREATE TABLE IF NOT EXISTS track_metadata (path TEXT PRIMARY KEY, payload TEXT NOT NULL, search_text TEXT NOT NULL)");
            sql.executeUpdate("CREATE TRIGGER IF NOT EXISTS remove_track_metadata AFTER DELETE ON tracks BEGIN DELETE FROM track_metadata WHERE path=OLD.path; END");
        }
    }
    static TrackMetadata decode(String text) throws SQLException { return saved(text).metadata(); }
    private static Saved saved(String text) throws SQLException {
        if (text == null || text.isBlank()) return new Saved(TrackMetadata.empty(), Set.of(), false);
        try {
            Saved value = JSON.fromJson(text, Saved.class);
            if (value == null) throw new IllegalArgumentException("Empty metadata");
            return value;
        } catch (RuntimeException error) { throw new SQLException("Stored track metadata could not be read.", error); }
    }
    private static Saved load(Connection connection, Path path) throws SQLException {
        try (var sql = connection.prepareStatement("SELECT payload FROM track_metadata WHERE path=?")) {
            sql.setString(1, path.toString());
            try (var row = sql.executeQuery()) { return saved(row.next() ? row.getString(1) : null); }
        }
    }
    static void scan(Connection connection, Track track) throws SQLException {
        Saved previous = load(connection, track.path());
        var overrides = new EnumMap<Field, String>(Field.class);
        previous.overrides().forEach(field -> overrides.put(field, previous.metadata().text(field)));
        TrackMetadata merged = track.metadata().edit(overrides, previous.tagsOverride() ? previous.metadata().tags() : null);
        save(connection, track.path(), new Saved(merged, previous.overrides(), previous.tagsOverride()));
    }
    static void canonicalize(Connection connection, Path path, Map<String,String> aliases) throws SQLException {
        Saved previous=load(connection,path);var m=previous.metadata();
        var shell=new Track(path,"","","",0,"","",m);var normalized=ArtistEnrichmentStore.canonical(shell,aliases).metadata();
        save(connection,path,new Saved(normalized,previous.overrides(),previous.tagsOverride()));
    }
    static void edit(Connection connection, Path path, Map<Field, String> changes, Set<String> tags) throws SQLException {
        Saved previous = load(connection, path);
        var overrides = new HashSet<>(previous.overrides());
        overrides.addAll(changes.keySet());
        save(connection, path, new Saved(previous.metadata().edit(changes, tags), overrides, previous.tagsOverride() || tags != null));
    }

    static void restore(Connection connection, Track source, DetailReset reset) throws SQLException {
        Saved previous = load(connection, source.path());
        var fields = new EnumMap<Field, TrackMetadata.Value>(Field.class);
        fields.putAll(previous.metadata().fields());
        var overrides = new HashSet<>(previous.overrides());
        for (Field field : reset.credits()) {
            overrides.remove(field);
            if (source.metadata().fields().containsKey(field)) fields.put(field, source.metadata().fields().get(field));
            else fields.remove(field);
        }
        TrackMetadata data = source.metadata();
        TrackMetadata restored = new TrackMetadata(fields, reset.customTags() ? data.tags() : previous.metadata().tags(),
            data.bitrateKbps(), data.sampleRateHz(), data.bitsPerSample(), data.channels(), data.embeddedArtwork(),
            Math.min(previous.metadata().extractionVersion(), data.extractionVersion()));
        save(connection, source.path(), new Saved(restored, overrides, !reset.customTags() && previous.tagsOverride()));
    }
    private static void save(Connection connection, Path path, Saved saved) throws SQLException {
        String base;
        try (var sql = connection.prepareStatement("SELECT title,artist,album,genre FROM tracks WHERE path=?")) {
            sql.setString(1, path.toString());
            try (var row = sql.executeQuery()) {
                if (!row.next()) return;
                base = String.join(" ", row.getString(1), row.getString(2), row.getString(3), row.getString(4));
            }
        }
        try (var sql = connection.prepareStatement("INSERT INTO track_metadata(path,payload,search_text) VALUES(?,?,?) "
            + "ON CONFLICT(path) DO UPDATE SET payload=excluded.payload,search_text=excluded.search_text")) {
            sql.setString(1, path.toString());
            sql.setString(2, JSON.toJson(saved));
            sql.setString(3, (base + " " + saved.metadata().searchableText()).toLowerCase(Locale.ROOT));
            sql.executeUpdate();
        }
    }
}
