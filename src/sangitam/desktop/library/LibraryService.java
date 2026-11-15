package sangitam.desktop.library;

import sangitam.desktop.model.FileStamp;
import sangitam.desktop.model.LibrarySource;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;
import sangitam.desktop.model.DetailReset;
import sangitam.desktop.model.RecordingCandidate;
import sangitam.desktop.storage.LibraryStore;
import sangitam.desktop.storage.ManagedLibraryStore;
import sangitam.desktop.storage.RecordingMatchStore;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/** Keeps file decoding and SQLite work off Swing, then publishes immutable catalog snapshots on its event thread. */
public final class LibraryService {
    public record ManagedLocations(ManagedLibraryCopy.Settings settings, List<LibrarySource> sources,
                                   Map<Path, Path> paths, Map<Path, Path> aliases) {
        private static final ManagedLocations EMPTY = new ManagedLocations(null, List.of(), Map.of(), Map.of());
    }
    public record ImportSummary(int added, int indexed, int skipped, int unanalyzed,
                                List<String> errors, boolean cancelled, int reused, int unavailable, List<Path> paths) { }
    public record Catalog(List<Track> tracks, Map<Path, TrackTags> tags, Map<Path, Integer> playCounts,
                          Set<Path> manualTags, Set<Path> unavailable, Map<Path, Long> recent, Set<Path> metadataRefreshPaths,
                          Map<Path, FileStamp> stamps, Map<Path, Track> byPath, long measuredCount,
                          ManagedLocations locations) {
        public Catalog {
            tracks = List.copyOf(tracks);
            tags = Map.copyOf(tags);
            playCounts = Map.copyOf(playCounts);
            manualTags = Set.copyOf(manualTags);
            unavailable = Set.copyOf(unavailable);
            recent = Map.copyOf(recent);
            metadataRefreshPaths = Set.copyOf(metadataRefreshPaths);
            stamps = Map.copyOf(stamps);
            byPath = Map.copyOf(byPath);
        }
        public Catalog(List<Track> tracks, Map<Path, TrackTags> tags, Map<Path, Integer> playCounts,
                       Set<Path> manualTags, Set<Path> unavailable, Map<Path, Long> recent, Set<Path> metadataRefreshPaths,
                       Map<Path, FileStamp> stamps, Map<Path, Track> byPath, long measuredCount) {
            this(tracks, tags, playCounts, manualTags, unavailable, recent, metadataRefreshPaths, stamps, byPath, measuredCount,
                ManagedLocations.EMPTY);
        }
        public Catalog(List<Track> tracks, Map<Path, TrackTags> tags, Map<Path, Integer> playCounts,
                       Set<Path> manualTags, Set<Path> unavailable, Map<Path, Long> recent,
                       Set<Path> metadataRefreshPaths, Map<Path, FileStamp> stamps) {
            this(tracks, tags, playCounts, manualTags, unavailable, recent, metadataRefreshPaths, stamps,
                indexPaths(tracks), tags.values().stream()
                    .filter(value -> value.features().version() == sangitam.desktop.model.AudioFeatures.VERSION).count());
        }
        private static Map<Path, Track> indexPaths(List<Track> tracks) {
            var index = new HashMap<Path, Track>();
            for (Track track : tracks) index.put(track.path(), track);
            return index;
        }
        public Catalog(List<Track> tracks, Map<Path, TrackTags> tags, Map<Path, Integer> playCounts,
                       Set<Path> manualTags, Set<Path> unavailable, Map<Path, Long> recent, Set<Path> metadataRefreshPaths) {
            this(tracks, tags, playCounts, manualTags, unavailable, recent, metadataRefreshPaths, Map.of());
        }
        public Catalog(List<Track> tracks, Map<Path, TrackTags> tags, Map<Path, Integer> playCounts) {
            this(tracks, tags, playCounts, Set.of(), Set.of(), Map.of(), Set.of());
        }
        public TrackTags tagsFor(Track track) { return tags.getOrDefault(track.path(), TrackTags.pending()); }
        public int playsFor(Track track) { return playCounts.getOrDefault(track.path(), 0); }
        public Track currentTrack(Path path) {
            Track direct = byPath.get(path);
            if (direct != null) return direct;
            Path target = locations.aliases().getOrDefault(path, locations.paths().get(path));
            return target == null ? null : byPath.get(target);
        }
    }
    private record Snapshot(Catalog catalog, long revision) { }
    private enum ScanMode { IMPORT, RESCAN, REFRESH_METADATA, REPAIR_MANAGED }

