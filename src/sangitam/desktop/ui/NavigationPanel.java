package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Left navigation rail. IMPORTANT: Card names here must match MainFrame's CardLayout keys. */
public final class NavigationPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final Map<JButton, String> labels = new LinkedHashMap<>();
    private final Map<String, JButton> pages = new LinkedHashMap<>();
    private final JLabel brand;
    private final JButton collapse;
    private Consumer<Boolean> collapseListener = ignored -> { };
    private boolean collapsed;

    public NavigationPanel(Consumer<String> navigate, Runnable importMedia, Runnable toggleLibrary,
                           Runnable quickActions, Runnable openSettings) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(Theme.BLACK);
        setPreferredSize(new Dimension(205, 0));
        setBorder(Theme.padding(22, 16, 18, 16));

        brand = UiFactory.label("Sangitam", Theme.font(Font.BOLD, 23), Theme.BLUE);
        brand.getAccessibleContext().setAccessibleName("Sangitam");
        brand.setIcon(AppIcons.of(AppIcons.Kind.MUSIC, 24, Theme.BLUE));
        brand.setIconTextGap(10);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(brand);
        add(Box.createVerticalStrut(10));
        collapse = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.COLLAPSE, 17, Theme.TEXT_MUTED), "Collapse navigation");
        collapse.setAlignmentX(Component.LEFT_ALIGNMENT);
        collapse.addActionListener(event -> setCollapsed(!collapsed));
        add(collapse);
        add(Box.createVerticalStrut(12));

        add(pageButton("home", "Listen now", AppIcons.Kind.HOME, navigate));
        add(Box.createVerticalStrut(6));
        add(pageButton("search", "Search", AppIcons.Kind.SEARCH, navigate));
        add(Box.createVerticalStrut(6));
        add(pageButton("discover", "Discover online", AppIcons.Kind.GLOBE, navigate));
        add(Box.createVerticalStrut(6));
        add(pageButton("rediscover", "Rediscover", AppIcons.Kind.HISTORY, navigate));
        add(Box.createVerticalStrut(6));
        add(navButton("Browse media", AppIcons.Kind.FOLDER, importMedia));
        add(Box.createVerticalStrut(6));
        add(navButton("Your library", AppIcons.Kind.MUSIC, toggleLibrary));
        add(Box.createVerticalStrut(22));

        add(Box.createVerticalGlue());
        add(navButton("Quick actions", AppIcons.Kind.PLUS, quickActions));
        add(Box.createVerticalStrut(6));
        add(navButton("Settings", AppIcons.Kind.SETTINGS, openSettings));
        setActivePage("home");
    }

    boolean isCollapsed() { return collapsed; }

    void setCollapseListener(Consumer<Boolean> listener) { collapseListener = listener; }

    void setCollapsed(boolean value) {
        if (collapsed == value) return;
        collapsed = value;
        setPreferredSize(new Dimension(value ? 64 : 205, 0));
        setBorder(value ? Theme.padding(22, 9, 18, 9) : Theme.padding(22, 16, 18, 16));
        brand.setText(value ? "" : "Sangitam");
        labels.forEach((button, label) -> {
            button.setText(value ? "" : label);
            button.setHorizontalAlignment(value ? SwingConstants.CENTER : SwingConstants.LEFT);
        });
        String action = value ? "Expand navigation" : "Collapse navigation";
        collapse.setIcon(AppIcons.of(value ? AppIcons.Kind.EXPAND : AppIcons.Kind.COLLAPSE, 17, Theme.TEXT_MUTED));
        collapse.setToolTipText(action);
        collapse.getAccessibleContext().setAccessibleName(action);
        collapseListener.accept(value);
        revalidate();
        repaint();
    }

    void setActivePage(String page) {
        pages.forEach((key, button) -> {
            boolean active = key.equals(page);
            button.getModel().setSelected(active);
            button.getAccessibleContext().setAccessibleDescription(active ? "Current page" : null);
            button.setForeground(active ? Theme.BLUE : Theme.TEXT);
        });
    }

    private JButton pageButton(String page, String label, AppIcons.Kind icon, Consumer<String> navigate) {
        JButton button = navButton(label, icon, () -> navigate.accept(page));
        button.setSelectedIcon(AppIcons.of(icon, 17, Theme.BLUE));
        pages.put(page, button);
        return button;
    }

    private JButton navButton(String text, AppIcons.Kind icon, Runnable action) {
        JButton button = UiFactory.button(text, AppIcons.of(icon, 17, Theme.TEXT_MUTED), false);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setIconTextGap(12);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setToolTipText(text);
        button.getAccessibleContext().setAccessibleName(text);
        labels.put(button, text);
        button.addActionListener(event -> action.run());
        return button;
    }
}
