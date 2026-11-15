package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseWheelEvent;

/** Pixel-based wheel scrolling, including fractional wheel rotation from trackpads. */
final class PixelScrollPane extends JScrollPane {
    private static final int PIXELS_PER_UNIT = 18;
    private double verticalRemainder;
    private double horizontalRemainder;

    PixelScrollPane(Component view) {
        this(view, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }

    PixelScrollPane(Component view, int verticalPolicy, int horizontalPolicy) {
        super(view, verticalPolicy, horizontalPolicy);
        setWheelScrollingEnabled(false);
        enableEvents(AWTEvent.MOUSE_WHEEL_EVENT_MASK);
    }

    @Override protected void processMouseWheelEvent(MouseWheelEvent event) {
        if (!event.isConsumed() && event.getPreciseWheelRotation() != 0) {
            boolean horizontal = event.isShiftDown();
            for (Component component = this; component != null; component = component.getParent()) {
                if (component instanceof PixelScrollPane pane && pane.scroll(event, horizontal)) {
                    event.consume();
                    return;
                }
            }
        }
        super.processMouseWheelEvent(event);
    }

    private boolean scroll(MouseWheelEvent event, boolean horizontal) {
        if (horizontal ? getHorizontalScrollBarPolicy() == HORIZONTAL_SCROLLBAR_NEVER
                       : getVerticalScrollBarPolicy() == VERTICAL_SCROLLBAR_NEVER) return false;
        JScrollBar bar = horizontal ? getHorizontalScrollBar() : getVerticalScrollBar();
        int minimum = bar.getMinimum();
        int maximum = bar.getMaximum() - bar.getVisibleAmount();
        int current = bar.getValue();
        double rotation = event.getPreciseWheelRotation();
        double remainder = horizontal ? horizontalRemainder : verticalRemainder;
        if (rotation > 0 ? current >= maximum : current <= minimum) {
            setRemainder(horizontal, 0);
            return false;
        }
        if (Math.signum(rotation) != Math.signum(remainder)) remainder = 0;
        int pixels = event.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL
            ? bar.getVisibleAmount() : Math.max(1, event.getScrollAmount()) * PIXELS_PER_UNIT;
        double delta = rotation * pixels + remainder;
        int target = (int) Math.max(minimum, Math.min(maximum, current + (long) delta));
        bar.setValue(target);
        boolean reachedEdge = rotation > 0 ? target == maximum : target == minimum;
        setRemainder(horizontal, reachedEdge ? 0 : delta - (long) delta);
        return true;
    }

    private void setRemainder(boolean horizontal, double value) {
        if (horizontal) horizontalRemainder = value;
        else verticalRemainder = value;
    }
}
