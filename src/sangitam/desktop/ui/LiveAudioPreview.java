package sangitam.desktop.ui;

import sangitam.desktop.playback.AudioSettings;

import javax.swing.Timer;
import java.util.function.Consumer;

/** Applies the first edit immediately, then coalesces a continuous drag without waiting for release. EDT only. */
final class LiveAudioPreview {
    private final Consumer<AudioSettings> apply;
    private final Timer timer = new Timer(30, event -> tick());
    private AudioSettings pending;

    LiveAudioPreview(Consumer<AudioSettings> apply) { this.apply = apply; }

    void request(AudioSettings settings) {
        pending = settings;
        if (!timer.isRunning()) {
            flush();
            timer.start();
        }
    }

    private void tick() {
        if (pending == null) timer.stop();
        else flush();
    }

    private void flush() {
        AudioSettings next = pending;
        pending = null;
        apply.accept(next);
    }

    void cancel() {
        timer.stop();
        pending = null;
    }
}
