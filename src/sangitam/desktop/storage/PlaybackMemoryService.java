package sangitam.desktop.storage;

import sangitam.desktop.model.PlaybackMemory;

import javax.swing.SwingUtilities;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Serializes last-track reads and writes so the player bar never waits for SQLite. */
public final class PlaybackMemoryService implements AutoCloseable {
    private final LibraryStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task ->
        new Thread(task, "sangitam-playback-memory"));
    private boolean closed;

    public PlaybackMemoryService(LibraryStore store) { this.store = store; }

    public void load(Consumer<PlaybackMemory> ready, Consumer<String> error) {
        worker.execute(() -> {
            try {
                PlaybackMemory memory;
                synchronized (store) { store.initialize(); memory = store.loadPlaybackMemory(); }
                SwingUtilities.invokeLater(() -> ready.accept(memory));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> error.accept("Could not restore last played track."));
            }
        });
    }

    public void save(PlaybackMemory memory) {
        if (closed || memory == null) return;
        worker.execute(() -> {
            try { synchronized (store) { store.savePlaybackMemory(memory); } }
            catch (Exception ignored) { /* Listening can continue if app state cannot be written. */ }
        });
    }

    public void loadVolume(Consumer<Double> ready) {
        worker.execute(() -> {
            try {
                double volume; synchronized (store) { store.initialize(); volume = store.loadVolume(); }
                SwingUtilities.invokeLater(() -> { if (!closed) ready.accept(volume); });
            } catch (Exception ignored) { }
        });
    }
    public void saveVolume(double volume) {
        if (!closed) worker.execute(() -> { try { synchronized (store) { store.saveVolume(volume); } } catch (Exception ignored) { } });
    }
    public void saveListening(java.util.Map<java.nio.file.Path, Double> seconds) {
        if (closed || seconds.isEmpty()) return;
        worker.execute(() -> { try { synchronized (store) { var artists = new ArtistStore(store); artists.initialize(); artists.addListening(seconds); } } catch (Exception ignored) { } });
    }
    public void afterPending(Runnable ready) {
        if (!closed) worker.execute(() -> SwingUtilities.invokeLater(() -> { if (!closed) ready.run(); }));
    }

    @Override public void close() { closed = true; worker.shutdown(); }
}
