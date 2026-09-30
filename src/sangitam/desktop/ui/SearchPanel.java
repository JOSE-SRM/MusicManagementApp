package sangitam.desktop.ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Search prototype that demonstrates text, tabs and list components.
 * IMPORTANT: Document listeners only update UI feedback; search services can be connected here later.
 */
public final class SearchPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    public SearchPanel(Consumer<String> notify) {
        super(new BorderLayout(0, 20));
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(28, 30, 24, 30));

        JPanel top = new JPanel(new BorderLayout(12, 10));
        top.setOpaque(false);
        top.add(UiFactory.label("Search", Theme.TITLE, Theme.TEXT), BorderLayout.NORTH);
        JTextField search = new JTextField();
        search.setFont(Theme.BODY);
        search.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 14), Theme.padding(11, 14, 11, 14)));
        search.setToolTipText("Search titles, artists and albums");
        top.add(search, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        DefaultListModel<String> model = new DefaultListModel<>();
        model.addElement("Midnight Drive — Aster Avenue");
        model.addElement("Soft Current — North Arcade");
        model.addElement("City Rain — Mira Coast");
        JList<String> songs = new JList<>(model);
        songs.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        songs.setFixedCellHeight(42);
        songs.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && songs.getSelectedValue() != null) {
                notify.accept("Selected “" + songs.getSelectedValue() + "”");
            }
        });

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Songs", new JScrollPane(songs));
        tabs.addTab("Albums", emptyState("Album results appear here"));
        tabs.addTab("Artists", emptyState("Artist results appear here"));
        add(tabs, BorderLayout.CENTER);

        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
            private void changed() {
                if (!search.getText().trim().isEmpty()) notify.accept("Searching for “" + search.getText().trim() + "”");
            }
        });
    }

    private JComponent emptyState(String text) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Theme.SURFACE);
        panel.add(UiFactory.label(text, Theme.BODY, Theme.TEXT_MUTED));
        return panel;
    }
}
