package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Left navigation rail. IMPORTANT: Card names here must match MainFrame's CardLayout keys. */
public final class NavigationPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    public NavigationPanel(Consumer<String> navigate, Runnable importMedia, Runnable toggleLibrary,
                           Runnable quickActions, Consumer<String> notify) {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(Theme.BLACK);
        setPreferredSize(new Dimension(205, 0));
        setBorder(Theme.padding(22, 16, 18, 16));

        JLabel brand = UiFactory.label("Sangitam", Theme.font(Font.BOLD, 23), Theme.BLUE);
        brand.setIcon(AppIcons.of(AppIcons.Kind.MUSIC, 24, Theme.BLUE));
        brand.setIconTextGap(10);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(brand);
        add(Box.createVerticalStrut(30));

        add(navButton("Listen now", AppIcons.Kind.HOME, () -> navigate.accept("home")));
        add(Box.createVerticalStrut(6));
        add(navButton("Search", AppIcons.Kind.SEARCH, () -> navigate.accept("search")));
        add(Box.createVerticalStrut(6));
        add(navButton("Browse media", AppIcons.Kind.FOLDER, importMedia));
        add(Box.createVerticalStrut(6));
        add(navButton("Your library", AppIcons.Kind.MUSIC, toggleLibrary));
        add(Box.createVerticalStrut(22));

        JLabel collection = UiFactory.label("Your collection", Theme.SMALL, Theme.TEXT_MUTED);
        collection.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(collection);
        add(Box.createVerticalStrut(10));
        add(navButton("Songs", AppIcons.Kind.MUSIC, () -> notify.accept("Songs selected")));
        add(Box.createVerticalStrut(6));
        add(navButton("Albums", AppIcons.Kind.MUSIC, () -> notify.accept("Albums selected")));
        add(Box.createVerticalStrut(6));
        add(navButton("Artists", AppIcons.Kind.MUSIC, () -> notify.accept("Artists selected")));

        add(Box.createVerticalGlue());
        String shortcut = System.getProperty("os.name", "").toLowerCase().contains("mac") ? "⌘K" : "Ctrl K";
        JButton quick = navButton("Quick actions  " + shortcut, AppIcons.Kind.SEARCH, quickActions);
        quick.setFont(Theme.SMALL);
        add(quick);
        add(Box.createVerticalStrut(6));
        add(navButton("Settings", AppIcons.Kind.SETTINGS, () -> notify.accept("Settings selected")));
    }

    private JButton navButton(String text, AppIcons.Kind icon, Runnable action) {
        JButton button = UiFactory.button(text, AppIcons.of(icon, 17, Theme.TEXT_MUTED), false);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setIconTextGap(12);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.addActionListener(event -> action.run());
        return button;
    }
}
