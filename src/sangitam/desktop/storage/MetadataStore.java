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
    static TrackMetadata decode(String text, String enrichment) throws SQLException {
        TrackMetadata base = decode(text);
        if (enrichment == null || enrichment.equals("{}")) return base;
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<Field, TrackMetadata.Value>>() { }.getType();
        Map<Field, TrackMetadata.Value> additions = JSON.fromJson(enrichment, type);
        var fields = new EnumMap<Field, TrackMetadata.Value>(Field.class);
        fields.putAll(base.fields());
        additions.forEach((field, value) -> {
            var current = fields.get(field);
            if (current == null || value.origin() == TrackMetadata.Origin.SCORE_REFERENCE
                && (current.origin() == TrackMetadata.Origin.TITLE_HINT || current.origin() == TrackMetadata.Origin.FOLDER_HINT))
                fields.put(field, value);
        });
        return new TrackMetadata(fields, base.tags(), base.bitrateKbps(), base.sampleRateHz(), base.bitsPerSample(),
            base.channels(), base.embeddedArtwork(), base.extractionVersion());
    }
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

    static void mergeDuplicate(Connection connection, Path previous, Path target) throws SQLException {
        Saved old = load(connection, previous);
        Saved current = load(connection, target);
        var fields = new EnumMap<Field, TrackMetadata.Value>(Field.class);
        fields.putAll(current.metadata().fields());
        old.metadata().fields().forEach((field, value) -> {
            if (old.overrides().contains(field) && !current.overrides().contains(field)) fields.put(field, value);
            else fields.putIfAbsent(field, value);
        });
        var overrides = new HashSet<>(current.overrides());
        overrides.addAll(old.overrides());
        var metadata = current.metadata().extractionVersion() == 0 ? old.metadata() : current.metadata();
        var tags = current.tagsOverride() || !old.tagsOverride() ? current.metadata().tags() : old.metadata().tags();
        if (tags.isEmpty() && !current.tagsOverride() && !old.tagsOverride()) tags = old.metadata().tags();
        save(connection, target, new Saved(new TrackMetadata(fields, tags, metadata.bitrateKbps(), metadata.sampleRateHz(),
            metadata.bitsPerSample(), metadata.channels(), metadata.embeddedArtwork(), metadata.extractionVersion()),
            overrides, current.tagsOverride() || old.tagsOverride()));
    }

    static void updateFilingHints(Connection connection, Path path, Map<Field, TrackMetadata.Value> hints) throws SQLException {
        Saved previous = load(connection, path);
        var fields = new EnumMap<Field, TrackMetadata.Value>(Field.class);
        fields.putAll(previous.metadata().fields());
        hints.forEach((field, hint) -> {
            var prior = fields.get(field);
            boolean reference = hint.origin() == TrackMetadata.Origin.SCORE_REFERENCE;
            boolean inferred = Set.of(Field.COMPOSER, Field.PERIOD, Field.FORM, Field.WORK, Field.MOVEMENT,
                    Field.OPUS, Field.INSTRUMENTATION).contains(field)
                && (hint.origin() == TrackMetadata.Origin.TITLE_HINT || hint.origin() == TrackMetadata.Origin.FOLDER_HINT)
                && (prior == null || prior.origin() == TrackMetadata.Origin.TITLE_HINT || prior.origin() == TrackMetadata.Origin.FOLDER_HINT);
            if (!previous.overrides().contains(field) && (inferred || reference && (prior == null
                || prior.origin() == TrackMetadata.Origin.TITLE_HINT || prior.origin() == TrackMetadata.Origin.FOLDER_HINT)))
                fields.put(field, hint);
        });
        var data = previous.metadata();
        if (!fields.equals(data.fields())) save(connection, path, new Saved(new TrackMetadata(fields, data.tags(),
            data.bitrateKbps(), data.sampleRateHz(), data.bitsPerSample(), data.channels(), data.embeddedArtwork(),
            data.extractionVersion()), previous.overrides(), previous.tagsOverride()));
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
