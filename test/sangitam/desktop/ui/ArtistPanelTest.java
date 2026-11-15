package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.*;
import sangitam.desktop.storage.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtistPanelTest {
    @TempDir Path folder;
    @Test void openingArtistAutofillsCanonicalProfileOffEdtAndReusesCache() throws Exception {
        var library=new LibraryStore(folder.resolve("auto.db"));library.initialize();
        var track=new Track(folder.resolve("song.mp3"),"Sonata","Beethoven","Sonatas",60,"MP3");library.saveAll(List.of(track));
        var catalog=new LibraryService.Catalog(List.of(track),Map.of(),Map.of());var calls=new AtomicInteger();var changes=new AtomicInteger();
        var client=new sangitam.desktop.discovery.ArtistEnrichmentClient((uri,limit)->{
            assertFalse(SwingUtilities.isEventDispatchThread());calls.incrementAndGet();
            return ("{\"query\":{\"pages\":[{\"title\":\"Ludwig van Beethoven\",\"extract\":\"Ludwig van Beethoven was a German composer. "+"Biographical notes. ".repeat(40)+"\"}]}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        });
        String previous=System.getProperty("sangitam.artistOnline");System.setProperty("sangitam.artistOnline","true");
        var panels=new ArrayList<ArtistPanel>();
        try {
            for(int i=0;i<2;i++) {
                var panel=new AtomicReference<ArtistPanel>();SwingUtilities.invokeAndWait(()->{
                    panel.set(new ArtistPanel(library,(songs,shuffle)->{},entry->{},name->{},t->new JPopupMenu(),message->{},client));panels.add(panel.get());
                    panel.get().onEnriched(changes::incrementAndGet);panel.get().showArtist("Beethoven",catalog,LibraryCollections.empty());
                });
                awaitTitle(panel.get(),"Ludwig van Beethoven");
                SwingUtilities.invokeAndWait(()->{
                    assertTrue(AudioSettingsPanelTest.children(panel.get(),JButton.class).stream().anyMatch(b->"Read more".equals(b.getText())));
                    assertTrue(AudioSettingsPanelTest.children(panel.get(),JButton.class).stream().anyMatch(b->b.getText().contains("Wikipedia source")));
                });
            }
            assertEquals(1,calls.get());assertEquals(1,changes.get());assertEquals("Ludwig van Beethoven",library.loadAll().getFirst().artist());
        } finally {
            SwingUtilities.invokeAndWait(()->panels.forEach(ArtistPanel::close));
            if(previous==null)System.clearProperty("sangitam.artistOnline");else System.setProperty("sangitam.artistOnline",previous);
        }
    }
    private static void awaitTitle(ArtistPanel panel,String title) throws Exception {
        var ready=new AtomicBoolean();long deadline=System.nanoTime()+5_000_000_000L;
        while(!ready.get()&&System.nanoTime()<deadline){SwingUtilities.invokeAndWait(()->ready.set(AudioSettingsPanelTest.children(panel,JLabel.class).stream().anyMatch(label->title.equals(label.getText()))));if(!ready.get())Thread.sleep(20);}
        assertTrue(ready.get(),"Artist page did not finish loading");
    }
    @Test void offlineProfileRendersExpandableBioAndRoutesArtistAndAlbumPlayback() throws Exception {
        var library = new LibraryStore(folder.resolve("library.db")); library.initialize(); var profiles = new ArtistStore(library); profiles.initialize();
        Path source = folder.resolve("portrait.png"); var original = new BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = original.createGraphics(); graphics.setColor(Theme.BLUE_STRONG); graphics.fillRect(0,0,800,400); graphics.dispose(); javax.imageio.ImageIO.write(original, "png", source.toFile());
        byte[] portrait = ArtistPanel.portrait(source); assertEquals(320, javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(portrait)).getWidth());
        profiles.save("Composer", new ArtistStore.Profile("Offline recording and life notes. ".repeat(35), portrait)); Files.delete(source);
        Track track = new Track(folder.resolve("song.mp3"), "Sonata", "Soloist", "Recital", 100, "MP3", "Classical", TrackMetadata.empty().edit(Map.of(TrackMetadata.Field.COMPOSER, "Composer"), null));
        var catalog = new LibraryService.Catalog(List.of(track), Map.of(), Map.of(track.path(), 7));
        var played = new AtomicReference<List<Track>>(); var shuffled = new AtomicBoolean(); var album = new AtomicReference<LibraryEntry>(); var panel = new AtomicReference<ArtistPanel>();
        SwingUtilities.invokeAndWait(() -> { Theme.install(); panel.set(new ArtistPanel(library, (songs, shuffle) -> { played.set(songs); shuffled.set(shuffle); }, album::set, ignored -> { }, ignored -> new JPopupMenu(), ignored -> { })); panel.get().showArtist("Composer", catalog, LibraryCollections.empty()); });
        try {
            var ready = new AtomicBoolean(); long deadline = System.nanoTime() + 5_000_000_000L;
            while (!ready.get() && System.nanoTime() < deadline) { SwingUtilities.invokeAndWait(() -> ready.set(AudioSettingsPanelTest.children(panel.get(), JButton.class).stream().anyMatch(button -> "Play artist".equals(button.getText())))); if (!ready.get()) Thread.sleep(20); }
            assertTrue(ready.get());
            SwingUtilities.invokeAndWait(() -> {
                var buttons = AudioSettingsPanelTest.children(panel.get(), JButton.class);
                buttons.stream().filter(button -> "Play artist".equals(button.getText())).findFirst().orElseThrow().doClick(); assertEquals(List.of(track), played.get()); assertFalse(shuffled.get());
                buttons.stream().filter(button -> "Shuffle".equals(button.getText())).findFirst().orElseThrow().doClick(); assertTrue(shuffled.get());
                JButton more = buttons.stream().filter(button -> "Read more".equals(button.getText())).findFirst().orElseThrow(); more.doClick(); assertEquals("Show less", more.getText()); more.doClick();
                var albums = AudioSettingsPanelTest.children(panel.get(), JList.class).stream().filter(list -> list.getModel().getSize() > 0 && list.getModel().getElementAt(0) instanceof LibraryEntry).findFirst().orElseThrow();
                albums.setSelectedIndex(0); albums.getActionMap().get("open").actionPerformed(new ActionEvent(albums, 0, "")); assertEquals("Recital", album.get().name());
                panel.get().setSize(1060,900); AudioSettingsPanelTest.layout(panel.get());
                var image = new BufferedImage(1060,900,BufferedImage.TYPE_INT_RGB); Graphics2D g = image.createGraphics(); panel.get().printAll(g); g.dispose();
                try { javax.imageio.ImageIO.write(image,"png",Path.of("target/artist-preview.png").toFile()); } catch (Exception error) { throw new AssertionError(error); }
            });
        } finally { SwingUtilities.invokeAndWait(() -> panel.get().close()); }
    }
}
