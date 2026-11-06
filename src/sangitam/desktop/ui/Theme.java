package sangitam.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;

/**
 * Central visual language for Sangitam.
 * IMPORTANT: Keep colours, fonts and spacing here so every screen stays consistent.
 */
public final class Theme {
    public static final Color BLACK = new Color(5, 8, 12);
    public static final Color SURFACE = new Color(11, 17, 24);
    public static final Color SURFACE_RAISED = new Color(17, 25, 34);
    public static final Color SURFACE_HOVER = new Color(25, 37, 49);
    public static final Color BLUE = new Color(112, 203, 255);
    public static final Color BLUE_STRONG = new Color(51, 157, 224);
    public static final Color TEXT = new Color(240, 247, 252);
    public static final Color TEXT_MUTED = new Color(145, 165, 181);
    public static final Color DIVIDER = new Color(31, 45, 58);
    public static final Color SCROLL_THUMB = new Color(75, 96, 115);
    public static final Color SUCCESS = new Color(100, 218, 178);

    public static final String FONT_FAMILY = System.getProperty("os.name", "").startsWith("Windows") ? "Segoe UI" : Font.DIALOG;
    public static final Font BODY = font(Font.PLAIN, 15);
    public static final Font BODY_BOLD = font(Font.BOLD, 15);
    public static final Font SMALL = font(Font.PLAIN, 13);
    public static final Font TITLE = font(Font.BOLD, 31);
    public static final Font HEADING = font(Font.BOLD, 20);

    private Theme() {
    }

