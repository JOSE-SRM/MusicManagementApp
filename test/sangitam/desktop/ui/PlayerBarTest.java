package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;
import sangitam.desktop.playback.PlaybackState;

import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlayerBarTest {
    @Test void clickingSeekTrackJumpsWithoutDraggingThumb() throws Exception {
        AtomicReference<Double> seek = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            PlayerBar bar = new PlayerBar(new PlayerBar.Actions() {
                public void playPause() { }
                public void previous() { }
                public void next() { }
                public void seek(double fraction) { seek.set(fraction); }
                public void volume(double fraction) { }
            });
            bar.setSize(900, 88);
            layout(bar);
            Track track = new Track(Path.of("song.mp3"), "Song", "Artist", "Album", 100, "MP3");
            bar.setState(new PlaybackState(track, PlaybackState.Status.PAUSED, 0, 100, null));
            JSlider slider = findProgress(bar);
            assertNotNull(slider);
            assertTrue(slider.isEnabled());
            slider.dispatchEvent(new MouseEvent(slider, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, slider.getWidth() * 3 / 4,
                slider.getHeight() / 2, 1, false, MouseEvent.BUTTON1));
            assertNotNull(seek.get());
            assertEquals(0.75, seek.get(), 0.08);
        });
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static JSlider findProgress(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JSlider slider && "Track position".equals(slider.getToolTipText())) return slider;
            if (child instanceof Container nested) {
                JSlider found = findProgress(nested);
                if (found != null) return found;
            }
        }
        return null;
    }
}
