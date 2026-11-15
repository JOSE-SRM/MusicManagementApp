package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WidthTransitionTest {
    @Test void easingUsesElapsedTimeAndCanReverseFromTheVisibleWidth() {
        var opening=new WidthTransition(300,1000,100,220);
        assertEquals(300,opening.at(50));assertEquals(650,opening.at(210));assertEquals(1000,opening.at(1000));assertTrue(opening.finished(1000));
        int visible=opening.at(190);var closing=new WidthTransition(visible,0,190,220);
        assertEquals(visible,closing.at(190));assertTrue(closing.at(200)>closing.at(300));assertEquals(0,closing.at(410));
    }
}
