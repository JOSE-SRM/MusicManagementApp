package sangitam.desktop.ui;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;

/** Flat tabs with a filled selected state and a visible keyboard focus ring. */
public final class RoundTabUI extends BasicTabbedPaneUI {
    public static ComponentUI createUI(JComponent component) { return new RoundTabUI(); }

    @Override protected void paintTabBackground(Graphics graphics, int placement, int index,
        int x, int y, int width, int height, boolean selected) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(selected ? Theme.SURFACE_HOVER : Theme.SURFACE);
        g.fillRoundRect(x + 1, y + 1, width - 2, height - 3, 10, 10);
        if (selected) {
            g.setColor(Theme.BLUE);
            g.fillRoundRect(x + 8, y + height - 4, Math.max(0, width - 16), 2, 2, 2);
        }
        g.dispose();
    }

    @Override protected void paintTabBorder(Graphics g, int placement, int index,
        int x, int y, int width, int height, boolean selected) { }

    @Override protected void paintContentBorder(Graphics g, int placement, int selectedIndex) { }
}
