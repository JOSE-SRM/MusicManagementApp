package sangitam.desktop.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * Small vector icons used throughout the interface.
 * IMPORTANT: Icons are painted in Java, so they remain sharp and never depend on missing image files.
 */
public final class AppIcons {
    public enum Kind { HOME, SEARCH, MUSIC, FOLDER, PLAY, PAUSE, PREVIOUS, NEXT, PLUS, EXPAND, COLLAPSE, SETTINGS, HELP, SHUFFLE, REPEAT, STOP, QUEUE, VOLUME, EQ, HEART, PIN, SPECTRUM }

    private AppIcons() {
    }

    public static Icon of(Kind kind, int size) {
        return of(kind, size, Theme.TEXT);
    }

    public static Icon of(Kind kind, int size, Color color) {
        return new VectorIcon(kind, size, color);
    }

    public static BufferedImage image(Kind kind, int size, Color color, Color background) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(background);
        graphics.fillRoundRect(0, 0, size, size, size / 3, size / 3);
        of(kind, size * 3 / 4, color).paintIcon(null, graphics, size / 8, size / 8);
        graphics.dispose();
        return image;
    }

    public static BufferedImage applicationIcon(int size) {
        return image(Kind.MUSIC, size, Theme.BLUE, Theme.SURFACE);
    }

    private static final class VectorIcon implements Icon {
        private final Kind kind;
        private final int size;
        private final Color color;

        private VectorIcon(Kind kind, int size, Color color) {
            this.kind = kind;
            this.size = size;
            this.color = color;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new BasicStroke(Math.max(1.6f, size / 10f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int p = Math.max(2, size / 5);
            int m = size / 2;
            switch (kind) {
                case HOME:
                    Path2D home = new Path2D.Double();
                    home.moveTo(p, m); home.lineTo(m, p); home.lineTo(size - p, m);
                    home.lineTo(size - p, size - p); home.lineTo(p, size - p); home.closePath();
                    g.draw(home); break;
                case SEARCH:
                    g.drawOval(p, p, size - p * 2 - 2, size - p * 2 - 2);
                    g.drawLine(size - p - 3, size - p - 3, size - 2, size - 2); break;
                case MUSIC:
                    g.drawLine(m, p, m, size - p); g.drawLine(m, p, size - p, p + 2);
                    g.fillOval(m - p, size - p - 3, p + 3, p + 3); g.fillOval(size - p - 2, m, p + 3, p + 3); break;
                case FOLDER:
                    g.drawRoundRect(p, p + 2, size - p * 2, size - p * 2 - 1, 3, 3);
                    g.drawLine(p + 1, p + 2, m - 1, p + 2); g.drawLine(m - 1, p + 2, m + 2, p + 5); break;
                case PLAY:
                    Path2D play = new Path2D.Double();
                    play.moveTo(p + 2, p); play.lineTo(size - p, m); play.lineTo(p + 2, size - p); play.closePath();
                    g.fill(play); break;
                case PAUSE:
                    int bar = Math.max(2, size / 6);
                    g.fillRoundRect(p, p, bar, size - p * 2, 2, 2);
                    g.fillRoundRect(size - p - bar, p, bar, size - p * 2, 2, 2); break;
                case QUEUE:
                    for (int row = 0; row < 3; row++) g.drawLine(p, p + row * (size - 2 * p) / 2, size - p - 4, p + row * (size - 2 * p) / 2);
                    triangle(g, size - 5, m, size - 1, m + 3, size - 5, m + 6); break;
                case EQ:
                    for (int i = 0; i < 3; i++) { int at = p + i * (size - 2 * p) / 2; int knob = i % 2 == 0 ? m + 2 : m - 3; g.drawLine(p, at, size - p, at); g.setColor(Theme.SURFACE); g.fillOval(knob - 3, at - 3, 6, 6); g.setColor(color); g.drawOval(knob - 3, at - 3, 6, 6); } break;
                case SPECTRUM:
                    for (int i = 0; i < 4; i++) { int at = p + i * (size - 2 * p) / 3; int top = p + (i % 2) * size / 5; g.drawLine(at, top, at, size - p); } break;
                case VOLUME:
                    Path2D speaker = new Path2D.Double(); speaker.moveTo(p, m - 2); speaker.lineTo(m - 2, m - 2); speaker.lineTo(m + 1, p); speaker.lineTo(m + 1, size - p); speaker.lineTo(m - 2, m + 2); speaker.lineTo(p, m + 2); speaker.closePath(); g.fill(speaker);
                    g.drawArc(m, p, size / 2 - 1, size - p * 2, -65, 130); break;
                case HEART:
                    Path2D heart = new Path2D.Double(); heart.moveTo(m, size - p); heart.curveTo(-2, m, p, 0, m, p + 2); heart.curveTo(size - p, 0, size + 2, m, m, size - p); g.draw(heart); break;
                case PIN:
                    int wide = Math.max(2, size / 4);
                    g.drawLine(m - wide, p, m + wide, p);
                    g.drawLine(m - wide / 2, p, m - wide, m + 1);
                    g.drawLine(m + wide / 2, p, m + wide, m + 1);
                    g.drawLine(m - wide, m + 1, m + wide, m + 1);
                    g.drawLine(m, m + 1, m, size - p); break;
                case PREVIOUS:
                    g.fillRect(p, p, 3, size - p * 2); triangle(g, size - p, p, p + 4, m, size - p, size - p); break;
                case NEXT:
                    g.fillRect(size - p - 3, p, 3, size - p * 2); triangle(g, p, p, size - p - 4, m, p, size - p); break;
                case PLUS:
                    g.drawLine(m, p, m, size - p); g.drawLine(p, m, size - p, m); break;
                case EXPAND:
                    g.drawLine(p, p, m - 2, p); g.drawLine(p, p, p, m - 2);
                    g.drawLine(size - p, size - p, m + 2, size - p); g.drawLine(size - p, size - p, size - p, m + 2); break;
                case COLLAPSE:
                    g.drawLine(p, m - 2, m - 2, m - 2); g.drawLine(m - 2, p, m - 2, m - 2);
                    g.drawLine(size - p, m + 2, m + 2, m + 2); g.drawLine(m + 2, size - p, m + 2, m + 2); break;
                case SETTINGS:
                    g.drawOval(p, p, size - p * 2, size - p * 2); g.drawOval(m - 2, m - 2, 4, 4); break;
                case HELP:
                    g.drawOval(p, p, size - p * 2, size - p * 2); g.drawString("?", m - 3, size - p - 1); break;
                case SHUFFLE:
                    g.drawLine(p, p + 2, size - p, size - p - 2);
                    g.drawLine(p, size - p - 2, size - p, p + 2);
                    g.drawLine(size - p - 4, p + 2, size - p, p + 2);
                    g.drawLine(size - p, p + 2, size - p, p + 6);
                    g.drawLine(size - p - 4, size - p - 2, size - p, size - p - 2);
                    g.drawLine(size - p, size - p - 2, size - p, size - p - 6); break;
                case REPEAT:
                    g.drawArc(p, p + 2, size - 2 * p, size - 2 * p, 35, 225);
                    g.drawArc(p, p + 2, size - 2 * p, size - 2 * p, 215, 225);
                    g.drawLine(size - p - 2, p + 2, size - p, p + 6);
                    g.drawLine(size - p - 2, p + 2, size - p - 6, p + 3);
                    g.drawLine(p + 2, size - p - 2, p, size - p - 6);
                    g.drawLine(p + 2, size - p - 2, p + 6, size - p - 3); break;
                case STOP:
                    g.fillRoundRect(p + 1, p + 1, size - 2 * p - 2, size - 2 * p - 2, 2, 2); break;
                default: break;
            }
            g.dispose();
        }

        private void triangle(Graphics2D g, int x1, int y1, int x2, int y2, int x3, int y3) {
            Path2D shape = new Path2D.Double();
            shape.moveTo(x1, y1); shape.lineTo(x2, y2); shape.lineTo(x3, y3); shape.closePath();
            g.fill(shape);
        }
    }
}