    private final LibraryStore store;
    private final BackgroundRecordingMetadata metadataResolver;
    private final ManagedLibraryStore managedState;
    private final ActiveManagedLibrary managed;
    private final java.util.ArrayDeque<MetadataRequest> pendingMetadata = new java.util.ArrayDeque<>();
    private SwingWorker<MetadataBatch, Snapshot> metadataWorker;
    private MetadataRequest activeMetadata;
    private boolean metadataClosed;
    private record MetadataRequest(Path path, boolean online, boolean refresh, Consumer<String> report) { }
    private record MetadataBatch(int updated, String warning) { }
    private final Object scanLock = new Object();
    private final LibraryScanner scanner = new LibraryScanner();
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final List<Consumer<List<Track>>> trackListeners = new ArrayList<>();
    private final List<Consumer<Catalog>> catalogListeners = new ArrayList<>();
    private final AtomicLong revisions = new AtomicLong();
    private Catalog catalog = new Catalog(List.of(), Map.of(), Map.of());
    private Catalog latestStoredCatalog; // Accessed only under the store lock.
    private long publishedRevision = -1;
    private boolean initialLoaded;
    public boolean initialLoaded() { return initialLoaded; }

    public LibraryService(LibraryStore store) {
        this.store = store;
        this.metadataResolver = new BackgroundRecordingMetadata(store);
        this.managedState = new ManagedLibraryStore(store);
        this.managed = new ActiveManagedLibrary(store);
    }

    public void identifyInBackground(boolean online, boolean refresh, Consumer<String> report) {
        pendingMetadata.removeIf(request -> request.path() == null);
        pendingMetadata.addLast(new MetadataRequest(null, online, refresh, report));
        startMetadataWorker();
    }

    public void cancelMetadataLookups() {
        pendingMetadata.clear();
        if (metadataWorker != null) metadataWorker.cancel(true);
    }

    public void closeMetadataLookups() {
        metadataClosed = true;
        cancelMetadataLookups();
    }

    private void startMetadataWorker() {
        if (metadataClosed || metadataWorker != null || pendingMetadata.isEmpty()) return;
        var request = pendingMetadata.removeFirst();
        activeMetadata = request;
        Catalog snapshot = catalog();
        metadataWorker = new SwingWorker<>() {
            @Override protected MetadataBatch doInBackground() throws Exception {
                int updated = 0;
                int pending = 0;
                var pendingManaged = new ArrayList<Path>();
                long lastPublish = System.nanoTime();
                String warning = "";
                boolean online = request.online();
                boolean fingerprints = request.online();
                String key = sangitam.desktop.storage.ProviderCredentials.get(
                    sangitam.desktop.storage.ProviderCredentials.ACOUSTID_KEY, "SANGITAM_ACOUSTID_KEY");
                var candidates = request.path() == null ? snapshot.tracks()
                    : java.util.stream.Stream.of(snapshot.byPath().get(request.path())).filter(java.util.Objects::nonNull).toList();
                for (Track track : candidates) {
                    if (isCancelled()) break;
                    boolean needsPlacementMetadata = snapshot.locations().settings() != null && online
                        && track.genre().toLowerCase(java.util.Locale.ROOT).contains("classical")
                        && List.of(sangitam.desktop.model.TrackMetadata.Field.COMPOSER,
                            sangitam.desktop.model.TrackMetadata.Field.WORK, sangitam.desktop.model.TrackMetadata.Field.FORM)
                        .stream().anyMatch(field -> track.metadata().text(field).isBlank());
                    if (snapshot.unavailable().contains(track.path())
                        || request.path() == null && !needsPlacementMetadata
                        && !BackgroundRecordingMetadata.eligible(track, online || fingerprints && key != null)) continue;
                    try {
                        var result = metadataResolver.update(track.path(), online, fingerprints, request.refresh(), key);
                        if (result.changed()) {
                            updated++;
                            pending++;
                            if (snapshot.locations().settings() != null) pendingManaged.add(track.path());
                        }
                        if (!result.warning().isBlank() && (warning.isBlank() || result.warning().contains("lookup paused")))
                            warning = result.warning();
                        if (result.musicBrainzFailed()) online = false;
                        if (result.acoustIdFailed()) fingerprints = false;
                    } catch (InterruptedException stop) {
                        break;
                    } catch (Exception error) {
                        if (warning.isBlank()) warning = "Some files could not be checked.";
                    }
                    if (pending > 0 && System.nanoTime() - lastPublish > 10_000_000_000L) {
                        String filing = organizeManaged(pendingManaged);
                        if (!filing.isBlank()) warning = filing;
                        pendingManaged.clear();
                        synchronized (store) { publish(changedSnapshot()); }
                        pending = 0;
                        lastPublish = System.nanoTime();
                    }
                }
                if (pending > 0 && !isCancelled()) {
                    String filing = organizeManaged(pendingManaged);
                    if (!filing.isBlank()) warning = filing;
                    synchronized (store) { publish(changedSnapshot()); }
                }
                return new MetadataBatch(updated, warning);
            }

            @Override protected void process(List<Snapshot> updates) {
                if (!metadataClosed) publishSnapshot(updates.getLast());
            }

            @Override protected void done() {
                metadataWorker = null;
                activeMetadata = null;
                if (!metadataClosed && !isCancelled()) {
                    try {
                        var result = get();
                        if (!result.warning().isBlank()) request.report().accept(result.warning());
                        else if (request.online() && result.updated() > 0)
                            request.report().accept("Background identification checked " + result.updated() + " recordings.");
                    } catch (Exception error) { request.report().accept("Could not update background metadata."); }
                }
                startMetadataWorker();
            }
        };
        metadataWorker.execute();
    }

