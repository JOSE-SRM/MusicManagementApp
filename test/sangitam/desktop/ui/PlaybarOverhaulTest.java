package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.*;
import sangitam.desktop.model.TrackMetadata.Field;
import sangitam.desktop.playback.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PlaybarOverhaulTest {
    @Test void titleAndOrderedCreditsNavigateAndTransportShowsAllTimes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            AtomicReference<Track> opened = new AtomicReference<>(); AtomicReference<String> artist = new AtomicReference<>();
            var bar = new PlayerBar(new PlayerBar.Actions() {
                public void playPause() { } public void previous() { } public void next() { } public void seek(double f) { } public void volume(double f) { }
                public void openTrack(Track track) { opened.set(track); } public void openArtist(String name) { artist.set(name); }
            });
            try {
                Track track = new Track(Path.of("missing.mp3"), "Piano Sonata No. 14 in C-sharp minor, Op. 27 No. 2 — Adagio sostenuto", "Mitsuko Uchida", "Beethoven Piano Sonatas", 684, "MP3", "Classical",
                    TrackMetadata.empty().edit(Map.of(Field.COMPOSER, "Ludwig van Beethoven", Field.CONDUCTOR, "Conductor", Field.SOLOIST, "Mitsuko Uchida"), null));
                bar.setState(new PlaybackState(track, PlaybackState.Status.PLAYING, 406, 684, null));
                MarqueeButton title = AudioSettingsPanelTest.children(bar, MarqueeButton.class).getFirst(); assertEquals(track.title(), title.getText()); title.doClick(); assertEquals(track, opened.get());
                var buttons = AudioSettingsPanelTest.children(bar, JButton.class);
                var credits = buttons.stream().filter(button -> button.getToolTipText() != null && button.getToolTipText().endsWith("Open artist")).toList();
                assertEquals(List.of("Ludwig van Beethoven", "Conductor", "Mitsuko Uchida"), credits.stream().map(JButton::getText).toList()); credits.getFirst().doClick(); assertEquals("Ludwig van Beethoven", artist.get());
                var labels = AudioSettingsPanelTest.children(bar, JLabel.class).stream().map(JLabel::getText).filter(Objects::nonNull).toList();
                assertTrue(labels.contains("6:46")); assertTrue(labels.contains("11:24")); assertTrue(labels.contains("−4:38"));
                JButton pause = buttons.stream().filter(button -> "Pause".equals(button.getToolTipText())).findFirst().orElseThrow(); assertTrue(pause.getIcon().getIconWidth() >= 28);
                bar.setSize(1440, 112); AudioSettingsPanelTest.layout(bar);
                var image = new BufferedImage(1440, 112, BufferedImage.TYPE_INT_RGB); Graphics2D g = image.createGraphics(); bar.printAll(g); g.dispose();
                try { javax.imageio.ImageIO.write(image, "png", Path.of("target/playbar-preview.png").toFile()); } catch (Exception error) { throw new AssertionError(error); }
            } finally { bar.close(); }
        });
    }
    @Test void pauseHasTwoSeparateBarsEvenAtSmallSizes() {
        for (int size : new int[]{15,18,28}) {
            var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB); Graphics2D g = image.createGraphics(); AppIcons.of(AppIcons.Kind.PAUSE, size).paintIcon(null, g, 0, 0); g.dispose();
            assertEquals(0, image.getRGB(size / 2, size / 2) >>> 24, "Pause bars overlap at " + size);
        }
    }
}
