package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.tree.*;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryFolderTreeLazyTest {
    @Test void pagesActivateCorrectSongsRestoreLaterSelectionsAndEvictCollapsedChildren() throws Exception {
        var songs=GeneralLibraryPagesTest.songs(305); Path folder=songs.getFirst().path().getParent();
        var played=new AtomicReference<Track>(); var tree=new AtomicReference<LibraryFolderTree>();
        SwingUtilities.invokeAndWait(()->{tree.set(new LibraryFolderTree(played::set));tree.get().setTracks(songs,Set.of());});
        try {
            LibraryPagingPanelTest.await(()->find(tree.get(),songs.getFirst().path())!=null);
            SwingUtilities.invokeAndWait(()->{var branch=find(tree.get(),folder); assertEquals(101,branch.getChildCount()); activate(tree.get(),(DefaultMutableTreeNode)branch.getLastChild());});
            LibraryPagingPanelTest.await(()->find(tree.get(),songs.get(100).path())!=null);
            SwingUtilities.invokeAndWait(()->{activate(tree.get(),find(tree.get(),songs.get(100).path()));assertEquals(songs.get(100),played.get());activate(tree.get(),(DefaultMutableTreeNode)find(tree.get(),folder).getLastChild());});
            LibraryPagingPanelTest.await(()->find(tree.get(),songs.get(250).path())!=null);
            SwingUtilities.invokeAndWait(()->{tree.get().setSelectionPath(new TreePath(find(tree.get(),songs.get(250).path()).getPath())); tree.get().setTracks(new ArrayList<>(songs),Set.of(songs.get(250).path()));});
            LibraryPagingPanelTest.await(()->tree.get().selectedTrack()!=null && tree.get().selectedTrack().path().equals(songs.get(250).path()));
            SwingUtilities.invokeAndWait(()->{
                assertEquals(102,find(tree.get(),folder).getChildCount()); assertNull(find(tree.get(),songs.getFirst().path()));
                var branch=find(tree.get(),folder); tree.get().collapsePath(new TreePath(branch.getPath())); assertEquals(1,branch.getChildCount()); assertNull(find(tree.get(),songs.get(250).path()));
                tree.get().expandPath(new TreePath(branch.getPath()));
            });
            LibraryPagingPanelTest.await(()->tree.get().selectedTrack()!=null && tree.get().selectedTrack().path().equals(songs.get(250).path()));
        } finally {SwingUtilities.invokeAndWait(()->tree.get().close());}
    }
    @Test void aLateOldSearchCannotReplaceNewNodesAndFailedIndexesCanRetry() throws Exception {
        var oldStarted=new CountDownLatch(1);var release=new CountDownLatch(1);var finished=new CountDownLatch(1);
        var tracks=List.of(new Track(Path.of("music/old.mp3"),"Old","Artist","Album",60,"MP3"),new Track(Path.of("music/new.mp3"),"New","Artist","Album",60,"MP3"));
        var tree=new AtomicReference<LibraryFolderTree>(); var failure=new AtomicBoolean();
        SwingUtilities.invokeAndWait(()->{
            tree.set(new LibraryFolderTree(ignored->{},(songs,text)->{
                if(text.equals("old")){oldStarted.countDown();boolean done=false;while(!done)try{done=release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}finished.countDown();}
                if(text.equals("retry")&&!failure.getAndSet(true))throw new IllegalStateException("Deliberate index failure");
                return LocationIndex.build(songs,text.equals("retry")?"new":text);
            }));tree.get().setTracks(tracks,Set.of(),"old",ignored->{});
        });
        try {
            assertTrue(oldStarted.await(5,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->tree.get().setTracks(tracks,Set.of(),"new",ignored->{}));
            LibraryPagingPanelTest.await(()->find(tree.get(),tracks.get(1).path())!=null); release.countDown();assertTrue(finished.await(5,TimeUnit.SECONDS));
            settle(); SwingUtilities.invokeAndWait(()->{assertNull(find(tree.get(),tracks.getFirst().path()));assertNotNull(find(tree.get(),tracks.get(1).path()));tree.get().setTracks(tracks,Set.of(),"retry",ignored->{});});
            LibraryPagingPanelTest.await(()->root(tree.get()).getChildCount()==1 && ((DefaultMutableTreeNode)root(tree.get()).getFirstChild()).getUserObject().toString().contains("retry"));
            SwingUtilities.invokeAndWait(()->activate(tree.get(),(DefaultMutableTreeNode)root(tree.get()).getFirstChild()));
            LibraryPagingPanelTest.await(()->find(tree.get(),tracks.get(1).path())!=null);
        } finally {release.countDown();SwingUtilities.invokeAndWait(()->tree.get().close());}
    }
    private static void settle() throws Exception {var done=new CountDownLatch(1);SwingUtilities.invokeAndWait(()->{Timer timer=new Timer(120,event->done.countDown());timer.setRepeats(false);timer.start();});assertTrue(done.await(5,TimeUnit.SECONDS));}
    private static void activate(LibraryFolderTree tree,DefaultMutableTreeNode node){tree.setSelectionPath(new TreePath(node.getPath()));tree.getActionMap().get("openLibraryRow").actionPerformed(new ActionEvent(tree,0,""));}
    private static DefaultMutableTreeNode root(JTree tree){return(DefaultMutableTreeNode)tree.getModel().getRoot();}
    private static DefaultMutableTreeNode find(JTree tree,Path path){var children=root(tree).depthFirstEnumeration();while(children.hasMoreElements()){var node=(DefaultMutableTreeNode)children.nextElement();Object value=node.getUserObject();if(path.equals(value instanceof Track track?track.path():value))return node;}return null;}
}
