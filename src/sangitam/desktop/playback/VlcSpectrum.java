package sangitam.desktop.playback;

import com.sun.jna.Pointer;
import uk.co.caprica.vlcj.binding.lib.LibVlc;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.State;
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Silent secondary decoder with its own worker and no connection to the audible output. */
final class VlcSpectrum implements AutoCloseable {
    record Position(Path path, double seconds, long observedNanos, long request, boolean confirmed) { }
    private record Delivery(float[] bands, long revision) { }
    private static final System.Logger LOG = System.getLogger(VlcSpectrum.class.getName());
    private static final float[] EMPTY = {};
    private static final int MAX_ALIGNMENT_RESTARTS = 2;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "sangitam-vlc-spectrum");
        thread.setDaemon(true);
        return thread;
    });
    private final Supplier<Position> transport;
    private final Consumer<float[]> listener;
    private final AtomicLong revision = new AtomicLong();
    private final AtomicReference<Delivery> pending = new AtomicReference<>();
    private final AtomicBoolean deliveryQueued = new AtomicBoolean();
    private volatile boolean closed;
    private volatile Path failedPath;
    private MediaPlayerFactory factory;
    private MediaPlayer player;
    private VlcLocalMedia mediaInput;
    private Samples samples;
    private Path path;
    private long activeRevision = -1;
    private long startedAt;
    private int alignmentRestarts;
    private long outOfSyncSince;
    private final MediaClock primaryClock = new MediaClock();
    private final MediaClock secondaryClock = new MediaClock();
    private double driftSeconds = Double.NaN;
    private float[] displayed = EMPTY;

    VlcSpectrum(Supplier<Position> transport, Consumer<float[]> listener) {
        this.transport = transport;
        this.listener = listener;
        worker.scheduleWithFixedDelay(this::tick, 0, 20, TimeUnit.MILLISECONDS);
    }

    boolean availableFor(Path path) { return path == null || !path.equals(failedPath); }

    void invalidate() {
        revision.incrementAndGet();
        // The caller may be on the native command thread.
        emit(EMPTY, revision.get());
    }

    private void tick() {
        if (closed) return;
        Position position = transport.get();
        Path playingPath = position == null ? null : position.path();
        long version = revision.get();
        try {
            if (position == null) {
                releasePlayer();
                path = null;
                show(EMPTY, version);
                return;
            }
            if (version != activeRevision || !playingPath.equals(path)) {
                if (version != activeRevision) failedPath = null;
                releasePlayer();
                activeRevision = version;
                path = playingPath;
                alignmentRestarts = 0;
                primaryClock.reset();
                show(EMPTY, version);
                if (availableFor(path)) open(path, position.seconds());
                return;
            }
            if (player == null || !position.confirmed()) return;
            long decoderTime = player.status().time();
            long now = System.nanoTime();
            long elapsed = now - startedAt;
            var decoderState = player.status().state();
            if (decoderState == State.ERROR
                || (decoderTime < 0 && elapsed > TimeUnit.SECONDS.toNanos(3))) {
                failedPath = path;
                releasePlayer();
                show(EMPTY, version);
                return;
            }
            if (decoderTime < 0) return;
            primaryClock.observe(position.seconds(), position.observedNanos());
            secondaryClock.observe(decoderTime / 1000.0, now);
            double primary = primaryClock.at(now);
            double offset = secondaryClock.at(now) - primary;
            if (Math.abs(offset) > 0.65) {
                if (outOfSyncSince == 0) outOfSyncSince = now;
                if (now - outOfSyncSince > TimeUnit.MILLISECONDS.toNanos(650)) {
                    releasePlayer();
                    show(EMPTY, version);
                    if (alignmentRestarts++ < MAX_ALIGNMENT_RESTARTS) open(path, primary);
                    else failedPath = path;
                }
                return;
            }
            outOfSyncSince = 0;
            driftSeconds = Double.isNaN(driftSeconds) ? offset
                : driftSeconds + Math.clamp((offset - driftSeconds) * 0.12, -0.004, 0.004);
            long clock = LibVlc.libvlc_clock() - Math.round(driftSeconds * 1_000_000);
            float[] aligned = samples.at(clock);
            if (aligned != null) show(aligned, version);
        } catch (RuntimeException | LinkageError error) {
            failedPath = playingPath;
            LOG.log(System.Logger.Level.DEBUG, "VLC spectrum decoder unavailable", error);
            try { releasePlayer(); }
            catch (RuntimeException | LinkageError releaseError) {
                LOG.log(System.Logger.Level.DEBUG, "Could not release VLC spectrum player", releaseError);
            }
            show(EMPTY, version);
        }
    }

    private void open(Path source, double seconds) {
        if (factory == null) factory = new MediaPlayerFactory("--no-video", "--audio-replay-gain-mode=none");
        samples = new Samples();
        secondaryClock.reset();
        driftSeconds = Double.NaN;
        outOfSyncSince = 0;
        player = factory.mediaPlayers().newMediaPlayer();
        player.audio().callback("S16N", SpectrumBands.RATE, SpectrumBands.CHANNELS, samples);
        startedAt = System.nanoTime();
        mediaInput = new VlcLocalMedia(source);
        if (!mediaInput.play(player, seconds)) {
            failedPath = source;
            releasePlayer();
        }
    }

    private void show(float[] bands, long version) {
        if (bands != displayed) {
            displayed = bands;
            emit(bands, version);
        }
    }

    private void emit(float[] bands, long version) {
        if (version != revision.get() || closed) return;
        Delivery next = new Delivery(bands, version);
        pending.accumulateAndGet(next, (old, update) -> old == null || old.revision() <= version ? update : old);
        scheduleDelivery();
    }

    private void scheduleDelivery() {
        if (closed || !deliveryQueued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            Delivery next = pending.getAndSet(null);
            try {
                Position position = transport.get();
                if (!closed && next != null && next.revision() == revision.get()
                    && (next.bands().length == 0 || position != null && position.confirmed())) listener.accept(next.bands());
            } finally {
                deliveryQueued.set(false);
                if (pending.get() != null) scheduleDelivery();
            }
        });
    }

    private void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        samples = null;
        if (old != null) {
            try { old.controls().stop(); }
            finally {
                try { old.release(); }
                finally { mediaInput = null; }
            }
        }
    }

    @Override public void close() {
        closed = true;
        pending.set(null);
        worker.execute(() -> {
            try { releasePlayer(); }
            finally { if (factory != null) factory.release(); }
        });
        worker.shutdown();
    }

    /** VLC may publish several identical time readings between decoder clock updates. */
    private static final class MediaClock {
        private double last = Double.NaN;
        private double seconds;
        private long observedAt;

        void reset() { last = Double.NaN; }

        void observe(double reported, long now) {
            if (reported == last) return;
            double projected = at(now);
            double difference = reported - projected;
            seconds = Double.isNaN(last) || Math.abs(difference) > 0.5 ? reported
                : projected + Math.clamp(difference * 0.2, -0.025, 0.025);
            observedAt = now;
            last = reported;
        }

        double at(long now) { return seconds + (now - observedAt) * 1e-9; }
    }

    private static final class Samples extends AudioCallbackAdapter {
        private record Frame(long at, float[] bands) { }
        private static final int MAX_FRAMES = 80;
        private final ArrayDeque<Frame> frames = new ArrayDeque<>(MAX_FRAMES);
        private final SpectrumBands spectrum = new SpectrumBands();
        private final short[] pcm = new short[SpectrumBands.SIZE * SpectrumBands.CHANNELS];
        private int fill;
        private long nextPts;

        @Override public synchronized void play(MediaPlayer player, Pointer source, int count, long pts) {
            if (nextPts != 0 && Math.abs(pts - nextPts) > 80_000) flush(player, pts);
            nextPts = pts + micros(count);
            for (int i = 0; i < count;) {
                int copied = Math.min(count - i, SpectrumBands.SIZE - fill);
                int channels = SpectrumBands.CHANNELS;
                source.read((long) i * channels * Short.BYTES, pcm, fill * channels, copied * channels);
                fill += copied;
                i += copied;
                if (fill == SpectrumBands.SIZE) {
                    long center = pts + micros(i - SpectrumBands.SIZE / 2);
                    if (frames.size() == MAX_FRAMES) frames.removeFirst();
                    frames.addLast(new Frame(center, spectrum.measure(pcm)));
                    fill = SpectrumBands.SIZE - SpectrumBands.HOP;
                    System.arraycopy(pcm, SpectrumBands.HOP * channels, pcm, 0, fill * channels);
                }
            }
        }

        @Override public synchronized void flush(MediaPlayer player, long pts) {
            frames.clear();
            fill = 0;
            nextPts = 0;
        }

        synchronized float[] at(long clock) {
            while (frames.size() > 1) {
                Frame first = frames.removeFirst();
                if (frames.getFirst().at() > clock + 12_000) {
                    frames.addFirst(first);
                    break;
                }
            }
            Frame frame = frames.peekFirst();
            return frame != null && frame.at() >= clock - 125_000 && frame.at() <= clock + 45_000
                ? frame.bands() : null;
        }

        private static long micros(int frames) { return Math.round(frames * 1_000_000.0 / SpectrumBands.RATE); }
    }
}
