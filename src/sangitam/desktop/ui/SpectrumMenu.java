package sangitam.desktop.ui;

import sangitam.desktop.model.SpectrumPreferences;
import sangitam.desktop.model.SpectrumPreferences.*;

import javax.swing.*;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/** Gives the plot, its button, and the View menu the same controls. */
final class SpectrumMenu {
    private final JMenu view = new JMenu("Spectrum");
    private final JPopupMenu popup = new JPopupMenu();
    private final List<JCheckBoxMenuItem> toggles = new ArrayList<>();
    private final List<JMenu> appearanceMenus = new ArrayList<>();
    private final List<Consumer<SpectrumPreferences>> selections = new ArrayList<>();
    private final BiFunction<String, Color, Color> chooseColor;
    private SpectrumPreferences preferences = SpectrumPreferences.defaults();

    SpectrumMenu(Consumer<Boolean> enabled, Consumer<SpectrumPreferences> change,
                 BiFunction<String, Color, Color> chooseColor) {
        this.chooseColor = chooseColor;
        populate(view::add, enabled, change);
        populate(popup::add, enabled, change);
        setPreferences(preferences);
    }

    JMenu view() { return view; }
    JPopupMenu popup() { return popup; }

    void attach(JComponent component) {
        component.setComponentPopupMenu(popup);
        component.getInputMap().put(KeyStroke.getKeyStroke("shift F10"), "spectrum-menu");
        component.getInputMap().put(KeyStroke.getKeyStroke("CONTEXT_MENU"), "spectrum-menu");
        component.getActionMap().put("spectrum-menu", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                popup.show(component, 0, component.getHeight());
            }
        });
    }

    void setEnabled(boolean enabled) {
        toggles.forEach(item -> item.setSelected(enabled));
    }

    void setAppearanceReady(boolean ready) {
        appearanceMenus.forEach(menu -> menu.setEnabled(ready));
    }

    void setPreferences(SpectrumPreferences value) {
        preferences = value;
        selections.forEach(select -> select.accept(value));
    }

    private void populate(Consumer<JMenuItem> add, Consumer<Boolean> enabled, Consumer<SpectrumPreferences> change) {
        var show = new JCheckBoxMenuItem("Enable spectrum");
        show.addActionListener(event -> enabled.accept(show.isSelected()));
        toggles.add(show);
        add.accept(show);
        JMenu palette = choices("Color scheme", Palette.values(), SpectrumPreferences::palette,
            preset -> change.accept(new SpectrumPreferences(preset, preferences.colors(), preferences.heights())));
        palette.addSeparator();
        palette.add(colorPicker("Primary color…", true, change));
        palette.add(colorPicker("Secondary color…", false, change));
        add.accept(palette);
        add.accept(choices("Color distribution", Colors.values(), SpectrumPreferences::colors,
            colors -> change.accept(new SpectrumPreferences(preferences.primary(), preferences.secondary(),
                colors, preferences.heights()))));
        add.accept(choices("Bar length distribution", Heights.values(), SpectrumPreferences::heights,
            heights -> change.accept(new SpectrumPreferences(preferences.primary(), preferences.secondary(),
                preferences.colors(), heights))));
    }

    private JMenuItem colorPicker(String label, boolean primary, Consumer<SpectrumPreferences> change) {
        var item = new JMenuItem(label);
        item.setFont(Theme.BODY);
        selections.add(preferences -> item.setIcon(new Swatch(new Color(
            primary ? preferences.primary() : preferences.secondary()))));
        item.addActionListener(event -> {
            var oldColor = new Color(primary ? preferences.primary() : preferences.secondary());
            Color chosen = chooseColor.apply(primary ? "Primary spectrum color" : "Secondary spectrum color", oldColor);
            if (chosen == null || chosen.equals(oldColor)) return;
            int rgb = chosen.getRGB() & 0xffffff;
            change.accept(new SpectrumPreferences(primary ? rgb : preferences.primary(),
                primary ? preferences.secondary() : rgb, preferences.colors(), preferences.heights()));
        });
        return item;
    }

    private <E extends Enum<E>> JMenu choices(String label, E[] values, Function<SpectrumPreferences, E> current,
                                              Consumer<E> select) {
        var menu = new JMenu(label);
        appearanceMenus.add(menu);
        var group = new ButtonGroup();
        selections.add(preferences -> { if (current.apply(preferences) == null) group.clearSelection(); });
        for (E value : values) {
            var item = new JRadioButtonMenuItem(value.toString());
            item.setFont(Theme.BODY);
            item.addActionListener(event -> select.accept(value));
            selections.add(preferences -> item.setSelected(current.apply(preferences) == value));
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    private record Swatch(Color color) implements Icon {
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics g = graphics.create();
            try {
                g.setColor(color);
                g.fillRoundRect(x, y, 14, 14, 4, 4);
                g.setColor(Theme.TEXT_MUTED);
                g.drawRoundRect(x, y, 13, 13, 4, 4);
            } finally { g.dispose(); }
        }
    }
}
