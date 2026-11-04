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

/** MP3/WAV decoder adapter. Only this class touches JavaFX; every visible component remains Swing. */
public final class JavaFxPlayerService implements PlayerService {
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private volatile PlaybackState state = PlaybackState.idle();
    private volatile Consumer<PlaybackState> listener = ignored -> { };
    private volatile boolean closed;
    private MediaPlayer player; // accessed only on the JavaFX application thread
    private double volume = 0.72;
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
                fail(null, "Audio system could not start: " + message(error));
            }
        }, "sangitam-audio-startup");
        startup.setDaemon(true);
        startup.start();
    }

    @Override public void setListener(Consumer<PlaybackState> listener) {
        this.listener = listener;
        SwingUtilities.invokeLater(() -> listener.accept(state));
    }

    @Override public PlaybackState state() { return state; }

    @Override public void play(Track track, double startSeconds) {
        publish(new PlaybackState(track, PlaybackState.Status.LOADING, 0, track.durationSeconds(), null));
        onAudioThread(() -> open(track, startSeconds));
    }

    private void open(Track track, double startSeconds) {
        if (player != null) player.dispose();
        player = null;
        if (!Files.isRegularFile(track.path())) {
            fail(track, "File is missing: " + track.path().getFileName());
            return;
        }
        try {
            Media media = new Media(track.path().toUri().toString());
            MediaPlayer next = new MediaPlayer(media);
            player = next;
            next.setVolume(volume);
            media.setOnError(() -> {
                if (next == player) fail(track, "Cannot read audio: " + message(media.getError()));
            });
            next.setOnError(() -> {
                if (next == player) fail(track, "Playback failed: " + message(next.getError()));
            });
            next.setOnReady(() -> {
                if (next != player) return;
                double duration = next.getTotalDuration().toSeconds();
                double length = Double.isFinite(duration) ? duration : track.durationSeconds();
                double start = Math.max(0, Math.min(startSeconds, Math.max(0, length - 1)));
                publish(new PlaybackState(track, PlaybackState.Status.PAUSED, start, length, null));
                if (start > 0) next.seek(Duration.seconds(start));
                next.play();
            });
            next.setOnPlaying(() -> updateStatus(next, PlaybackState.Status.PLAYING));
            next.setOnPaused(() -> updateStatus(next, PlaybackState.Status.PAUSED));
            next.setOnEndOfMedia(() -> {
                if (next == player) publish(new PlaybackState(track, PlaybackState.Status.ENDED,
                    state.durationSeconds(), state.durationSeconds(), null));
            });
            next.currentTimeProperty().addListener((observable, before, now) -> {
                if (next != player) return;
                long clock = System.nanoTime();
                if (clock - lastPositionNotice < 200_000_000L) return;
                lastPositionNotice = clock;
                PlaybackState current = state;
                publish(new PlaybackState(track, current.status(), now.toSeconds(),
                    current.durationSeconds(), current.error()));
            });
        } catch (Exception error) {
            fail(track, "Cannot open audio: " + message(error));
        }
    }

    private void updateStatus(MediaPlayer source, PlaybackState.Status status) {
        if (source != player) return;
        PlaybackState current = state;
        publish(new PlaybackState(current.track(), status, source.getCurrentTime().toSeconds(),
            current.durationSeconds(), null));
    }

    @Override public void pause() { onAudioThread(() -> { if (player != null) player.pause(); }); }

    @Override public void resume() {
        onAudioThread(() -> {
            if (player == null) return;
            if (state.status() == PlaybackState.Status.ENDED) player.seek(Duration.ZERO);
            player.play();
        });
    }

    @Override public void seek(double fraction) {
        onAudioThread(() -> {
            if (player != null && state.durationSeconds() > 0) {
                player.seek(Duration.seconds(state.durationSeconds() * Math.max(0, Math.min(1, fraction))));
            }
        });
    }

    @Override public void setVolume(double volume) {
        this.volume = Math.max(0, Math.min(1, volume));
        onAudioThread(() -> { if (player != null) player.setVolume(this.volume); });
    }

    private void onAudioThread(Runnable action) {
        ready.thenRun(() -> {
            if (!closed) Platform.runLater(action);
        }).exceptionally(error -> {
            fail(state.track(), "Audio system unavailable: " + message(error));
            return null;
        });
    }

    private void publish(PlaybackState next) {
        state = next;
        SwingUtilities.invokeLater(() -> listener.accept(next));
    }

    private void fail(Track track, String reason) {
        publish(new PlaybackState(track, PlaybackState.Status.ERROR, 0, 0, reason));
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
