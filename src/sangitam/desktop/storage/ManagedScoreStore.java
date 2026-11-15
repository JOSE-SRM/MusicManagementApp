package sangitam.desktop.storage;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import sangitam.desktop.library.ScorePdf;
import sangitam.desktop.library.ScoreSync;
import sangitam.desktop.library.ScoreWork;
import sangitam.desktop.model.Track;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/** Files are shared by work. Alignment data is associated with one recording. */
public final class ManagedScoreStore {
    private static final Gson JSON = new Gson();
    private static final TypeToken<List<Movement>> MOVEMENTS = new TypeToken<>() { };
    private static final TypeToken<List<String>> SOURCES = new TypeToken<>() { };
    private static final TypeToken<TreeMap<Integer, Double>> TIMES = new TypeToken<>() { };
    private static final String PATH = """
        SELECT path FROM tracks WHERE path=?1 OR path=(SELECT current FROM managed_aliases WHERE previous=?1)
        ORDER BY path=?1 DESC LIMIT 1
        """;

    public record Movement(int number, int firstPage, int lastPage, String midi) { }
    public record Part(int number, byte[] pdf, byte[] midi, String source) { }
    public record Recording(NavigableMap<Integer, Double> marks, ScoreSync.Alignment alignment) { }
    public record Asset(String id, String name, Path file, int pages, List<Movement> movements, List<String> sources) {
        @Override public String toString() { return name; }

        public Movement movement(int number) {
            if (number == 0 && movements.size() == 1) return movements.getFirst();
            return movements.stream().filter(movement -> movement.number() == number).findFirst().orElse(null);
        }
    }

    private final LibraryStore library;

    public ManagedScoreStore(LibraryStore library) { this.library = library; }

    static void initialize(Connection db) throws SQLException {
        try (var schema = db.createStatement()) {
            schema.executeUpdate("""
                CREATE TABLE IF NOT EXISTS managed_scores (
                    id TEXT PRIMARY KEY, path TEXT NOT NULL, work_key TEXT NOT NULL, name TEXT NOT NULL,
                    file TEXT NOT NULL, pages INTEGER NOT NULL, movements TEXT NOT NULL, sources TEXT NOT NULL,
                    checksum TEXT NOT NULL
                )
                """);
            schema.executeUpdate("CREATE INDEX IF NOT EXISTS managed_scores_work ON managed_scores(work_key)");
            schema.executeUpdate("""
                CREATE TABLE IF NOT EXISTS score_recordings (
                    asset_id TEXT NOT NULL, path TEXT NOT NULL, sync TEXT NOT NULL, marks TEXT NOT NULL,
                    size INTEGER NOT NULL, modified INTEGER NOT NULL, PRIMARY KEY(asset_id,path)
                )
                """);
        }
    }

    public List<Asset> list(Track track) throws Exception {
        library.initialize();
        String key = ScoreWork.of(track).key();
        var result = new ArrayList<Asset>();
        try (var db = library.connect(); var query = db.prepareStatement("""
            SELECT id,name,file,pages,movements,sources FROM managed_scores
            WHERE path=(%s) OR (work_key<>'' AND work_key=?2)
            ORDER BY (instr(sources,'www.mutopiaproject.org')>0 OR instr(sources,'www.ibiblio.org/mutopia/')>0) DESC,rowid DESC
            """.formatted(PATH))) {
            query.setString(1, track.path().toString());
            query.setString(2, key);
            try (var rows = query.executeQuery()) {
                while (rows.next()) result.add(new Asset(rows.getString(1), rows.getString(2), Path.of(rows.getString(3)),
                    rows.getInt(4), JSON.fromJson(rows.getString(5), MOVEMENTS), JSON.fromJson(rows.getString(6), SOURCES)));
            }
        }
        return List.copyOf(result);
    }

    public Asset save(Track track, String name, List<Part> parts) throws Exception {
        return save(track, name, parts, true);
    }

