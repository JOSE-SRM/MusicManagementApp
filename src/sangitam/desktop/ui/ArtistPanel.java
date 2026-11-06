package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.*;
import sangitam.desktop.storage.*;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.function.*;

/** Worker-loaded, sourced online profiles with an offline cache and protected manual details. */
final class ArtistPanel extends JPanel {
    private final LibraryStore library;
    private final ArtistStore profiles;
    private final ArtistEnrichmentStore enrichment;
    private final sangitam.desktop.discovery.ArtistEnrichmentClient online;
    private Boolean onlineEnabled;
    private boolean forceRefresh, sourcedPortrait, saveOnlinePreference;
    private String matchName;
    private Runnable enriched = () -> { };
    private ArtistEnrichmentStore.Cached source;
    private String onlineStatus = "";
    private final Map<String,Long> failures = new java.util.concurrent.ConcurrentHashMap<>();
    private final BiConsumer<List<Track>, Boolean> play;
    private final Consumer<LibraryEntry> openAlbum;
    private final Consumer<String> openArtist, notify;
    private final Function<Track, JPopupMenu> menu;
    private String name;
    private ArtistStore.Profile profile = new ArtistStore.Profile("", null);
    private LibraryService.Catalog catalog;
    private LibraryCollections organization;
    private SwingWorker<?, ?> loader;
    private boolean closed;
    private Consumer<String> discovery;
    private final LibraryArtwork art = new LibraryArtwork(64);