    public List<Track> snapshot() { return catalog.tracks(); }
    public Catalog catalog() { return catalog; }

    /** Existing simple listeners remain useful for screens and stage-one callers. */
    public void addListener(Consumer<List<Track>> listener) {
        trackListeners.add(listener);
        listener.accept(catalog.tracks());
    }

    public void addCatalogListener(Consumer<Catalog> listener) {
        catalogListeners.add(listener);
        listener.accept(catalog);
    }

    public void loadInitial(Consumer<String> report) {
        new SwingWorker<Snapshot, Void>() {
            @Override protected Snapshot doInBackground() throws Exception {
                synchronized (store) {
                    store.initialize();
                    latestStoredCatalog = readCatalog();
                    return new Snapshot(latestStoredCatalog, revisions.get());
                }
            }

            @Override protected void done() {
                try {
                    Snapshot loaded = get();
                    initialLoaded = true;
                    publishSnapshot(loaded);
                    if (!catalog.tracks().isEmpty()) report.accept("Loaded " + catalog.tracks().size() + " saved tracks");
                    refreshMetadata(report);
                } catch (Exception error) {
                    report.accept("Library could not be loaded: " + cause(error).getMessage());
                }
            }
        }.execute();
    }

    private void refreshMetadata(Consumer<String> report) {
        if (catalog.metadataRefreshPaths().isEmpty()) { analyzeMissing(report, false); return; }
        Catalog input = catalog;
        new SwingWorker<List<LibrarySource>, Void>() {
            @Override protected List<LibrarySource> doInBackground() {
                return input.metadataRefreshPaths().stream().filter(path -> !input.unavailable().contains(path))
                    .map(path -> new LibrarySource(path, false)).toList();
            }
            @Override protected void done() {
                try {
                    List<LibrarySource> repairs = get();
                    if (repairs.isEmpty()) { analyzeMissing(report, false); return; }
                    scanMedia(repairs, ScanMode.REFRESH_METADATA, ignored -> { }, result -> {
                        int repaired = result.indexed() - result.reused();
                        if (repaired > 0) report.accept("Refreshed metadata for " + repaired + " tracks");
                        analyzeMissing(report, false);
                    }, report);
                } catch (Exception error) { report.accept("Could not prepare metadata refresh"); }
            }
        }.execute();
    }

    /** Reanalyzes older libraries in the background after their tracks are already visible. */
    private SwingWorker<?,?> analysisWorker;
    private AnalysisControl analysisControl;
    private static final class AnalysisControl {
        private volatile boolean cancelled;
        private Thread thread;
        synchronized void attach() {
            thread = Thread.currentThread();
            if (cancelled) thread.interrupt();
        }
        synchronized void cancel() {
            cancelled = true;
            if (thread != null) thread.interrupt();
        }
        synchronized void detach() {
            thread = null;
            Thread.interrupted();
        }
    }
    public void cancelAnalysis() { if (analysisControl != null) analysisControl.cancel(); }
    public void analyzeNow(Consumer<String> report) { analyzeMissing(report, true); }

    public void analyzeTrack(Track track, AudioAnalyzer.Sampling sampling, boolean suggestMoodTags, String musicalKey,
                             Function<TrackTags, List<String>> review, Consumer<String> report) {
        if (analysisWorker != null && !analysisWorker.isDone()) {
            report.accept("Music analysis is already running. Wait for it to finish or choose Cancel analysis.");
            return;
        }
        AnalysisControl control = new AnalysisControl();
        analysisControl = control;
        analysisWorker = new SwingWorker<Snapshot, Void>() {
            private String message = "Track analysis discarded";

            @Override protected Snapshot doInBackground() throws Exception {
                control.attach();
                try {
                    TrackTags measured = analyzer.analyze(track.path(), track.durationSeconds(), sampling, suggestMoodTags);
                    if (control.cancelled) return null;
                    if (!measured.analyzed()) {
                        message = measured.failure() == null ? "No audio measurements were available" : measured.failure().message();
                        return null;
                    }
                    var selected = new AtomicReference<List<String>>();
                    SwingUtilities.invokeAndWait(() -> {
                        if (!control.cancelled) selected.set(review.apply(measured));
                    });
                    if (selected.get() == null || control.cancelled) return null;
                    synchronized (scanLock) {
                        var stamp = Files.readAttributes(track.path(), BasicFileAttributes.class);
                        synchronized (store) {
                            if (control.cancelled) return null;
                            if (!stamp.isRegularFile() || !measured.matches(stamp.size(), stamp.lastModifiedTime().toMillis())
                                || !store.saveTrackAnalysis(track.path(), measured.withMoodTags(selected.get()), musicalKey)) {
                                message = "The file or catalog entry changed. Rescan the library and try again.";
                                return null;
                            }
                            message = "Analysis saved for " + track.title();
                            return changedSnapshot();
                        }
                    }
                } catch (InterruptedException cancelled) {
                    return null;
                } finally {
                    control.detach();
                }
            }

            @Override protected void done() {
                try {
                    Snapshot result = get();
                    if (result != null) publishSnapshot(result);
                    report.accept(control.cancelled && result == null ? "Track analysis cancelled" : message);
                } catch (Exception error) {
                    report.accept("Could not save track analysis: " + cause(error).getMessage());
                }
            }
        };
        report.accept("Analysing " + track.title() + "…");
        analysisWorker.execute();
    }

