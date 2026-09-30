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
    public static final Color SUCCESS = new Color(100, 218, 178);

    public static final Font BODY = new Font("SansSerif", Font.PLAIN, 14);
    public static final Font BODY_BOLD = new Font("SansSerif", Font.BOLD, 14);
    public static final Font SMALL = new Font("SansSerif", Font.PLAIN, 12);
    public static final Font TITLE = new Font("SansSerif", Font.BOLD, 28);
    public static final Font HEADING = new Font("SansSerif", Font.BOLD, 18);

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
        UIManager.put("TextField.background", SURFACE_RAISED);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", BLUE);
        UIManager.put("TextArea.background", SURFACE_RAISED);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("ComboBox.background", SURFACE_RAISED);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("List.background", SURFACE);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("List.selectionBackground", BLUE_STRONG);
        UIManager.put("Table.background", SURFACE);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.gridColor", DIVIDER);
        UIManager.put("Table.selectionBackground", BLUE_STRONG);
        UIManager.put("TableHeader.background", SURFACE_RAISED);
        UIManager.put("TableHeader.foreground", TEXT);
        UIManager.put("Tree.background", SURFACE);
        UIManager.put("Tree.foreground", TEXT);
        UIManager.put("Tree.selectionBackground", BLUE_STRONG);
        UIManager.put("MenuBar.background", BLACK);
        UIManager.put("MenuBar.foreground", TEXT);
        UIManager.put("Menu.background", BLACK);
        UIManager.put("Menu.foreground", TEXT);
        UIManager.put("MenuItem.background", SURFACE_RAISED);
        UIManager.put("MenuItem.foreground", TEXT);
        UIManager.put("MenuItem.selectionBackground", BLUE_STRONG);
        UIManager.put("PopupMenu.background", SURFACE_RAISED);
        UIManager.put("Separator.foreground", DIVIDER);
        UIManager.put("ToolTip.background", SURFACE_RAISED);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ScrollPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("TabbedPane.background", BLACK);
        UIManager.put("TabbedPane.foreground", TEXT);
        UIManager.put("TabbedPane.selected", SURFACE_RAISED);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", TEXT);
    }

    public static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }
}
