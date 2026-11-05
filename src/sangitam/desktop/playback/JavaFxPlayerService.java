package sangitam.desktop.playback;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;
import sangitam.desktop.model.Track;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** MP3/WAV decoder adapter. Only this class touches JavaFX; every visible component remains Swing. */
public final class JavaFxPlayerService implements PlayerService {
    private static final System.Logger LOG = System.getLogger(JavaFxPlayerService.class.getName());
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private CompletableFuture<Void> queued = ready;
    private volatile PlaybackState state = PlaybackState.idle();
    private volatile Consumer<PlaybackState> listener = ignored -> { };
    private volatile boolean closed;
    private volatile long request;
    private MediaPlayer player; // accessed only on the JavaFX application thread
    private volatile double volume = 0.72;
    private long lastPositionNotice;

    public JavaFxPlayerService() {
        Thread startup = new Thread(() -> {
            try {
                Platform.startup(() -> {
                    Platform.setImplicitExit(false);
                    ready.complete(null);
                });
            } catch (Throwable error) {
                ready.completeExceptionally(error);
            }
        }, "sangitam-audio-startup");
        startup.setDaemon(true);
        startup.start();
    }

    @Override public void setListener(Consumer<PlaybackState> listener) {
        this.listener = listener;
        SwingUtilities.invokeLater(() -> { if (!closed) listener.accept(state); });
    }

    @Override public PlaybackState state() { return state; }

    @Override public synchronized void play(Track track, double startSeconds) {
        if (closed) return;
        long id = ++request;
        publish(id, new PlaybackState(track, PlaybackState.Status.LOADING, startSeconds, track.durationSeconds(), null));
        onAudioThread(() -> { if (id == request) open(id, track, startSeconds); })
            .exceptionally(error -> {
                fail(id, track, "JavaFX audio is unavailable. Check the installation or install VLC.");
                return null;
            });
    }

    private void open(long id, Track track, double startSeconds) {
        MediaPlayer old = player;
        player = null;
        if (old != null) old.dispose();
        lastPositionNotice = 0;
        if (!Files.isRegularFile(track.path())) {
            fail(id, track, "File is unavailable. Reconnect its drive or import it from its new location.");
            return;
        }
        try {
            Media media = new Media(track.path().toUri().toString());
            MediaPlayer next = new MediaPlayer(media);
            player = next;
            next.setVolume(volume);
            media.setOnError(() -> {
                if (next == player) fail(id, track, "Cannot read audio: " + message(media.getError()));
            });
            next.setOnError(() -> {
                if (next == player) fail(id, track, "Playback failed: " + message(next.getError()));
            });
            next.setOnReady(() -> {
                if (next != player || id != request) return;
                double duration = next.getTotalDuration().toSeconds();
                boolean seekable = Double.isFinite(duration) && duration > 0;
                double length = seekable ? duration : track.durationSeconds();
                double start = seekable ? Math.max(0, Math.min(startSeconds, Math.max(0, length - 0.001))) : 0;
                publish(id, new PlaybackState(track, PlaybackState.Status.LOADING, start, length, null, seekable));
                if (start > 0) next.seek(Duration.seconds(start));
                next.play();
            });
            next.setOnPlaying(() -> updateStatus(id, next, PlaybackState.Status.PLAYING));
            next.setOnPaused(() -> updateStatus(id, next, PlaybackState.Status.PAUSED));
            next.setOnEndOfMedia(() -> {
                if (next == player) publish(id, new PlaybackState(track, PlaybackState.Status.ENDED,
                    state.durationSeconds(), state.durationSeconds(), null, false));
            });
            next.currentTimeProperty().addListener((observable, before, now) -> {
                if (next != player || id != request) return;
                long clock = System.nanoTime();
                PlaybackState current = state;
                if (current.status() == PlaybackState.Status.ERROR || current.status() == PlaybackState.Status.ENDED
                    || (current.status() == PlaybackState.Status.PLAYING
                        && clock - lastPositionNotice < 100_000_000L)) return;
                lastPositionNotice = clock;
                publish(id, new PlaybackState(track, current.status(), now.toSeconds(),
                    current.durationSeconds(), current.error(), current.seekable()));
            });
        } catch (Exception error) {
            fail(id, track, "JavaFX could not open audio. Check the format or try VLC. " + message(error));
        }
    }

    private void updateStatus(long id, MediaPlayer source, PlaybackState.Status status) {
        if (source != player || id != request) return;
        PlaybackState current = state;
        if (current.status() == PlaybackState.Status.ERROR || current.status() == PlaybackState.Status.ENDED) return;
        publish(id, new PlaybackState(current.track(), status, source.getCurrentTime().toSeconds(),
            current.durationSeconds(), null, current.seekable()));
    }

    @Override public void pause() { transport(id -> { if (player != null) player.pause(); }); }

    @Override public void stop() {
        transport(id -> {
            PlaybackState current = state;
            if (player != null) {
                MediaPlayer old = player;
                player = null;
                old.stop();
                old.dispose();
            }
            if (current.track() != null) publish(id, new PlaybackState(current.track(),
                PlaybackState.Status.STOPPED, 0, current.durationSeconds(), null));
        });
    }

    @Override public void resume() {
        PlaybackState current = state;
        if ((current.status() == PlaybackState.Status.STOPPED || current.status() == PlaybackState.Status.ENDED)
            && current.track() != null) {
            play(current.track(), 0);
            return;
        }
        transport(id -> { if (player != null) player.play(); });
    }

    @Override public void seek(double fraction) {
        if (!Double.isFinite(fraction)) return;
        transport(id -> {
            PlaybackState current = state;
            if (player != null && current.seekable()) {
                double length = current.durationSeconds();
                double target = Math.min(Math.max(0, length - 0.001), length * Math.max(0, Math.min(1, fraction)));
                player.seek(Duration.seconds(target));
            }
        });
    }

    @Override public void setVolume(double volume) {
        this.volume = Math.max(0, Math.min(1, volume));
        onAudioThread(() -> { if (player != null) player.setVolume(this.volume); });
    }

    private void transport(LongConsumer action) {
        long id = request;
        onAudioThread(() -> { if (id == request) action.accept(id); });
    }

    private synchronized CompletableFuture<Void> onAudioThread(Runnable action) {
        // CompletableFuture dependents alone do not preserve submission order during startup.
        queued = queued.thenRun(() -> {
            if (!closed) Platform.runLater(() -> { if (!closed) action.run(); });
        });
        return queued;
    }

    private synchronized void publish(long id, PlaybackState next) {
        if (closed || id != request || next.equals(state)) return;
        if (next.status() != state.status())
            LOG.log(System.Logger.Level.DEBUG, "JavaFX transport: {0}", next.status());
        state = next;
        SwingUtilities.invokeLater(() -> { if (!closed && id == request) listener.accept(next); });
    }

    private void fail(long id, Track track, String reason) {
        publish(id, new PlaybackState(track, PlaybackState.Status.ERROR, 0, 0, reason));
    }

    private String message(Throwable error) {
        return error == null || error.getMessage() == null ? "unknown error" : error.getMessage();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ready.thenRun(() -> Platform.runLater(() -> {
            if (player != null) player.dispose();
            Platform.exit();
        }));
    }
}
