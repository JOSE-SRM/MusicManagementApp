package sangitam.desktop.playback;

import java.util.concurrent.Executor;

/** At most one queued job plus one running job; requests use the latest shared control value. */
final class CoalescedCommand {
    private final Executor executor;
    private final Runnable apply;
    private boolean queued;
    CoalescedCommand(Executor executor, Runnable apply) { this.executor = executor; this.apply = apply; }
    synchronized void request() {
        if (queued) return; queued = true;
        try { executor.execute(() -> { synchronized (this) { queued = false; } apply.run(); }); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { queued = false; }
    }
}
