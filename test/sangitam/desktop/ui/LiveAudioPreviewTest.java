package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.playback.AudioSettings;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LiveAudioPreviewTest {
    @Test void firstChangeIsImmediateAndContinuousDraggingUpdatesBeforeRelease() throws Exception {
        AtomicReference<LiveAudioPreview> preview = new AtomicReference<>();
        AtomicInteger dragPosition = new AtomicInteger();
        AtomicInteger updatesDuringDrag = new AtomicInteger();
        AtomicReference<AudioSettings> applied = new AtomicReference<>();
        CountDownLatch complete = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            preview.set(new LiveAudioPreview(settings -> {
                applied.set(settings);
                if (dragPosition.get() > 0 && dragPosition.get() < 20) updatesDuringDrag.incrementAndGet();
                if (settings.preampDb() == -12) complete.countDown();
            }));
            preview.get().request(value(0));
            assertNotNull(applied.get(), "The initial edit must reach playback before returning");
            Timer drag = new Timer(10, event -> {
                int position = dragPosition.incrementAndGet();
                preview.get().request(value(position == 20 ? -12 : -position / 2d));
                if (position == 20) ((Timer) event.getSource()).stop();
            });
            drag.start();
        });
        try {
            assertTrue(complete.await(3, TimeUnit.SECONDS), "Last drag value must reach playback");
            assertTrue(updatesDuringDrag.get() >= 2, "EQ must update during a drag, without waiting for release");
            assertEquals(-12, applied.get().preampDb());
        } finally { SwingUtilities.invokeAndWait(() -> preview.get().cancel()); }
    }

    @Test void cancellingDropsQueuedEditsSoTheyCannotOverrideRestoredSound() throws Exception {
        List<AudioSettings> applied = new ArrayList<>();
        CountDownLatch elapsed = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            LiveAudioPreview preview = new LiveAudioPreview(applied::add);
            preview.request(value(0));
            preview.request(value(-6));
            preview.cancel();
            Timer timer = new Timer(100, event -> elapsed.countDown());
            timer.setRepeats(false);
            timer.start();
        });
        assertTrue(elapsed.await(2, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> assertEquals(List.of(value(0)), applied));
    }

    private static AudioSettings value(double preamp) {
        return new AudioSettings(true, AudioSettings.defaults().bands(), preamp, AudioSettings.ReplayGain.OFF);
    }
}
