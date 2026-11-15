package sangitam.desktop.library;

import sangitam.desktop.model.FileStamp;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata;
import sangitam.desktop.model.TrackMetadata.Field;
import sangitam.desktop.model.TrackMetadata.Origin;
import sangitam.desktop.model.TrackMetadata.Value;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Plans and verifies managed files before handing their paths to the normal importer. */
public final class ManagedLibraryCopy {
    public enum Policy {
        COPY("Copy"), HARD_LINK("Hard link to original");
        private final String label;
        Policy(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum Layout {
        FORMS("Musical Forms"), OPUS("Opus Numbers"), SIMPLE("Work folders");
        private final String label;
        Layout(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public record Options(boolean genre, boolean period, boolean composer, boolean instrument,
                          boolean work, boolean recording, Layout layout) { }
    public record Settings(Path root, Policy policy, Options options, boolean writeTags) { }
    public record Entry(Path source, Path target, FileStamp stamp, boolean reuse, boolean renamed) { }
    public record Plan(Path root, Policy policy, List<Entry> entries, long bytes, long available, List<String> scanNotes) { }
    public record Result(List<Path> indexedPaths, List<String> failures, boolean cancelled) { }
    private static final Pattern RESERVED = Pattern.compile("(?i)(?:CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])(?:\\..*)?");
    private static final Pattern MOVEMENT = Pattern.compile("(?i)^\\s*(\\d{1,2}|[IVXLCDM]{1,8})(?:[.) :–-]|$)");

    public Plan preview(Path destination, List<Path> selections, boolean recursive, Options options,
                        Map<Path, Track> indexed, BooleanSupplier cancelled) throws IOException {
        return preview(destination, selections, recursive, options, Policy.COPY, indexed, cancelled);
    }

    public Plan preview(Path destination, List<Path> selections, boolean recursive, Options options,
                        Policy policy, Map<Path, Track> indexed, BooleanSupplier cancelled) throws IOException {
        Path root = root(destination);
        for (Path selection : selections) {
            Path source = selection.toRealPath();
            if (source.startsWith(root) || Files.isDirectory(source) && root.startsWith(source))
                throw new IOException("Choose source files outside the managed folder and its parent tree.");
        }
        var scan = new LibraryScanner().scan(selections, recursive, ignored -> { }, cancelled);
        return plan(root, options, policy, scan, indexed, Set.of(), cancelled);
    }

    static Path root(Path destination) throws IOException {
        Path requested = destination.toAbsolutePath().normalize();
        Path root = Files.exists(requested, LinkOption.NOFOLLOW_LINKS) ? requested.toRealPath()
            : requested.getParent().toRealPath().resolve(requested.getFileName());
        verifyFolders(root, root, false);
        return root;
    }

    static void createRoot(Path root) throws IOException { verifyFolders(root, root, true); }

    void restore(Settings settings, Path source, Path target, FileStamp stamp, BooleanSupplier cancelled) throws IOException {
        if (source.startsWith(settings.root()) || !source.equals(source.toRealPath()))
            throw new IOException("The original resolves to a different location.");
        verifyFolders(settings.root(), target.getParent(), true);
        var entry = new Entry(source, target, stamp, false, false);
        verifySource(entry);
        if (settings.policy() == Policy.HARD_LINK) publishLink(entry, cancelled);
        else publishCopy(entry, cancelled);
    }

    Plan plan(Path root, Options options, Policy policy, LibraryScanner.Result scan, Map<Path, Track> indexed,
              Set<Path> occupied, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        var entries = new ArrayList<Entry>();
        var notes = new ArrayList<>(scan.errors());
        var reserved = new HashSet<String>();
        Path disk = Files.exists(root) ? root : root.getParent();
        var destinationStore = Files.getFileStore(disk);
        long bytes = 0;
        for (Track scanned : scan.tracks()) {
            checkCancelled(cancelled);
            Path source = scanned.path();
            if (source.startsWith(root)) throw new IOException("A selected source resolves inside the managed folder.");
            if (policy == Policy.HARD_LINK && !sameVolume(Files.getFileStore(source), destinationStore)) {
                notes.add(source + " · Hard links require the source and managed folder to be on the same volume.");
                continue;
            }
            Track track = withWorkReferences(indexed.getOrDefault(source, scanned));
            Path folder = root.resolve(relativeFolder(track, options));
            verifyFolders(root, folder, false);
            String filename = filename(track);
            Path preferred = folder.resolve(filename);
            Path target = preferred;
            boolean reuse = false;
            for (int suffix = 2; ; suffix++) {
                String key = target.toString().toLowerCase(Locale.ROOT);
                if (!reserved.contains(key) && !occupied.contains(target)) {
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        reserved.add(key);
                        break;
                    }
                    if (canReuse(source, target, policy, cancelled)) {
                        reserved.add(key);
                        reuse = true;
                        break;
                    }
                }
                target = folder.resolve(suffix(filename, suffix));
            }
            FileStamp stamp = scan.stamps().get(source);
            if (!reuse && policy == Policy.COPY) bytes = Math.addExact(bytes, stamp.size());
            entries.add(new Entry(source, target, stamp, reuse, !target.equals(preferred)));
        }
        return new Plan(root, policy, List.copyOf(entries), bytes, destinationStore.getUsableSpace(), List.copyOf(notes));
    }

    Path refile(Settings settings, Path source, Track track, boolean legacy, Set<Path> occupied,
                BooleanSupplier cancelled) throws IOException {
        var options = settings.options();
        Path folder = settings.root().resolve(relativeFolder(track, options, legacy));
        Path preferred = folder.resolve(legacy ? source.getFileName().toString()
            : settings.writeTags() ? filename(track, track.title()) : filename(track));
        if (source.equals(preferred)) return source;
        if (source.getParent().equals(folder) && source.getFileName().toString()
            .matches(Pattern.quote(preferred.getFileName().toString().replaceFirst("\\.[^.]+$", "")) + " \\(\\d+\\)\\.[^.]+$")) return source;
        verifyFolders(settings.root(), folder, true);
        Path target = preferred;
        for (int suffix = 2; occupied.contains(target) || Files.exists(target, LinkOption.NOFOLLOW_LINKS); suffix++) {
            if (!occupied.contains(target) && canReuse(source, target, settings.policy(), cancelled)) return target;
            target = folder.resolve(suffix(preferred.getFileName().toString(), suffix));
        }
        var attrs = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        var entry = new Entry(source, target, new FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis()), false, false);
        verifySource(entry);
        if (settings.policy() == Policy.HARD_LINK) publishLink(entry, cancelled);
        else publishCopy(entry, cancelled);
        return target;
    }

    private static boolean sameVolume(FileStore source, FileStore destination) throws IOException {
        if (source.equals(destination)) return true;
        if (!System.getProperty("os.name", "").startsWith("Windows")) return false;
        try {
            // Windows may report an extended-length root for one path and a drive letter for the other.
            return Objects.equals(source.getAttribute("volume:vsn"), destination.getAttribute("volume:vsn"));
        } catch (UnsupportedOperationException | IllegalArgumentException unavailable) {
            return false;
        }
    }

    public Result apply(Plan plan, BooleanSupplier cancelled, Consumer<Entry> progress) {
        var paths = new ArrayList<Path>();
        var failures = new ArrayList<String>();
        for (Entry entry : plan.entries()) {
            if (cancelled.getAsBoolean()) break;
            progress.accept(entry);
            try {
                verifyFolders(plan.root(), entry.target().getParent(), true);
                verifySource(entry);
                if (entry.reuse()) {
                    if (!canReuse(entry.source(), entry.target(), plan.policy(), cancelled))
                        throw new IOException("The existing destination changed after preview.");
                    verifySource(entry);
                } else if (plan.policy() == Policy.HARD_LINK) {
                    publishLink(entry, cancelled);
                } else {
                    publishCopy(entry, cancelled);
                }
                paths.add(entry.target());
            } catch (CancellationException stop) { break; }
            catch (IOException | SecurityException | UnsupportedOperationException error) {
                failures.add(entry.source().getFileName() + " · " + error.getMessage());
            }
        }
        return new Result(List.copyOf(paths), List.copyOf(failures), cancelled.getAsBoolean());
    }

    private static boolean canReuse(Path source, Path target, Policy policy, BooleanSupplier cancelled) throws IOException {
        if (!regular(target)) return false;
        boolean sameFile = Files.isSameFile(source, target);
        if (policy == Policy.HARD_LINK) return sameFile;
        return !sameFile && Files.size(target) == Files.size(source) && identical(source, target, cancelled);
    }

    private static void publishLink(Entry entry, BooleanSupplier cancelled) throws IOException {
        Path scratch = Files.createTempDirectory(entry.target().getParent(), ".sangitam-link-");
        Path staged = scratch.resolve("media");
        try {
            checkCancelled(cancelled);
            try {
                Files.createLink(staged, entry.source());
            } catch (IOException | UnsupportedOperationException error) {
                throw new IOException("Cannot create a hard link on this volume. " + error.getMessage(), error);
            }
            if (!regular(staged) || !Files.isSameFile(staged, entry.source()))
                throw new IOException("The original changed while the hard link was being created.");
            verifySource(entry);
            checkCancelled(cancelled);
            Files.move(staged, entry.target());
        } finally {
            Files.deleteIfExists(staged);
            Files.deleteIfExists(scratch);
        }
    }

    private static void publishCopy(Entry entry, BooleanSupplier cancelled) throws IOException {
        Path stage = Files.createTempFile(entry.target().getParent(), ".sangitam-copy-", ".part");
        try {
            try (var input = Files.newInputStream(entry.source(), LinkOption.NOFOLLOW_LINKS);
                 var output = Files.newOutputStream(stage)) {
                byte[] buffer = new byte[256 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    checkCancelled(cancelled);
                    output.write(buffer, 0, read);
                }
            }
            if (Files.size(stage) != entry.stamp().size() || !identical(entry.source(), stage, cancelled))
                throw new IOException("Copy verification failed.");
            verifySource(entry);
            Files.setLastModifiedTime(stage, java.nio.file.attribute.FileTime.fromMillis(entry.stamp().modifiedMillis()));
            checkCancelled(cancelled);
            // Stage and destination share a directory. Do not replace a file created since preview.
            Files.move(stage, entry.target());
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private static boolean identical(Path first, Path second, BooleanSupplier cancelled) throws IOException {
        try (var left = Files.newInputStream(first, LinkOption.NOFOLLOW_LINKS);
             var right = Files.newInputStream(second, LinkOption.NOFOLLOW_LINKS)) {
            byte[] a = new byte[256 * 1024], b = new byte[a.length];
            while (true) {
                checkCancelled(cancelled);
                int count = left.readNBytes(a, 0, a.length);
                if (count != right.readNBytes(b, 0, b.length)
                    || !Arrays.equals(a, 0, count, b, 0, count)) return false;
                if (count < a.length) return true;
            }
        }
    }

    private static void verifySource(Entry entry) throws IOException {
        var attrs = Files.readAttributes(entry.source(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || !entry.stamp().equals(new FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis())))
            throw new IOException("The source changed after preview.");
    }

    private static boolean regular(Path path) { return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS); }

    private static void verifyFolders(Path root, Path folder, boolean create) throws IOException {
        if (!folder.startsWith(root) || !folder.equals(folder.normalize()))
            throw new IOException("The managed folder moved or resolves to another location.");
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = root.getParent();
            if (parent == null || !parent.toRealPath().equals(parent)) throw new IOException("The library parent folder changed.");
            if (!create) return;
            try { Files.createDirectory(root); }
            catch (FileAlreadyExistsException concurrent) { /* Validate the newly created entry. */ }
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root))
            throw new IOException("The managed folder moved or resolves to another location.");
        Path current = root;
        for (Path part : root.relativize(folder)) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return;
                try { Files.createDirectory(current); }
                catch (FileAlreadyExistsException concurrent) { /* Validate the newly created entry. */ }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || !current.toRealPath().equals(current))
                throw new IOException("A destination folder is blocked or redirects to another location: " + current);
        }
    }

    static Track withWorkReferences(Track track) {
        var metadata = track.metadata();
        var fields = new EnumMap<Field, Value>(Field.class);
        fields.putAll(metadata.fields());
        String title = track.title().replace('_', ' ');
        String listedWork = metadata.text(Field.WORK);
        String work = ClassicalHints.workHint(title);
        if (work.isBlank()) {
            String filename = LibraryScanner.fileTitle(track.path().getFileName().toString()).replace('_', ' ');
            String filenameWork = ClassicalHints.workHint(filename);
            if (!filenameWork.isBlank()) {
                title = filename;
                work = filenameWork;
            }
        }
        String composer = WorkDetails.composerHint(track);
        boolean classical = track.genre().toLowerCase(Locale.ROOT).contains("classical")
            || track.metadata().fields().containsKey(Field.COMPOSER)
            || track.path().toString().toLowerCase(Locale.ROOT).contains("classical");
        if (!composer.isBlank() && (!work.isBlank() || WorkDetails.fromTitle(title, "", false).containsKey(Field.OPUS)
            || classical))
            fields.putIfAbsent(Field.COMPOSER, new Value(composer, Origin.TITLE_HINT));
        var references = WorkDetails.reference(track);
        boolean score = references.containsKey(Field.WORK);
        var origin = score ? Origin.SCORE_REFERENCE : Origin.TITLE_HINT;
        references.forEach((field, text) -> fields.compute(field, (key, existing) -> {
            if (existing == null) return new Value(text, origin);
            if (score && (existing.origin() == Origin.FOLDER_HINT || existing.origin() == Origin.TITLE_HINT))
                return new Value(text, origin);
            if (score && field == Field.WORK && existing.origin() == Origin.ONLINE
                && WorkDetails.genericWorkLabel(existing.text(), references.getOrDefault(Field.FORM, "")))
                return new Value(text, origin);
            return existing;
        }));
        if (score || fields.containsKey(Field.COMPOSER) || classical) {
            var currentForm = fields.get(Field.FORM);
            String namedForm = ClassicalHints.form(listedWork);
            if (namedForm.isBlank()) namedForm = ClassicalHints.form(title);
            if (!namedForm.isBlank() && currentForm != null && currentForm.origin() != Origin.USER
                && ClassicalHints.form(currentForm.text()).isBlank())
                fields.put(Field.FORM, new Value(namedForm, Origin.TITLE_HINT));
            ClassicalHints.enrich(track.path(), title, "Classical", fields).forEach(fields::putIfAbsent);
            if (!listedWork.isBlank())
                ClassicalHints.enrich(track.path(), listedWork, "Classical", fields).forEach(fields::putIfAbsent);
            if (!work.isBlank()) fields.putIfAbsent(Field.WORK, new Value(work, Origin.TITLE_HINT));
            fields.computeIfPresent(Field.WORK, (field, value) -> value.origin() == Origin.TITLE_HINT
                ? new Value(ClassicalHints.withoutPhase(value.text()), value.origin()) : value);
            if (fields.containsKey(Field.COMPOSER)) {
                var credit = fields.get(Field.COMPOSER);
                if (credit.origin() == Origin.FOLDER_HINT || credit.origin() == Origin.TITLE_HINT)
                    fields.put(Field.COMPOSER, new Value(WorkDetails.canonicalComposer(credit.text()), credit.origin()));
                String period = WorkDetails.periodFor(fields.get(Field.COMPOSER).text());
                if (!period.isBlank()) fields.compute(Field.PERIOD, (field, value) -> value == null
                    || value.origin() == Origin.FOLDER_HINT || value.origin() == Origin.TITLE_HINT
                    ? new Value(period, Origin.TITLE_HINT) : value);
            }
        }
        if (fields.equals(metadata.fields())) return track;
        var enriched = new TrackMetadata(fields, metadata.tags(), metadata.bitrateKbps(), metadata.sampleRateHz(),
            metadata.bitsPerSample(), metadata.channels(), metadata.embeddedArtwork(), metadata.extractionVersion());
        return new Track(track.path(), track.title(), track.artist(), track.album(), track.durationSeconds(),
            track.format(), track.genre(), enriched);
    }

    static Path relativeFolder(Track track, Options options) {
        return relativeFolder(track, options, false);
    }

    private static Path relativeFolder(Track track, Options options, boolean legacy) {
        var parts = new ArrayList<String>();
        var metadata = track.metadata();
        boolean classical = track.genre().toLowerCase(Locale.ROOT).contains("classical")
            || !metadata.text(Field.COMPOSER).isBlank() || !metadata.text(Field.OPUS).isBlank();
        if (options.genre()) parts.add(segment(classical ? "Classical"
            : track.genre().equalsIgnoreCase("Unknown genre") ? "Uncategorized" : track.genre(), "Uncategorized"));
        if (classical) {
            String composer = WorkDetails.canonicalComposer(metadata.text(Field.COMPOSER));
            var savedPeriod = metadata.fields().get(Field.PERIOD);
            String canonicalPeriod = WorkDetails.periodFor(composer);
            String period = !canonicalPeriod.isBlank() && (savedPeriod == null || savedPeriod.origin() != Origin.USER)
                ? canonicalPeriod : metadata.text(Field.PERIOD);
            if (options.period()) parts.add(segment(period, "Period unverified"));
            if (options.composer()) parts.add(segment(composer, "Composer unverified"));
            if (options.layout() == Layout.FORMS) {
                parts.add(Layout.FORMS.toString());
                String rawForm = metadata.text(Field.FORM);
                String form = ClassicalHints.form(rawForm);
                if (form.isBlank() && metadata.fields().containsKey(Field.FORM) && metadata.fields().get(Field.FORM).origin() == Origin.USER)
                    form = ClassicalHints.categoryCase(rawForm);
                parts.add(segment(form, "Other forms"));
                if (options.instrument() && !Set.of("symphony", "mass", "ballet").contains(form.toLowerCase(Locale.ROOT))
                    && !metadata.text(Field.INSTRUMENTATION).isBlank())
                    parts.add(segment(ClassicalHints.instrumentLabel(metadata.text(Field.INSTRUMENTATION)), "Unknown instrument"));
            } else if (options.layout() == Layout.OPUS) {
                parts.add(Layout.OPUS.toString());
                addKnown(parts, metadata.text(Field.OPUS));
            }
            if (options.work()) addKnown(parts, metadata.text(Field.WORK));
            if (options.recording()) parts.add(legacy ? legacyRecordingFolder(track) : recordingFolder(track));
        } else if (options.recording()) {
            addKnown(parts, track.artist());
            parts.add(recordingFolder(track));
        }
        return Path.of("", parts.toArray(String[]::new));
    }

    private static String legacyRecordingFolder(Track track) {
        String album = track.album();
        if (album.isBlank() || album.equalsIgnoreCase("Unknown album")) album = "";
        String artist = track.artist();
        if (artist.isBlank() || artist.equalsIgnoreCase("Unknown artist")) artist = "";
        return segment(String.join(" · ", java.util.stream.Stream.of(album, artist).filter(value -> !value.isBlank()).toList()),
            "Recording");
    }

    private static void addKnown(List<String> parts, String label) {
        if (!label.isBlank() && !label.toLowerCase(Locale.ROOT).startsWith("unknown"))
            parts.add(segment(label, "Uncategorized"));
    }

    private static String recordingFolder(Track track) {
        Path parent = track.path().getParent();
        if (parent == null || parent.getFileName() == null) return segment(track.artist(), "Unknown recording");
        String source = "-" + Integer.toUnsignedString(parent.toString().toLowerCase(Locale.ROOT).hashCode(), 36);
        boolean knownArtist = !track.artist().isBlank() && !track.artist().equalsIgnoreCase("Unknown artist");
        if (!track.album().isBlank() && !track.album().equalsIgnoreCase("Unknown album"))
            return segment(track.album() + (knownArtist ? " · " + track.artist() : ""), "Unknown recording") + source;
        String prefix = knownArtist ? track.artist() + " · " : "";
        return segment(prefix + parent.getFileName(), "Unknown recording") + source;
    }

    static String filename(Track track) {
        return filename(track, "");
    }

    private static String filename(Track track, String title) {
        String original = track.path().getFileName().toString();
        int dot = original.lastIndexOf('.');
        String extension = dot > 0 ? original.substring(dot) : "";
        var movement = MOVEMENT.matcher(track.metadata().text(Field.MOVEMENT));
        String prefix = "";
        if (movement.find()) {
            String number = movement.group(1).toUpperCase(Locale.ROOT);
            int position = switch (number) {
                case "I" -> 1; case "II" -> 2; case "III" -> 3; case "IV" -> 4; case "V" -> 5;
                case "VI" -> 6; case "VII" -> 7; case "VIII" -> 8; case "IX" -> 9; case "X" -> 10;
                default -> Character.isDigit(number.charAt(0)) ? Integer.parseInt(number) : 0;
            };
            if (position > 0) prefix = String.format(Locale.ROOT, "%02d - ", position);
        }
        String stem = title.isBlank() ? (dot > 0 ? original.substring(0, dot) : original) : title;
        if (!stem.startsWith(prefix)) stem = prefix + stem;
        int length = stem.codePointCount(0, stem.length());
        if (length > 68) stem = stem.substring(0, stem.offsetByCodePoints(0, 39)) + "…"
            + stem.substring(stem.offsetByCodePoints(0, length - 28));
        return segment(stem, "Recording") + extension;
    }

    private static String suffix(String filename, int index) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename + " (" + index + ")"
            : filename.substring(0, dot) + " (" + index + ")" + filename.substring(dot);
    }

    static String segment(String value, String fallback) {
        String cleaned = value.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_").strip().replaceAll("[. ]+$", "");
        if (cleaned.isBlank()) cleaned = fallback;
        if (cleaned.codePointCount(0, cleaned.length()) > 68)
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, 68)).replaceAll("[. ]+$", "");
        return RESERVED.matcher(cleaned).matches() ? "_" + cleaned : cleaned;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
    }
}
