package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.*;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryPagingPanelTest {
    @Test void nextPagePlaysItsOwnTrackAndFilteringResetsThenSurvivesCatalogRefresh() throws Exception {
        var played=new AtomicReference<Track>(); var panel=new AtomicReference<LibraryPanel>(); var songs=GeneralLibraryPagesTest.songs(205);
        SwingUtilities.invokeAndWait(()->{
            Theme.install();
            panel.set(new LibraryPanel(new LibraryTestActions(){ @Override public void play(List<Track> tracks){played.set(tracks.getFirst());} }));
            panel.get().setCatalog(new LibraryService.Catalog(songs,Map.of(),Map.of())); panel.get().selectSort(1);
        });
        try {
            await(()->general(panel.get()).getModel().getSize()==100 && next(panel.get()).isEnabled());
            SwingUtilities.invokeAndWait(()->{
                panel.get().setSize(300,650); AudioSettingsPanelTest.layout(panel.get());
                var image=new java.awt.image.BufferedImage(300,650,java.awt.image.BufferedImage.TYPE_INT_RGB); var g=image.createGraphics(); panel.get().printAll(g); g.dispose();
                try {javax.imageio.ImageIO.write(image,"png",java.nio.file.Path.of("target/general-page-preview.png").toFile());}catch(Exception error){throw new AssertionError(error);}
            });
            SwingUtilities.invokeAndWait(()->next(panel.get()).doClick());
            await(()->general(panel.get()).getModel().getSize()==100 && ((LibraryEntry)general(panel.get()).getModel().getElementAt(0)).name().equals("Song 0100"));
            SwingUtilities.invokeAndWait(()->{
                var list=general(panel.get()); list.setSelectedIndex(0); list.getActionMap().get("open").actionPerformed(new ActionEvent(list,0,"")); assertEquals(songs.get(100),played.get());
                search(panel.get()).setText("Song 0204");
            });
            await(()->general(panel.get()).getModel().getSize()==1 && ((LibraryEntry)general(panel.get()).getModel().getElementAt(0)).name().equals("Song 0204"));
            SwingUtilities.invokeAndWait(()->panel.get().setCatalog(new LibraryService.Catalog(songs,Map.of(),Map.of(songs.getLast().path(),5))));
            await(()->general(panel.get()).getModel().getSize()==1 && !next(panel.get()).isEnabled());
            SwingUtilities.invokeAndWait(()->assertFalse(previous(panel.get()).isEnabled()));
        } finally {SwingUtilities.invokeAndWait(()->panel.get().close());}
    }
    static void await(java.util.function.BooleanSupplier ready) throws Exception {
        var result=new AtomicBoolean(); long end=System.nanoTime()+8_000_000_000L;
        while(System.nanoTime()<end){SwingUtilities.invokeAndWait(()->result.set(ready.getAsBoolean()));if(result.get())return;Thread.sleep(20);} fail("Library worker did not publish expected state");
    }
    static JList<?> general(LibraryPanel panel){return AudioSettingsPanelTest.children(panel,JList.class).getFirst();}
    static JButton next(LibraryPanel panel){return AudioSettingsPanelTest.children(panel,JButton.class).stream().filter(b->"Next ›".equals(b.getText())).findFirst().orElseThrow();}
    static JButton previous(LibraryPanel panel){return AudioSettingsPanelTest.children(panel,JButton.class).stream().filter(b->"‹ Previous".equals(b.getText())).findFirst().orElseThrow();}
    static JTextField search(LibraryPanel panel){return AudioSettingsPanelTest.children(panel,JTextField.class).stream().filter(f->"Search library".equals(f.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();}
}