    private void analyzeMissing(Consumer<String> report, boolean checkDisk) {
        if (analysisWorker != null && !analysisWorker.isDone()) { report.accept("Music analysis is already running"); return; }
        Catalog input = catalog;
        AnalysisControl control = new AnalysisControl();
        analysisControl = control;
        analysisWorker = new SwingWorker<Snapshot, Void>() {
            private List<Track> missing = List.of();
            @Override protected Snapshot doInBackground() throws Exception {
                control.attach();
                try {
                    missing = input.tracks().stream()
                        .takeWhile(track -> !control.cancelled)
                        .filter(track -> !input.unavailable().contains(track.path()))
                        .filter(track -> checkDisk || !freshAtIndexedStamp(input, track))
                        .filter(track -> needsAnalysis(input.tagsFor(track), track.path(), checkDisk)).toList();
                    if (missing.isEmpty()) return null;
                    long lastPublish = System.nanoTime();
                    int completed = 0;
                    for (Track track : missing) {
                        if (control.cancelled) break;
                        TrackTags tags = analyzer.analyze(track.path(), track.durationSeconds());
                        if (control.cancelled) break;
                        if (tags.fileSize() >= 0) {
                            synchronized (store) { store.saveTags(track.path(), tags); }
                        }
                        completed++;
                        if (System.nanoTime() - lastPublish > 10_000_000_000L) {
                            Snapshot partial;
                            synchronized (store) { partial = changedSnapshot(); }
                            int progress = completed;
                            SwingUtilities.invokeLater(() -> {
                                publishSnapshot(partial);
                                report.accept("Deep analysis: " + progress + " / " + missing.size());
                            });
                            lastPublish = System.nanoTime();
                        }
                    }
                } catch (Exception error) {
                    if (!control.cancelled) throw error;
                } finally {
                    control.detach();
                }
                synchronized (store) { return changedSnapshot(); }
            }

            @Override protected void done() {
                try {
                    Snapshot result = get();
                    if (result != null) publishSnapshot(result);
                    if (control.cancelled) {
                        report.accept("Music analysis cancelled. Completed measurements were saved.");
                        return;
                    }
                    if (result == null) {
                        if (checkDisk) report.accept("All available tracks have current analysis");
                        return;
                    }
                    long unavailable = missing.stream().filter(track ->
                        catalog.tagsFor(track).features().version() != sangitam.desktop.model.AudioFeatures.VERSION).count();
                    report.accept(unavailable == 0 ? "Music analysis finished"
                        : "Analysis finished; " + unavailable + " tracks could not be measured");
                } catch (Exception error) {
                    report.accept("Some tracks could not be analyzed: " + cause(error).getMessage());
                }
            }
        }; analysisWorker.execute();
    }

    private boolean freshAtIndexedStamp(Catalog input, Track track) {
        FileStamp stamp = input.stamps().get(track.path());
        return stamp != null && stamp.size() >= 0 && input.tagsFor(track).current(stamp.size(), stamp.modifiedMillis());
    }

    public SwingWorker<ImportSummary, LibraryScanner.Progress> importMedia(
        List<Path> sources, boolean recursive, Consumer<LibraryScanner.Progress> onProgress,
        Consumer<ImportSummary> onDone, Consumer<String> onError) {
        List<LibrarySource> requested = sources.stream().map(path -> new LibrarySource(path, recursive)).toList();
        return scanMedia(requested, ScanMode.IMPORT, onProgress, onDone, onError);
    }

    public SwingWorker<ImportSummary, LibraryScanner.Progress> rescan(
        Consumer<LibraryScanner.Progress> onProgress, Consumer<ImportSummary> onDone, Consumer<String> onError) {
        return scanMedia(List.of(), ScanMode.RESCAN, onProgress, onDone, onError);
    }

    private SwingWorker<ImportSummary, LibraryScanner.Progress> managedSync;

