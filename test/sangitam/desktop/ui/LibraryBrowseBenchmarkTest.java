package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.*;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in snapshot/presentation benchmark, separate from database search and whole-app startup. */
@EnabledIfSystemProperty(named="sangitam.catalogBenchmark",matches="true")
class LibraryBrowseBenchmarkTest {
    @Test void measuresOneHundredThousandSnapshotRowsWithBoundedPublishedPagesAndNodes() throws Exception {
        var tracks = new ArrayList<Track>(); Path folder = Path.of("synthetic-music").toAbsolutePath();
        for (int i=0;i<100_000;i++) tracks.add(new Track(folder.resolve(i+".mp3"),"Song %06d".formatted(i),"Unknown artist","Unknown album",180,"MP3"));
        var catalog = new LibraryService.Catalog(tracks,Map.of(),Map.of()); var organization = LibraryCollections.empty();
        long start=System.nanoTime(); var entries=LibraryEntry.build(catalog,organization); double mixedMs=(System.nanoTime()-start)/1e6;
        var pages = new GeneralLibraryPages.Index(entries, organization);
        for (int offset:new int[]{0,99_900}) {
            start=System.nanoTime(); var page=pages.query(new GeneralLibraryPages.Request("",0,GeneralLibraryPages.Order.ALPHABETICAL,offset)); double ms=(System.nanoTime()-start)/1e6;
            assertEquals(100_000,page.total()); assertEquals(100,page.entries().size()); assertEquals(offset,page.offset());
            System.out.printf(Locale.ROOT,"BROWSE_BENCH rows=100000 mixed_build_ms=%.1f offset=%d page_ms=%.1f published=%d%n",mixedMs,offset,ms,page.entries().size());
        }
        start=System.nanoTime(); var index=LocationIndex.build(tracks,""); double indexMs=(System.nanoTime()-start)/1e6;
        assertEquals(100,index.page(folder,99_900).children().size());
        var tree = new AtomicReference<LibraryFolderTree>();
        SwingUtilities.invokeAndWait(()->{tree.set(new LibraryFolderTree(ignored->{},(input,text)->index));tree.get().setTracks(tracks,Set.of());});
        try {
            LibraryPagingPanelTest.await(()->{var root=(DefaultMutableTreeNode)tree.get().getModel().getRoot();return root.getChildCount()==1 && ((DefaultMutableTreeNode)root.getFirstChild()).getChildCount()==101;});
            var count=new AtomicInteger();SwingUtilities.invokeAndWait(()->{var nodes=((DefaultMutableTreeNode)tree.get().getModel().getRoot()).depthFirstEnumeration();while(nodes.hasMoreElements()){nodes.nextElement();count.incrementAndGet();}});
            assertEquals(103,count.get());System.out.printf(Locale.ROOT,"LOCATION_BENCH tracks=100000 index_ms=%.1f loaded_nodes=%d branch_page=100%n",indexMs,count.get());
        } finally {SwingUtilities.invokeAndWait(()->tree.get().close());}
    }
}
