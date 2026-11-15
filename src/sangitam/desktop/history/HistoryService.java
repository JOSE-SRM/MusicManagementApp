package sangitam.desktop.history;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.storage.LibraryStore;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Serializes network and database history work off Swing's event thread. */
public final class HistoryService implements AutoCloseable {
    private final LibraryStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "sangitam-listening-history");
        thread.setDaemon(true);
        return thread;
    });
    private volatile LibraryService.Catalog catalog = new LibraryService.Catalog(List.of(),
        java.util.Map.of(), java.util.Map.of());
    private Consumer<List<Favorite>> listener = ignored -> { };
    private Consumer<String> errors = ignored -> { };

    public HistoryService(LibraryStore store) { this.store = store; }

    /** Listener is called on Swing's event thread with a stable snapshot. */
    public void setListener(Consumer<List<Favorite>> listener, Consumer<String> errors) {
        this.listener = listener;
        this.errors = errors;
    }

    public void updateCatalog(LibraryService.Catalog updated) {
        catalog = updated;
        worker.execute(this::recompute);
    }

    /** Each available source is independent, so one expired token does not stop other syncs. */
    public void sync(List<HistorySource> sources, Consumer<String> status) {
        worker.execute(() -> {
            List<String> reports = new ArrayList<>();
            for (HistorySource source : sources) {
                if (!source.available()) continue;
                try {
                    List<ListenEvent> listens = source.recent();
                    int added;
                    synchronized (store) {
                        store.initialize();
                        added = store.saveListens(listens);
                    }
                    reports.add(source.name() + ": " + added + " new plays");
                } catch (Exception error) {
                    reports.add(source.name() + ": " + safeMessage(error));
                }
            }
            recompute();
            String message = reports.isEmpty() ? "Enter a username or add a Spotify token to connect a source."
                : String.join(" · ", reports);
            SwingUtilities.invokeLater(() -> status.accept(message));
        });
    }

    private void recompute() {
        try {
            List<ListenEvent> listens;
            synchronized (store) {
                store.initialize();
                listens = store.loadListens();
            }
            LibraryService.Catalog current = catalog;
            List<Favorite> favorites = FavoritesAnalyzer.analyze(current.tracks(), current.playCounts(), listens);
            SwingUtilities.invokeLater(() -> listener.accept(favorites));
        } catch (Exception error) {
            SwingUtilities.invokeLater(() -> errors.accept("Could not load listening history: " + safeMessage(error)));
        }
    }

    private String safeMessage(Exception error) {
        return error instanceof java.io.IOException || error instanceof IllegalStateException
            ? error.getMessage() : "Could not read this source.";
    }

    @Override public void close() { worker.shutdownNow(); }
}