    public void syncManaged(Consumer<String> report) {
        if (catalog.locations().settings() == null || managedSync != null && !managedSync.isDone()) return;
        managedSync = rescan(ignored -> { }, summary -> {
            if (!summary.errors().isEmpty()) report.accept(summary.errors().getFirst());
            else if (summary.added() > 0) report.accept("Managed library added " + summary.added() + " tracks.");
        }, report);
    }

    public SwingWorker<ImportSummary, LibraryScanner.Progress> repairManaged(
        Consumer<LibraryScanner.Progress> progress, Consumer<ImportSummary> done, Consumer<String> error) {
        return scanMedia(List.of(), ScanMode.REPAIR_MANAGED, progress, done, error);
    }

    public SwingWorker<ImportSummary, LibraryScanner.Progress> activateManaged(ManagedLibraryCopy.Settings settings,
        List<Path> sources, boolean recursive, Consumer<LibraryScanner.Progress> progress,
        Consumer<ImportSummary> done, Consumer<String> error) {
        return scanMedia(sources.stream().map(path -> new LibrarySource(path, recursive)).toList(),
            ScanMode.IMPORT, progress, done, error, settings);
    }

    private SwingWorker<ImportSummary, LibraryScanner.Progress> scanMedia(
        List<LibrarySource> requested, ScanMode mode, Consumer<LibraryScanner.Progress> onProgress,
        Consumer<ImportSummary> onDone, Consumer<String> onError) {
        return scanMedia(requested, mode, onProgress, onDone, onError, null);
    }