    /** Installs a dependency-free dark Swing theme before any widgets are created. */
    public static void install() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's default look and feel is a safe fallback on every supported JDK.
        }

        UIManager.put("Panel.background", BLACK);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.font", BODY);
        UIManager.put("Button.font", BODY_BOLD);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.background", SURFACE_RAISED);
        UIManager.put("Button.focus", new Color(0, 0, 0, 0));
        UIManager.put("ToggleButton.font", BODY_BOLD);
        UIManager.put("ToggleButton.background", SURFACE_RAISED);
        UIManager.put("ToggleButton.foreground", TEXT);
        UIManager.put("ToggleButton.select", BLUE_STRONG);
        UIManager.put("TextField.background", SURFACE_RAISED);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", BLUE);
        UIManager.put("TextField.font", BODY);
        UIManager.put("TextField.selectionBackground", BLUE_STRONG);
        UIManager.put("TextField.border", new javax.swing.plaf.BorderUIResource(UiFactory.inputBorder()));
        UIManager.put("FormattedTextField.border", new javax.swing.plaf.BorderUIResource(UiFactory.inputBorder()));
        UIManager.put("TextArea.background", SURFACE_RAISED);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("TextArea.font", BODY);
        UIManager.put("ComboBoxUI", FlatInputUI.Combo.class.getName());
        UIManager.put("ComboBox.background", SURFACE_RAISED);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.font", BODY);
        UIManager.put("ComboBox.border", new javax.swing.plaf.BorderUIResource(UiFactory.roundedBorder(DIVIDER, 8)));
        UIManager.put("ComboBox.selectionBackground", BLUE_STRONG);
        UIManager.put("ComboBox.selectionForeground", TEXT);
        UIManager.put("CheckBox.background", SURFACE);
        UIManager.put("CheckBox.foreground", TEXT_MUTED);
        UIManager.put("CheckBox.font", BODY);
        UIManager.put("RadioButton.background", SURFACE);
        UIManager.put("RadioButton.foreground", TEXT);
        UIManager.put("RadioButton.font", BODY);
        UIManager.put("List.background", SURFACE);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("List.font", BODY);
        UIManager.put("List.selectionBackground", BLUE_STRONG);
        UIManager.put("List.selectionForeground", TEXT);
        UIManager.put("Table.background", SURFACE);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.font", BODY);
        UIManager.put("Table.gridColor", DIVIDER);
        UIManager.put("Table.selectionBackground", BLUE_STRONG);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("TableHeader.background", SURFACE_RAISED);
        UIManager.put("TableHeader.foreground", TEXT);
        UIManager.put("TableHeader.font", BODY_BOLD);
        UIManager.put("TableHeader.cellBorder", BorderFactory.createMatteBorder(0, 0, 1, 0, DIVIDER));
        UIManager.put("Tree.background", SURFACE);
        UIManager.put("Tree.foreground", TEXT);
        UIManager.put("Tree.font", BODY);
        UIManager.put("Tree.selectionBackground", BLUE_STRONG);
        UIManager.put("MenuBar.background", BLACK);
        UIManager.put("MenuBar.foreground", TEXT);
        UIManager.put("Menu.background", BLACK);
        UIManager.put("Menu.foreground", TEXT);
        UIManager.put("MenuItem.background", SURFACE_RAISED);
        UIManager.put("MenuItem.foreground", TEXT);
        UIManager.put("MenuItem.selectionBackground", BLUE_STRONG);
        UIManager.put("Menu.font", BODY);
        UIManager.put("MenuItem.font", BODY);
        UIManager.put("PopupMenu.background", SURFACE_RAISED);
        UIManager.put("Separator.foreground", DIVIDER);
        UIManager.put("ToolTip.background", SURFACE_RAISED);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ScrollPane.border", new javax.swing.plaf.BorderUIResource(BorderFactory.createEmptyBorder()));
        UIManager.put("ScrollBarUI", RoundScrollBarUI.class.getName());
        UIManager.put("Viewport.background", BLACK);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.background", BLACK);
        UIManager.put("ScrollBar.track", BLACK);
        UIManager.put("ScrollBar.thumb", SURFACE_HOVER);
        UIManager.put("ScrollBar.thumbHighlight", BLUE_STRONG);
        UIManager.put("ScrollBar.thumbShadow", SURFACE_HOVER);
        UIManager.put("ScrollBar.thumbDarkShadow", SURFACE);
        UIManager.put("TabbedPaneUI", RoundTabUI.class.getName());
        UIManager.put("TabbedPane.background", BLACK);
        UIManager.put("TabbedPane.foreground", TEXT);
        UIManager.put("TabbedPane.font", BODY_BOLD);
        UIManager.put("TabbedPane.selected", SURFACE_RAISED);
        UIManager.put("TabbedPane.unselectedBackground", SURFACE);
        UIManager.put("TabbedPane.light", DIVIDER);
        UIManager.put("TabbedPane.highlight", DIVIDER);
        UIManager.put("TabbedPane.shadow", DIVIDER);
        UIManager.put("TabbedPane.darkShadow", DIVIDER);
        UIManager.put("TabbedPane.selectHighlight", BLUE);
        UIManager.put("TabbedPane.borderHightlightColor", DIVIDER);
        UIManager.put("Slider.background", SURFACE);
        UIManager.put("Slider.foreground", BLUE);
        UIManager.put("Slider.font", SMALL);
        UIManager.put("ProgressBar.background", SURFACE_RAISED);
        UIManager.put("ProgressBar.foreground", BLUE_STRONG);
        UIManager.put("ProgressBar.selectionBackground", TEXT);
        UIManager.put("ProgressBar.selectionForeground", BLACK);
        UIManager.put("ProgressBar.font", SMALL);
        UIManager.put("Spinner.font", BODY);
        UIManager.put("SpinnerUI", FlatInputUI.Spinner.class.getName());
        UIManager.put("Spinner.background", SURFACE_RAISED);
        UIManager.put("Spinner.border", new javax.swing.plaf.BorderUIResource(UiFactory.roundedBorder(DIVIDER, 8)));
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", TEXT);
    }

    public static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    public static Font font(int style, int size) {
        return new Font(FONT_FAMILY, style, size);
    }

}
