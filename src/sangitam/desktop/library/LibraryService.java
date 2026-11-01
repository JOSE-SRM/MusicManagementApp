package sangitam.desktop.library;

import sangitam.desktop.model.Track;
import sangitam.desktop.storage.LibraryStore;

import javax.swing.SwingWorker;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Coordinates background scanning and storage, then publishes one library snapshot on the Swing thread. */
public final class LibraryService {
    public record ImportSummary(int added, int indexed, int skipped, List<String> errors, boolean cancelled) { }
    private record Snapshot(List<Track> tracks, long revision) { }

    private final LibraryStore store;
    private final LibraryScanner scanner = new LibraryScanner();
    private final List<Consumer<List<Track>>> listeners = new ArrayList<>();
    private final AtomicLong revisions = new AtomicLong();
    private List<Track> tracks = List.of();
    private long publishedRevision = -1;

    public LibraryService(LibraryStore store) { this.store = store; }

    public List<Track> snapshot() { return tracks; }

    public void addListener(Consumer<List<Track>> listener) {
        listeners.add(listener);
        listener.accept(tracks);
    }

    public void loadInitial(Consumer<String> report) {
        new SwingWorker<Snapshot, Void>() {
            @Override protected Snapshot doInBackground() throws Exception {
                synchronized (store) {
                    store.initialize();
                    return new Snapshot(store.loadAll(), revisions.get());
                }
            }

            @Override protected void done() {
                try {
                    Snapshot loaded = get();
                    publishSnapshot(loaded.tracks(), loaded.revision());
                    if (!tracks.isEmpty()) report.accept("Loaded " + tracks.size() + " saved tracks");
                } catch (Exception error) {
                    report.accept("Library could not be loaded: " + cause(error).getMessage());
                }
            }
        }.execute();
    }

    public SwingWorker<ImportSummary, LibraryScanner.Progress> importMedia(
        List<Path> sources, boolean recursive, Consumer<LibraryScanner.Progress> onProgress,
        Consumer<ImportSummary> onDone, Consumer<String> onError) {
        SwingWorker<ImportSummary, LibraryScanner.Progress> worker = new SwingWorker<>() {
            private List<Track> savedTracks = List.of();
            private long savedRevision;

            @Override protected ImportSummary doInBackground() throws Exception {
                LibraryScanner.Result result = scanner.scan(sources, recursive, this::publish, this::isCancelled);
                if (isCancelled()) return new ImportSummary(0, 0, result.skipped(), result.errors(), true);
                int added;
                synchronized (store) {
                    store.initialize();
                    Set<Path> existing = new HashSet<>();
                    for (Track track : store.loadAll()) existing.add(track.path());
                    added = (int) result.tracks().stream().filter(track -> !existing.contains(track.path())).count();
                    if (isCancelled()) return new ImportSummary(0, 0, result.skipped(), result.errors(), true);
                    store.saveAll(result.tracks());
                    savedTracks = store.loadAll();
                    savedRevision = revisions.incrementAndGet();
                }
                return new ImportSummary(added, result.tracks().size(), result.skipped(), result.errors(), false);
            }

            @Override protected void process(List<LibraryScanner.Progress> progress) {
                if (!progress.isEmpty()) onProgress.accept(progress.get(progress.size() - 1));
            }

            @Override protected void done() {
                if (isCancelled()) {
                    onDone.accept(new ImportSummary(0, 0, 0, List.of(), true));
                    return;
                }
                try {
                    ImportSummary summary = get();
                    if (!summary.cancelled()) publishSnapshot(savedTracks, savedRevision);
                    onDone.accept(summary);
                } catch (Exception error) {
                    onError.accept("Import failed: " + cause(error).getMessage());
                }
            }
        };
        worker.execute();
        return worker;
    }

    private void publishSnapshot(List<Track> updated, long revision) {
        if (revision < publishedRevision) return;
        publishedRevision = revision;
        tracks = List.copyOf(updated);
        for (Consumer<List<Track>> listener : listeners) listener.accept(tracks);
    }

    private Throwable cause(Exception error) {
        return error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
    }
}
