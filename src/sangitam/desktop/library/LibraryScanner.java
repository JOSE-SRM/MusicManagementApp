package sangitam.desktop.library;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import sangitam.desktop.model.Track;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Scans user-selected files and folders in a worker thread; it never mutates Swing components. */
public final class LibraryScanner {
    public record Progress(int visited, int accepted, Path current, String phase) {
        public Progress(int visited, int accepted, Path current) {
            this(visited, accepted, current, "Scanning");
        }
    }
    public record Result(List<Track> tracks, int skipped, List<String> errors) { }

    public Result scan(List<Path> sources, boolean recursive, Consumer<Progress> progress,
                       BooleanSupplier cancelled) {
        List<Track> tracks = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        int[] counts = new int[2]; // visited, skipped

        for (Path source : sources) {
            if (cancelled.getAsBoolean()) break;
            Path root = source.toAbsolutePath().normalize();
            if (Files.isDirectory(root)) {
                try {
                    Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                        recursive ? Integer.MAX_VALUE : 1, new SimpleFileVisitor<>() {
                            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (cancelled.getAsBoolean()) return FileVisitResult.TERMINATE;
                                inspect(file, seen, tracks, errors, counts, progress);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                                rememberError(errors, file + ": " + error.getMessage());
                                counts[1]++;
                                return FileVisitResult.CONTINUE;
                            }
                        });
                } catch (IOException error) {
                    rememberError(errors, root + ": " + error.getMessage());
                    counts[1]++;
                }
            } else {
                inspect(root, seen, tracks, errors, counts, progress);
            }
        }
        return new Result(List.copyOf(tracks), counts[1], List.copyOf(errors));
    }

    private void inspect(Path file, Set<Path> seen, List<Track> tracks, List<String> errors,
                         int[] counts, Consumer<Progress> progress) {
        counts[0]++;
        String filename = file.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals("mp3") && !extension.equals("wav")) {
            counts[1]++;
            progress.accept(new Progress(counts[0], tracks.size(), file));
            return;
        }
        try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new IOException("File is missing or unreadable");
            }
            Path canonical = file.toRealPath();
            if (!seen.add(canonical)) return;
            AudioFile audio = AudioFileIO.read(canonical.toFile());
            Tag tag = audio.getTag();
            String name = filename.substring(0, filename.length() - extension.length() - 1);
            String title = field(tag, FieldKey.TITLE, name);
            String artist = field(tag, FieldKey.ARTIST, "Unknown artist");
            String album = field(tag, FieldKey.ALBUM, "Unknown album");
            String genre = field(tag, FieldKey.GENRE, GenreHints.fromFolder(canonical));
            double duration = audio.getAudioHeader() == null ? 0 : audio.getAudioHeader().getPreciseTrackLength();
            tracks.add(new Track(canonical, title, artist, album, duration,
                extension.toUpperCase(Locale.ROOT), genre));
        } catch (Exception error) {
            counts[1]++;
            rememberError(errors, file.getFileName() + ": " + error.getMessage());
        }
        progress.accept(new Progress(counts[0], tracks.size(), file));
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

    private void rememberError(List<String> errors, String message) {
        if (errors.size() < 8) errors.add(message);
    }
}