    ArtistPanel(LibraryStore library, BiConsumer<List<Track>, Boolean> play, Consumer<LibraryEntry> openAlbum,
                Consumer<String> openArtist, Function<Track, JPopupMenu> menu, Consumer<String> notify) {
        this(library,play,openAlbum,openArtist,menu,notify,new sangitam.desktop.discovery.ArtistEnrichmentClient());
    }
    ArtistPanel(LibraryStore library, BiConsumer<List<Track>, Boolean> play, Consumer<LibraryEntry> openAlbum,
                Consumer<String> openArtist, Function<Track, JPopupMenu> menu, Consumer<String> notify, sangitam.desktop.discovery.ArtistEnrichmentClient online) {
        super(new BorderLayout()); this.library = library; profiles = new ArtistStore(library); enrichment = new ArtistEnrichmentStore(library); this.play = play;
        this.online = online;
        this.openAlbum = openAlbum; this.openArtist = openArtist; this.menu = menu; this.notify = notify; setBackground(Theme.BLACK);
    }
    void onEnriched(Runnable action) { enriched = action; }
    void setDiscoveryAction(Consumer<String> action) { discovery = action; }
    void showArtist(String name, LibraryService.Catalog catalog, LibraryCollections organization) {
        this.name = name; this.catalog = catalog; this.organization = organization;
        if (loader != null) loader.cancel(true);
        removeAll(); add(UiFactory.label("Loading " + name + "…", Theme.HEADING, Theme.TEXT_MUTED)); revalidate(); repaint();
        Boolean preference = onlineEnabled; boolean force = forceRefresh; forceRefresh = false;
        boolean savePreference = saveOnlinePreference; saveOnlinePreference = false;
        String lookup = matchName == null ? name : matchName; matchName = null;
        record Loaded(ArtistPageData data, ArtistStore.Profile profile, Icon portrait, ArtistEnrichmentStore.Cached source, String status, boolean changed, boolean enabled, boolean sourcedPortrait) { }
        loader = new SwingWorker<Loaded, Void>() {
            @Override protected Loaded doInBackground() throws Exception {
                ArtistStore.Profile profile; double seconds; ArtistEnrichmentStore.Cached cached;
                synchronized (library) { profiles.initialize(); cached = enrichment.load(name); }
                boolean fetchOnline = preference != null ? preference : System.getProperty("sangitam.artistOnline") != null
                    ? Boolean.parseBoolean(System.getProperty("sangitam.artistOnline")) : enrichment.onlineEnabled();
                if (savePreference) synchronized (library) { enrichment.setOnlineEnabled(fetchOnline); }
                boolean changed = false; String status = "";
                if ((fetchOnline || force) && (force || cached == null || System.currentTimeMillis()-cached.fetched()>30L*86400000)
                    && (force || System.currentTimeMillis()-failures.getOrDefault(name,0L)>3600000)) {
                    try {
                        String role = catalog.tracks().stream().flatMap(track -> ArtistCredits.of(track).stream()).filter(credit -> ArtistCredits.key(credit.name()).equals(ArtistCredits.key(name))).map(ArtistCredits.Credit::role).findFirst().orElse("musician");
                        var result = online.fetch(lookup,role);
                        if (isCancelled()) return null;
                        synchronized (library) { enrichment.save(name,result); cached = enrichment.load(name); } changed = true;
                    } catch (Exception error) { if (isCancelled()) return null; failures.put(name,System.currentTimeMillis()); status = "Online profile unavailable or ambiguous · cached/manual details kept. Use a fuller name to match."; }
                }
                String canonical = cached == null ? name : cached.name();
                synchronized (library) { profile = profiles.load(canonical); seconds = profiles.artistSeconds(canonical); }
                boolean useCached = cached != null && profile.description().isBlank() && profile.portrait() == null;
                if (useCached) profile = new ArtistStore.Profile(cached.description(), cached.portrait());
                var aliases = enrichment.aliases();
                List<Track> normalized = catalog.tracks().stream().map(track -> ArtistEnrichmentStore.canonical(track,aliases)).toList();
                var view = new LibraryService.Catalog(normalized,catalog.tags(),catalog.playCounts(),catalog.manualTags(),catalog.unavailable(),catalog.recent(),catalog.metadataRefreshPaths(),catalog.stamps());
                Icon portrait = profile.portrait() == null ? null : new ImageIcon(ImageIO.read(new ByteArrayInputStream(profile.portrait())).getScaledInstance(152,152,Image.SCALE_SMOOTH));
                return new Loaded(ArtistPageData.build(canonical,view,organization,seconds),profile,portrait,cached,status,changed,fetchOnline,useCached);
            }
            @Override protected void done() {
                if (closed || isCancelled() || loader != this) return;
                try { var loaded = get(); if (loaded == null) return; ArtistPanel.this.profile = loaded.profile(); onlineEnabled = loaded.enabled(); sourcedPortrait = loaded.sourcedPortrait(); source = loaded.source(); onlineStatus = loaded.status(); if (source != null) ArtistPanel.this.name = source.name(); render(loaded.data(), loaded.portrait()); if (loaded.changed()) enriched.run(); }
                catch (Exception error) { removeAll(); add(UiFactory.label("Artist could not be loaded. Open the artist again to retry.", Theme.BODY, Theme.TEXT_MUTED)); revalidate(); repaint(); }
            }
        }; loader.execute();
    }
    private void render(ArtistPageData data, Icon portrait) {
        removeAll(); JPanel content = new Column(); content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS)); content.setBorder(Theme.padding(24, 28, 30, 28)); content.setBackground(Theme.BLACK);
        JPanel header = new JPanel(new BorderLayout(20, 16)); header.setBackground(Theme.SURFACE_RAISED); header.setBorder(BorderFactory.createCompoundBorder(UiFactory.roundedBorder(Theme.DIVIDER, 28), Theme.padding(20, 20, 20, 20)));
        JLabel image = new JLabel(portrait == null ? AppIcons.of(AppIcons.Kind.MUSIC, 96, Theme.BLUE) : portrait); image.setPreferredSize(new Dimension(152, 152)); image.setHorizontalAlignment(SwingConstants.CENTER); header.add(image, BorderLayout.WEST);
        JPanel bio = new JPanel(); bio.setLayout(new BoxLayout(bio, BoxLayout.Y_AXIS)); bio.setOpaque(false);
        JLabel title = UiFactory.label(name, Theme.TITLE, Theme.TEXT); title.putClientProperty("html.disable", true); bio.add(title);
        bio.add(UiFactory.label(duration(data.seconds()) + " listened on this device", Theme.SMALL, Theme.BLUE)); bio.add(Box.createVerticalStrut(10));
        String description = profile.description().isBlank() ? "Add a short biography, notes about their music, recordings, and life." : profile.description();
        JTextArea text = new JTextArea(); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setOpaque(false); text.setForeground(Theme.TEXT_MUTED); text.setFont(Theme.SMALL);
        boolean lengthy = description.length() > 260; String collapsed = lengthy ? description.substring(0, 260) + "…" : description;
        text.setText(collapsed); text.setRows(4);
        JScrollPane biographyView = new JScrollPane(text, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        biographyView.setBorder(null); biographyView.setOpaque(false); biographyView.getViewport().setOpaque(false); UiFactory.styleScroll(biographyView); bio.add(biographyView);
        if (lengthy) { JButton more = link("Read more", () -> { }); more.addActionListener(event -> {
            boolean expand = more.getText().equals("Read more"); text.setText(expand ? description : collapsed); text.setRows(expand ? Math.min(18, 4 + description.length() / 90) : 4); more.setText(expand ? "Show less" : "Read more");
            biographyView.setVerticalScrollBarPolicy(expand ? ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED : ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
            text.setCaretPosition(0);
            header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height)); revalidate();
        }); bio.add(more); }
        header.add(bio, BorderLayout.CENTER);
        JPanel controls = new JPanel(new GridLayout(0,3,8,8)); controls.setOpaque(false);
        JButton playButton = UiFactory.button("Play artist", AppIcons.of(AppIcons.Kind.PLAY, 23, Theme.BLACK), true); playButton.addActionListener(event -> play.accept(data.tracks(), false));
        JButton shuffle = UiFactory.button("Shuffle", AppIcons.of(AppIcons.Kind.SHUFFLE, 22), false); shuffle.addActionListener(event -> play.accept(data.tracks(), true));
        playButton.setEnabled(!data.tracks().isEmpty()); shuffle.setEnabled(!data.tracks().isEmpty());
        JButton edit = UiFactory.button("Edit artist profile…", null, false); edit.addActionListener(event -> editProfile());
        controls.add(playButton); controls.add(shuffle); controls.add(edit);
        JCheckBox auto = new JCheckBox("Online profile", onlineEnabled); auto.setOpaque(false); auto.setToolTipText("Automatically fetch Wikipedia descriptions and a freely licensed Commons portrait on artist opening");
        auto.addActionListener(event -> { onlineEnabled = auto.isSelected(); saveOnlinePreference = true; showArtist(name,catalog,organization); }); controls.add(auto);
        JButton refresh = UiFactory.button("Refresh / match…", null, false); refresh.addActionListener(event -> { String match = JOptionPane.showInputDialog(this,"Fuller artist name for an online match",name); if(match != null && !match.isBlank()){forceRefresh = true; matchName = match.strip(); showArtist(name,catalog,organization);} }); controls.add(refresh); header.add(controls, BorderLayout.SOUTH); addBlock(content, header);
        if (!onlineStatus.isBlank()) addBlock(content, UiFactory.label(onlineStatus,Theme.SMALL,Theme.TEXT_MUTED));
        if (source != null) {
            addBlock(content, link("Wikipedia source · CC BY-SA", () -> openSource(source.source())));
            if (sourcedPortrait && !source.credit().isBlank()) { JLabel credit = UiFactory.label("Image: " + source.credit(),Theme.SMALL,Theme.TEXT_MUTED); credit.putClientProperty("html.disable",true); credit.setToolTipText(source.credit()); addBlock(content,credit); addBlock(content,link("Image source and licence", () -> openSource(source.imagePage()))); }
        }
        heading(content, "Popular · your 10 most played"); addSongs(content, data.popular(), true);
        heading(content, "Liked songs"); addSongs(content, data.liked(), false);
        heading(content, "Discovery · albums in your library");
        if (discovery != null) addBlock(content, link("Find more recordings in an online catalog…", () -> discovery.accept(name)));
        if (data.albums().isEmpty()) addBlock(content, UiFactory.label("No tagged albums featuring this artist yet.", Theme.SMALL, Theme.TEXT_MUTED));
        else {
            JList<LibraryEntry> albums = new JList<>(data.albums().toArray(LibraryEntry[]::new)); albums.setFixedCellHeight(76);
            albums.setCellRenderer((list, entry, index, selected, focus) -> {
                JLabel label = UiFactory.label(entry.name() + " · " + entry.tracks().size() + " tracks", Theme.BODY, Theme.TEXT); label.putClientProperty("html.disable", true);
                Icon cover = art.forEntry(entry, catalog.unavailable(), catalog.stamps(), albums::repaint); label.setIcon(cover == null ? AppIcons.of(AppIcons.Kind.MUSIC, 40, Theme.BLUE) : cover); label.setIconTextGap(16);
                label.setOpaque(true); label.setBackground(selected ? Theme.SURFACE_HOVER : Theme.SURFACE); label.setBorder(Theme.padding(6, 10, 6, 10)); return label;
            });
            bindOpen(albums, () -> { if (albums.getSelectedValue() != null) openAlbum.accept(albums.getSelectedValue()); });
            addBlock(content, albums);
        }
        heading(content, "Often on the same recordings");
        if (data.collaborators().isEmpty()) addBlock(content, UiFactory.label("Add separate musician credits to discover collaborators.", Theme.SMALL, Theme.TEXT_MUTED));
        for (String partner : data.collaborators()) { String[] parts = partner.split("\u001f"); addBlock(content, link(parts[0] + " · " + parts[1] + " shared tracks", () -> openArtist.accept(parts[0]))); }
        JScrollPane scroll = new JScrollPane(content); UiFactory.styleScroll(scroll); scroll.setBorder(null); scroll.getVerticalScrollBar().setUnitIncrement(20); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); add(scroll); revalidate(); repaint();
    }
    private void addSongs(JPanel content, List<Track> tracks, boolean ranked) {
        if (tracks.isEmpty()) { addBlock(content, UiFactory.label("No songs here yet.", Theme.SMALL, Theme.TEXT_MUTED)); return; }
        JList<Track> list = new JList<>(tracks.toArray(Track[]::new)); list.setFixedCellHeight(68); list.setFixedCellWidth(360);
        TrackCellRenderer renderer = new TrackCellRenderer(); renderer.setTags(catalog.tags()); renderer.setUnavailable(catalog.unavailable()); renderer.setManuallyEdited(catalog.manualTags());
        list.setCellRenderer((view, track, index, selected, focus) -> {
            if (!ranked) return renderer.getListCellRendererComponent(view, track, index, selected, focus);
            JPanel row = new JPanel(new BorderLayout(8, 0)); row.setBackground(selected ? Theme.SURFACE_HOVER : Theme.SURFACE);
            JLabel rank = UiFactory.label(" " + (index + 1), Theme.SMALL, Theme.TEXT_MUTED); rank.setPreferredSize(new Dimension(30, 60)); row.add(rank, BorderLayout.WEST);
            row.add(renderer.getListCellRendererComponent(view, track, index, selected, focus));
            JLabel plays = UiFactory.label(catalog.playsFor(track) + " plays  ", Theme.SMALL, Theme.TEXT_MUTED); row.add(plays, BorderLayout.EAST); return row;
        });
        bindOpen(list, () -> { if (list.getSelectedValue() != null) play.accept(List.of(list.getSelectedValue()), false); });
        list.addMouseListener(new MouseAdapter() {
            private void popup(MouseEvent event) { if (!event.isPopupTrigger()) return; int at = list.locationToIndex(event.getPoint()); if (at >= 0 && list.getCellBounds(at, at).contains(event.getPoint())) { list.setSelectedIndex(at); menu.apply(list.getSelectedValue()).show(list, event.getX(), event.getY()); } }
            @Override public void mousePressed(MouseEvent event) { popup(event); } @Override public void mouseReleased(MouseEvent event) { popup(event); }
        });
        LibraryPanel.bind(list, KeyStroke.getKeyStroke("shift F10"), "menu", () -> { if (list.getSelectedIndex() >= 0) menu.apply(list.getSelectedValue()).show(list, 12, list.getCellBounds(list.getSelectedIndex(), list.getSelectedIndex()).y); });
        addBlock(content, list);
    }
    private static void bindOpen(JList<?> list, Runnable open) {
        LibraryPanel.bind(list, KeyStroke.getKeyStroke("ENTER"), "open", open);
        list.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) {
            int at = list.locationToIndex(event.getPoint()); if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event) && at >= 0 && list.getCellBounds(at, at).contains(event.getPoint())) open.run();
        } });
    }
    private static JButton link(String label, Runnable action) { JButton button = new JButton(label); button.putClientProperty("html.disable", true); button.setFont(Theme.SMALL); button.setForeground(Theme.BLUE); button.setHorizontalAlignment(SwingConstants.LEFT); button.setContentAreaFilled(false); button.setBorder(Theme.padding(5, 0, 5, 0)); button.addActionListener(event -> action.run()); return button; }
    private static void heading(JPanel content, String title) { content.add(Box.createVerticalStrut(24)); addBlock(content, UiFactory.label(title, Theme.HEADING, Theme.TEXT)); content.add(Box.createVerticalStrut(10)); }
    private static void addBlock(JPanel content, JComponent block) { block.setAlignmentX(Component.LEFT_ALIGNMENT); block.setMaximumSize(new Dimension(Integer.MAX_VALUE, block.getPreferredSize().height)); content.add(block); }
    static String duration(double seconds) { long minutes = (long) seconds / 60; return seconds < 60 ? (long) seconds + " sec" : minutes / 60 + " hr " + minutes % 60 + " min"; }
    private void editProfile() {
        String artist = name; ArtistStore.Profile original = profile;
        JTextArea biography = new JTextArea(profile.description(), 10, 48); biography.setLineWrap(true); biography.setWrapStyleWord(true);
        JPanel editor = new JPanel(new BorderLayout(0, 10)); editor.add(new JScrollPane(biography));
        final Path[] selectedImage = {null}; final boolean[] remove = {false};
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton image = UiFactory.button("Choose portrait…", null, false), clear = UiFactory.button("Remove portrait", null, false);
        image.addActionListener(event -> { JFileChooser chooser = new JFileChooser(); chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Portrait images (PNG, JPEG)", "png", "jpg", "jpeg")); if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) { selectedImage[0] = chooser.getSelectedFile().toPath(); remove[0] = false; image.setText("Portrait selected"); } });
        clear.addActionListener(event -> { selectedImage[0] = null; remove[0] = true; image.setText("Choose portrait…"); }); tools.add(image); tools.add(clear); editor.add(tools, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, editor, "Edit " + artist, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String description = biography.getText();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                byte[] portrait = remove[0] ? null : selectedImage[0] == null ? original.portrait() : portrait(selectedImage[0]);
                synchronized (library) { profiles.save(artist, new ArtistStore.Profile(description, portrait)); } return null;
            }
            @Override protected void done() { if (closed) return; try { get(); if (name.equals(artist)) showArtist(name, catalog, organization); notify.accept("Artist profile saved locally"); } catch (Exception error) { notify.accept("Profile could not be saved. Use a PNG/JPEG up to 8 MB and a description up to 20,000 characters."); } }
        }.execute();
    }
    private void openSource(String url) {
        new SwingWorker<Void,Void>() { protected Void doInBackground() throws Exception { if (!url.isBlank() && Desktop.isDesktopSupported()) Desktop.getDesktop().browse(java.net.URI.create(url)); return null; } protected void done() { try { get(); } catch(Exception error) { notify.accept("Could not open the source link"); } } }.execute();
    }
    static byte[] portrait(Path path) throws IOException {
        if (Files.size(path) > 8_000_000) throw new IOException("Image is too large");
        try (var stream = ImageIO.createImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(stream); if (!readers.hasNext()) throw new IOException("Unsupported image");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true); int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 12000 || height > 12000) throw new IOException("Image dimensions are too large");
                var params = reader.getDefaultReadParam(); int sample = Math.max(1, Math.max(width, height) / 512); params.setSourceSubsampling(sample, sample, 0, 0);
                var decoded = reader.read(0, params); var resized = new java.awt.image.BufferedImage(320, 320, java.awt.image.BufferedImage.TYPE_INT_RGB);
                Graphics2D g = resized.createGraphics(); g.setColor(Theme.SURFACE); g.fillRect(0, 0, 320, 320);
                double scale = 320d / Math.max(decoded.getWidth(), decoded.getHeight()); int w = (int) (decoded.getWidth() * scale), h = (int) (decoded.getHeight() * scale);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); g.drawImage(decoded, (320-w)/2, (320-h)/2, w, h, null); g.dispose();
                var output = new ByteArrayOutputStream(); ImageIO.write(resized, "png", output); return output.toByteArray();
            } finally { reader.dispose(); }
        }
    }
    void close() { closed = true; if (loader != null) loader.cancel(true); art.close(); }
    private static final class Column extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 20; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(100, r.height - 30); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
