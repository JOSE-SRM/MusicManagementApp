package sangitam.desktop.ui;

import sangitam.desktop.model.SpectrumPreferences;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;

/** Animates measured magnitudes with a fast attack and a slightly longer decay. */
final class SpectrumPanel extends JComponent {
    private final SpectrumMotion motion = new SpectrumMotion();
    private final Timer animation = new Timer(16, event -> animate(System.nanoTime()));
    private long animatedAt;
    private boolean supported, playing, preEffects;
    private SpectrumPreferences preferences;
    private Color primary, secondary;
    private final Color[] gradientColors = new Color[101];

    SpectrumPanel() {
        setPreferredSize(new Dimension(0, 112));
        setOpaque(false);
        setFocusable(true);
        setPreferences(SpectrumPreferences.defaults());
        updateDescription();
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) updateTimer();
        });
    }

    void setPreferences(SpectrumPreferences value) {
        preferences = value;
        motion.setHeights(value.heights(), System.nanoTime());
        primary = new Color(value.primary());
        secondary = new Color(value.secondary());
        for (int i = 0; i < gradientColors.length; i++) gradientColors[i] = mix(primary, secondary, i / 100.0);
        repaint();
    }

    void setSamples(float[] values) {
        if (values.length == 0) {
            motion.clear();
            repaint();
        } else if (playing && supported) {
            boolean newBands = motion.size() != values.length;
            motion.setSamples(values, System.nanoTime());
            if (newBands) repaint();
        }
    }

    void setStatus(boolean supported, boolean playing, boolean preEffects) {
        if (this.supported == supported && this.playing == playing && this.preEffects == preEffects) return;
        if (!playing || !supported || this.preEffects != preEffects) motion.clear();
        this.supported = supported;
        this.playing = playing;
        this.preEffects = preEffects;
        updateDescription();
        updateTimer();
        repaint();
    }

    private void updateDescription() {
        String description = preEffects ? "Live spectrum before audio effects" : "Live audio spectrum";
        setToolTipText(description + " · Right-click or open View → Spectrum for display options");
        getAccessibleContext().setAccessibleName(description);
    }

    @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() { };
        return accessibleContext;
    }

    private void updateTimer() {
        boolean active = isShowing() && playing && supported;
        if (active && !animation.isRunning()) {
            animatedAt = System.nanoTime();
            animation.start();
        } else if (!active) animation.stop();
    }

    private void animate(long now) {
        double elapsedMs = Math.clamp((now - animatedAt) * 1e-6, 0, 64);
        animatedAt = now;
        if (motion.advance(now, elapsedMs)) repaint();
    }

    private static Color mix(Color from, Color to, double fraction) {
        return new Color(
            (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * fraction),
            (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * fraction),
            (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * fraction));
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            if (motion.size() == 0) {
                g.setFont(Theme.SMALL);
                g.setColor(Theme.TEXT_MUTED);
                String status = !playing ? "Spectrum paused" : !supported ? "Spectrum unavailable"
                    : "Waiting for audio…";
                g.drawString(status, 20, getHeight() / 2 + 4);
                return;
            }
            int inset = 18;
            int baseline = getHeight() - 10;
            double step = Math.max(0, getWidth() - inset * 2) / (double) motion.size();
            if (step == 0 || baseline <= 4) return;
            double width = Math.min(8, step * 0.64);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var distribution = preferences.colors();
            var bar = new java.awt.geom.RoundRectangle2D.Double();
            for (int i = 0; i < motion.size(); i++) {
                float level = motion.level(i);
                float peakLevel = Math.max(motion.peak(i), level);
                double height = level * (baseline - 4);
                double peak = peakLevel * (baseline - 4);
                double x = inset + (i + .5) * step - width / 2;
                Color across = gradientColors[Math.round(100f * i / Math.max(1, motion.size() - 1))];
                if (height >= 1) {
                    g.setPaint(switch (distribution) {
                        case SOLID -> primary;
                        case ACROSS -> across;
                        case HEIGHT -> gradientColors[Math.round(level * 100)];
                        case VERTICAL -> new GradientPaint(0, baseline, primary,
                            0, (float) (baseline - height), secondary);
                    });
                    bar.setRoundRect(x, baseline - height, width, height, 2, 2);
                    g.fill(bar);
                }
                if (peak >= 3) {
                    g.setColor(switch (distribution) {
                        case SOLID -> primary;
                        case ACROSS -> across;
                        case HEIGHT -> gradientColors[Math.round(peakLevel * 100)];
                        case VERTICAL -> secondary;
                    });
                    bar.setRoundRect(x, baseline - peak - 2, width, 2, 1, 1);
                    g.fill(bar);
                }
            }
        } finally {
            g.dispose();
        }
    }
}
