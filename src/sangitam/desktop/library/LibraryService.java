package sangitam.desktop.library;

import sangitam.desktop.model.FileStamp;
import sangitam.desktop.model.LibrarySource;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;
import sangitam.desktop.model.DetailReset;
import sangitam.desktop.storage.LibraryStore;

import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Keeps file decoding and SQLite work off Swing, then publishes immutable catalog snapshots on its event thread. */
public final class LibraryService {
    public record ImportSummary(int added, int indexed, int skipped, int unanalyzed,
                                List<String> errors, boolean cancelled, int reused, int unavailable, List<Path> paths) { }
    public record Catalog(List<Track> tracks, Map<Path, TrackTags> tags, Map<Path, Integer> playCounts,
                          Set<Path> manualTags, Set<Path> unavailable, Map<Path, Long> recent, Set<Path> metadataRefreshPaths,
                          Map<Path, FileStamp> stamps, Map<Path, Track> byPath, long measuredCount) {
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
                       Set<Path> manualTags, Set<Path> unavailable, Map<Path, Long> recent,
                       Set<Path> metadataRefreshPaths, Map<Path, FileStamp> stamps) {
            this(tracks, tags, playCounts, manualTags, unavailable, recent, metadataRefreshPaths, stamps,
                indexPaths(tracks), tags.values().stream().filter(value -> value.features().version() > 0).count());
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
    }
    private record Snapshot(Catalog catalog, long revision) { }
    private enum ScanMode { IMPORT, RESCAN, REFRESH_METADATA }

    private final LibraryStore store;
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

    public LibraryService(LibraryStore store) { this.store = store; }

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
    public void cancelAnalysis() { if (analysisWorker != null) analysisWorker.cancel(true); }
    public void analyzeNow(Consumer<String> report) { analyzeMissing(report, true); }
    private void analyzeMissing(Consumer<String> report, boolean checkDisk) {
        if (analysisWorker != null && !analysisWorker.isDone()) { report.accept("Music analysis is already running"); return; }
        Catalog input = catalog;
        analysisWorker = new SwingWorker<Snapshot, Void>() {
            private List<Track> missing = List.of();
            @Override protected Snapshot doInBackground() throws Exception {
                missing = input.tracks().stream()
                    .filter(track -> !input.unavailable().contains(track.path()))
                    .filter(track -> checkDisk || !freshAtIndexedStamp(input, track))
                    .filter(track -> Files.isRegularFile(track.path()))
                    .filter(track -> !fresh(input.tagsFor(track), track.path())).toList();
                if (missing.isEmpty()) return null;
                long lastPublish=System.nanoTime(); int completed=0;
                for (Track track : missing) {
                    if (isCancelled()) break;
                    TrackTags tags = analyzer.analyze(track.path(), track.durationSeconds());
                    synchronized (store) { store.saveTags(track.path(), tags); }
                    completed++;
                    if (System.nanoTime()-lastPublish>10_000_000_000L) {
                        Snapshot partial; synchronized(store) { partial=changedSnapshot(); }
                        int progress=completed; SwingUtilities.invokeLater(() -> { publishSnapshot(partial); report.accept("Deep analysis: "+progress+" / "+missing.size()); }); lastPublish=System.nanoTime();
                    }
                }
                synchronized (store) { return changedSnapshot(); }
            }

            @Override protected void done() {
                try {
                    Snapshot result = get();
                    if (result == null) return;
                    publishSnapshot(result);
                    long unavailable = missing.stream().filter(track -> !catalog.tagsFor(track).analyzed()).count();
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
        return stamp != null && stamp.size() >= 0 && input.tagsFor(track).features().current(stamp.size(), stamp.modifiedMillis());
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

    private SwingWorker<ImportSummary, LibraryScanner.Progress> scanMedia(
        List<LibrarySource> requested, ScanMode mode, Consumer<LibraryScanner.Progress> onProgress,
        Consumer<ImportSummary> onDone, Consumer<String> onError) {
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
                List<LibrarySource> sources;
                List<Path> exclusions;
                synchronized (store) {
                    store.initialize();
                    for (LibraryStore.CatalogEntry entry : store.loadCatalog()) existing.put(entry.track().path(), entry);
                    sources = mode == ScanMode.RESCAN ? store.loadSources() : requested;
                    exclusions = store.loadExclusions();
                }
                Map<Path, LibraryScanner.KnownTrack> known = new HashMap<>();
                existing.forEach((path, entry) -> known.put(path, new LibraryScanner.KnownTrack(entry.track(),
                    entry.stamp(), needsMetadataRefresh(entry))));
                LibraryScanner.Result result = scanner.scan(sources, known, mode == ScanMode.RESCAN,
                    path -> mode != ScanMode.RESCAN || exclusions.stream().noneMatch(path::startsWith), update -> {
                    if (update.visited() % 32 == 1) publish(update);
                }, this::isCancelled);
                if (isCancelled()) return cancelledSummary();

                List<Track> changed = new ArrayList<>();
                Map<Path, TrackTags> measured = new HashMap<>();
                int added = 0;
                int unanalyzed = 0;
                for (int i = 0; i < result.tracks().size(); i++) {
                    if (isCancelled()) return cancelledSummary();
                    Track track = result.tracks().get(i);
                    FileStamp stamp = result.stamps().get(track.path());
                    LibraryStore.CatalogEntry before = existing.get(track.path());
                    if (before == null) added++;
                    if (before == null || !stamp.equals(before.stamp()) || !track.equals(before.track())) changed.add(track);
                    TrackTags tags = before == null ? TrackTags.pending() : before.tags();
                    if ((before == null || !before.manualTags()) && !tags.matches(stamp.size(), stamp.modifiedMillis())) {
                        tags = analyzer.analyze(track.path(), track.durationSeconds());
                        if (tags.matches(stamp.size(), stamp.modifiedMillis())) measured.put(track.path(), tags);
                    }
                    if (!tags.analyzed()) unanalyzed++;
                    if (i % 16 == 0) publish(new LibraryScanner.Progress(i + 1,
                        result.tracks().size(), track.path(), "Checking analysis"));
                }
                synchronized (store) {
                    if (isCancelled()) return cancelledSummary();
                    List<Path> restored = mode == ScanMode.IMPORT ? exclusions.stream().filter(excluded ->
                        result.sources().stream().anyMatch(source -> source.path().startsWith(excluded)
                            || (source.recursive() && excluded.startsWith(source.path())))
                        || result.stamps().containsKey(excluded)).toList() : List.of();
                    store.saveScan(changed, result.stamps(), result.unavailable(),
                        mode == ScanMode.IMPORT ? result.sources() : List.of(), measured, restored);
                    saved = changedSnapshot();
                }
                SwingUtilities.invokeLater(() -> publishSnapshot(saved));
                return new ImportSummary(added, result.tracks().size(), result.skipped(), unanalyzed,
                    result.errors(), false, result.reused(), saved.catalog().unavailable().size(),
                    result.tracks().stream().map(Track::path).toList());
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
                    };
                    onError.accept(action + " failed: " + cause(error).getMessage());
                }
            }
        };
        worker.execute();
        return worker;
    }

