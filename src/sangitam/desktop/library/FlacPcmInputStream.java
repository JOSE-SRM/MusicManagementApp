package sangitam.desktop.library;

import org.jflac.FLACDecoder;
import org.jflac.FrameListener;
import org.jflac.frame.Frame;
import org.jflac.io.RandomFileInputStream;
import org.jflac.metadata.Metadata;
import sangitam.desktop.model.TrackTags.Failure;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.Objects;

/** Pulls one native FLAC frame at a time and exposes signed, little-endian 16-bit PCM. */
final class FlacPcmInputStream extends InputStream {
    static final class DecodeFailure extends IOException {
        private final Failure failure;
        DecodeFailure(Failure failure) { super(failure.message()); this.failure = failure; }
        Failure failure() { return failure; }
    }

    private final FLACDecoder decoder;
    private final int channels;
    private final int bits;
    private final int sampleRate;
    private final long expectedSamples;
    private byte[] pcm = new byte[0];
    private int offset;
    private int length;
    private boolean frameError;
    private int previousBadFrames;

    AudioInputStream pcm() {
        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        // Let the decoder see EOF itself so it can detect a stream shorter than STREAMINFO claims.
        return new AudioInputStream(this, format, AudioSystem.NOT_SPECIFIED);
    }

    FlacPcmInputStream(InputStream file) throws IOException {
        decoder = new FLACDecoder(file);
        var info = decoder.readStreamInfo();
        if (info == null) throw new DecodeFailure(Failure.DAMAGED_FLAC);
        channels = info.getChannels();
        bits = info.getBitsPerSample();
        sampleRate = info.getSampleRate();
        expectedSamples = info.getTotalSamples();
        if (bits < 4 || bits > 24 || sampleRate < 4000 || sampleRate > 384000) {
            throw new DecodeFailure(Failure.UNSUPPORTED_PCM);
        }
        decoder.readMetadata(info);
        decoder.addFrameListener(new FrameListener() {
            @Override public void processFrame(Frame frame) { }
            @Override public void processMetadata(Metadata metadata) { }
            @Override public void processError(String error) { frameError = true; }
        });
    }

    long frameCount() { return expectedSamples; }

    long seek(SeekableFile file, long frame) throws IOException {
        long landed = file.seek(decoder, frame);
        if (landed < 0 || landed > frame || frame - landed > 65535) {
            throw new IOException("FLAC seek did not reach the requested passage");
        }
        // Searching for a frame boundary can report skipped bytes. Subsequent reads still check CRCs.
        frameError = false;
        previousBadFrames = decoder.getBadFrames();
        offset = length = 0;
        return landed;
    }

    @Override public int read() throws IOException {
        if (offset == length && !readFrame()) return -1;
        return pcm[offset++] & 0xff;
    }

    @Override public int read(byte[] buffer, int start, int count) throws IOException {
        Objects.checkFromIndexSize(start, count, buffer.length);
        if (count == 0) return 0;
        if (offset == length && !readFrame()) return -1;
        int copied = Math.min(count, length - offset);
        System.arraycopy(pcm, offset, buffer, start, copied);
        offset += copied;
        return copied;
    }

    private boolean readFrame() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        if (expectedSamples > 0 && decoder.getSamplesDecoded() == expectedSamples) return false;
        Frame frame = decoder.readNextFrame();
        if (frameError || decoder.getBadFrames() > previousBadFrames
            || (expectedSamples > 0 && (decoder.getSamplesDecoded() > expectedSamples
                || (frame == null && decoder.getSamplesDecoded() < expectedSamples)))) {
            throw new DecodeFailure(Failure.DAMAGED_FLAC);
        }
        if (frame == null) return false;
        if (frame.header.channels != channels || frame.header.bitsPerSample != bits
            || frame.header.sampleRate != sampleRate) throw new DecodeFailure(Failure.DAMAGED_FLAC);

        offset = 0;
        length = frame.header.blockSize * channels * 2;
        if (pcm.length < length) pcm = new byte[length];
        var samples = decoder.getChannelData();
        int position = 0;
        for (int sample = 0; sample < frame.header.blockSize; sample++) {
            for (int channel = 0; channel < channels; channel++) {
                int value = samples[channel].getOutput()[sample];
                int scaled = bits >= 16 ? value >> (bits - 16) : value << (16 - bits);
                pcm[position++] = (byte) scaled;
                pcm[position++] = (byte) (scaled >> 8);
            }
        }
        return true;
    }

    /** Bounds the decoder's frame search, including files without a FLAC seek table. */
    static final class SeekableFile extends RandomFileInputStream {
        private boolean searching;
        private int bytesLeft;
        private int seeksLeft;

        SeekableFile(Path file) throws IOException { super(file.toFile()); }

        long seek(FLACDecoder decoder, long frame) throws IOException {
            searching = true;
            bytesLeft = 16 * 1024 * 1024;
            seeksLeft = 128;
            try { return decoder.seek(frame); }
            finally { searching = false; }
        }

        @Override public void seek(long position) throws IOException {
            if (searching && --seeksLeft < 0) throw new IOException("FLAC seek limit reached");
            checkRead(0);
            super.seek(position);
        }

        @Override public int read() throws IOException {
            checkRead(1);
            return super.read();
        }

        @Override public int read(byte[] buffer) throws IOException {
            return read(buffer, 0, buffer.length);
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            checkRead(length);
            return randomFile.read(buffer, offset, length);
        }

        private void checkRead(int count) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (searching && (bytesLeft -= count) < 0) throw new IOException("FLAC seek read limit reached");
        }
    }
}