    private SwingWorker<ImportSummary, LibraryScanner.Progress> scanMedia(
        List<LibrarySource> requested, ScanMode mode, Consumer<LibraryScanner.Progress> onProgress,
        Consumer<ImportSummary> onDone, Consumer<String> onError, ManagedLibraryCopy.Settings activation) {
        SwingWorker<ImportSummary, LibraryScanner.Progress> worker = new SwingWorker<>() {
            private Snapshot saved;

            @Override protected ImportSummary doInBackground() throws Exception {
                synchronized (scanLock) {
                    if (isCancelled()) return cancelledSummary();
                    return scan();
                }
            }

            private ImportSummary scan() throws Exception {
                Map<Path, LibraryStore.CatalogEntry> existing = new HashMap<>();
                List<LibraryStore.CatalogEntry> fileCatalog;
                List<LibrarySource> sources;
                List<Path> exclusions;
                ManagedLibraryCopy.Settings settings;
                synchronized (store) {
                    store.initialize();
                    fileCatalog = store.loadFileCatalog();
                    sources = mode == ScanMode.RESCAN ? store.loadSources() : requested;
                    exclusions = store.loadExclusions();
                    settings = managedState.settings();
                }
                if (activation != null && settings != null && (!settings.root().equals(ManagedLibraryCopy.root(activation.root()))
                    || settings.policy() != activation.policy()))
                    throw new IOException("The active managed library already has a location and import policy.");
                if (settings != null) managed.recover(settings);
                boolean checkIndexed = mode == ScanMode.RESCAN || mode == ScanMode.REPAIR_MANAGED;
                if (mode == ScanMode.REPAIR_MANAGED && settings == null) throw new IOException("No active managed library.");
                if (settings != null && checkIndexed && managed.mergeCaseVariants(settings, fileCatalog))
                    fileCatalog = store.loadFileCatalog();
                for (var entry : fileCatalog) existing.put(entry.track().path(), entry);
                var repair = settings != null && checkIndexed
                    ? managed.repair(settings, existing, this::publish, this::isCancelled)
                    : new ActiveManagedLibrary.Repaired(ManagedLibraryStore.Repair.EMPTY, List.of());
                Map<Path, LibraryScanner.KnownTrack> known = new HashMap<>();
                existing.forEach((path, entry) -> {
                    FileStamp restored = repair.database().restored().get(path);
                    known.put(path, new LibraryScanner.KnownTrack(entry.track(),
                        restored == null ? entry.stamp() : restored, restored == null && needsMetadataRefresh(entry)));
                });
                var configuration = activation != null ? activation : settings;
                var prepared = configuration == null || mode == ScanMode.REFRESH_METADATA || mode == ScanMode.REPAIR_MANAGED ? null
                    : managed.prepare(configuration, activation != null, mode == ScanMode.RESCAN, sources, known,
                        exclusions, this::publish, this::isCancelled);
                var requestedScan = prepared == null ? sources : prepared.media();
                LibraryScanner.Result result = scanner.scan(requestedScan, known, checkIndexed,
                    path -> !checkIndexed || settings != null && path.startsWith(settings.root()) && known.containsKey(path)
                        || exclusions.stream().noneMatch(path::startsWith), update -> {
                    if (update.visited() % 32 == 1) publish(update);
                }, this::isCancelled);
                if (isCancelled()) return cancelledSummary();

                List<Track> changed = new ArrayList<>();
                Map<Path, TrackTags> measured = new HashMap<>();
                Map<Path, Path> sourcesByPlacement = new HashMap<>();
                if (prepared != null) prepared.batch().placements().forEach(p -> sourcesByPlacement.put(p.target(), p.source()));
                int added = 0;
                int unanalyzed = 0;
                for (int i = 0; i < result.tracks().size(); i++) {
                    if (isCancelled()) return cancelledSummary();
                    Track track = result.tracks().get(i);
                    if (prepared != null) track = ActiveManagedLibrary.retainHints(track, prepared.hints().get(track.path()));
                    FileStamp stamp = result.stamps().get(track.path());
                    LibraryStore.CatalogEntry before = existing.get(track.path());
                    if (before == null) {
                        Path source = sourcesByPlacement.get(track.path());
                        if (source != null) before = existing.get(source);
                        if (before == null) added++;
                    }
                    if (before == null || !track.equals(before.track())
                        || !stamp.equals(before.stamp()) && !repair.database().restored().containsKey(track.path())) changed.add(track);
                    TrackTags tags = before == null ? TrackTags.pending() : before.tags();
                    if (!tags.current(stamp.size(), stamp.modifiedMillis())) {
                        tags = analyzer.analyze(track.path(), track.durationSeconds());
                        if (tags.matches(stamp.size(), stamp.modifiedMillis())) measured.put(track.path(), tags);
                    }
                    if (tags.features().version() == 0) unanalyzed++;
                    if (i % 16 == 0) publish(new LibraryScanner.Progress(i + 1,
                        result.tracks().size(), track.path(), "Checking analysis"));
                }
                synchronized (store) {
                    if (isCancelled()) return cancelledSummary();
                    if (prepared != null && !result.stamps().keySet().containsAll(prepared.hints().keySet()))
                        throw new IOException("A managed file could not be indexed. Its source and catalog entry remain available.");
                    List<Path> restored = mode == ScanMode.IMPORT ? exclusions.stream().filter(excluded ->
                        (prepared == null ? result.sources() : prepared.remembered()).stream().anyMatch(source -> source.path().startsWith(excluded)
                            || (source.recursive() && excluded.startsWith(source.path())))
                        || result.stamps().containsKey(excluded)).toList() : List.of();
                    store.saveScan(changed, result.stamps(), result.unavailable(),
                        mode == ScanMode.IMPORT ? (prepared == null ? result.sources() : prepared.remembered()) : List.of(),
                        measured, restored, prepared == null ? null : prepared.batch(), repair.database());
                }
                var notes = new ArrayList<>(result.errors());
                notes.addAll(repair.notes());
                if (prepared != null) {
                    notes.addAll(prepared.notes());
                    var changedPaths = new java.util.LinkedHashSet<Path>();
                    changed.forEach(track -> changedPaths.add(track.path()));
                    if (activation != null || !changedPaths.isEmpty()) {
                        String warning = managed.organize(activation == null ? List.copyOf(changedPaths)
                            : store.loadAll().stream().map(Track::path).toList(), true);
                        if (!warning.isBlank()) notes.add(warning);
                    }
                }
                if (settings != null && checkIndexed && !filingReviewed) {
                    String warning = managed.reviewFiling();
                    if (!warning.isBlank()) notes.add(warning);
                    filingReviewed = warning.isBlank();
                }
                synchronized (store) { saved = changedSnapshot(); }
                var aliases = managedState.aliases();
                SwingUtilities.invokeLater(() -> publishSnapshot(saved));
                return new ImportSummary(added, result.tracks().size(), result.skipped(), unanalyzed,
                    List.copyOf(notes), false, result.reused(), saved.catalog().unavailable().size(),
                    result.tracks().stream().map(Track::path).map(path -> aliases.getOrDefault(path, path)).toList());
            }

            private ImportSummary cancelledSummary() {
                return new ImportSummary(0, 0, 0, 0, List.of(), true, 0, 0, List.of());
            }

            @Override protected void process(List<LibraryScanner.Progress> updates) {
                if (!updates.isEmpty()) onProgress.accept(updates.get(updates.size() - 1));
            }

            @Override protected void done() {
                if (isCancelled()) {
                    onDone.accept(cancelledSummary());
                    return;
                }
                try {
                    ImportSummary summary = get();
                    publishSnapshot(saved);
                    onDone.accept(summary);
                } catch (Exception error) {
                    String action = switch (mode) {
                        case IMPORT -> "Import";
                        case RESCAN -> "Rescan";
                        case REFRESH_METADATA -> "Track metadata refresh";
                        case REPAIR_MANAGED -> "Managed library repair";
                    };
                    onError.accept(action + " failed: " + cause(error).getMessage());
                }
            }
        };
        worker.execute();
        return worker;
    }

    private boolean filingReviewed; // Accessed on the serialized scan worker.

