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
        JScrollPane scroll = new JScrollPane(content(notify));
        scroll.setBorder(null);
        scroll.setBackground(Theme.BLACK);
        scroll.getViewport().setBackground(Theme.BLACK);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll, BorderLayout.CENTER);
    }

    private JComponent header(Runnable importMedia) {
        JPanel header = new JPanel(new BorderLayout(20, 10));
        header.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(UiFactory.label("Music for the moment you’re in", Theme.TITLE, Theme.TEXT));
        titles.add(Box.createVerticalStrut(5));
        titles.add(UiFactory.label("Shape a session, continue listening, or bring in something new.", Theme.BODY, Theme.TEXT_MUTED));
        header.add(titles, BorderLayout.CENTER);

        JButton add = UiFactory.button("Add music", AppIcons.of(AppIcons.Kind.PLUS, 16, Theme.BLACK), true);
        add.addActionListener(event -> importMedia.run());
        header.add(add, BorderLayout.EAST);
        return header;
    }

    private JComponent content(Consumer<String> notify) {
        JPanel content = new JPanel();
        content.setOpaque(true);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        content.setBackground(Theme.BLACK);
        SessionComposerPanel composer = new SessionComposerPanel(notify);
        composer.setAlignmentX(Component.LEFT_ALIGNMENT);
        composer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 380));
        composer.setPreferredSize(new Dimension(760, 350));
        content.add(composer);
        content.add(Box.createVerticalStrut(26));

        JLabel recent = UiFactory.label("Continue listening", Theme.HEADING, Theme.TEXT);
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
        table.setFont(Theme.BODY);
        table.getTableHeader().setFont(Theme.BODY_BOLD);
        table.getTableHeader().setPreferredSize(new Dimension(0, 40));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                notify.accept("Selected “" + table.getValueAt(table.getSelectedRow(), 1) + "”");
            }
        });
        table.getColumnModel().getColumn(0).setPreferredWidth(42);
        table.getColumnModel().getColumn(1).setPreferredWidth(210);
        table.getColumnModel().getColumn(2).setPreferredWidth(175);
        table.getColumnModel().getColumn(3).setPreferredWidth(175);
        table.getColumnModel().getColumn(4).setPreferredWidth(82);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBackground(Theme.SURFACE);
        scroll.getViewport().setBackground(Theme.SURFACE);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setBorder(UiFactory.roundedBorder(Theme.DIVIDER, 14));
        scroll.setPreferredSize(new Dimension(700, 220));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
        content.add(scroll);
        return content;
    }
}
