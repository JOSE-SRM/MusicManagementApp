package sangitam.desktop.playback;

import sangitam.desktop.model.Track;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.media.TrackType;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import static sangitam.desktop.playback.PlaybackState.Status.*;

/** libVLC-backed local player with a JavaFX fallback when native initialization fails. */
public final class VlcjPlayerService implements PlayerService {
    private static final System.Logger LOG = System.getLogger(VlcjPlayerService.class.getName());
    private final ScheduledExecutorService commands = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "sangitam-libvlc");
        thread.setDaemon(true);
        return thread;
    });
    private volatile PlaybackState state = PlaybackState.idle();
    private volatile Consumer<PlaybackState> listener = ignored -> { };
    private volatile long request;
    private volatile boolean closed;
    private volatile JavaFxPlayerService fallback;
    private volatile double volume = 0.72;
    private volatile Consumer<float[]> spectrum;
    private final CoalescedCommand volumeCommand = new CoalescedCommand(commands, () -> {
        if (closed) return;
        if (fallback != null) fallback.setVolume(volume);
        else if (this.player != null) try { this.player.audio().setVolume(AudioGain.vlcVolume(volume)); } catch (RuntimeException ignored) { }
    });
    private volatile AudioSettings audioSettings = AudioSettings.defaults();
    private volatile AudioStatus audioStatus = AudioStatus.checking();

    // Native handles and per-media state are used only on the command thread.
    private MediaPlayerFactory factory;
    private boolean nativeUnavailable;
    private MediaPlayer player;
    private long activeRequest;
    private boolean foundAudioStream;
    private AudioSettings.ReplayGain factoryReplayGain;

    public VlcjPlayerService() {
        commands.scheduleWithFixedDelay(this::sample, 0, 100, TimeUnit.MILLISECONDS);
    }

    @Override public synchronized void setListener(Consumer<PlaybackState> listener) {
        this.listener = listener;
        if (fallback != null) fallback.setListener(listener);
        else SwingUtilities.invokeLater(() -> {
            if (!closed && fallback == null) listener.accept(state);
        });
    }

    @Override public PlaybackState state() {
        JavaFxPlayerService alternate = fallback;
        return alternate == null ? state : alternate.state();
    }

    @Override public synchronized void prepare() {
        if (!closed) commands.execute(this::prepareNative);
    }

    private void prepareNative() {
        if (factory != null || nativeUnavailable) return;
        try {
            NativeDiscovery discovery = new NativeDiscovery();
            if (discovery.discover()) {
                AudioSettings settings = audioSettings;
                factory = new MediaPlayerFactory(discovery, settings.vlcOptions());
                factoryReplayGain = settings.replayGain();
                audioStatus = new AudioStatus(AudioStatus.Backend.VLC, AudioSettings.ReplayGain.OFF, null);
            } else nativeUnavailable = true;
        } catch (RuntimeException | LinkageError error) {
            LOG.log(System.Logger.Level.DEBUG, "Could not initialize libVLC");
            nativeUnavailable = true;
        }
        if (nativeUnavailable) audioStatus = new AudioStatus(AudioStatus.Backend.JAVA_FX, AudioSettings.ReplayGain.OFF, null);
    }

    @Override public synchronized void play(Track track, double startSeconds) {
        if (closed) return;
        long id = ++request;
        if (fallback != null) {
            fallback.play(track, startSeconds);
            return;
        }
        double start = Math.max(0, startSeconds);
        publish(id, new PlaybackState(track, LOADING, start, track.durationSeconds(), null, false));
        commands.execute(() -> open(id, track, start));
    }

    private void open(long id, Track track, double startSeconds) {
        if (id != request || closed) return;
        try {
            releasePlayer();
            if (!Files.isRegularFile(track.path())) {
                fail(id, track, "File is unavailable. Reconnect its drive or import it from its new location.");
                return;
            }
            prepareNative();
            if (nativeUnavailable) {
                startFallback(id, track, startSeconds);
                return;
            }
            // Audio output options belong to the libVLC instance, not individual media.
            // Recreate it only between tracks, so changing ReplayGain never interrupts playback.
            if (factoryReplayGain != audioSettings.replayGain()) {
                factory.release();
                factory = null;
                prepareNative();
                if (nativeUnavailable) { startFallback(id, track, startSeconds); return; }
            }
            MediaPlayer next = factory.mediaPlayers().newMediaPlayer();
            player = next;
            activeRequest = id;
            foundAudioStream = false;
            next.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override public void elementaryStreamAdded(MediaPlayer source, TrackType type, int streamId) {
                    if (type == TrackType.AUDIO) submitEvent(id, source, () -> foundAudioStream = true);
                }
                @Override public void finished(MediaPlayer source) {
                    submitEvent(id, source, () -> end(id, track));
                }
                @Override public void error(MediaPlayer source) {
                    submitEvent(id, source, () -> decodeError(id, track));
                }
            });
            applyEqualizer(next);
            if (!next.media().play(track.path().toUri().toString(), ":no-video", ":no-sub-autodetect-file",
                ":start-time=" + startSeconds)) {
                decodeError(id, track);
            }
            // libVLC's audio output is not necessarily available until the playing state.
            next.audio().setVolume(AudioGain.vlcVolume(volume));
            audioStatus = new AudioStatus(AudioStatus.Backend.VLC, factoryReplayGain, audioStatus.error());
        } catch (RuntimeException | LinkageError error) {
            LOG.log(System.Logger.Level.DEBUG, "VLC media initialization failed", error);
            fail(id, track, "VLC could not open audio. Check the file and the VLC installation.");
        }
    }

    private synchronized void submitEvent(long id, MediaPlayer source, Runnable event) {
        if (closed || id != request) return;
        commands.execute(() -> {
            if (source == player && id == request && !closed) event.run();
        });
    }

    private void sample() {
        if (closed || player == null || activeRequest != request) return;
        long id = activeRequest;
        PlaybackState current = state;
        if (current.status() == ENDED || current.status() == ERROR) return;
        try {
            PlaybackState.Status status;
            switch (player.status().state()) {
                case PLAYING -> status = PLAYING;
                case PAUSED -> status = PAUSED;
                case ENDED -> { end(id, current.track()); return; }
                case ERROR -> { decodeError(id, current.track()); return; }
                default -> { return; }
            }
            if (!foundAudioStream) foundAudioStream = player.audio().trackCount() > 0;
            if (current.status() == LOADING) player.audio().setVolume(AudioGain.vlcVolume(volume));
            long length = player.status().length();
            boolean seekable = length > 0 && player.status().isSeekable();
            long time = player.status().time();
            publish(id, new PlaybackState(current.track(), status,
                time >= 0 ? time / 1000.0 : current.positionSeconds(),
                length > 0 ? length / 1000.0 : current.durationSeconds(), null, seekable));
        } catch (RuntimeException | LinkageError error) {
            decodeError(id, current.track());
        }
    }

    private synchronized void startFallback(long id, Track track, double startSeconds) {
        if (closed || id != request) return;
        LOG.log(System.Logger.Level.DEBUG, "Using JavaFX Media");
        JavaFxPlayerService alternate = new JavaFxPlayerService();
        alternate.setAudioSettings(audioSettings);
        alternate.setVolume(volume);
        alternate.setSpectrumListener(spectrum);
        alternate.play(track, startSeconds);
        alternate.setListener(listener);
        fallback = alternate;
    }

    @Override public void pause() {
        command(JavaFxPlayerService::pause, id -> {
            if (player != null && state.status() == PLAYING) player.controls().setPause(true);
        });
    }

    @Override public void resume() {
        PlaybackState current = state();
        if (current.track() != null && (current.status() == STOPPED || current.status() == ENDED)) {
            play(current.track(), 0);
            return;
        }
        command(JavaFxPlayerService::resume, id -> {
            if (player != null && state.status() == PAUSED) player.controls().setPause(false);
        });
    }

    @Override public void stop() {
        command(JavaFxPlayerService::stop, id -> {
            PlaybackState current = state;
            releasePlayer();
            if (current.track() != null) publish(id, new PlaybackState(current.track(), STOPPED,
                0, current.durationSeconds(), null, false));
        });
    }

    @Override public void seek(double fraction) {
        if (!Double.isFinite(fraction)) return;
        double bounded = Math.max(0, Math.min(1, fraction));
        command(alternate -> alternate.seek(bounded), id -> {
            if (player == null || !state.seekable()) return;
            long length = player.status().length();
            if (length > 0 && player.status().isSeekable()) {
                player.controls().setTime(Math.min(length - 1, Math.round(length * bounded)));
            }
        });
    }

    @Override public synchronized void setVolume(double volume) {
        this.volume = AudioGain.clamp(volume);
        if (!closed) volumeCommand.request();
    }

    @Override public synchronized void setAudioSettings(AudioSettings settings) {
        audioSettings = java.util.Objects.requireNonNull(settings);
        command(alternate -> alternate.setAudioSettings(audioSettings), id -> {
            if (player != null) applyEqualizer(player);
        });
    }

    @Override public AudioStatus audioStatus() {
        JavaFxPlayerService alternate = fallback;
        return alternate == null ? audioStatus : alternate.audioStatus();
    }

    @Override public double maximumVolume() {
        return audioStatus().backend() == AudioStatus.Backend.JAVA_FX ? 1 : AudioGain.MAX_VOLUME;
    }
    @Override public boolean supportsSpectrum() { return fallback != null && fallback.supportsSpectrum(); }
    @Override public void setSpectrumListener(Consumer<float[]> listener) { spectrum = listener; if (fallback != null) fallback.setSpectrumListener(listener); }

    private void applyEqualizer(MediaPlayer target) {
        try {
            AudioSettings settings = audioSettings;
            if (!settings.equalizerEnabled()) target.audio().setEqualizer(null);
            else {
                var equalizer = target.audio().equalizer();
                boolean attach = equalizer == null;
                if (attach) equalizer = factory.equalizer().newEqualizer();
                if (equalizer.bandCount() != settings.bands().size()) throw new IllegalStateException("Unexpected EQ layout");
                equalizer.setPreamp(AudioGain.vlcEqualizerPreamp(settings.effectivePreampDb()));
                float[] amps = new float[equalizer.bandCount()];
                for (int i = 0; i < amps.length; i++) amps[i] = settings.bands().get(i).floatValue();
                equalizer.setAmps(amps);
                if (attach) target.audio().setEqualizer(equalizer);
            }
            audioStatus = new AudioStatus(AudioStatus.Backend.VLC, audioStatus.activeReplayGain(), null);
        } catch (RuntimeException | LinkageError error) {
            // An optional effect must not turn a playable track into a transport error.
            target.audio().setEqualizer(null);
            audioStatus = new AudioStatus(AudioStatus.Backend.VLC, audioStatus.activeReplayGain(), "Equalizer unavailable; audio is playing without EQ.");
            LOG.log(System.Logger.Level.DEBUG, "Could not apply equalizer", error);
        }
    }

    private synchronized void command(Consumer<JavaFxPlayerService> alternateCommand, LongConsumer nativeCommand) {
        if (closed) return;
        if (fallback != null) {
            alternateCommand.accept(fallback);
            return;
        }
        long id = request;
        commands.execute(() -> {
            if (closed || id != request) return;
            try {
                synchronized (this) {
                    if (closed || id != request) return;
                    if (fallback != null) {
                        alternateCommand.accept(fallback);
                        return;
                    }
                }
                nativeCommand.accept(id);
            } catch (RuntimeException | LinkageError error) {
                fail(id, state.track(), "VLC could not complete the playback command.");
            }
        });
    }

    private void end(long id, Track track) {
        if (state.status() != ERROR) {
            if (!foundAudioStream) {
                decodeError(id, track);
                return;
            }
            double length = state.durationSeconds();
            publish(id, new PlaybackState(track, ENDED, length, length, null, false));
        }
    }

    private void decodeError(long id, Track track) {
        fail(id, track, "VLC could not decode this track. Check that the file plays in VLC.");
    }

    private void fail(long id, Track track, String message) {
        publish(id, new PlaybackState(track, ERROR, 0, 0, message, false));
    }

    private synchronized void publish(long id, PlaybackState next) {
        if (closed || id != request || next.equals(state)) return;
        if (next.status() != state.status()) LOG.log(System.Logger.Level.DEBUG, "VLC transport: {0}", next.status());
        state = next;
        SwingUtilities.invokeLater(() -> {
            if (!closed && id == request && fallback == null) listener.accept(next);
        });
    }

    private void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try { old.controls().stop(); }
            finally { old.release(); }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (fallback != null) fallback.close();
        commands.execute(() -> {
            try { releasePlayer(); }
            finally { if (factory != null) factory.release(); }
        });
        commands.shutdown();
    }
}
