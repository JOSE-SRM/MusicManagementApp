package sangitam.desktop.library;

import com.google.gson.Gson;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Runs the local Audiveris recognizer on original PDF pages and caches the recognized notation. */
public final class OpticalScoreRecognition {
    private static final int VERSION = 1;
    private static final Gson JSON = new Gson();
    private static final java.util.concurrent.Semaphore RECOGNITION_SLOT = new java.util.concurrent.Semaphore(1, true);
    private static final String COMMAND = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
        ? "Audiveris.exe" : "audiveris";

    public record Variant(ScoreSync.Reference reference, List<OpticalScoreXml.Mark> marks, String id) { }
    public record Result(Variant written, Variant repeated, String revision) { }

    private OpticalScoreRecognition() { }

    public static Path locate(String configured) {
        var candidates = new ArrayList<Path>();
        add(candidates, System.getProperty("sangitam.audiveris"));
        add(candidates, configured);
        add(candidates, System.getenv("SANGITAM_AUDIVERIS"));
        String launcher = System.getProperty("jpackage.app-path", "");
        if (!launcher.isBlank()) candidates.add(Path.of(launcher).toAbsolutePath().getParent().resolve("tools").resolve("Audiveris").resolve(COMMAND));
        Path runtime = Path.of(System.getProperty("java.home")).toAbsolutePath().getParent();
        if (runtime != null) candidates.add(runtime.resolve("tools").resolve("Audiveris").resolve(COMMAND));
        ProcessHandle.current().info().command().map(Path::of).map(Path::getParent).ifPresent(parent ->
            candidates.add(parent.resolve("tools").resolve("Audiveris").resolve(COMMAND)));
        for (String key : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            String root = System.getenv(key);
            if (root != null && !root.isBlank()) {
                candidates.add(Path.of(root, "Audiveris", COMMAND));
                candidates.add(Path.of(root, "Programs", "Audiveris", COMMAND));
            }
        }
        for (String path : System.getenv().getOrDefault("PATH", "").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            if (!path.isBlank()) add(candidates, path + java.io.File.separator + COMMAND);
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static void add(List<Path> candidates, String path) {
        if (path == null || path.isBlank()) return;
        try { candidates.add(Path.of(path)); } catch (java.nio.file.InvalidPathException ignored) { }
    }

    public static Result analyze(Path engine, Path pdf, int first, int last, Consumer<String> progress) throws Exception {
        return analyze(engine, pdf, first, last, last, progress);
    }

    public static Result analyze(Path engine, Path pdf, int first, int last, int totalPages, Consumer<String> progress) throws Exception {
        var stamp = Files.readAttributes(pdf, java.nio.file.attribute.BasicFileAttributes.class);
        String suffix = ":" + stamp.size() + ":" + stamp.lastModifiedTime().toMillis();
        String revision = VERSION + ":" + first + ":" + last + suffix;
        Path cache = cachePath(pdf, first, last);
        var exact = readCache(cache, revision);
        if (exact != null) return exact;
        var bundled = bundledNotation(pdf, first, last, revision);
        if (bundled != null) return bundled;
        if (first != 1 || last != totalPages) {
            var complete = readCache(cachePath(pdf, 1, totalPages), VERSION + ":1:" + totalPages + suffix);
            if (complete != null) {
                var written = slice(complete.written(), first, last);
                if (written != null) return new Result(written, slice(complete.repeated(), first, last), revision);
            }
        }
        if (engine == null) throw new IOException("Set up Audiveris in Sources to recognize the printed notation on this score.");
        if (!RECOGNITION_SLOT.tryAcquire(0, TimeUnit.SECONDS)) {
            progress.accept("Waiting for the previous score recognition to close…");
            RECOGNITION_SLOT.acquire();
        }
        try {
            var cachedWhileWaiting = readCache(cache, revision);
            if (cachedWhileWaiting != null) return cachedWhileWaiting;
            return recognize(engine, pdf, first, last, cache, revision, stamp, progress);
        } finally {
            RECOGNITION_SLOT.release();
        }
    }

    private static Result recognize(Path engine, Path pdf, int first, int last, Path cache, String revision,
                                    java.nio.file.attribute.BasicFileAttributes stamp, Consumer<String> progress) throws Exception {
        Path temp = Files.createTempDirectory("sangitam-score-");
        try {
            var pages = render(pdf, first, last, temp, 250, progress);
            for (int index = 0; index < pages.size(); index += 35) {
                int end = Math.min(index + 35, pages.size());
                progress.accept("Recognizing score pages " + (first + index) + "–" + (first + end - 1) + " locally…");
                run(engine, pages.subList(index, end), temp, first + index, first + end - 1, progress);
            }
            List<Path> xml = exportedPages(temp);
            if (xml.isEmpty()) throw new IOException("Audiveris did not recognize playable notation in this PDF.");
            var retry = new ArrayList<Path>();
            for (int page = pageNumber(xml.getFirst()); page < pageNumber(xml.getLast()); page++) {
                final int missing = page;
                if (xml.stream().noneMatch(path -> pageNumber(path) == missing))
                    retry.addAll(render(pdf, page, page, Files.createDirectories(temp.resolve("retry")), 218, progress));
            }
            if (!retry.isEmpty()) {
                progress.accept("Retrying " + retry.size() + " unrecognized score pages…");
                run(engine, retry, temp.resolve("retry"), pageNumber(retry.getFirst()), pageNumber(retry.getLast()), progress);
                xml = exportedPages(temp);
            }
            progress.accept("Reading recognized score notes…");
            var result = OpticalScoreXml.read(xml, revision);
            if (!Files.getLastModifiedTime(pdf).equals(stamp.lastModifiedTime()) || Files.size(pdf) != stamp.size())
                throw new IOException("The score PDF changed during recognition.");
            Path partial = Files.createTempFile(cache.getParent(), ".notation-", ".tmp");
            try {
                try (var writer = Files.newBufferedWriter(partial)) { JSON.toJson(result, writer); }
                try { Files.move(partial, cache, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                    Files.move(partial, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(partial); }
            return result;
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    try { Files.deleteIfExists(path); } catch (IOException locked) { path.toFile().deleteOnExit(); }
                }
            }
        }
    }

    private static List<Path> exportedPages(Path directory) throws IOException {
        try (var paths = Files.walk(directory, 3)) {
            return paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().matches("score-\\d{4}\\.(?:xml|musicxml)"))
                .sorted(Comparator.comparingInt(OpticalScoreRecognition::pageNumber)).toList();
        }
    }

    private static int pageNumber(Path page) {
        return Integer.parseInt(page.getFileName().toString().substring(6, 10));
    }

    private static Path cachePath(Path pdf, int first, int last) {
        return pdf.resolveSibling(pdf.getFileName() + ".pages-" + first + "-" + last + ".notation.json");
    }

    private static Result bundledNotation(Path pdf, int first, int last, String revision) throws IOException {
        if (first != 1 || last != 21 || !ScoreMovementPages.isNoctaveAppassionata(pdf)) return null;
        try (var source = OpticalScoreRecognition.class.getResourceAsStream("appassionata-i-notation.json.gz")) {
            if (source == null) return null;
            try (var reader = new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(source), java.nio.charset.StandardCharsets.UTF_8)) {
                var result = JSON.fromJson(reader, Result.class);
                return new Result(result.written(), result.repeated(), revision + ":noctave-1");
            }
        }
    }

    private static Result readCache(Path path, String revision) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 32 * 1024 * 1024) return null;
            try (var reader = Files.newBufferedReader(path)) {
                var cached = JSON.fromJson(reader, Result.class);
                if (cached != null && revision.equals(cached.revision()) && !cached.written().reference().notes().isEmpty()) return cached;
            }
        } catch (IOException | RuntimeException corrupt) { /* Regenerate a damaged cache. */ }
        return null;
    }

    private static Variant slice(Variant original, int first, int last) {
        if (original == null) return null;
        var marks = original.marks();
        int from = 0;
        while (from < marks.size() && marks.get(from).position().page() < first) from++;
        if (from == marks.size() || marks.get(from).position().page() > last) return null;
        int to = from + 1;
        while (to < marks.size() && marks.get(to).position().page() >= first && marks.get(to).position().page() <= last) to++;
        if (marks.get(from).position().page() != first || marks.subList(from, to).stream()
                .noneMatch(mark -> mark.position().page() == last)) return null;
        double start = from == 0 ? 0 : marks.get(from).time();
        double end = to == marks.size() ? original.reference().duration() : marks.get(to).time();
        var notes = original.reference().notes().stream().filter(note -> note.start() >= start && note.start() < end)
            .map(note -> new ScoreSync.Note(note.pitch(), note.start() - start, Math.min(note.end(), end) - start)).toList();
        if (notes.isEmpty() || end <= start) return null;
        var positions = marks.subList(from, to).stream()
            .map(mark -> new OpticalScoreXml.Mark(mark.time() - start, mark.position())).toList();
        return new Variant(new ScoreSync.Reference(end - start, notes), positions, original.id());
    }

    private static List<Path> render(Path pdf, int first, int last, Path temp, int dpi, Consumer<String> progress) throws IOException {
        try (var document = Loader.loadPDF(pdf.toFile())) {
            var renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            var files = new ArrayList<Path>();
            for (int page = first; page <= Math.min(last, document.getNumberOfPages()); page++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                progress.accept("Preparing page " + page + " of " + last + "…");
                var box = document.getPage(page - 1).getCropBox();
                float scale = Math.min(dpi / 72f, 3900f / Math.max(box.getWidth(), box.getHeight()));
                var image = renderer.renderImage(page - 1, scale, ImageType.GRAY);
                Path file = temp.resolve("score-%04d.png".formatted(page));
                ImageIO.write(image, "png", file.toFile());
                files.add(file);
            }
            return files;
        }
    }

    private static void run(Path engine, List<Path> pages, Path temp, int first, int last, Consumer<String> progress) throws Exception {
        var command = new ArrayList<>(List.of(engine.toAbsolutePath().toString(), "-batch", "-export", "-option",
            "org.audiveris.omr.sheet.BookManager.useCompression=false", "-output", temp.toString(), "--"));
        pages.forEach(page -> command.add(page.toString()));
        var process = new ProcessBuilder(command).directory(temp.toFile()).redirectErrorStream(true)
            .redirectOutput(temp.resolve("recognition.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(25);
            int previous = -1;
            while (!process.waitFor(2, TimeUnit.SECONDS)) {
                if (System.nanoTime() >= deadline) throw new IOException("Score recognition timed out. Try a shorter score or movement PDF.");
                int complete = 0;
                for (Path page : pages) {
                    String name = "score-%04d".formatted(pageNumber(page));
                    if (Files.isRegularFile(temp.resolve(name + ".xml")) || Files.isRegularFile(temp.resolve(name + ".musicxml")))
                        complete++;
                }
                if (complete != previous) {
                    progress.accept("Recognizing pages " + first + "–" + last + " locally · " + complete + "/" + pages.size() + " exported");
                    previous = complete;
                }
            }
            // A batch may report a failure for a cover page and still export the musical pages.
        } finally {
            if (process.isAlive()) {
                var descendants = process.descendants().toList();
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                boolean interrupted = Thread.interrupted();
                try { process.waitFor(3, TimeUnit.SECONDS); }
                catch (InterruptedException again) { interrupted = true; }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }
}
