package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import static org.junit.jupiter.api.Assertions.*;

class PixelScrollPaneTest {
    @Test void fractionalMotionAccumulatesFromTheEdgeAndReversesImmediately() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = new JPanel();
            content.setPreferredSize(new Dimension(180, 900));
            var scroll = new PixelScrollPane(content);
            scroll.setSize(200, 160);
            scroll.doLayout();
            for (int i = 0; i < 3; i++) assertTrue(wheel(scroll, 0, 0.01).isConsumed());
            assertEquals(1, scroll.getVerticalScrollBar().getValue());
            wheel(scroll, 0, -0.1);
            assertEquals(0, scroll.getVerticalScrollBar().getValue());
        });
    }

    @Test void verticalGesturesOverHorizontalShelvesScrollThePage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel shelfContent = new JPanel();
            shelfContent.setPreferredSize(new Dimension(1000, 80));
            var shelf = new PixelScrollPane(shelfContent, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            shelf.setSize(200, 100);
            shelf.doLayout();
            JPanel pageContent = new JPanel();
            pageContent.setPreferredSize(new Dimension(200, 900));
            pageContent.add(shelf);
            var page = new PixelScrollPane(pageContent);
            page.setSize(230, 180);
            page.doLayout();
            assertTrue(wheel(shelf, 0, 0.5).isConsumed());
            assertEquals(27, page.getVerticalScrollBar().getValue());
            assertEquals(0, shelf.getHorizontalScrollBar().getValue());
            assertTrue(wheel(shelf, MouseEvent.SHIFT_DOWN_MASK, 0.5).isConsumed());
            assertEquals(27, shelf.getHorizontalScrollBar().getValue());
            assertEquals(27, page.getVerticalScrollBar().getValue());
        });
    }

    private static MouseWheelEvent wheel(PixelScrollPane pane, int modifiers, double rotation) {
        var event = new MouseWheelEvent(pane, MouseEvent.MOUSE_WHEEL, 0, modifiers, 5, 5, 5, 5,
            0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 0, rotation);
        pane.dispatchEvent(event);
        return event;
    }
}
