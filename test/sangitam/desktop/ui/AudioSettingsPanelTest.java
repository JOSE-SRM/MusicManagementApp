package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.Track;
import sangitam.desktop.playback.*;
import sangitam.desktop.storage.AudioSettingsStore;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class AudioSettingsPanelTest {
    @TempDir Path folder;
    static final class FakePlayer implements PlayerService {
        AudioSettings settings = AudioSettings.defaults();
        public void setListener(Consumer<PlaybackState> listener) { } public PlaybackState state() { return PlaybackState.idle(); }
        public void play(Track t, double at) { } public void pause() { } public void resume() { } public void seek(double f) { } public void setVolume(double f) { } public void close() { }
        public void setAudioSettings(AudioSettings settings) { this.settings = settings; }
        public AudioStatus audioStatus() { return new AudioStatus(AudioStatus.Backend.VLC, AudioSettings.ReplayGain.OFF, null); }
    }
    @Test void movingBypassedBandsOrPreampEnablesEqAndCancelRestoresOriginal() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            var player = new FakePlayer(); var panel = new AudioSettingsPanel(AudioSettings.defaults(), player, new AudioSettingsStore(folder.resolve("audio.properties")), ignored -> { }, () -> { });
            try {
                var sliders = children(panel, JSlider.class); assertEquals(11, sliders.size());
                assertTrue(sliders.stream().allMatch(Component::isEnabled));
                sliders.getFirst().setValue(5); assertTrue(player.settings.equalizerEnabled()); assertEquals(5, player.settings.bands().getFirst());
                JCheckBox enabled = children(panel, JCheckBox.class).getFirst(); enabled.doClick(); assertFalse(enabled.isSelected());
                sliders.getLast().setValue(-6); assertTrue(enabled.isSelected());
                panel.setSize(panel.getPreferredSize()); layout(panel);
                var image = new java.awt.image.BufferedImage(panel.getWidth(), panel.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB); Graphics2D g = image.createGraphics(); panel.printAll(g); g.dispose();
                try { javax.imageio.ImageIO.write(image, "png", Path.of("target/eq-preview.png").toFile()); } catch (Exception error) { throw new AssertionError(error); }
            } finally { panel.cancel(); }
            assertEquals(AudioSettings.defaults(), player.settings);
        });
    }
    @Test void saveCommitsCurrentEqAndReplayGainToDiskBeforeClosing() throws Exception {
        var complete = new CompletableFuture<AudioSettings>(); var closed = new CountDownLatch(1); var player = new FakePlayer();
        var store = new AudioSettingsStore(folder.resolve("audio.properties")); var editor = new AtomicReference<AudioSettingsPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AudioSettingsPanel(AudioSettings.defaults(), player, store, complete::complete, closed::countDown); editor.set(panel);
            children(panel, JSlider.class).get(3).setValue(4);
            children(panel, JComboBox.class).stream().filter(combo -> "ReplayGain mode".equals(combo.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow().setSelectedItem(AudioSettings.ReplayGain.ALBUM);
            children(panel, JButton.class).stream().filter(button -> "Save".equals(button.getText())).findFirst().orElseThrow().doClick();
        });
        try {
            var settings = complete.get(5, TimeUnit.SECONDS); assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertTrue(settings.equalizerEnabled()); assertEquals(4, settings.bands().get(3)); assertEquals(AudioSettings.ReplayGain.ALBUM, settings.replayGain()); assertEquals(settings, store.load());
        } finally { SwingUtilities.invokeAndWait(() -> editor.get().cancel()); }
    }
    static <T> List<T> children(Container root, Class<T> type) {
        var result = new ArrayList<T>();
        for (Component child : root.getComponents()) { if (type.isInstance(child)) result.add(type.cast(child)); if (child instanceof Container nested) result.addAll(children(nested, type)); } return result;
    }
    static void layout(Container root) {
        root.doLayout(); for (Component child : root.getComponents()) if (child instanceof Container nested) layout(nested);
        if (root instanceof JList<?> list && list.getModel().getSize() > 0 && list.getFixedCellHeight() > 0) layoutRenderer(list);
    }
    private static <T> void layoutRenderer(JList<T> list) {
        Component renderer = list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(0), 0, false, false);
        renderer.setSize(list.getWidth(), list.getFixedCellHeight()); if (renderer instanceof Container container) layout(container);
    }
}
