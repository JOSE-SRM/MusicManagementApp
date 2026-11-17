package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Left navigation rail. IMPORTANT: Card names here must match MainFrame's CardLayout keys. */
public final class NavigationPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final Map<String, JButton> destinations = new LinkedHashMap<>();
    private final Map<String, AppIcons.Kind> icons = new LinkedHashMap<>();

    public NavigationPanel(Consumer<String> navigate, Runnable importMedia, Runnable toggleLibrary,
                           Runnable quickActions, Consumer<String> notify) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(Theme.BLACK);
        setPreferredSize(new Dimension(205, 0));
        setBorder(Theme.padding(22, 16, 18, 16));

        JLabel brand = UiFactory.label("Sangitam", Theme.font(Font.BOLD, 23), Theme.TEXT);
        brand.setIcon(AppIcons.of(AppIcons.Kind.MUSIC, 24, Theme.BLUE));
        brand.setIconTextGap(10);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(brand);
        add(Box.createVerticalStrut(30));

        add(navButton("home", "Listen now", AppIcons.Kind.HOME, () -> navigate.accept("home")));
        add(Box.createVerticalStrut(6));
        add(navButton("search", "Search", AppIcons.Kind.SEARCH, () -> navigate.accept("search")));
        add(Box.createVerticalStrut(6));
        add(navButton("discover", "Discover online", AppIcons.Kind.SEARCH, () -> navigate.accept("discover")));
        add(Box.createVerticalStrut(6));
        add(navButton("rediscover", "Rediscover", AppIcons.Kind.MUSIC, () -> navigate.accept("rediscover")));
        add(Box.createVerticalStrut(6));
        add(navButton("import", "Browse media", AppIcons.Kind.FOLDER, importMedia));
        add(Box.createVerticalStrut(6));
        add(navButton("library", "Your library", AppIcons.Kind.MUSIC, toggleLibrary));
        add(Box.createVerticalStrut(22));

        JLabel collection = UiFactory.label("Your collection", Theme.SMALL, Theme.TEXT_MUTED);
        collection.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(collection);
        add(Box.createVerticalStrut(10));
        add(navButton("songs", "Songs", AppIcons.Kind.MUSIC, () -> navigate.accept("songs")));
        add(Box.createVerticalStrut(6));
        add(navButton("albums", "Albums", AppIcons.Kind.MUSIC, () -> navigate.accept("albums")));
        add(Box.createVerticalStrut(6));
        add(navButton("artists", "Artists", AppIcons.Kind.MUSIC, () -> navigate.accept("artists")));
        add(Box.createVerticalStrut(6));
        add(navButton("playlists", "Playlists", AppIcons.Kind.MUSIC, () -> navigate.accept("playlists")));

        add(Box.createVerticalGlue());
        String shortcut = System.getProperty("os.name", "").toLowerCase().contains("mac") ? "⌘K" : "Ctrl K";
        JButton quick = navButton("quick", "Quick actions  " + shortcut, AppIcons.Kind.SEARCH, quickActions);
        quick.setFont(Theme.SMALL);
        add(quick);
        add(Box.createVerticalStrut(6));
        add(navButton("settings", "Settings", AppIcons.Kind.SETTINGS, () -> notify.accept("Settings selected")));
        setActive("home");
    }

    private JButton navButton(String key, String text, AppIcons.Kind icon, Runnable action) {
        JButton button = UiFactory.button(text, AppIcons.of(icon, 17, Theme.TEXT_MUTED), false);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setIconTextGap(12);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.addActionListener(event -> action.run());
        destinations.put(key, button);
        icons.put(key, icon);
        return button;
    }

    public void setActive(String destination) {
        String selected = switch (destination) {
            case "songs", "albums", "artists", "playlists" -> destination;
            case "search", "discover", "rediscover" -> destination;
            default -> "home";
        };
        destinations.forEach((key, button) -> {
            boolean active = key.equals(selected);
            button.putClientProperty("sangitam.selected", active);
            button.setBackground(active ? Theme.SURFACE_HOVER : Theme.SURFACE_RAISED);
            button.setForeground(active ? Theme.TEXT : Theme.TEXT_MUTED);
            button.setIcon(AppIcons.of(icons.get(key), 17, active ? Theme.BLUE : Theme.TEXT_MUTED));
            button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, active ? 3 : 0, 0, 0,
                    active ? Theme.BLUE : Theme.SURFACE_RAISED), Theme.padding(8, active ? 10 : 13, 8, 13)));
        });
    }
}
