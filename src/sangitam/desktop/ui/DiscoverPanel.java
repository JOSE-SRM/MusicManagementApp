package sangitam.desktop.ui;

import sangitam.desktop.discovery.AppleMusicProvider;
import sangitam.desktop.discovery.CatalogTrack;
import sangitam.desktop.discovery.MusicBrainzProvider;
import sangitam.desktop.discovery.MusicProvider;
import sangitam.desktop.discovery.SpotifyProvider;
import sangitam.desktop.discovery.YouTubeProvider;
import sangitam.desktop.storage.ProviderCredentials;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** Searches remote catalogs without pretending those results are local audio files. */
public final class DiscoverPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JComboBox<MusicProvider> source = new JComboBox<>(new MusicProvider[] {new MusicBrainzProvider()});
    private final JTextField query = new JTextField();
    private final JButton search = UiFactory.button("Search catalog", null, true);
    private final JButton open = UiFactory.button("Open in provider", null, false);
    private final JLabel hint = UiFactory.label("", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel status = UiFactory.label("Choose a source and search for music.", Theme.SMALL, Theme.TEXT_MUTED);
    private final DefaultListModel<CatalogTrack> model = new DefaultListModel<>();
    private final JList<CatalogTrack> results = new JList<>(model);
    private SwingWorker<List<CatalogTrack>, Void> activeSearch;
    private int generation;

    public DiscoverPanel(Consumer<String> notify) {
        super(new BorderLayout(0, 18));
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(28, 30, 24, 30));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = UiFactory.label("Discover music", Theme.TITLE, Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);
        header.add(Box.createVerticalStrut(7));
        JLabel subtitle = UiFactory.label("Explore catalogs, then listen through the provider.", Theme.BODY,
            Theme.TEXT_MUTED);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(subtitle);
        header.add(Box.createVerticalStrut(22));

        JPanel controls = new JPanel(new BorderLayout(10, 0));
        controls.setOpaque(false);
        source.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                if (value instanceof MusicProvider provider) setText(provider.name());
                return this;
            }
        });
        source.setPreferredSize(new Dimension(170, 44));
        source.getAccessibleContext().setAccessibleName("Music provider");
        query.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 12), Theme.padding(8, 12, 8, 12)));
        query.setToolTipText("Song, artist, or album");
        query.getAccessibleContext().setAccessibleName("Catalog search query");
        controls.add(source, BorderLayout.WEST);
        controls.add(query, BorderLayout.CENTER);
        controls.add(search, BorderLayout.EAST);
        controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(controls);
        header.add(Box.createVerticalStrut(9));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(hint);
        add(header, BorderLayout.NORTH);

        results.setFixedCellHeight(72);
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setCellRenderer((list, value, index, selected, focused) -> {
            JPanel row = new JPanel(new BorderLayout(8, 3));
            row.setBorder(Theme.padding(10, 14, 10, 14));
            row.setBackground(selected ? Theme.SURFACE_HOVER : Theme.SURFACE);
            JLabel song = UiFactory.label(value.title(), Theme.BODY_BOLD, Theme.TEXT);
            JLabel detail = UiFactory.label(value.artist() + (value.album().isBlank() ? "" : " · " + value.album()),
                Theme.SMALL, Theme.TEXT_MUTED);
            row.add(song, BorderLayout.NORTH);
            row.add(detail, BorderLayout.SOUTH);
            return row;
        });
        JScrollPane scroll = new JScrollPane(results);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);
        footer.add(status, BorderLayout.CENTER);
        open.setEnabled(false);
        footer.add(open, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        source.addActionListener(event -> providerChanged());
        search.addActionListener(event -> search());
        query.addActionListener(event -> search());
        results.addListSelectionListener(event -> open.setEnabled(results.getSelectedValue() != null));
        results.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) openSelected(notify);
            }
        });
        results.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openTrack");
        results.getActionMap().put("openTrack", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { openSelected(notify); }
        });
        open.addActionListener(event -> openSelected(notify));
        providerChanged();
        ProviderCredentials.preload(loaded -> SwingUtilities.invokeLater(this::refreshProviders));
    }

    public void focusSearch() { query.requestFocusInWindow(); }
    public void prepareArtistSearch(String artist) { if (activeSearch != null) activeSearch.cancel(true); generation++; query.setText(artist); model.clear(); status.setText("Choose a provider and Search catalog to look up this artist."); focusSearch(); }

    public void refreshProviders() {
        MusicProvider selectedProvider = (MusicProvider) source.getSelectedItem();
        source.removeAllItems();
        MusicProvider[] providers = {new MusicBrainzProvider(), new SpotifyProvider(),
            new YouTubeProvider(), new AppleMusicProvider()};
        for (MusicProvider provider : providers) source.addItem(provider);
        if (selectedProvider != null) {
            for (int i = 0; i < source.getItemCount(); i++) {
                if (source.getItemAt(i).name().equals(selectedProvider.name())) {
                    source.setSelectedIndex(i);
                    break;
                }
            }
        }
        providerChanged();
    }

    private void providerChanged() {
        if (activeSearch != null) activeSearch.cancel(true);
        generation++;
        model.clear();
        MusicProvider provider = (MusicProvider) source.getSelectedItem();
        if (provider == null) return;
        hint.setText(provider.setupHint());
        status.setText(provider.available() ? "Ready to search " + provider.name() + "."
            : "This provider needs setup before search.");
        search.setEnabled(provider.available());
        open.setEnabled(false);
    }

    private void search() {
        MusicProvider provider = (MusicProvider) source.getSelectedItem();
        String term = query.getText().strip();
        if (provider == null || !provider.available()) return;
        if (term.isEmpty()) { status.setText("Enter a song, artist, or album."); return; }
        if (activeSearch != null) activeSearch.cancel(true);
        int request = ++generation;
        model.clear();
        search.setEnabled(false);
        status.setText("Searching " + provider.name() + "…");
        activeSearch = new SwingWorker<>() {
            @Override protected List<CatalogTrack> doInBackground() throws Exception {
                return provider.search(term);
            }
            @Override protected void done() {
                if (isCancelled() || request != generation) return;
                search.setEnabled(true);
                try {
                    List<CatalogTrack> found = get();
                    model.addAll(found);
                    status.setText(found.isEmpty() ? "No results found. Try a different search."
                        : found.size() + " results · double-click or press Enter to open a provider page.");
                } catch (Exception failure) {
                    Throwable reason = failure.getCause() == null ? failure : failure.getCause();
                    // Do not show a provider URL or credential if an HTTP library includes it in an error.
                    status.setText("Search failed: " + (reason instanceof IOException
                        || reason instanceof IllegalStateException ? reason.getMessage()
                        : "Check your connection or provider setup."));
                }
            }
        };
        activeSearch.execute();
    }

    private void openSelected(Consumer<String> notify) {
        CatalogTrack track = results.getSelectedValue();
        if (track == null) return;
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            notify.accept("Opening a browser is not supported on this computer.");
            return;
        }
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                Desktop.getDesktop().browse(track.link());
                return null;
            }
            @Override protected void done() {
                try { get(); }
                catch (Exception failure) { notify.accept("Could not open " + track.source() + " in the browser."); }
            }
        }.execute();
    }
}
