package sangitam.desktop.app;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.Executors;

/** Optional startup milestones. File writes stay off the Swing event thread. */
public final class StartupTrace {
    private final Path report;
    private final long mainStarted;
    private final java.util.concurrent.ExecutorService writer;

    public StartupTrace(Path report, long mainStarted) {
        this.report = report;
        this.mainStarted = mainStarted;
        writer = report == null ? null : Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "sangitam-startup-report");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void mark(String milestone) {
        if (writer == null) return;
        long wallTime = System.currentTimeMillis();
        double mainMs = (System.nanoTime() - mainStarted) / 1_000_000d;
        writer.execute(() -> {
            long processStart = ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(-1L);
            String line = String.format(Locale.ROOT, "pid=%d %s main_ms=%.1f process_ms=%d%n", ProcessHandle.current().pid(),
                milestone, mainMs, processStart < 0 ? -1 : wallTime - processStart);
            try { Files.writeString(report, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException error) { System.err.println("Could not write startup report: " + error.getMessage()); }
        });
    }

    public void close() { if (writer != null) writer.shutdown(); }
}
