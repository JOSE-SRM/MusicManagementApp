package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class NavigationPanelTest {
    @Test void compactNavigationKeepsTheSameAccessiblePageButtonsAndSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Runnable idle = () -> { };
            var opened = new AtomicReference<String>();
            var rail = new NavigationPanel(opened::set, idle, idle, idle, idle);
            JButton search = button(rail, "Search");
            rail.setActivePage("search");
            var collapse = button(rail, "Collapse navigation");
            collapse.doClick();
            assertTrue(rail.isCollapsed());
            assertEquals(64, rail.getPreferredSize().width);
            assertSame(search, button(rail, "Search"));
            assertTrue(search.isSelected());
            assertEquals("Search", search.getToolTipText());
            assertEquals("", search.getText());
            search.doClick();
            assertEquals("search", opened.get());
            assertSame(collapse, button(rail, "Expand navigation"));
            collapse.doClick();
            assertEquals("Search", search.getText());
            assertTrue(search.isSelected());
        });
    }

    private static JButton button(NavigationPanel rail, String name) {
        return Arrays.stream(rail.getComponents()).filter(JButton.class::isInstance).map(JButton.class::cast)
            .filter(button -> name.equals(button.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
    }
}
