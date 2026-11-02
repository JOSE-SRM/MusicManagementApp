package sangitam.desktop.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * Small vector icons used throughout the interface.
 * IMPORTANT: Icons are painted in Java, so they remain sharp and never depend on missing image files.
 */
public final class AppIcons {
    public enum Kind { HOME, SEARCH, MUSIC, FOLDER, PLAY, PAUSE, PREVIOUS, NEXT, PLUS, EXPAND, COLLAPSE, SETTINGS, HELP }

    private AppIcons() {
    }

    public static Icon of(Kind kind, int size) {
        return of(kind, size, Theme.TEXT);
    }

    public static Icon of(Kind kind, int size, Color color) {
        return new VectorIcon(kind, size, color);
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
                    g.fillRoundRect(p + 2, p, 4, size - p * 2, 2, 2);
                    g.fillRoundRect(size - p - 6, p, 4, size - p * 2, 2, 2); break;
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