    public void editDetails(List<Path> paths, LibraryStore.DetailChanges changes, Consumer<String> report) {
        changeCatalog(() -> store.updateDetails(paths, changes), "Track details saved", report, paths);
    }
    public void refreshFromStore(Consumer<String> report) { changeCatalog(() -> { }, "Artist identities refreshed", report); }

    public void restoreDetails(List<Path> paths, DetailReset reset, Consumer<String> report) {
        if (reset.empty()) { report.accept("Select at least one detail to restore."); return; }
        List<Path> requested = paths.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
        Set<Path> selected = Set.copyOf(requested);
        new SwingWorker<Snapshot, Void>() {
            private int restored;
            @Override protected Snapshot doInBackground() throws Exception {
                synchronized (scanLock) {
                    var known = new HashMap<Path, LibraryScanner.KnownTrack>();
                    synchronized (store) {
                        for (var entry : store.loadFileCatalog()) {
                            if (selected.contains(entry.track().path())) known.put(entry.track().path(),
                                new LibraryScanner.KnownTrack(entry.track(), entry.stamp(), true));
                        }
                    }
                    var sources = known.keySet().stream().map(path -> new LibrarySource(path, false)).toList();
                    var read = scanner.scan(sources, known, false, ignored -> { }, this::isCancelled);
                    if (isCancelled()) return null;
                    synchronized (store) {
                        restored = store.restoreDetails(read.tracks(), reset);
                        return restored == 0 ? null : changedSnapshot();
                    }
                }
            }
            @Override protected void done() {
                try {
                    Snapshot snapshot = get();
                    if (snapshot != null) publishSnapshot(snapshot);
                    int skipped = requested.size() - restored;
                    report.accept("Restored file details for " + restored + " tracks" + (skipped == 0 ? "" : " · " + skipped + " unchanged (unavailable, unreadable, or no longer indexed)"));
                } catch (Exception error) { report.accept("Could not restore file details: " + cause(error).getMessage()); }
            }
        }.execute();
    }

    public void removeFromLibrary(List<Path> paths, Path scope, Consumer<String> report) {
        changeCatalog(() -> {
            var removed = new java.util.LinkedHashSet<Path>(paths);
            if (scope != null) {
                store.loadAll().stream().map(Track::path).filter(path -> path.startsWith(scope)).forEach(removed::add);
                managedState.paths().forEach((source, managed) -> {
                    if (source.startsWith(scope)) removed.add(managed);
                });
            }
            store.removeFromLibrary(List.copyOf(removed), scope);
        }, "Removed from library", report);
    }

    public void saveRecordingMatch(RecordingMatchStore.Context expected, RecordingCandidate candidate,
                                   RecordingCandidate.Release release,
                                   boolean fill, String query, Consumer<String> report) {
        String linked = "MusicBrainz recording linked";
        changeCatalog(() -> new RecordingMatchStore(store).save(expected, candidate, release, fill, query), linked, message -> {
            report.accept(message);
            if (linked.equals(message)) {
                pendingMetadata.addFirst(new MetadataRequest(expected.track().path(), true, true, report));
                if (activeMetadata != null && activeMetadata.path() == null) {
                    if (pendingMetadata.stream().noneMatch(request -> request.path() == null))
                        pendingMetadata.addLast(activeMetadata);
                    metadataWorker.cancel(true);
                }
                startMetadataWorker();
            }
        });
    }

    public void removeRecordingMatch(RecordingMatchStore.Context expected,
                                     Consumer<String> report) {
        changeCatalog(() -> new RecordingMatchStore(store).remove(expected),
            "MusicBrainz link removed", report);
    }

    @FunctionalInterface
    private interface CatalogEdit { void run() throws Exception; }

    private void changeCatalog(CatalogEdit edit, String message, Consumer<String> report) {
        changeCatalog(edit, message, report, List.of());
    }

    private void changeCatalog(CatalogEdit edit, String message, Consumer<String> report, List<Path> organize) {
        new SwingWorker<Snapshot, Void>() {
            private String filing = "";
            @Override protected Snapshot doInBackground() throws Exception {
                synchronized (scanLock) {
                    synchronized (store) {
                        edit.run();
                    }
                    filing = organizeManaged(organize);
                    synchronized (store) { return changedSnapshot(); }
                }
            }

            @Override protected void done() {
                try {
                    publishSnapshot(get());
                    report.accept(filing.isBlank() ? message : message + " · " + filing);
                } catch (Exception error) {
                    report.accept("Library change failed: " + cause(error).getMessage());
                }
            }
        }.execute();
    }