    public void editDetails(List<Path> paths, LibraryStore.DetailChanges changes, Consumer<String> report) {
        changeCatalog(() -> store.updateDetails(paths, changes), "Track details saved", report);
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
                        for (var entry : store.loadCatalog()) {
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
            List<Path> removed = scope == null ? paths : store.loadAll().stream().map(Track::path)
                .filter(path -> path.startsWith(scope)).toList();
            store.removeFromLibrary(removed, scope);
        }, "Removed from library", report);
    }

    @FunctionalInterface
    private interface CatalogEdit { void run() throws Exception; }

    private void changeCatalog(CatalogEdit edit, String message, Consumer<String> report) {
        new SwingWorker<Snapshot, Void>() {
            @Override protected Snapshot doInBackground() throws Exception {
                synchronized (scanLock) {
                    synchronized (store) {
                        edit.run();
                        return changedSnapshot();
                    }
                }
            }

            @Override protected void done() {
                try {
                    publishSnapshot(get());
                    report.accept(message);
                } catch (Exception error) {
                    report.accept("Library change failed: " + cause(error).getMessage());
                }
            }
        }.execute();
    }

    /** A successful play is counted once by MainFrame, never on the Swing thread. */
    public void recordPlay(Path path, Consumer<String> onError) {
        new SwingWorker<Snapshot, Void>() {
            @Override protected Snapshot doInBackground() throws Exception {
                synchronized (store) {
                    var play = store.recordPlay(path);
                    if (play == null) return null;
                    var current = latestStoredCatalog;
                    if (current == null || !current.byPath().containsKey(path)) return changedSnapshot();
                    var counts = new HashMap<>(current.playCounts());
                    counts.put(path, play.plays());
                    var recent = new HashMap<>(current.recent());
                    recent.put(path, play.recentAt());
                    latestStoredCatalog = new Catalog(current.tracks(), current.tags(), counts, current.manualTags(),
                        current.unavailable(), recent, current.metadataRefreshPaths(), current.stamps(), current.byPath(), current.measuredCount());
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

    private boolean fresh(TrackTags tags, Path path) {
        try { return tags.features().current(Files.size(path), Files.getLastModifiedTime(path).toMillis()); }
        catch (Exception error) { return false; }
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
        return new Catalog(tracks, tags, plays, manual, unavailable, recent, metadataRefreshPaths, stamps);
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
