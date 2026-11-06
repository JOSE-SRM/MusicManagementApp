package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;

/** Draws actual decoder magnitudes, never generated animation. */
final class SpectrumPanel extends JComponent {
    private float[] magnitudes = new float[0];
    private boolean supported, playing;
    SpectrumPanel() { setPreferredSize(new Dimension(0, 64)); getAccessibleContext(); }
    void setSamples(float[] values) { magnitudes = values.clone(); repaint(); }
    void setStatus(boolean supported, boolean playing) { this.supported = supported; this.playing = playing; if (!playing) magnitudes = new float[0]; repaint(); }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create(); g.setColor(Theme.SURFACE); g.fillRect(0, 0, getWidth(), getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (!supported || !playing || magnitudes.length == 0) {
            g.setFont(Theme.SMALL); g.setColor(Theme.TEXT_MUTED); g.drawString(!supported ? "Spectrum is available with JavaFX playback; VLC does not expose samples here." : !playing ? "Spectrum paused" : "Waiting for decoder spectrum…", 22, 36);
        } else {
            int width = Math.max(1, (getWidth() - 44) / magnitudes.length);
            for (int i = 0; i < magnitudes.length; i++) { int height = (int) (Math.max(0, Math.min(1, (magnitudes[i] + 72) / 72)) * 48); g.setColor(i < 20 ? Theme.BLUE : Theme.SUCCESS); g.fillRoundRect(22 + i * width, getHeight() - 8 - height, Math.max(1, width - 3), height, 4, 4); }
        }
        g.dispose();
    }
}
