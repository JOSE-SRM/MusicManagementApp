package sangitam.desktop.playback;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ShuffleOrderTest {
    @Test void nextUsesTheDisplayedOrderAndRetracesSuccessfulPlayback() {
        var order = new ShuffleOrder<String>(new Random(29));
        order.reset(List.of("A", "B", "C", "D"));
        assertEquals("A", order.select("A"));
        assertTrue(order.started());
        var expected = List.copyOf(order.visible().subList(1, order.visible().size()));
        assertEquals(expected.get(0), order.next());
        order.started();
        assertEquals(expected.get(1), order.next());
        order.started();
        assertEquals(expected.get(0), order.previous());
        order.started();
        assertEquals("A", order.previous());
        order.started();
        assertEquals(expected, order.visible().subList(1, order.visible().size()));
        for (String entry : expected) {
            assertEquals(entry, order.next());
            order.started();
        }
        assertNull(order.next());
        assertEquals(List.of(expected.getLast()), order.visible());
    }

    @Test void failuresStayOutOfHistoryAndAvailableAdditionsFollowTheRemainingOrder() {
        var order = new ShuffleOrder<String>(new Random(13));
        order.reset(List.of("A", "B", "C", "D"));
        order.select("A");
        order.started();
        String failed = order.next();
        order.failed();
        assertFalse(order.visible().contains(failed));
        var remaining = new ArrayList<>(order.visible());
        String removed = remaining.removeFirst();
        remaining.add("E");
        var revisedSource = new ArrayList<>(List.of("D", "C", "B", "A", "E"));
        revisedSource.remove(removed);
        order.reconcile(revisedSource);
        assertEquals(remaining, order.visible());
        assertEquals(remaining.getFirst(), order.next());
        order.started();
        assertEquals("A", order.previous());
        order.failed();
        assertEquals("E", order.next());
    }

    @Test void duplicateTracksCanOccupySeparateQueueEntriesAndRepeatKeepsOneHistorySlot() {
        record Slot(String track, int occurrence) { }
        var first = new Slot("A", 0);
        var second = new Slot("A", 1);
        var order = new ShuffleOrder<Slot>(new Random(8));
        order.reset(List.of(first, second));
        order.select(first);
        order.started();
        assertEquals(first, order.chooseVisible(0));
        assertFalse(order.started());
        assertEquals(second, order.chooseVisible(1));
        order.started();
        assertEquals(first, order.previous());
        order.started();
        assertNull(order.previous());
        assertEquals(second, order.next());
        order.started();
        assertNull(order.next());
    }
}
