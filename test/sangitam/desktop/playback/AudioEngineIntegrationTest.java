package sangitam.desktop.playback;

import com.sun.jna.Pointer;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.id3.ID3v24Tag;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.Track;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.callback.AudioCallback;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static sangitam.desktop.playback.PlaybackState.Status.*;

/** Opt in on a Windows desktop with VLC installed: -Dsangitam.nativeAudioTests=true. */
@EnabledIfSystemProperty(named = "sangitam.nativeAudioTests", matches = "true")
class AudioEngineIntegrationTest {
    @TempDir Path directory;

    /** Silent native decode through memory callbacks; opens no windows and uses no speaker output. */
    @Test void nativeEqHasNoAutomaticAttenuationAndBoostsSelectedBand() throws Exception {
        Path wave = tone(2);
        double baseline = pcmRms(wave, AudioSettings.defaults());
        var flat = new AudioSettings(true, AudioSettings.defaults().bands(), 0, AudioSettings.ReplayGain.OFF);
        double flatRms = pcmRms(wave, flat);
        assertEquals(0, 20 * Math.log10(flatRms / baseline), 0.1, "Enabling flat EQ should keep the level");
        var bands = new ArrayList<>(flat.bands());
        bands.set(5, 6d); // generated tone is 1 kHz
        double boosted = pcmRms(wave, new AudioSettings(true, bands, 0, AudioSettings.ReplayGain.OFF));
        assertTrue(20 * Math.log10(boosted / baseline) > 3, "A boosted band should increase this tone, without automatic preamp attenuation");
        double attenuated = pcmRms(wave, new AudioSettings(true, bands, -6, AudioSettings.ReplayGain.OFF));
        assertEquals(-6, 20 * Math.log10(attenuated / boosted), 0.2, "Only the manual preamp should attenuate");
    }

    @Test void nativeReplayGainChangesDecodedPcmAndLeavesUntaggedAudioAlone() throws Exception {
        Path wave = tone(2);
        Path mp3 = directory.resolve("tagged.mp3");
        MediaPlayerFactory factory = new MediaPlayerFactory(new NativeDiscovery());
        MediaPlayer encoder = factory.mediaPlayers().newMediaPlayer();
        CountDownLatch end = endSignal(encoder);
        try {
            assertTrue(encoder.media().play(wave.toUri().toString(), ":no-video",
                ":sout=#transcode{acodec=mp3,ab=128,channels=1}:standard{access=file,mux=raw,dst=" + mp3 + "}"));
            assertTrue(end.await(15, TimeUnit.SECONDS), "MP3 encode timeout");
        } finally { encoder.release(); factory.release(); }

        assertEquals(pcmRms(wave, AudioSettings.ReplayGain.OFF), pcmRms(wave, AudioSettings.ReplayGain.TRACK), 1);
        var audio = AudioFileIO.read(mp3.toFile());
        var tag = new ID3v24Tag();
        for (String[] field : new String[][] { { "REPLAYGAIN_TRACK_GAIN", "-9.00 dB" },
            { "REPLAYGAIN_ALBUM_GAIN", "-3.00 dB" }, { "REPLAYGAIN_TRACK_PEAK", "0.5" }, { "REPLAYGAIN_ALBUM_PEAK", "0.5" } }) {
            var frame = tag.createFrame("TXXX");
            frame.setBody(new FrameBodyTXXX((byte) 3, field[0], field[1]));
            tag.addField(frame);
        }
        audio.setTag(tag);
        AudioFileIO.write(audio);
        double off = pcmRms(mp3, AudioSettings.ReplayGain.OFF);
        assertEquals(-9, 20 * Math.log10(pcmRms(mp3, AudioSettings.ReplayGain.TRACK) / off), 0.2);
        assertEquals(-3, 20 * Math.log10(pcmRms(mp3, AudioSettings.ReplayGain.ALBUM) / off), 0.2);
    }

    @Test void vlcEffectsPreserveTransportAndNormalizationChangesAtNextStart() throws Exception {
        try (var player = new VlcjPlayerService()) {
            exercise(player);
            assertEquals(AudioStatus.Backend.VLC, player.audioStatus().backend());
            assertEquals(AudioSettings.ReplayGain.ALBUM, player.audioStatus().activeReplayGain());
        }
    }

    @Test void vlcPlaysLocalAudioWithNonAsciiFilename() throws Exception {
        Path file = Files.move(tone(12), directory.resolve("Tone 'Path\u00e9tique' [one].wav"));
        var track = new Track(file, "Tone", "Test", "Test", 12, "WAV");
        try (var player = new VlcjPlayerService()) {
            player.setVolume(0);
            player.play(track, 2);
            await(() -> player.state().status() == PLAYING && player.state().positionSeconds() >= 2,
                "local path and resume position");
            assertEquals(AudioStatus.Backend.VLC, player.audioStatus().backend());
            await(() -> player.state().seekable(), "seekable");
            player.seek(0.5);
            await(() -> Math.abs(player.state().positionSeconds() - 6) < 0.5, "seek");
            player.stop();
            await(() -> player.state().status() == STOPPED, "stop");
            Files.delete(file);
            player.play(track);
            await(() -> player.state().status() == ERROR, "unavailable file");
            assertTrue(player.state().error().contains("File is unavailable"));
        }
    }

