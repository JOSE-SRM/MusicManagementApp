package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.SpectrumPreferences;
import sangitam.desktop.model.SpectrumPreferences.*;

import javax.swing.*;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

class SpectrumMenuTest {
    @Test void popupAndViewShareToggleAndAllAppearanceSelections() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var holder = new SpectrumMenu[1];
            var menu = new SpectrumMenu(visible -> holder[0].setEnabled(visible),
                preferences -> holder[0].setPreferences(preferences),
                (title, initial) -> title.startsWith("Primary") ? Color.CYAN : null);
            holder[0] = menu;
            var panel = new SpectrumPanel();
            menu.attach(panel);
            assertSame(menu.popup(), panel.getComponentPopupMenu());
            ((JMenuItem) menu.popup().getComponent(0)).doClick(0);
            assertTrue(menu.view().getItem(0).isSelected());
            ((JMenu) menu.popup().getComponent(2)).getItem(2).doClick(0);
            assertTrue(((JMenu) menu.view().getItem(2)).getItem(2).isSelected());
            menu.setPreferences(new SpectrumPreferences(Palette.PLUM, Colors.ACROSS, Heights.LOGARITHMIC));
            assertTrue(((JMenu) menu.popup().getComponent(1)).getItem(3).isSelected());
            assertTrue(((JMenu) menu.popup().getComponent(2)).getItem(1).isSelected());
            assertTrue(((JMenu) menu.popup().getComponent(3)).getItem(2).isSelected());
            JMenu palette = (JMenu) menu.popup().getComponent(1);
            JMenu viewPalette = (JMenu) menu.view().getItem(1);
            JMenuItem primary = palette.getItem(Palette.values().length + 1);
            JMenuItem secondary = palette.getItem(Palette.values().length + 2);
            Icon previousSecondary = secondary.getIcon();
            primary.doClick(0);
            assertFalse(viewPalette.getItem(Palette.PLUM.ordinal()).isSelected());
            assertEquals(primary.getIcon(), viewPalette.getItem(Palette.values().length + 1).getIcon());
            secondary.doClick(0);
            assertEquals(previousSecondary, secondary.getIcon());
            ((JMenu) menu.popup().getComponent(2)).getItem(0).doClick(0);
            assertFalse(viewPalette.getItem(Palette.PLUM.ordinal()).isSelected());
            palette.getItem(Palette.GOLD.ordinal()).doClick(0);
            assertTrue(viewPalette.getItem(Palette.GOLD.ordinal()).isSelected());
        });
    }
}
