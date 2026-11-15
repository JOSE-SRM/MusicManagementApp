package sangitam.desktop.library;

import sangitam.desktop.model.FileStamp;
import sangitam.desktop.model.LibrarySource;
import sangitam.desktop.model.Track;
import sangitam.desktop.storage.LibraryStore;
import sangitam.desktop.storage.ManagedLibraryStore;
import sangitam.desktop.storage.ManagedLibraryStore.Batch;
import sangitam.desktop.storage.ManagedLibraryStore.Placement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Coordinates managed placement, catalog switches, and recovery on the library worker. */
final class ActiveManagedLibrary {
    record Prepared(List<LibrarySource> media, List<LibrarySource> remembered, List<String> notes,
                    Batch batch, Map<Path, Track> hints) { }
    record Repaired(ManagedLibraryStore.Repair database, List<String> notes) { }
    private record FormerFolder(long playlist, Path previous, Path current) { }
    private record Relink(String previous, Path target, FormerFolder folders) { }
    private final LibraryStore library;
    private final ManagedLibraryStore state;
    private final ManagedLibraryCopy files = new ManagedLibraryCopy();
    private final LibraryScanner scanner = new LibraryScanner();

    ActiveManagedLibrary(LibraryStore library) {
        this.library = library;
        state = new ManagedLibraryStore(library);
    }

