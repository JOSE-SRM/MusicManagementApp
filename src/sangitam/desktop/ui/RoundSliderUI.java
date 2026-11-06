package sangitam.desktop.ui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.MouseEvent;

/** Rounded, click-to-position slider with one commit at the end of a seek drag. */
final class RoundSliderUI extends BasicSliderUI {
    RoundSliderUI(JSlider slider) { super(slider); }
    @Override protected Dimension getThumbSize() { return new Dimension(14, 14); }
    @Override public void paintFocus(Graphics graphics) { }
    @Override public void paintTrack(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        boolean horizontal = slider.getOrientation() == JSlider.HORIZONTAL;
        int x = horizontal ? trackRect.x : trackRect.x + trackRect.width / 2 - 2;
        int y = horizontal ? trackRect.y + trackRect.height / 2 - 2 : trackRect.y;
        int width = horizontal ? trackRect.width : 4, height = horizontal ? 4 : trackRect.height;
        g.setColor(Theme.SURFACE_HOVER); g.fillRoundRect(x, y, width, height, 5, 5);
        g.setColor(slider.isEnabled() ? slider.getForeground() : Theme.TEXT_MUTED);
        if (horizontal) g.fillRoundRect(x, y, Math.max(0, xPositionForValue(slider.getValue()) - x), height, 5, 5);
        else { int top = yPositionForValue(slider.getValue()); g.fillRoundRect(x, top, width, Math.max(0, y + height - top), 5, 5); }
        g.dispose();
    }
    @Override public void paintThumb(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(slider.isEnabled() ? Theme.TEXT : Theme.TEXT_MUTED);
        g.fillOval(thumbRect.x + 2, thumbRect.y + 2, 10, 10);
        if (slider.hasFocus()) { g.setColor(Theme.BLUE); g.drawOval(thumbRect.x, thumbRect.y, 13, 13); }
        g.dispose();
    }
    @Override protected TrackListener createTrackListener(JSlider slider) {
        return new TrackListener() {
            private void move(MouseEvent event) { slider.setValue(slider.getOrientation() == JSlider.HORIZONTAL ? valueForXPosition(event.getX()) : valueForYPosition(event.getY())); }
            @Override public void mousePressed(MouseEvent event) {
                if (!slider.isEnabled() || !SwingUtilities.isLeftMouseButton(event)) return;
                calculateGeometry(); slider.requestFocusInWindow(); slider.setValueIsAdjusting(true); move(event);
            }
            @Override public void mouseDragged(MouseEvent event) { if (slider.getValueIsAdjusting()) move(event); }
            @Override public void mouseReleased(MouseEvent event) {
                if (slider.getValueIsAdjusting() && SwingUtilities.isLeftMouseButton(event)) { move(event); slider.setValueIsAdjusting(false); }
            }
        };
    }
}
