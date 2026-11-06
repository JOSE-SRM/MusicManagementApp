package sangitam.desktop.ui;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;

/** Shared horizontal and vertical scrollbar, including controls in dialogs. */
public final class RoundScrollBarUI extends BasicScrollBarUI {
    public static ComponentUI createUI(JComponent component) { return new RoundScrollBarUI(); }

    @Override protected void installDefaults() {
        super.installDefaults();
        scrollbar.setBorder(BorderFactory.createEmptyBorder());
        scrollbar.setOpaque(false);
    }

    @Override protected JButton createDecreaseButton(int orientation) { return hiddenButton(); }
    @Override protected JButton createIncreaseButton(int orientation) { return hiddenButton(); }

    private static JButton hiddenButton() {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension());
        button.setMinimumSize(new Dimension());
        button.setMaximumSize(new Dimension());
        button.setFocusable(false);
        return button;
    }

    @Override protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
        paintPill(graphics, bounds, Theme.SURFACE_RAISED);
    }

    @Override protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
        if (bounds.isEmpty() || !component.isEnabled()) return;
        paintPill(graphics, bounds, isDragging ? Theme.BLUE : isThumbRollover() ? Theme.TEXT_MUTED : Theme.SCROLL_THUMB);
    }

    private static void paintPill(Graphics graphics, Rectangle bounds, Color color) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.fillRoundRect(bounds.x + 2, bounds.y + 2, Math.max(0, bounds.width - 4), Math.max(0, bounds.height - 4), 8, 8);
        g.dispose();
    }
}
