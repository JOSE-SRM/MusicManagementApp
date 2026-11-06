package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.*;
import sangitam.desktop.session.SessionPlanner;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionJourneyPanelTest {
    @Test void composerRoutesEditableMoodArcAndAnalysisAndJourneyRoutesPlaybackReplacementAndTaste() throws Exception {
        var built=new AtomicReference<SessionPlanner.Request>();var analyzed=new AtomicBoolean();var started=new AtomicReference<List<SessionPlanner.Choice>>();
        var replaced=new AtomicInteger(-1);var rated=new AtomicInteger();var edited=new AtomicReference<List<SessionPlanner.Choice>>();
        Track a=new Track(Path.of("a.wav"),"Opening","Artist A","Album",180,"WAV"),b=new Track(Path.of("b.wav"),"Arrival","Artist B","Album",180,"WAV");
        var choices=List.of(new SessionPlanner.Choice(a,"Measured quiet opening"),new SessionPlanner.Choice(b,"Measured energetic arrival"));
        SwingUtilities.invokeAndWait(()->{
            Theme.install();var composer=new SessionComposerPanel(message->fail(message),built::set);composer.setAnalyzeAction(()->analyzed.set(true));
            var mood=AudioSettingsPanelTest.children(composer,JComboBox.class).stream().filter(c->c.getItemAt(0) instanceof SessionPlanner.Mood).findFirst().orElseThrow();mood.setSelectedItem(SessionPlanner.Mood.HAPPY);
            var sliders=AudioSettingsPanelTest.children(composer,JSlider.class);sliders.get(0).setValue(20);sliders.get(1).setValue(80);
            click(composer,"Deep analyse library");click(composer,"Build my journey");assertTrue(analyzed.get());
            assertEquals(SessionPlanner.Mood.HAPPY,built.get().mood());assertEquals(20,built.get().energy());assertEquals(80,built.get().endEnergy());assertFalse(built.get().useContext());
            mood.setSelectedItem(SessionPlanner.Mood.AUTO);assertFalse(sliders.get(0).isEnabled());
            click(composer,"Build my journey");assertTrue(built.get().useContext());
            mood.setSelectedItem(SessionPlanner.Mood.HAPPY);sliders.get(0).setValue(20);
            var journey=new SessionQueuePanel(new SessionQueuePanel.Actions(){
                public void start(List<SessionPlanner.Choice> c){started.set(c);} public void play(SessionPlanner.Choice c,List<SessionPlanner.Choice> all){started.set(all);}
                public void edited(List<SessionPlanner.Choice> c){edited.set(c);}public void save(String n,List<SessionPlanner.Choice> c){}public void load(Playlist p){}public void delete(Playlist p){}
                public void replace(int index,List<SessionPlanner.Choice> c){replaced.set(index);}public void taste(Track track,int rating){assertEquals(a,track);rated.set(rating);}
            });
            journey.setPlan(new SessionPlanner.Plan(choices,6,360));journey.setAnalysis(Map.of(a.path(),new TrackTags(20,20,true,1,1),b.path(),new TrackTags(80,60,true,1,1)));journey.setTaste(Map.of(a.path(),2));
            JList<?> list=AudioSettingsPanelTest.children(journey,JList.class).getFirst();list.setSelectedIndex(0);
            assertTrue(AudioSettingsPanelTest.children(journey,JTextArea.class).getFirst().getText().contains("Taste: Love"));
            click(journey,"Play journey");assertEquals(choices,started.get());click(journey,"Replace slot");assertEquals(0,replaced.get());
            click(journey,"Less like this");assertEquals(-1,rated.get());click(journey,"Avoid");assertEquals(-2,rated.get());click(journey,"Reset taste");assertEquals(0,rated.get());
            click(journey,"Move down");assertEquals(b,edited.get().getFirst().track());click(journey,"Remove");assertEquals(List.of(choices.get(1)),edited.get());
            journey.setPlan(new SessionPlanner.Plan(choices,6,360));
            JPanel preview=new JPanel(new GridLayout(1,2,16,0));preview.setBackground(Theme.BLACK);preview.add(composer);preview.add(journey);preview.setSize(1440,660);AudioSettingsPanelTest.layout(preview);
            var image=new BufferedImage(1440,660,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();preview.printAll(g);g.dispose();
            try{javax.imageio.ImageIO.write(image,"png",Path.of("target/session-journey-preview.png").toFile());}catch(Exception error){throw new AssertionError(error);}
            var nav=new NavigationPanel(screen->{},()->{},()->{},()->{},()->{});
            assertTrue(AudioSettingsPanelTest.children(nav,JButton.class).stream().noneMatch(button->List.of("Songs","Albums","Artists","Playlists").contains(button.getText())));
        });
    }
    private static void click(Container panel,String name){AudioSettingsPanelTest.children(panel,JButton.class).stream().filter(b->name.equals(b.getText())).findFirst().orElseThrow().doClick();}
}
