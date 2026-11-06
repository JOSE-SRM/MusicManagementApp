package sangitam.desktop.ui;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicSpinnerUI;
import java.awt.*;

/** Shared dark arrows and borders without Metal's inner bevels. */
public final class FlatInputUI {
    private FlatInputUI() { }

    public static final class Combo extends BasicComboBoxUI {
        public static ComponentUI createUI(JComponent component) { return new Combo(); }
        @Override protected JButton createArrowButton() { return arrow(false, 25, 22); }
        @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean focus) {
            g.setColor(focus ? Theme.SURFACE_HOVER : Theme.SURFACE_RAISED);
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }

    public static final class Spinner extends BasicSpinnerUI {
        public static ComponentUI createUI(JComponent component) { return new Spinner(); }
        @Override protected Component createNextButton() {
            JButton next = arrow(true, 20, 12);
            next.setName("Spinner.nextButton");
            installNextButtonListeners(next);
            return next;
        }
        @Override protected Component createPreviousButton() {
            JButton previous = arrow(false, 20, 12);
            previous.setName("Spinner.previousButton");
            installPreviousButtonListeners(previous);
            return previous;
        }
    }

    private static JButton arrow(boolean up, int width, int height) {
        JButton button = new JButton() {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int x = getWidth() / 2, y = getHeight() / 2, delta = up ? -2 : 2;
                g.setColor(isEnabled() ? Theme.TEXT_MUTED : Theme.DIVIDER);
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawPolyline(new int[]{x - 4, x, x + 4}, new int[]{y - delta, y + delta, y - delta}, 3);
                g.dispose();
            }
        };
        button.setPreferredSize(new Dimension(width, height));
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setFocusable(false);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        return button;
    }
}
