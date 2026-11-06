package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Bounded local shelves. Renderers only schedule artwork reads; they never read files. */
final class HomeLibraryPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final LibraryArtwork artwork = new LibraryArtwork(72);
    private HomeLibraryData data = new HomeLibraryData(List.of(), List.of(), java.util.Set.of(), java.util.Map.of());
    private final Shelf recent, pinned;

    HomeLibraryPanel(Consumer<LibraryEntry> open, Function<LibraryEntry, JPopupMenu> menu) {
        super(new GridLayout(2, 1, 0, 16));
        setOpaque(false);
        recent = new Shelf("Recently played", "Play a local track to find it here.", open, menu);
        pinned = new Shelf("Your pinned playlists", "Pin a playlist from its context menu to keep it here.", open, menu);
        add(recent); add(pinned);
        setPreferredSize(new Dimension(760, 300));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 300));
    }

    void setData(HomeLibraryData next) {
        data = next; recent.setEntries(next.recent()); pinned.setEntries(next.pinned());
    }
    void close() { artwork.close(); }

    private final class Shelf extends JPanel {
        private static final long serialVersionUID = 1L;
        private final DefaultListModel<LibraryEntry> model = new DefaultListModel<>();
        private final JList<LibraryEntry> list = new JList<>(model);
        private final CardLayout layout = new CardLayout();
        private final JPanel body = new JPanel(layout);

        Shelf(String title, String empty, Consumer<LibraryEntry> open, Function<LibraryEntry, JPopupMenu> menu) {
            super(new BorderLayout(0, 8)); setOpaque(false);
            add(UiFactory.label(title, Theme.HEADING, Theme.TEXT), BorderLayout.NORTH);
            list.setLayoutOrientation(JList.HORIZONTAL_WRAP); list.setVisibleRowCount(1);
            list.setFixedCellWidth(248); list.setFixedCellHeight(92);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setBackground(Theme.BLACK); list.setCellRenderer(new CoverRenderer());
            list.getAccessibleContext().setAccessibleName(title);
            list.setToolTipText("Double-click or press Enter to open · right-click for actions");
            JScrollPane scroll = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            scroll.setBorder(null); scroll.getViewport().setBackground(Theme.BLACK);
            body.setOpaque(false); body.add(scroll, "items");
            body.add(UiFactory.label(empty, Theme.SMALL, Theme.TEXT_MUTED), "empty");
            add(body, BorderLayout.CENTER); layout.show(body, "empty");
            list.addMouseListener(new MouseAdapter() {
                private void popup(MouseEvent event) {
                    if (!event.isPopupTrigger()) return;
                    int index = list.locationToIndex(event.getPoint());
                    if (index < 0 || !list.getCellBounds(index, index).contains(event.getPoint())) return;
                    list.setSelectedIndex(index); menu.apply(model.get(index)).show(list, event.getX(), event.getY());
                }
                @Override public void mousePressed(MouseEvent event) { popup(event); }
                @Override public void mouseReleased(MouseEvent event) { popup(event); }
                @Override public void mouseClicked(MouseEvent event) {
                    int index = list.locationToIndex(event.getPoint());
                    if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2 && index >= 0
                        && list.getCellBounds(index, index).contains(event.getPoint())) open.accept(model.get(index));
                }
            });
            list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openEntry");
            list.getActionMap().put("openEntry", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { if (list.getSelectedValue() != null) open.accept(list.getSelectedValue()); }
            });
            list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK), "entryMenu");
            list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), "entryMenu");
            list.getActionMap().put("entryMenu", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) {
                    int selected = list.getSelectedIndex(); if (selected < 0) return;
                    Rectangle bounds = list.getCellBounds(selected, selected);
                    menu.apply(list.getSelectedValue()).show(list, bounds.x, bounds.y + bounds.height);
                }
            });
        }
        void setEntries(List<LibraryEntry> entries) {
            var selected = list.getSelectedValue(); model.clear(); model.addAll(entries);
            if (selected != null) for (int i = 0; i < model.size(); i++) if (model.get(i).key().equals(selected.key())) list.setSelectedIndex(i);
            layout.show(body, entries.isEmpty() ? "empty" : "items");
        }
    }

    private final class CoverRenderer extends JPanel implements ListCellRenderer<LibraryEntry> {
        private static final long serialVersionUID = 1L;
        private final JLabel picture = new JLabel(), title = UiFactory.label("", Theme.BODY_BOLD, Theme.TEXT),
            detail = UiFactory.label("", Theme.SMALL, Theme.TEXT_MUTED);
        private final Icon fallback = AppIcons.of(AppIcons.Kind.MUSIC, 32, Theme.BLUE);
        CoverRenderer() {
            super(new BorderLayout(12, 0)); setBorder(Theme.padding(10, 10, 10, 10));
            picture.setPreferredSize(new Dimension(72, 72)); picture.setHorizontalAlignment(SwingConstants.CENTER);
            add(picture, BorderLayout.WEST);
            JPanel labels = new JPanel(new GridLayout(2, 1, 0, 4)); labels.setOpaque(false);
            labels.add(title); labels.add(detail); add(labels, BorderLayout.CENTER);
        }
        @Override public Component getListCellRendererComponent(JList<? extends LibraryEntry> list, LibraryEntry entry, int index, boolean selected, boolean focus) {
            title.setText(entry.name()); detail.setText(entry.detail());
            Icon cover = artwork.forEntry(entry, data.unavailable(), data.stamps(), list::repaint);
            picture.setIcon(cover == null ? fallback : cover);
            setBackground(selected ? Theme.SURFACE_HOVER : Theme.SURFACE);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(focus ? Theme.BLUE : Theme.BLACK), Theme.padding(9, 9, 9, 9)));
            setToolTipText(entry.name() + " · " + entry.detail());
            return this;
        }
    }
}
