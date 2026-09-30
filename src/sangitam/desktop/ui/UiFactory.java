package sangitam.desktop.ui;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Reusable Swing component styling. IMPORTANT: Add shared control styles here instead of duplicating them in screens. */
public final class UiFactory {
    private UiFactory() {
    }

    public static JButton button(String text, Icon icon, boolean primary) {
        JButton button = new JButton(text, icon);
        button.setFont(Theme.BODY_BOLD);
        button.setForeground(primary ? Theme.BLACK : Theme.TEXT);
        button.setBackground(primary ? Theme.BLUE : Theme.SURFACE_RAISED);
        button.setBorder(Theme.padding(9, 14, 9, 14));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Color normal = button.getBackground();
        Color hover = primary ? Theme.BLUE.brighter() : Theme.SURFACE_HOVER;
        button.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { button.setBackground(hover); }
            @Override public void mouseExited(MouseEvent e) { button.setBackground(normal); }
        });
        return button;
    }

    public static JButton iconButton(Icon icon, String tooltip) {
        JButton button = button("", icon, false);
        button.setToolTipText(tooltip);
        button.setBorder(Theme.padding(9, 10, 9, 10));
        return button;
    }

    public static JLabel label(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    public static JPanel surface(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(Theme.SURFACE);
        return panel;
    }

    public static Border roundedBorder(Color color, int radius) {
        return new RoundedBorder(color, radius);
    }

    private static final class RoundedBorder extends AbstractBorder {
        private static final long serialVersionUID = 1L;
        private final Color color;
        private final int radius;

        private RoundedBorder(Color color, int radius) {
            this.color = color;
            this.radius = radius;
        }

        @Override
        public void paintBorder(Component c, Graphics graphics, int x, int y, int width, int height) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
            g.dispose();
        }

        @Override public Insets getBorderInsets(Component c) { return new Insets(1, 1, 1, 1); }
    }
}
