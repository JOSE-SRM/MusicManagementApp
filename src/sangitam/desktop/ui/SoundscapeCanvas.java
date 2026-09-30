package sangitam.desktop.ui;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * A dependency-free visual signature for a generated listening session.
 * IMPORTANT: The canvas paints deterministic wave bands from the energy value; it is decorative and not audio data.
 */
public final class SoundscapeCanvas extends JPanel {
    private static final long serialVersionUID = 1L;
    private int energy = 45;
    private int reveal = 100;
    private Timer revealTimer;

    public SoundscapeCanvas() {
        setOpaque(false);
        setPreferredSize(new Dimension(300, 150));
        getAccessibleContext().setAccessibleName("Visual preview of the generated listening session");
    }

    public void reveal(int newEnergy) {
        energy = newEnergy;
        reveal = 0;
        if (revealTimer != null) revealTimer.stop();
        revealTimer = new Timer(18, event -> {
            reveal = Math.min(100, reveal + 5);
            repaint();
            if (reveal == 100) ((Timer) event.getSource()).stop();
        });
        revealTimer.start();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int width = getWidth();
        int height = getHeight();
        int visibleWidth = width * reveal / 100;

        g.setColor(new Color(112, 203, 255, 18));
        g.fillRoundRect(0, 0, width, height, 26, 26);
        g.setClip(0, 0, visibleWidth, height);

        for (int band = 0; band < 3; band++) {
            Path2D wave = new Path2D.Double();
            double center = height * (0.35 + band * 0.16);
            double amplitude = 8 + energy * (0.10 + band * 0.025);
            for (int x = 0; x <= width; x += 4) {
                double y = center + Math.sin((x / 27.0) + band * 1.35) * amplitude;
                if (x == 0) wave.moveTo(x, y); else wave.lineTo(x, y);
            }
            g.setColor(new Color(112, 203, 255, 210 - band * 55));
            g.setStroke(new BasicStroke(3f - band * 0.55f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(wave);
        }
        g.dispose();
    }
}
