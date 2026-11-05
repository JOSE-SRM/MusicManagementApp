package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;
import sangitam.desktop.playback.PlaybackState;

import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlayerBarTest {
    @Test void clickAndDragEachSubmitOneSeekOnRelease() throws Exception {
        AtomicReference<Double> seek = new AtomicReference<>();
        AtomicInteger seeks = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            PlayerBar bar = new PlayerBar(new PlayerBar.Actions() {
                public void playPause() { }
                public void previous() { }
                public void next() { }
                public void seek(double fraction) { seek.set(fraction); seeks.incrementAndGet(); }
                public void volume(double fraction) { }
            });
            bar.setSize(900, 88);
            layout(bar);
            Track track = new Track(Path.of("song.mp3"), "Song", "Artist", "Album", 100, "MP3");
            bar.setState(new PlaybackState(track, PlaybackState.Status.PAUSED, 0, 100, null));
            JSlider slider = findProgress(bar);
            assertNotNull(slider);
            assertTrue(slider.isEnabled());
            mouse(slider, MouseEvent.MOUSE_PRESSED, 0.75, MouseEvent.BUTTON1);
            assertNull(seek.get());
            mouse(slider, MouseEvent.MOUSE_RELEASED, 0.75, MouseEvent.BUTTON1);
            assertNotNull(seek.get());
            assertEquals(0.75, seek.get(), 0.08);
            assertEquals(1, seeks.get());

            mouse(slider, MouseEvent.MOUSE_PRESSED, 0.75, MouseEvent.BUTTON1);
            mouse(slider, MouseEvent.MOUSE_DRAGGED, 0.25, MouseEvent.NOBUTTON);
            assertEquals(1, seeks.get());
            int preview = slider.getValue();
            bar.setState(new PlaybackState(track, PlaybackState.Status.PAUSED, 75, 100, null));
            assertEquals(preview, slider.getValue());
            mouse(slider, MouseEvent.MOUSE_RELEASED, 0.25, MouseEvent.BUTTON1);
            assertEquals(0.25, seek.get(), 0.08);
            assertEquals(2, seeks.get());
        });
    }

    private static void mouse(JSlider slider, int event, double fraction, int button) {
        int modifiers = event == MouseEvent.MOUSE_RELEASED ? 0 : InputEvent.BUTTON1_DOWN_MASK;
        slider.dispatchEvent(new MouseEvent(slider, event, System.currentTimeMillis(), modifiers,
            (int) (slider.getWidth() * fraction), slider.getHeight() / 2, 1, false, button));
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
