package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;

/** A keyboard-accessible title link; only overflowing text scrolls. */
final class MarqueeButton extends JButton {
    private int offset, pause = 35;
    private final Timer ticker = new Timer(40, event -> {
        int width = getFontMetrics(getFont()).stringWidth(getText());
        if (width <= getWidth() - 8) { offset = 0; return; }
        if (pause > 0) { pause--; return; }
        offset++;
        if (offset > width + 40) { offset = 0; pause = 35; }
        repaint();
    });
    MarqueeButton() {
        setFont(Theme.BODY_BOLD); setForeground(Theme.TEXT); setContentAreaFilled(false); setBorderPainted(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); setPreferredSize(new Dimension(300, 26));
    }
    @Override public void setText(String text) { if (!java.util.Objects.equals(getText(), text)) { offset = 0; pause = 35; } super.setText(text); setToolTipText(text); }
    @Override public void addNotify() { super.addNotify(); ticker.start(); }
    @Override public void removeNotify() { ticker.stop(); super.removeNotify(); }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(getFont()); g.setColor(isEnabled() ? getForeground() : Theme.TEXT_MUTED);
        g.clipRect(4, 0, Math.max(0, getWidth() - 8), getHeight());
        String text = getText() == null ? "" : getText(); int width = g.getFontMetrics().stringWidth(text);
        int y = (getHeight() - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent();
        g.drawString(text, 4 - offset, y);
        if (offset > 0) g.drawString(text, 4 + width + 40 - offset, y);
        if (hasFocus()) { g.setColor(Theme.BLUE); g.drawRoundRect(4, 1, Math.max(0, getWidth() - 9), getHeight() - 3, 8, 8); }
        g.dispose();
    }
}