    boolean mergeCaseVariants(ManagedLibraryCopy.Settings settings, List<LibraryStore.CatalogEntry> catalog) throws Exception {
        var groups = new HashMap<Path, List<Path>>();
        for (var entry : catalog) {
            Path path = entry.track().path();
            if (path.startsWith(settings.root())) groups.computeIfAbsent(path, ignored -> new ArrayList<>()).add(path);
        }
        var mapped = new HashSet<String>();
        state.originals().forEach(item -> mapped.add(item.target().toString()));
        var duplicates = new ArrayList<ManagedLibraryStore.Duplicate>();
        for (var group : groups.values()) {
            if (group.size() < 2) continue;
            var preferred = group.stream().filter(path -> mapped.contains(path.toString())).toList();
            if (preferred.size() > 1) continue;
            Path target = preferred.isEmpty() ? group.getFirst() : preferred.getFirst();
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) continue;
            String physical = target.toRealPath().toString();
            for (Path old : group) {
                if (!old.toString().equals(target.toString()) && Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS)
                    && physical.equals(old.toRealPath().toString())) duplicates.add(new ManagedLibraryStore.Duplicate(old, target));
            }
        }
        if (duplicates.isEmpty()) return false;
        synchronized (library) { state.mergeDuplicates(duplicates); }
        return true;
    }

    Prepared prepare(ManagedLibraryCopy.Settings settings, boolean activating, boolean rescan,
                     List<LibrarySource> requested, Map<Path, LibraryScanner.KnownTrack> known,
                     List<Path> exclusions, Consumer<LibraryScanner.Progress> progress,
                     BooleanSupplier cancelled) throws Exception {
        Path root = ManagedLibraryCopy.root(settings.root());
        if (!activating && !settings.root().equals(root))
            throw new IOException("The managed library folder now resolves to a different location.");
        var paths = state.paths();
        var outside = new LinkedHashSet<LibrarySource>();
        var media = new LinkedHashSet<LibrarySource>();
        for (LibrarySource source : requested) {
            Path path = Files.exists(source.path()) ? source.path().toRealPath() : source.path();
            if (path.startsWith(root)) {
                if (!rescan) media.add(new LibrarySource(path, source.recursive()));
            } else if (paths.containsKey(path)) {
                if (!rescan) media.add(new LibrarySource(paths.get(path), false));
            } else outside.add(new LibrarySource(path, source.recursive()));
        }
        if (activating) known.keySet().stream().filter(path -> !path.startsWith(root))
            .map(path -> new LibrarySource(path, false)).forEach(outside::add);
        var scan = scanner.scan(List.copyOf(outside), known, false,
            path -> !path.startsWith(root) && !paths.containsKey(path)
                && (!rescan || exclusions.stream().noneMatch(path::startsWith)), progress, cancelled);
        var remembered = scan.sources().stream().filter(source -> requested.stream().anyMatch(request ->
            source.path().equals(request.path()))).toList();
        Map<Path, Track> indexed = new HashMap<>();
        if (activating) library.loadAll().forEach(track -> indexed.put(track.path(), track));
        var plan = files.plan(root, settings.options(), settings.policy(), scan, indexed, known.keySet(), cancelled);
        if (plan.bytes() > plan.available()) throw new IOException("The managed volume does not have enough free space.");
        if (activating) {
            var planned = new HashSet<Path>();
            plan.entries().forEach(entry -> planned.add(entry.source()));
            long missing = known.keySet().stream().filter(path -> !path.startsWith(root) && !planned.contains(path)).count();
            if (missing > 0) throw new IOException(missing + " existing tracks cannot be organized. Reconnect their source locations before activating.");
            if (!cancelled.getAsBoolean()) ManagedLibraryCopy.createRoot(root);
        }
        var result = files.apply(plan, cancelled, entry -> progress.accept(new LibraryScanner.Progress(
            0, plan.entries().size(), entry.source(), "Organizing managed files")));
        if (activating && !result.failures().isEmpty()) throw new IOException(result.failures().getFirst());
        var completed = Set.copyOf(result.indexedPaths());
        var placements = new ArrayList<Placement>();
        var hints = new HashMap<Path, Track>();
        var scanned = new HashMap<Path, Track>();
        scan.tracks().forEach(track -> scanned.put(track.path(), track));
        for (var entry : plan.entries()) {
            if (!completed.contains(entry.target())) continue;
            placements.add(new Placement(entry.source(), entry.target(), stamp(entry.target()), entry.stamp()));
            media.add(new LibrarySource(entry.target(), false));
            hints.put(entry.target(), ManagedLibraryCopy.withWorkReferences(
                indexed.getOrDefault(entry.source(), scanned.get(entry.source()))));
        }
        var notes = new ArrayList<>(plan.scanNotes());
        notes.addAll(result.failures());
        var activation = activating
            ? new ManagedLibraryCopy.Settings(root, settings.policy(), settings.options(), settings.writeTags()) : null;
        return new Prepared(List.copyOf(media), remembered, List.copyOf(notes), new Batch(activation, placements), Map.copyOf(hints));
    }

    Repaired repair(ManagedLibraryCopy.Settings settings, Map<Path, LibraryStore.CatalogEntry> indexed,
                    Consumer<LibraryScanner.Progress> progress, BooleanSupplier cancelled) throws Exception {
        Path root = ManagedLibraryCopy.root(settings.root());
        if (!root.equals(settings.root())) throw new IOException("The managed folder now resolves to a different location.");
        var originals = state.originals();
        var restored = new HashMap<Path, FileStamp>();
        var notes = new ArrayList<String>();
        for (var original : originals) {
            if (cancelled.getAsBoolean()) break;
            Path target = original.target();
            if (!target.startsWith(root) || !indexed.containsKey(target) || !Files.notExists(target, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(original.source(), LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                FileStamp sourceStamp = stamp(original.source());
                if (original.stamp() != null && !original.stamp().equals(sourceStamp))
                    throw new IOException("The original changed after import.");
                progress.accept(new LibraryScanner.Progress(restored.size(), originals.size(), target, "Restoring managed files"));
                files.restore(settings, original.source(), target, sourceStamp, cancelled);
                restored.put(target, stamp(target));
            } catch (IOException | SecurityException failure) {
                notes.add(target.getFileName() + " · " + failure.getMessage());
            }
        }
        return new Repaired(new ManagedLibraryStore.Repair(Map.copyOf(restored), repairReferences(originals, indexed, root)),
            List.copyOf(notes));
    }

    private Map<String, Path> repairReferences(List<ManagedLibraryStore.Original> originals,
        Map<Path, LibraryStore.CatalogEntry> indexed, Path root) throws Exception {
        var dangling = state.danglingPlaylistReferences();
        if (dangling.isEmpty()) return Map.of();
        var aliases = new HashMap<>(state.aliases());
        var byFilename = new HashMap<String, List<ManagedLibraryStore.Original>>();
        for (var original : originals) {
            aliases.put(original.source(), original.target());
            byFilename.computeIfAbsent(filenameKey(original.source()), ignored -> new ArrayList<>()).add(original);
        }
        var recovered = new HashMap<String, Path>();
        var candidates = new ArrayList<Relink>();
        var groups = new HashMap<FormerFolder, Set<Path>>();
        for (var item : dangling) {
            Path old;
            try { old = Path.of(item.path()).toAbsolutePath().normalize(); }
            catch (java.nio.file.InvalidPathException invalid) { continue; }
            var direct = indexed.get(aliases.getOrDefault(old, old));
            if (direct != null) {
                recovered.put(item.path(), direct.track().path());
                continue;
            }
            // A legacy folder move has stronger evidence when several exact filenames agree in the same playlist.
            var matches = byFilename.getOrDefault(filenameKey(old), List.of());
            if (matches.size() != 1 || old.getParent() == null || old.startsWith(root)
                || !Files.notExists(old) || !Files.isDirectory(old.getRoot())) continue;
            var match = matches.getFirst();
            if (!indexed.containsKey(match.target()) || !Files.isRegularFile(match.source())
                || match.source().getParent() == null
                || sangitam.desktop.discovery.RecordingLookup.missingTitle(LibraryScanner.fileTitle(filenameKey(old)))) continue;
            var folders = new FormerFolder(item.playlist(), old.getParent(), match.source().getParent());
            groups.computeIfAbsent(folders, ignored -> new HashSet<>()).add(old);
            candidates.add(new Relink(item.path(), match.target(), folders));
        }
        for (var candidate : candidates) {
            if (groups.get(candidate.folders()).size() >= 2) recovered.putIfAbsent(candidate.previous(), candidate.target());
        }
        return Map.copyOf(recovered);
    }

    private static String filenameKey(Path path) {
        return path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    }

    static Track retainHints(Track scanned, Track hint) {
        if (hint == null) return scanned;
        var metadata = scanned.metadata();
        var fields = new java.util.EnumMap<sangitam.desktop.model.TrackMetadata.Field,
            sangitam.desktop.model.TrackMetadata.Value>(sangitam.desktop.model.TrackMetadata.Field.class);
        fields.putAll(metadata.fields());
        hint.metadata().fields().forEach((field, value) -> {
            if (value.origin() != sangitam.desktop.model.TrackMetadata.Origin.USER
                && value.origin() != sangitam.desktop.model.TrackMetadata.Origin.ONLINE) fields.putIfAbsent(field, value);
        });
        return new Track(scanned.path(), scanned.title(), scanned.artist(), scanned.album(), scanned.durationSeconds(),
            scanned.format(), scanned.genre(), new sangitam.desktop.model.TrackMetadata(fields, metadata.tags(),
                metadata.bitrateKbps(), metadata.sampleRateHz(), metadata.bitsPerSample(), metadata.channels(),
                metadata.embeddedArtwork(), metadata.extractionVersion()));
    }

    String organize(List<Path> paths, boolean writeTags) throws Exception {
        return organize(paths, writeTags, false);
    }

    private String organize(List<Path> paths, boolean writeTags, boolean review) throws Exception {
        var settings = state.settings();
        if (settings == null || paths.isEmpty()) return "";
        var origins = new HashMap<Path, Path>();
        state.paths().forEach((source, target) -> origins.put(target, source));
        var selected = Set.copyOf(paths);
        var occupied = new HashSet<Path>();
        var tracks = library.loadAll();
        tracks.forEach(track -> occupied.add(track.path()));
        var failures = new ArrayList<String>();
        for (Track track : tracks) {
            Path path = track.path();
            Path original = origins.get(path);
            boolean legacy = original == null;
            if (Thread.currentThread().isInterrupted()) break;
            if (!selected.contains(path) || !path.startsWith(settings.root())
                || legacy && (!review || !WorkDetails.reference(track).containsKey(sangitam.desktop.model.TrackMetadata.Field.WORK))
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                continue;
            try {
                if (!path.equals(path.toRealPath()))
                    throw new IOException("The managed file resolves outside its catalog path.");
                if (writeTags && settings.writeTags() && settings.policy() == ManagedLibraryCopy.Policy.COPY
                    && ManagedAudioTags.write(track)) {
                    synchronized (library) { state.updateStamp(path, stamp(path)); }
                }
                var basis = ManagedLibraryCopy.withWorkReferences(new Track(legacy ? path : original, track.title(),
                    track.artist(), track.album(), track.durationSeconds(), track.format(), track.genre(), track.metadata()));
                Path target = files.refile(settings, path, basis, legacy, occupied, Thread.currentThread()::isInterrupted);
                if (!path.equals(target)) {
                    synchronized (library) { state.relocate(path, target, stamp(target)); }
                    occupied.add(target);
                }
                if (!basis.metadata().equals(track.metadata())) {
                    synchronized (library) { library.saveManagedFilingHints(target, basis.metadata().fields()); }
                }
            } catch (IOException | RuntimeException problem) {
                failures.add(path.getFileName() + " · " + problem.getMessage());
            }
        }
        recover(settings);
        return failures.isEmpty() ? "" : failures.size() + " managed files need attention. " + failures.getFirst();
    }

    String reviewFiling() throws Exception {
        var settings = state.settings();
        if (settings == null) return "";
        return organize(library.loadAll().stream().map(Track::path).toList(), false, true);
    }

    void recover(ManagedLibraryCopy.Settings settings) throws Exception {
        if (!Files.isDirectory(settings.root()) || !settings.root().equals(settings.root().toRealPath())) return;
        var pending = state.cleanup();
        if (pending.isEmpty()) return;
        var currentPaths = new HashSet<Path>();
        library.loadFileCatalog().forEach(entry -> currentPaths.add(entry.track().path()));
        for (var entry : pending) {
            Path previous = entry.previous();
            Path current = entry.current();
            if (!previous.startsWith(settings.root()) || !current.startsWith(settings.root())) continue;
            if (currentPaths.contains(previous) || previous.equals(current)) {
                state.finishCleanup(previous);
                continue;
            }
            try {
                if (Files.notExists(previous, LinkOption.NOFOLLOW_LINKS)) state.finishCleanup(previous);
                else if (Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)
                    && previous.equals(previous.toRealPath()) && current.equals(current.toRealPath())
                    && (Files.isSameFile(previous, current) || Files.mismatch(previous, current) == -1)) {
                    Files.delete(previous);
                    state.finishCleanup(previous);
                }
            } catch (IOException unavailable) {
                // A playing or temporarily unavailable file can be reconciled during a later rescan.
            }
        }
    }

    static FileStamp stamp(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("Not a regular file: " + path);
        return new FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis());
    }
}
