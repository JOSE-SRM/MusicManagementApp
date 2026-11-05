package sangitam.desktop.library;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import sangitam.desktop.model.FileStamp;
import sangitam.desktop.model.LibrarySource;
import sangitam.desktop.model.Track;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Scans user-selected files and folders in a worker thread; it never mutates Swing components. */
public final class LibraryScanner {
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
        "mp3", "wav", "flac", "ogg", "oga", "aac", "m4a", "aif", "aiff", "wma");
    public record Progress(int visited, int accepted, Path current, String phase) {
        public Progress(int visited, int accepted, Path current) {
            this(visited, accepted, current, "Scanning");
        }
    }
    public record KnownTrack(Track track, FileStamp stamp, boolean repairLabels) { }
    public record Result(List<Track> tracks, int skipped, List<String> errors,
                         Map<Path, FileStamp> stamps, Set<Path> unavailable,
                         List<LibrarySource> sources, int reused) { }

    static boolean needsLabelRepair(Track track, boolean titleOverride, boolean artistOverride) {
        String filename = fileTitle(track.path().getFileName().toString());
        return (!titleOverride && placeholderTitle(track.title()) && !placeholderTitle(filename))
            || (!artistOverride && missingArtist(track.artist()) && !classicalComposer(track.path()).isEmpty());
    }

    private static String fileTitle(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static boolean placeholderTitle(String value) {
        return value.isBlank() || value.equalsIgnoreCase("tmp");
    }

    private static boolean missingArtist(String value) {
        return value.isBlank() || value.equalsIgnoreCase("unknown artist") || value.equalsIgnoreCase("unknown");
    }

    private static String classicalComposer(Path file) {
        Path child = file.getParent();
        for (Path parent = child == null ? null : child.getParent(); parent != null; parent = parent.getParent()) {
            if (parent.getFileName() != null && parent.getFileName().toString().equalsIgnoreCase("Classical")) {
                String name = child.getFileName().toString();
                int comma = name.indexOf(',');
                if (comma > 0 && !name.substring(comma + 1).isBlank()) return name;
            }
            child = parent;
        }
        return "";
    }

    public Result scan(List<Path> sources, boolean recursive, Consumer<Progress> progress,
                       BooleanSupplier cancelled) {
        return scan(sources.stream().map(path -> new LibrarySource(path, recursive)).toList(),
            Map.of(), false, progress, cancelled);
    }

    public Result scan(List<LibrarySource> sources, Map<Path, KnownTrack> known, boolean checkIndexedFiles,
                       Consumer<Progress> progress, BooleanSupplier cancelled) {
        return scan(sources, known, checkIndexedFiles, ignored -> true, progress, cancelled);
    }

    public Result scan(List<LibrarySource> sources, Map<Path, KnownTrack> known, boolean checkIndexedFiles,
                       Predicate<Path> include, Consumer<Progress> progress, BooleanSupplier cancelled) {
        Scan scan = new Scan(known, include, progress, cancelled);
        for (LibrarySource source : sources) {
            if (cancelled.getAsBoolean()) break;
            scan.visitSource(source);
        }
        Set<Path> unavailable = new HashSet<>();
        if (checkIndexedFiles) {
            for (Path path : known.keySet()) {
                if (cancelled.getAsBoolean()) break;
                if (!scan.seen.contains(path) && Files.isRegularFile(path)) scan.inspect(path);
                if (!scan.stamps.containsKey(path)) unavailable.add(path);
            }
        }
        return new Result(List.copyOf(scan.tracks), scan.skipped, List.copyOf(scan.errors),
            Map.copyOf(scan.stamps), Set.copyOf(unavailable), List.copyOf(scan.sources), scan.reused);
    }

    private static final class Scan {
        private final Map<Path, KnownTrack> known;
        private final Predicate<Path> include;
        private final Consumer<Progress> progress;
        private final BooleanSupplier cancelled;
        private final List<Track> tracks = new ArrayList<>();
        private final Map<Path, FileStamp> stamps = new LinkedHashMap<>();
        private final List<String> errors = new ArrayList<>();
        private final Set<Path> seen = new HashSet<>();
        private final Set<LibrarySource> sources = new LinkedHashSet<>();
        private int visited;
        private int skipped;
        private int reused;

        Scan(Map<Path, KnownTrack> known, Predicate<Path> include, Consumer<Progress> progress,
             BooleanSupplier cancelled) {
            this.known = known;
            this.include = include;
            this.progress = progress;
            this.cancelled = cancelled;
        }

        void visitSource(LibrarySource source) {
            if (!include.test(source.path())) return;
            try {
                Path root = source.path().toRealPath();
                if (!include.test(root)) return;
                boolean folder = Files.readAttributes(root, BasicFileAttributes.class).isDirectory();
                if (folder) {
                    Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                        source.recursive() ? Integer.MAX_VALUE : 1, new SimpleFileVisitor<>() {
                            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
                                if (cancelled.getAsBoolean()) return FileVisitResult.TERMINATE;
                                return include.test(path) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                            }

                            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (cancelled.getAsBoolean()) return FileVisitResult.TERMINATE;
                                if (!attrs.isDirectory()) inspect(file);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                                reportError(file + ": " + error.getMessage());
                                return FileVisitResult.CONTINUE;
                            }
                        });
                } else {
                    inspect(root);
                }
                if (folder || stamps.containsKey(root)) sources.add(new LibrarySource(root, folder && source.recursive()));
            } catch (IOException | SecurityException error) {
                reportError("Could not access " + source.path() + ". Check the location, drive, and permissions.");
            }
        }

        void inspect(Path file) {
            visited++;
            String filename = file.getFileName().toString();
            int dot = filename.lastIndexOf('.');
            String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
            try {
                if (!AUDIO_EXTENSIONS.contains(extension)) {
                    skipped++;
                    return;
                }
                Path canonical = file.toRealPath();
                if (!include.test(canonical) || !seen.add(canonical)) return;
                KnownTrack previous = known.get(canonical);
                Path identity = previous == null ? canonical : previous.track().path();
                BasicFileAttributes attrs = Files.readAttributes(canonical, BasicFileAttributes.class);
                if (!attrs.isRegularFile() || !Files.isReadable(canonical)) {
                    throw new IOException("File is missing or unreadable");
                }
                FileStamp stamp = new FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis());
                if (previous != null && stamp.equals(previous.stamp()) && !previous.repairLabels()) {
                    tracks.add(previous.track());
                    reused++;
                } else {
                    tracks.add(readTrack(identity, canonical, filename, extension));
                }
                stamps.put(identity, stamp);
            } catch (Exception error) {
                reportError(file + ": " + error.getMessage());
            } finally {
                progress.accept(new Progress(visited, tracks.size(), file));
            }
        }

        private Track readTrack(Path identity, Path canonical, String filename, String extension) throws Exception {
            AudioFile audio = AudioFileIO.read(canonical.toFile());
            Tag tag = audio.getTag();
            Tag legacy = audio instanceof MP3File mp3 ? mp3.getID3v1Tag() : null;
            String title = field(tag, FieldKey.TITLE, "");
            if (placeholderTitle(title)) title = field(legacy, FieldKey.TITLE, "");
            if (placeholderTitle(title)) title = fileTitle(filename);
            String artist = field(tag, FieldKey.ARTIST, "");
            if (missingArtist(artist)) artist = field(legacy, FieldKey.ARTIST, "");
            if (missingArtist(artist)) artist = field(tag, FieldKey.ALBUM_ARTIST, "");
            if (missingArtist(artist)) artist = field(tag, FieldKey.COMPOSER, "");
            if (missingArtist(artist)) artist = classicalComposer(canonical);
            if (missingArtist(artist)) artist = "Unknown artist";
            String album = field(tag, FieldKey.ALBUM, "Unknown album");
            String genre = field(tag, FieldKey.GENRE, GenreHints.fromFolder(canonical));
            double duration = audio.getAudioHeader() == null ? 0 : audio.getAudioHeader().getPreciseTrackLength();
            return new Track(identity, title, artist, album, duration, extension.toUpperCase(Locale.ROOT), genre);
        }

        private String field(Tag tag, FieldKey key, String fallback) {
            if (tag == null) return fallback;
            try {
                String value = tag.getFirst(key);
                return value == null || value.isBlank() ? fallback : value.trim();
            } catch (RuntimeException ignored) {
                return fallback;
            }
        }

        private void reportError(String message) {
            skipped++;
            if (errors.size() < 8) errors.add(message);
        }
    }
}
