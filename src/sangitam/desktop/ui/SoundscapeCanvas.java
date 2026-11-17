package sangitam.desktop.ui;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/** A small painted preview of the chosen listening room, never presented as a live waveform. */
public final class SoundscapeCanvas extends JPanel {
    private static final long serialVersionUID = 1L;
    private int energy = 45;
    private int reveal = 100;
    private ListeningSpace space = ListeningSpace.QUIET_ORBIT;
    private int minutes = 25;
    private Timer revealTimer;

    public SoundscapeCanvas() {
        setOpaque(false);
        setPreferredSize(new Dimension(300, 190));
        getAccessibleContext().setAccessibleName("Listening room preview");
    }

    /** Compatibility entry point for older calls that only supplied the energy slider. */
    public void reveal(int newEnergy) { reveal(newEnergy, space, minutes); }

    public void reveal(int newEnergy, String intent, int newMinutes) {
        reveal(newEnergy, spaceFor(intent), newMinutes);
    }

    public void reveal(int newEnergy, ListeningSpace newSpace, int newMinutes) {
        energy = clamp(newEnergy);
        space = newSpace == null ? ListeningSpace.QUIET_ORBIT : newSpace;
        minutes = Math.max(1, newMinutes);
        reveal = 0;
        describe();
        if (revealTimer != null) revealTimer.stop();
        revealTimer = new Timer(18, event -> {
            reveal = Math.min(100, reveal + 5);
            repaint();
            if (reveal == 100) ((Timer) event.getSource()).stop();
        });
        revealTimer.start();
    }

    public void preview(int newEnergy, String intent, int newMinutes) {
        preview(newEnergy, spaceFor(intent), newMinutes);
    }

    public void preview(int newEnergy, ListeningSpace newSpace, int newMinutes) {
        energy = clamp(newEnergy);
        space = newSpace == null ? ListeningSpace.QUIET_ORBIT : newSpace;
        minutes = Math.max(1, newMinutes);
        reveal = 100;
        describe();
        repaint();
    }

    private void describe() {
        getAccessibleContext().setAccessibleDescription(space.displayName() + " listening room, " + minutes
            + " minutes, chosen energy " + energy + " out of 100");
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) { g.dispose(); return; }
        g.setPaint(new GradientPaint(0, 0, new Color(10, 17, 32), 0, h, space.horizon()));
        g.fillRoundRect(0, 0, w, h, 24, 24);

        double revealScale = reveal / 100.0;
        double sun = Math.min(w, h) * (.18 + energy / 650.0);
        double cx = w * .72, cy = h * .48;
        Color accent = space.accent();
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 18));
        g.fill(new Ellipse2D.Double(cx - sun * 1.8, cy - sun * 1.8, sun * 3.6, sun * 3.6));
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 150));
        g.fill(new Ellipse2D.Double(cx - sun / 2, cy - sun / 2, sun, sun));
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(Theme.TEXT.getRed(), Theme.TEXT.getGreen(), Theme.TEXT.getBlue(), 55));
        g.draw(new Ellipse2D.Double(cx - sun * 1.05, cy - sun * .55, sun * 2.1, sun * 1.1));

        int layers = 4;
        for (int layer = 0; layer < layers; layer++) {
            double y = h * (.65 + layer * .075);
            Path2D land = new Path2D.Double();
            land.moveTo(0, h);
            land.lineTo(0, y);
            int steps = Math.max(16, w / 16);
            for (int i = 0; i <= steps; i++) {
                double x = i * w / (double) steps;
                double wave = Math.sin(i * (.26 + energy / 650.0) + layer * 1.2)
                    * h * (.018 + energy / 5000.0);
                land.lineTo(x, y + wave);
            }
            land.lineTo(w, h);
            land.closePath();
            int alpha = Math.min(170, (int) (34 + layer * 20));
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), alpha));
            g.fill(land);
        }

        g.setClip(0, 0, (int) (w * revealScale), h);
        g.setColor(Theme.TEXT);
        g.setFont(Theme.font(Font.BOLD, 13));
        g.drawString(space.displayName(), 22, 27);
        g.setColor(new Color(Theme.TEXT.getRed(), Theme.TEXT.getGreen(), Theme.TEXT.getBlue(), 190));
        g.setFont(Theme.SMALL);
        g.drawString(minutes + " minutes  ·  " + energyLabel(), 22, 47);
        g.setClip(null);
        g.dispose();
    }

    private String energyLabel() {
        if (energy < 34) return "gentle pace";
        if (energy < 67) return "steady pace";
        return "brighter pace";
    }

    private int clamp(int value) { return Math.max(0, Math.min(100, value)); }

    private ListeningSpace spaceFor(String intent) {
        if ("Explore".equalsIgnoreCase(intent) || "The Current".equalsIgnoreCase(intent))
            return ListeningSpace.THE_CURRENT;
        if ("Unwind".equalsIgnoreCase(intent) || "Afterglow".equalsIgnoreCase(intent))
            return ListeningSpace.AFTERGLOW;
        return ListeningSpace.QUIET_ORBIT;
    }
}
