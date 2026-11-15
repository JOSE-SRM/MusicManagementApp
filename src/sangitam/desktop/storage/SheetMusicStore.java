package sangitam.desktop.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/** Stores offline editions and per-recording page timings in the local catalog. */
public final class SheetMusicStore {
    private static final Gson JSON = new Gson();
    private static final TypeToken<TreeMap<Integer, Double>> TIMES = new TypeToken<>() { };
    private static final String CURRENT_PATH = """
        SELECT path FROM tracks WHERE path=?1 OR path=(SELECT current FROM managed_aliases WHERE previous=?1)
        ORDER BY path=?1 DESC LIMIT 1
        """;

    public record Edition(String id, String name, int pages, NavigableMap<Integer, Double> timings, ManagedScoreStore.Asset asset) {
        public Edition(String id, String name, int pages, NavigableMap<Integer, Double> timings) {
            this(id, name, pages, timings, null);
        }
        public Edition {
            timings = Collections.unmodifiableNavigableMap(new TreeMap<>(timings));
        }

        @Override public String toString() { return name; }

        public int pageAt(double seconds) {
            int page = 1;
            for (var entry : timings.entrySet()) {
                if (entry.getValue() > seconds) break;
                page = entry.getKey();
            }
            return page;
        }
    }

    private final LibraryStore library;

    public SheetMusicStore(LibraryStore library) { this.library = library; }

    static void initialize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sheet_music (
                    id TEXT PRIMARY KEY, path TEXT NOT NULL, name TEXT NOT NULL,
                    pages INTEGER NOT NULL, pdf BLOB NOT NULL, timings TEXT NOT NULL DEFAULT '{}'
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS sheet_music_track ON sheet_music(path)");
        }
    }

    public List<Edition> editions(Path track) throws Exception {
        library.initialize();
        var editions = new ArrayList<Edition>();
        try (var db = library.connect(); var query = db.prepareStatement("""
            SELECT id,name,pages,timings FROM sheet_music WHERE path=(%s) ORDER BY rowid
            """.formatted(CURRENT_PATH))) {
            query.setString(1, track.toString());
            try (var rows = query.executeQuery()) {
                while (rows.next()) editions.add(new Edition(rows.getString("id"), rows.getString("name"),
                    rows.getInt("pages"), JSON.fromJson(rows.getString("timings"), TIMES)));
            }
        }
        return List.copyOf(editions);
    }

    public Edition add(Path track, String name, int pages, byte[] pdf) throws Exception {
        library.initialize();
        String id = UUID.randomUUID().toString();
        try (var db = library.connect(); var insert = db.prepareStatement("""
            INSERT INTO sheet_music(id,path,name,pages,pdf) SELECT ?2,path,?3,?4,?5 FROM (%s)
            """.formatted(CURRENT_PATH))) {
            insert.setString(1, track.toString());
            insert.setString(2, id);
            insert.setString(3, name);
            insert.setInt(4, pages);
            insert.setBytes(5, pdf);
            if (insert.executeUpdate() != 1) throw new SQLException("This track is no longer in the library.");
        }
        return new Edition(id, name, pages, new TreeMap<>());
    }

    public byte[] pdf(String id) throws SQLException {
        try (var db = library.connect(); var query = db.prepareStatement("SELECT pdf FROM sheet_music WHERE id=?")) {
            query.setString(1, id);
            try (var rows = query.executeQuery()) {
                if (rows.next()) return rows.getBytes(1);
                throw new SQLException("This score was removed from the library.");
            }
        }
    }

    public Edition saveTiming(Edition edition, int page, Double seconds) throws SQLException {
        if (page < 1 || page > edition.pages()) throw new IllegalArgumentException("Choose a page in this score.");
        var timings = new TreeMap<>(edition.timings());
        if (seconds == null) timings.remove(page);
        else {
            if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Enter a valid playback time.");
            var previous = timings.lowerEntry(page);
            var next = timings.higherEntry(page);
            if (previous != null && seconds <= previous.getValue() || next != null && seconds >= next.getValue())
                throw new IllegalArgumentException("Page times must increase in page order.");
            timings.put(page, seconds);
        }
        try (var db = library.connect(); var update = db.prepareStatement(
            "UPDATE sheet_music SET timings=? WHERE id=? AND timings=?")) {
            update.setString(1, JSON.toJson(timings));
            update.setString(2, edition.id());
            update.setString(3, JSON.toJson(edition.timings()));
            if (update.executeUpdate() != 1) throw new SQLException("The score changed. Reopen it to get the current timings.");
        }
        return new Edition(edition.id(), edition.name(), edition.pages(), timings);
    }

    public void remove(String id) throws SQLException {
        try (var db = library.connect(); var remove = db.prepareStatement("DELETE FROM sheet_music WHERE id=?")) {
            remove.setString(1, id);
            remove.executeUpdate();
        }
    }
}
