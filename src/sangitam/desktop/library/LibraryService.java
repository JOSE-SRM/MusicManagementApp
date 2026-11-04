package sangitam.desktop.library;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;
import sangitam.desktop.storage.LibraryStore;

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
                                List<String> errors, boolean cancelled) { }
    public record Catalog(List<Track> tracks, Map<Path, TrackTags> tags, Map<Path, Integer> playCounts,
                          Set<Path> manualTags) {
        public Catalog {
            tracks = List.copyOf(tracks);
            tags = Map.copyOf(tags);
            playCounts = Map.copyOf(playCounts);
            manualTags = Set.copyOf(manualTags);
        }
        public Catalog(List<Track> tracks, Map<Path, TrackTags> tags, Map<Path, Integer> playCounts) {
            this(tracks, tags, playCounts, Set.of());
        }
        public TrackTags tagsFor(Track track) { return tags.getOrDefault(track.path(), TrackTags.pending()); }
        public int playsFor(Track track) { return playCounts.getOrDefault(track.path(), 0); }
    }
    private record Snapshot(Catalog catalog, long revision) { }

    private final LibraryStore store;
    private final LibraryScanner scanner = new LibraryScanner();
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final List<Consumer<List<Track>>> trackListeners = new ArrayList<>();
    private final List<Consumer<Catalog>> catalogListeners = new ArrayList<>();
    private final AtomicLong revisions = new AtomicLong();
    private Catalog catalog = new Catalog(List.of(), Map.of(), Map.of());
    private long publishedRevision = -1;

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
                    return new Snapshot(readCatalog(), revisions.get());
                }
            }

            @Override protected void done() {
                try {
                    Snapshot loaded = get();
                    publishSnapshot(loaded);
                    if (!catalog.tracks().isEmpty()) report.accept("Loaded " + catalog.tracks().size() + " saved tracks");
                    analyzeMissing(report);
                } catch (Exception error) {
                    report.accept("Library could not be loaded: " + cause(error).getMessage());
                }
            }
        }.execute();
    }

    /** Reanalyzes older libraries in the background after their tracks are already visible. */
    private void analyzeMissing(Consumer<String> report) {
        Catalog input = catalog;
        new SwingWorker<Snapshot, Void>() {
            private List<Track> missing = List.of();
            @Override protected Snapshot doInBackground() throws Exception {
                missing = input.tracks().stream().filter(track -> !input.manualTags().contains(track.path()))
                    .filter(track -> Files.isRegularFile(track.path()))
                    .filter(track -> !fresh(input.tagsFor(track), track.path())).toList();
                if (missing.isEmpty()) return null;
                for (Track track : missing) {
                    if (isCancelled()) break;
                    TrackTags tags = analyzer.analyze(track.path(), track.durationSeconds());
                    synchronized (store) { store.saveTags(track.path(), tags); }
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
        }.execute();
    }

    public SwingWorker<ImportSummary, LibraryScanner.Progress> importMedia(
        List<Path> sources, boolean recursive, Consumer<LibraryScanner.Progress> onProgress,
        Consumer<ImportSummary> onDone, Consumer<String> onError) {
        SwingWorker<ImportSummary, LibraryScanner.Progress> worker = new SwingWorker<>() {
            private Snapshot saved;

            @Override protected ImportSummary doInBackground() throws Exception {
                LibraryScanner.Result result = scanner.scan(sources, recursive, this::publish, this::isCancelled);
                if (isCancelled()) return new ImportSummary(0, 0, result.skipped(), 0, result.errors(), true);
                int added;
                Map<Path, TrackTags> existingTags;
                Set<Path> beforeManualTags;
                synchronized (store) {
                    store.initialize();
                    Catalog before = readCatalog();
                    Set<Path> existing = new HashSet<>();
                    before.tracks().forEach(track -> existing.add(track.path()));
                    added = (int) result.tracks().stream().filter(track -> !existing.contains(track.path())).count();
                    store.saveAll(result.tracks());
                    existingTags = before.tags();
                    beforeManualTags = before.manualTags();
                }
                int unanalyzed = 0;
                for (int i = 0; i < result.tracks().size(); i++) {
                    if (isCancelled()) break;
                    Track track = result.tracks().get(i);
                    TrackTags tags = existingTags.getOrDefault(track.path(), TrackTags.pending());
                    if (!beforeManualTags.contains(track.path()) && !fresh(tags, track.path())) {
                        tags = analyzer.analyze(track.path(), track.durationSeconds());
                        synchronized (store) { store.saveTags(track.path(), tags); }
                    }
                    if (!tags.analyzed()) unanalyzed++;
                    publish(new LibraryScanner.Progress(i + 1, result.tracks().size(), track.path(), "Analyzing"));
                }
                synchronized (store) { saved = changedSnapshot(); }
                return new ImportSummary(added, result.tracks().size(), result.skipped(), unanalyzed,
                    result.errors(), false);
            }

            @Override protected void process(List<LibraryScanner.Progress> updates) {
                if (!updates.isEmpty()) onProgress.accept(updates.get(updates.size() - 1));
            }

            @Override protected void done() {
                if (isCancelled()) {
                    onDone.accept(new ImportSummary(0, 0, 0, 0, List.of(), true));
                    return;
                }
                try {
                    ImportSummary summary = get();
                    publishSnapshot(saved);
                    onDone.accept(summary);
                } catch (Exception error) {
                    onError.accept("Import failed: " + cause(error).getMessage());
                }
            }
        };
        worker.execute();
        return worker;
    }

    /** A successful play is counted once by MainFrame, never on the Swing thread. */
    public void recordPlay(Path path, Consumer<String> onError) {
        new SwingWorker<Snapshot, Void>() {
            @Override protected Snapshot doInBackground() throws Exception {
                synchronized (store) {
                    store.recordPlay(path);
                    return changedSnapshot();
                }
            }
            @Override protected void done() {
                try { publishSnapshot(get()); }
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
        try { return tags.matches(Files.size(path), Files.getLastModifiedTime(path).toMillis()); }
        catch (Exception error) { return false; }
    }

    private Catalog readCatalog() throws Exception {
        List<Track> tracks = new ArrayList<>();
        Map<Path, TrackTags> tags = new HashMap<>();
        Map<Path, Integer> plays = new HashMap<>();
        Set<Path> manual = new HashSet<>();
        for (LibraryStore.CatalogEntry entry : store.loadCatalog()) {
            tracks.add(entry.track());
            tags.put(entry.track().path(), entry.tags());
            plays.put(entry.track().path(), entry.playCount());
            if (entry.manualTags()) manual.add(entry.track().path());
        }
        return new Catalog(tracks, tags, plays, manual);
    }

    private Snapshot changedSnapshot() throws Exception {
        return new Snapshot(readCatalog(), revisions.incrementAndGet());
    }

    private void publishSnapshot(Snapshot snapshot) {
        if (snapshot.revision() < publishedRevision) return;
        publishedRevision = snapshot.revision();
        catalog = snapshot.catalog();
        for (Consumer<List<Track>> listener : trackListeners) listener.accept(catalog.tracks());
        for (Consumer<Catalog> listener : catalogListeners) listener.accept(catalog);
    }

    private Throwable cause(Exception error) {
        return error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
    }
}
