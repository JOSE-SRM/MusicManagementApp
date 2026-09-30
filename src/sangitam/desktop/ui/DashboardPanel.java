package sangitam.desktop.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Modern home dashboard built only from standard Swing components.
 * IMPORTANT: The central table uses a non-editable model because this screen is for navigation, not metadata editing.
 */
public final class DashboardPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    public DashboardPanel(Runnable importMedia, Consumer<String> notify) {
        super(new BorderLayout(0, 22));
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(28, 30, 24, 30));
        add(header(importMedia), BorderLayout.NORTH);
        add(content(notify), BorderLayout.CENTER);
    }

    private JComponent header(Runnable importMedia) {
        JPanel header = new JPanel(new BorderLayout(20, 10));
        header.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(UiFactory.label("Good evening", Theme.TITLE, Theme.TEXT));
        titles.add(Box.createVerticalStrut(5));
        titles.add(UiFactory.label("Pick up where you left off, or bring in something new.", Theme.BODY, Theme.TEXT_MUTED));
        header.add(titles, BorderLayout.CENTER);

        JButton add = UiFactory.button("Add music", AppIcons.of(AppIcons.Kind.PLUS, 16, Theme.BLACK), true);
        add.addActionListener(event -> importMedia.run());
        header.add(add, BorderLayout.EAST);
        return header;
    }

    private JComponent content(Consumer<String> notify) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel quick = new JPanel(new GridLayout(1, 3, 14, 0));
        quick.setOpaque(false);
        quick.setMaximumSize(new Dimension(Integer.MAX_VALUE, 126));
        quick.add(statCard("42", "Tracks", "Across 6 playlists", notify));
        quick.add(statCard("3 h 18 m", "Listening time", "This week", notify));
        quick.add(statCard("12", "Recently added", "Ready to explore", notify));
        content.add(quick);
        content.add(Box.createVerticalStrut(26));

        JLabel recent = UiFactory.label("Recently played", Theme.HEADING, Theme.TEXT);
        recent.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(recent);
        content.add(Box.createVerticalStrut(12));

        String[] columns = {"#", "Title", "Artist", "Album", "Duration"};
        Object[][] rows = {
            {1, "Midnight Drive", "Aster Avenue", "Blue Hours", "3:42"},
            {2, "Soft Current", "North Arcade", "Signals", "4:06"},
            {3, "City Rain", "Mira Coast", "Afterlight", "3:18"},
            {4, "Open Skies", "Sol Line", "Long Way Home", "4:51"},
            {5, "Quiet Motion", "Aster Avenue", "Blue Hours", "3:35"}
        };
        JTable table = new JTable(new DefaultTableModel(rows, columns) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        });
        table.setRowHeight(38);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                notify.accept("Selected “" + table.getValueAt(table.getSelectedRow(), 1) + "”");
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setBorder(UiFactory.roundedBorder(Theme.DIVIDER, 14));
        content.add(scroll);
        return content;
    }

    private JPanel statCard(String value, String title, String detail, Consumer<String> notify) {
        JPanel card = UiFactory.surface(new BorderLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 16), Theme.padding(18, 20, 18, 20)));
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(UiFactory.label(value, new Font("SansSerif", Font.BOLD, 24), Theme.BLUE));
        copy.add(Box.createVerticalStrut(6));
        copy.add(UiFactory.label(title, Theme.BODY_BOLD, Theme.TEXT));
        copy.add(Box.createVerticalStrut(3));
        copy.add(UiFactory.label(detail, Theme.SMALL, Theme.TEXT_MUTED));
        card.add(copy, BorderLayout.CENTER);
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) { notify.accept(title + " opened"); }
        });
        return card;
    }
}