    @Test void vlcPlaysAndSeeksLongManagedWindowsPaths() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        Path folders = directory.resolve("Long recording folder with accents".repeat(3))
            .resolve("Beethoven — orchestral recording ".repeat(3).strip());
        Files.createDirectories(folders);
        Path file = Files.move(tone(12), folders.resolve("01 - Pathétique ♭.wav"));
        assertTrue(file.toString().length() > 260);
        var track = new Track(file, "Long managed track", "Test", "Test", 12, "WAV");
        try (var player = new VlcjPlayerService()) {
            var receivedBands = new AtomicReference<>(new float[0]);
            player.setVolume(0);
            player.setSpectrumListener(receivedBands::set);
            player.play(track, 2);
            await(() -> player.state().status() == PLAYING && player.state().positionSeconds() >= 2
                && player.state().seekable(), "long-path play");
            await(() -> receivedBands.get().length > 0, "long-path spectrum");
            player.seek(.5);
            await(() -> Math.abs(player.state().positionSeconds() - 6) < .5, "long-path seek");
            player.setSpectrumListener(null);
            player.stop();
            await(() -> player.state().status() == STOPPED, "long-path stop");
        }
        await(() -> {
            try {
                Files.deleteIfExists(file);
                return true;
            } catch (IOException stillReleasing) { return false; }
        }, "long-path file handle released");
    }

    @Test void javaFxEffectsPreserveTransportAndReportNormalizationUnavailable() throws Exception {
        try (var player = new JavaFxPlayerService()) {
            exercise(player);
            assertEquals(AudioStatus.Backend.JAVA_FX, player.audioStatus().backend());
            assertEquals(AudioSettings.ReplayGain.OFF, player.audioStatus().activeReplayGain());
        }
    }

    @Test void nativeSpectrumTracksSeekPauseAndTrackChangesWithoutReplacingPlayback() throws Exception {
        var track = new Track(tone(12, 3000), "Two tones", "Test", "Test", 12, "WAV");
        var other = new Track(tone(14), "Other tone", "Test", "Test", 14, "WAV");
        var samples = new AtomicReference<>(new float[0]);
        var frames = new AtomicInteger();
        var clears = new AtomicInteger();
        try (var player = new VlcjPlayerService()) {
            player.setVolume(0);
            player.play(track, 2);
            await(() -> player.state().status() == PLAYING && player.state().seekable(), "start");
            player.setSpectrumListener(value -> {
                samples.set(value);
                if (value.length == 0) clears.incrementAndGet();
                else frames.incrementAndGet();
            });
            await(() -> hasPeak(samples.get(), 55), "1 kHz native spectrum");
            int initialFrames = frames.get();
            int initialClears = clears.get();
            Thread.sleep(1200);
            assertTrue(frames.get() - initialFrames >= 30, "sustained delivery above 25 frames per second");
            assertEquals(initialClears, clears.get(), "continuous playback must not clear the spectrum");
            player.seek(0.65);
            await(() -> player.state().positionSeconds() >= 7.7, "seek into 3 kHz passage");
            await(() -> hasPeak(samples.get(), 74), "3 kHz native spectrum after seek");
            player.setAudioSettings(new AudioSettings(true, AudioSettings.defaults().bands(), -3, AudioSettings.ReplayGain.OFF));
            player.pause();
            await(() -> player.state().status() == PAUSED && samples.get().length == 0, "pause clears samples");
            player.play(other);
            await(() -> player.state().status() == PLAYING && hasPeak(samples.get(), 55), "different track replaces samples");
            player.setSpectrumListener(null);
            samples.set(new float[0]);
            Thread.sleep(450);
            assertEquals(0, samples.get().length);
            assertEquals(PLAYING, player.state().status());
            assertNull(player.audioStatus().error());
            player.stop();
            await(() -> player.state().status() == STOPPED, "stop");
        }
    }

    private static boolean hasPeak(float[] bands, int expected) {
        if (bands.length != 96) return false;
        int max = -1;
        for (int i = 0; i < bands.length; i++) if (max < 0 || bands[i] > bands[max]) max = i;
        return Math.abs(max - expected) <= 1;
    }

    private void exercise(PlayerService player) throws Exception {
        Track track = new Track(tone(12), "Tone", "Test", "Test", 12, "WAV");
        var bands = new ArrayList<>(AudioSettings.defaults().bands());
        bands.set(5, 6d);
        player.setAudioSettings(new AudioSettings(true, bands, -3, AudioSettings.ReplayGain.TRACK));
        player.setVolume(0.5);
        var fallbackSpectrum = new AtomicReference<>(new float[0]);
        if (player instanceof JavaFxPlayerService) player.setSpectrumListener(fallbackSpectrum::set);
        player.play(track);
        await(() -> player.state().status() == PLAYING, "start");
        if (player instanceof JavaFxPlayerService) {
            await(() -> fallbackSpectrum.get().length == 96, "96-band JavaFX spectrum");
            player.setSpectrumListener(null);
        }
        assertNull(player.audioStatus().error());
        player.pause();
        await(() -> player.state().status() == PAUSED, "pause");
        player.seek(0.5);
        await(() -> Math.abs(player.state().positionSeconds() - 6) < 0.5, "paused seek");
        var previousMode = player.audioStatus().activeReplayGain();
        player.setAudioSettings(new AudioSettings(true, bands, -2, AudioSettings.ReplayGain.ALBUM));
        Thread.sleep(200);
        assertEquals(PAUSED, player.state().status());
        assertEquals(6, player.state().positionSeconds(), 0.5);
        assertEquals(previousMode, player.audioStatus().activeReplayGain());
        player.resume();
        await(() -> player.state().status() == PLAYING, "resume");
        player.stop();
        await(() -> player.state().status() == STOPPED, "stop");
        player.play(track);
        await(() -> player.state().status() == PLAYING, "restart");
        player.setAudioSettings(AudioSettings.defaults());
        Thread.sleep(200);
        assertEquals(PLAYING, player.state().status());
        assertNull(player.audioStatus().error());
        player.stop();
        await(() -> player.state().status() == STOPPED, "final stop releases the fixture");
        // JavaFX 21's file-URL probing can retain an abandoned FileURLConnection stream on
        // this Windows/JDK combination. Collect it in the test before JUnit deletes the
        // fixture; production deliberately does not force GC. See the handoff limitation.
        if (player instanceof JavaFxPlayerService) System.gc();
        await(() -> {
            try { Files.deleteIfExists(track.path()); return true; }
            catch (IOException busy) { return false; }
        }, "decoder file release");
    }

    private static void await(BooleanSupplier condition, String action) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(30);
        assertTrue(condition.getAsBoolean(), action + " timeout");
    }

    private Path tone(int seconds) throws Exception {
        return tone(seconds, 1000);
    }

    private Path tone(int seconds, int latterFrequency) throws Exception {
        byte[] bytes = new byte[44_100 * 2 * seconds];
        for (int i = 0; i < bytes.length / 2; i++) {
            int frequency = i < bytes.length / 4 ? 1000 : latterFrequency;
            short value = (short) (2000 * Math.sin(2 * Math.PI * frequency * i / 44_100));
            bytes[i * 2] = (byte) value;
            bytes[i * 2 + 1] = (byte) (value >>> 8);
        }
        Path file = directory.resolve("tone-" + seconds + ".wav");
        try (var stream = new AudioInputStream(new ByteArrayInputStream(bytes), new AudioFormat(44_100, 16, 1, true, false), bytes.length / 2)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }

    private static CountDownLatch endSignal(MediaPlayer player) {
        CountDownLatch end = new CountDownLatch(1);
        player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override public void finished(MediaPlayer source) { end.countDown(); }
            @Override public void error(MediaPlayer source) { end.countDown(); }
        });
        return end;
    }

    private static double pcmRms(Path path, AudioSettings.ReplayGain mode) throws Exception {
        var settings = new AudioSettings(false, AudioSettings.defaults().bands(), 0, mode);
        return pcmRms(path, settings);
    }

    private static double pcmRms(Path path, AudioSettings settings) throws Exception {
        var factory = new MediaPlayerFactory(new NativeDiscovery(), settings.vlcOptions());
        var player = factory.mediaPlayers().newMediaPlayer();
        CountDownLatch end = endSignal(player);
        class Capture implements AudioCallback {
            private double sum;
            private long samples;
            @Override public synchronized void play(MediaPlayer source, Pointer data, int count, long pts) {
                for (short sample : data.getShortArray(0, count)) sum += (double) sample * sample;
                samples += count;
            }
            synchronized double rms() { return Math.sqrt(sum / samples); }
            @Override public void pause(MediaPlayer source, long pts) { }
            @Override public void resume(MediaPlayer source, long pts) { }
            @Override public void flush(MediaPlayer source, long pts) { }
            @Override public void drain(MediaPlayer source) { }
            @Override public void setVolume(float volume, boolean mute) { }
        }
        Capture capture = new Capture();
        player.audio().callback("S16N", 44_100, 1, capture);
        try {
            if (settings.equalizerEnabled()) {
                var equalizer = factory.equalizer().newEqualizer();
                equalizer.setPreamp(AudioGain.vlcEqualizerPreamp(settings.effectivePreampDb()));
                for (int i = 0; i < settings.bands().size(); i++) equalizer.setAmp(i, settings.bands().get(i).floatValue());
                player.audio().setEqualizer(equalizer);
            }
            assertTrue(player.media().play(path.toUri().toString(), ":no-video"));
            assertTrue(end.await(15, TimeUnit.SECONDS), "decode timeout");
            double rms = capture.rms();
            assertTrue(Double.isFinite(rms) && rms > 1, "decoded PCM should contain a tone");
            return rms;
        } finally { player.release(); factory.release(); }
    }
}
