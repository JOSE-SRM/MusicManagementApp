package sangitam.desktop.playback;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CoalescedCommandTest {
    @Test void rapidControlChangesQueueOneJobThatReadsTheLatestValue() {
        var jobs = new ArrayDeque<Runnable>(); var latest = new AtomicInteger(); var applied = new AtomicInteger();
        var command = new CoalescedCommand(jobs::add, () -> applied.set(latest.get()));
        for (int value = 0; value <= 150; value++) { latest.set(value); command.request(); }
        assertEquals(1, jobs.size()); jobs.remove().run(); assertEquals(150, applied.get());
        latest.set(0); command.request(); assertEquals(1, jobs.size()); jobs.remove().run(); assertEquals(0, applied.get());
    }
}