    /** A successful play is counted once by MainFrame, never on the Swing thread. */
    public void recordPlay(Path path, Consumer<String> onError) {
        Track currentTrack = catalog.currentTrack(path);
        Path recordedPath = currentTrack == null ? path : currentTrack.path();
        new SwingWorker<Snapshot, Void>() {
            @Override protected Snapshot doInBackground() throws Exception {
                synchronized (store) {
                    var play = store.recordPlay(recordedPath);
                    if (play == null) return null;
                    var current = latestStoredCatalog;
                    if (current == null || !current.byPath().containsKey(recordedPath)) return changedSnapshot();
                    var counts = new HashMap<>(current.playCounts());
                    counts.put(recordedPath, play.plays());
                    var recent = new HashMap<>(current.recent());
                    recent.put(recordedPath, play.recentAt());
                    latestStoredCatalog = new Catalog(current.tracks(), current.tags(), counts, current.manualTags(),
                        current.unavailable(), recent, current.metadataRefreshPaths(), current.stamps(), current.byPath(),
                        current.measuredCount(), current.locations());
                    return new Snapshot(latestStoredCatalog, revisions.incrementAndGet());
                }
            }
            @Override protected void done() {
                try { var update = get(); if (update != null) publishSnapshot(update); }
                catch (Exception error) { onError.accept("Could not save play history: " + cause(error).getMessage()); }
            }
        }.execute();
    }

    /** Saves the listener's corrections locally; it does not rewrite their audio file. */
    public void updateTags(Track track, String genre, int energy, int activity,
                           Consumer<String> onDone, Consumer<String> onError) {
        new SwingWorker<Snapshot, Void>() {
            @Override protected Snapshot doInBackground() throws Exception {
                long size = Files.size(track.path());
                long modified = Files.getLastModifiedTime(track.path()).toMillis();
                synchronized (store) {
                    store.updateTags(track.path(), genre, energy, activity, size, modified);
                    return changedSnapshot();
                }
            }
            @Override protected void done() {
                try { publishSnapshot(get()); onDone.accept("Tags saved for " + track.title()); }
                catch (Exception error) { onError.accept("Could not save tags: " + cause(error).getMessage()); }
            }
        }.execute();
    }

    private boolean needsAnalysis(TrackTags tags, Path path, boolean retryFailures) {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.isRegularFile() && (!tags.current(attributes.size(), attributes.lastModifiedTime().toMillis())
                || (retryFailures && tags.failure() != null));
        } catch (IOException | SecurityException error) {
            return false;
        }
    }

    private Catalog readCatalog() throws Exception {
        List<Track> tracks = new ArrayList<>();
        Map<Path, TrackTags> tags = new HashMap<>();
        Map<Path, Integer> plays = new HashMap<>();
        Set<Path> manual = new HashSet<>();
        Set<Path> unavailable = new HashSet<>();
        Map<Path, Long> recent = new HashMap<>();
        Set<Path> metadataRefreshPaths = new HashSet<>();
        Map<Path, FileStamp> stamps = new HashMap<>();
        for (LibraryStore.CatalogEntry entry : store.loadCatalog()) {
            tracks.add(entry.track());
            tags.put(entry.track().path(), entry.tags());
            plays.put(entry.track().path(), entry.playCount());
            if (entry.manualTags()) manual.add(entry.track().path());
            if (entry.unavailable()) unavailable.add(entry.track().path());
            recent.put(entry.track().path(), entry.recentAt());
            stamps.put(entry.track().path(), entry.stamp());
            if (needsMetadataRefresh(entry)) metadataRefreshPaths.add(entry.track().path());
        }
        var settings = managedState.settings();
        var locations = settings == null ? ManagedLocations.EMPTY
            : new ManagedLocations(settings, store.loadSources(), managedState.paths(), managedState.aliases());
        return new Catalog(tracks, tags, plays, manual, unavailable, recent, metadataRefreshPaths, stamps,
            Catalog.indexPaths(tracks), tags.values().stream().filter(value ->
                value.features().version() == sangitam.desktop.model.AudioFeatures.VERSION).count(), locations);
    }

    private String organizeManaged(List<Path> paths) {
        if (paths.isEmpty()) return "";
        synchronized (scanLock) {
            try { return managed.organize(paths, true); }
            catch (Exception failure) { return "Managed organization needs attention. " + failure.getMessage(); }
        }
    }

    private boolean needsMetadataRefresh(LibraryStore.CatalogEntry entry) {
        return entry.track().metadata().extractionVersion() < sangitam.desktop.model.TrackMetadata.CURRENT_VERSION
            || LibraryScanner.needsLabelRepair(entry.track(), entry.titleOverride(), entry.artistOverride());
    }

    private Snapshot changedSnapshot() throws Exception {
        latestStoredCatalog = readCatalog();
        return new Snapshot(latestStoredCatalog, revisions.incrementAndGet());
    }

    private void publishSnapshot(Snapshot snapshot) {
        if (snapshot.revision() <= publishedRevision) return;
        publishedRevision = snapshot.revision();
        catalog = snapshot.catalog();
        for (Consumer<List<Track>> listener : trackListeners) listener.accept(catalog.tracks());
        for (Consumer<Catalog> listener : catalogListeners) listener.accept(catalog);
    }

    private Throwable cause(Exception error) {
        return error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
    }
}