    public Asset save(Track track, String name, List<Part> parts, boolean shared) throws Exception {
        if (parts.isEmpty()) throw new IOException("No score pages were found.");
        library.initialize();
        var work = ScoreWork.of(track);
        boolean online = parts.stream().anyMatch(part -> !part.source().isBlank());
        int pages = 0;
        var movements = new ArrayList<Movement>();
        var sources = new ArrayList<String>();
        for (Part part : parts) {
            int count = ScorePdf.validate(part.pdf()).pages();
            movements.add(new Movement(part.number(), pages + 1, pages + count, ""));
            pages += count;
            if (!part.source().isBlank()) sources.add(part.source());
        }
        byte[] pdf = ScorePdf.combine(parts.stream().map(Part::pdf).toList());
        String checksum = digest(pdf);
        synchronized (library) {
            // Another window may have finished the same download while this one validated its pages.
            for (Asset existing : list(track)) {
                if (!Files.isRegularFile(existing.file())) continue;
                if (online && sources.equals(existing.sources())) return existing;
                if (existing.file().getFileName().toString().endsWith("-" + checksum.substring(0, 16) + ".pdf")) return existing;
            }
            Path root = library.loadManagedRoot();
            if (root != null && !Files.isDirectory(root)) throw new IOException("The managed library drive is unavailable.");
            Path physicalRoot = (root == null ? library.dataDirectory() : root).toRealPath();
            Path directory = physicalRoot.resolve("Sheet Music")
                .resolve(segment(work.composer(), "Unclassified")).resolve(segment(work.title(), "Score"));
            for (Path parent = directory; !parent.equals(physicalRoot); parent = parent.getParent()) {
                if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && !parent.toRealPath().startsWith(physicalRoot))
                    throw new IOException("The score destination points outside the managed library.");
            }
            Files.createDirectories(directory);
            Path file = directory.resolve(segment(name.replaceFirst("(?i)\\.pdf$", ""), "Score") + "-" + checksum.substring(0, 16) + ".pdf");
            write(directory, file, pdf);
            for (int i = 0; i < parts.size(); i++) {
                byte[] midi = parts.get(i).midi();
                if (midi == null) continue;
                String filename = "Reference-" + digest(midi).substring(0, 16) + ".mid";
                write(directory, directory.resolve(filename), midi);
                var entry = movements.get(i);
                movements.set(i, new Movement(entry.number(), entry.firstPage(), entry.lastPage(), filename));
            }
            String id = UUID.randomUUID().toString();
            try (var db = library.connect(); var insert = db.prepareStatement("""
                INSERT INTO managed_scores(id,path,work_key,name,file,pages,movements,sources,checksum)
                SELECT ?2,path,?3,?4,?5,?6,?7,?8,?9 FROM (%s)
                """.formatted(PATH))) {
                insert.setString(1, track.path().toString());
                insert.setString(2, id);
                insert.setString(3, shared ? work.key() : "");
                insert.setString(4, name);
                insert.setString(5, file.toString());
                insert.setInt(6, pages);
                insert.setString(7, JSON.toJson(movements));
                insert.setString(8, JSON.toJson(sources));
                insert.setString(9, checksum);
                if (insert.executeUpdate() != 1) throw new IOException("The recording is no longer in the library.");
            }
            return new Asset(id, name, file, pages, List.copyOf(movements), List.copyOf(sources));
        }
    }

    public void remove(String id) throws SQLException {
        try (var db = library.connect()) {
            db.setAutoCommit(false);
            try (var scores = db.prepareStatement("DELETE FROM managed_scores WHERE id=?");
                 var recordings = db.prepareStatement("DELETE FROM score_recordings WHERE asset_id=?")) {
                scores.setString(1, id);
                recordings.setString(1, id);
                scores.executeUpdate();
                recordings.executeUpdate();
                db.commit();
            }
        }
    }

    public Recording recording(Track track, Asset asset) throws Exception {
        try (var db = library.connect(); var query = db.prepareStatement("""
            SELECT sync,marks,size,modified,path FROM score_recordings WHERE path=(%s) AND asset_id=?2
            """.formatted(PATH))) {
            query.setString(1, track.path().toString());
            query.setString(2, asset.id());
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    Path audio = Path.of(rows.getString(5));
                    var marks = JSON.fromJson(rows.getString(2), TIMES);
                    if (!Files.isRegularFile(audio)) return new Recording(marks, null);
                    var attrs = Files.readAttributes(audio, java.nio.file.attribute.BasicFileAttributes.class);
                    if (attrs.size() == rows.getLong(3) && attrs.lastModifiedTime().toMillis() == rows.getLong(4))
                        return new Recording(marks, JSON.fromJson(rows.getString(1), ScoreSync.Alignment.class));
                }
            }
        }
        return new Recording(new TreeMap<>(), null);
    }

    public void saveRecording(Track track, Asset asset, Recording value) throws Exception {
        var attrs = Files.readAttributes(track.path(), java.nio.file.attribute.BasicFileAttributes.class);
        try (var db = library.connect(); var write = db.prepareStatement("""
            INSERT INTO score_recordings(asset_id,path,sync,marks,size,modified)
            SELECT ?2,path,?3,?4,?5,?6 FROM (%s) WHERE EXISTS (SELECT 1 FROM managed_scores WHERE id=?2)
            ON CONFLICT(asset_id,path) DO UPDATE SET sync=excluded.sync,marks=excluded.marks,
                size=excluded.size,modified=excluded.modified
            """.formatted(PATH))) {
            write.setString(1, track.path().toString());
            write.setString(2, asset.id());
            write.setString(3, JSON.toJson(value.alignment()));
            write.setString(4, JSON.toJson(value.marks()));
            write.setLong(5, attrs.size());
            write.setLong(6, attrs.lastModifiedTime().toMillis());
            if (write.executeUpdate() != 1) throw new IOException("The score or recording is no longer in the library.");
        }
    }

    private static void write(Path directory, Path target, byte[] bytes) throws Exception {
        if (!Files.exists(target)) {
            Path temporary = Files.createTempFile(directory, ".score-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try { Files.move(temporary, target); } catch (FileAlreadyExistsException alreadyWritten) { }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != bytes.length
            || !digest(Files.readAllBytes(target)).equals(digest(bytes)))
            throw new IOException("A different file already occupies the score destination.");
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String segment(String value, String fallback) {
        String result = value.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "-").strip().replaceAll("[. ]+$", "");
        if (result.isBlank()) return fallback;
        if (result.matches("(?i)(?:con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\\..*)?")) result = "_" + result;
        return result.length() > 65 ? result.substring(0, 65).replaceAll("[. ]+$", "") : result;
    }
}
