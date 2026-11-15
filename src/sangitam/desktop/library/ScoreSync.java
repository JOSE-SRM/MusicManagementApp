package sangitam.desktop.library;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Aligns a symbolic edition to decoded recording audio using time-varying pitch classes. */
public final class ScoreSync {
    private static final double STEP = .20;
    private static final int MAX_FRAMES = 9000;
    private static final int FFT = 4096;
    private static final double[] WINDOW = new double[FFT];
    static { for (int i = 0; i < FFT; i++) WINDOW[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (FFT - 1)); }

    public record Note(int pitch, double start, double end) { }
    public record Reference(double duration, List<Note> notes) { }
    public record Alignment(double offset, double step, double[] scoreSeconds, double evidence, String referenceId) {
        public Alignment(double offset, double step, double[] scoreSeconds, double evidence) {
            this(offset, step, scoreSeconds, evidence, "");
        }
        public double scoreAt(double audioSeconds) {
            double frame = Math.clamp((audioSeconds - offset) / step, 0, scoreSeconds.length - 1);
            int before = (int) frame;
            int after = Math.min(scoreSeconds.length - 1, before + 1);
            return scoreSeconds[before] + (frame - before) * (scoreSeconds[after] - scoreSeconds[before]);
        }

        public double audioAt(double scoreTime) {
            int low = 0, high = scoreSeconds.length - 1;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (scoreSeconds[middle] < scoreTime) low = middle + 1;
                else high = middle;
            }
            if (low == 0 || scoreTime >= scoreSeconds[low] || scoreSeconds[low] == scoreSeconds[low - 1])
                return offset + low * step;
            return offset + step * (low - 1 + (scoreTime - scoreSeconds[low - 1]) / (scoreSeconds[low] - scoreSeconds[low - 1]));
        }
    }

    private ScoreSync() { }

    public record OpticalAlignment(Alignment alignment, OpticalScoreRecognition.Variant notation) { }

    public static Reference reference(Path midi) throws Exception {
        Sequence sequence = MidiSystem.getSequence(midi.toFile());
        if (sequence.getDivisionType() != Sequence.PPQ) throw new IOException("This MIDI timing format is unsupported.");
        var events = new ArrayList<MidiEvent>();
        for (var track : sequence.getTracks()) for (int i = 0; i < track.size(); i++) events.add(track.get(i));
        events.sort(Comparator.comparingLong(MidiEvent::getTick));
        var notes = new ArrayList<Note>();
        double[][] starts = new double[16][128];
        for (double[] channel : starts) Arrays.fill(channel, -1);
        double seconds = 0;
        long tick = 0;
        int tempo = 500_000;
        for (var event : events) {
            seconds += (event.getTick() - tick) * (tempo / 1_000_000.0 / sequence.getResolution());
            tick = event.getTick();
            if (event.getMessage() instanceof MetaMessage meta && meta.getType() == 0x51 && meta.getData().length == 3) {
                byte[] data = meta.getData();
                tempo = (data[0] & 255) << 16 | (data[1] & 255) << 8 | data[2] & 255;
            }
            if (!(event.getMessage() instanceof ShortMessage note) || note.getChannel() == 9) continue;
            int key = note.getData1();
            double[] channel = starts[note.getChannel()];
            boolean on = note.getCommand() == ShortMessage.NOTE_ON && note.getData2() > 0;
            if (on || note.getCommand() == ShortMessage.NOTE_OFF || note.getCommand() == ShortMessage.NOTE_ON) {
                if (channel[key] >= 0 && seconds > channel[key]) notes.add(new Note(key, channel[key], seconds));
                channel[key] = on ? seconds : -1;
            }
        }
        double duration = notes.stream().mapToDouble(Note::end).max().orElse(0);
        if (duration <= 1 || duration / STEP >= MAX_FRAMES) throw new IOException("The score MIDI is empty or exceeds 30 minutes.");
        notes.sort(Comparator.comparingDouble(Note::start));
        return new Reference(duration, List.copyOf(notes));
    }

    public static Alignment align(Path audio, Reference reference) throws Exception {
        var recording = decode(audio);
        return match(recording.frames(), features(reference), recording.offset());
    }

    public static OpticalAlignment alignOptical(Path audio, OpticalScoreRecognition.Result recognition, boolean findMovement) throws Exception {
        return alignOptical(audio, recognition, findMovement, false);
    }

    public static String opticalReferenceId(OpticalScoreRecognition.Result notation, OpticalScoreRecognition.Variant variant,
                                            boolean opening) {
        return (opening ? "opening-v3/" : "") + notation.revision() + ":" + variant.id();
    }

    public static OpticalAlignment alignOptical(Path audio, OpticalScoreRecognition.Result recognition, boolean findMovement,
                                                 boolean opening) throws Exception {
        var recording = decode(audio);
        OpticalAlignment best = null;
        var variants = new ArrayList<OpticalScoreRecognition.Variant>();
        variants.add(recognition.written());
        if (recognition.repeated() != null) variants.add(recognition.repeated());
        for (var variant : variants) {
            double step = variant.reference().duration() / recording.frames().size();
            Alignment full = attempt(recording, features(variant.reference(), step), 0, step, opening);
            if (findMovement) {
                for (double speed : new double[]{.28, .46, .68, 1.0}) {
                    double candidateStep = STEP * speed;
                    double targetFrames = variant.reference().duration() / candidateStep;
                    if (targetFrames < recording.frames().size() * 1.25 || targetFrames > MAX_FRAMES * 3) continue;
                    double[][] score = features(variant.reference(), candidateStep);
                    int[] range = locatePassage(recording.frames(), score, opening);
                    if (range[1] - range[0] < recording.frames().size() / 3) continue;
                    Alignment partial = attempt(recording, Arrays.copyOfRange(score, range[0], range[1]), range[0], candidateStep, opening);
                    if (partial != null && (full == null || partial.evidence() > full.evidence() + .035)) full = partial;
                }
            }
            if (full != null && (best == null || full.evidence() > best.alignment().evidence()))
                best = new OpticalAlignment(new Alignment(full.offset(), full.step(), full.scoreSeconds(), full.evidence(),
                    opticalReferenceId(recognition, variant, opening)), variant);
        }
        if (best == null) throw new IOException("The recognized notation does not match this recording reliably. Try a different edition or movement.");
        return best;
    }

    private record Recording(List<double[]> frames, double offset) { }

    private static Recording decode(Path audio) throws Exception {
        return decode(audio, MAX_FRAMES + 1);
    }

    private static Recording decode(Path audio, int limit) throws Exception {
        List<double[]> decoded;
        if (audio.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".flac")) {
            try (var file = Files.newInputStream(audio); var pcm = new FlacPcmInputStream(file).pcm()) { decoded = chroma(pcm, limit); }
        } else {
            try (var input = AudioSystem.getAudioInputStream(audio.toFile())) {
                var format = input.getFormat();
                var signed = new AudioFormat(format.getSampleRate(), 16, format.getChannels(), true, false);
                try (var pcm = AudioSystem.getAudioInputStream(signed, input)) { decoded = chroma(pcm, limit); }
            } catch (javax.sound.sampled.UnsupportedAudioFileException | IllegalArgumentException unsupported) {
                throw new IOException("This recording format cannot be decoded for score alignment.", unsupported);
            }
        }
        int first = 0, end = decoded.size();
        while (first < end && silent(decoded.get(first))) first++;
        while (end > first && silent(decoded.get(end - 1))) end--;
        if (end - first < 10) throw new IOException("The recording contains too little pitched audio to align.");
        return new Recording(decoded.subList(first, end), (first + .5) * STEP);
    }

    static int identifyOpening(Path audio, List<String> openings) throws Exception {
        var recording = decode(audio, (int) (32 / STEP)).frames();
        double best = 0, runnerUp = 0;
        int movement = 0;
        for (int number = 0; number < openings.size(); number++) {
            var notes = new ArrayList<Note>();
            double time = 0;
            for (String run : openings.get(number).split(" ")) {
                String[] fields = run.split(":");
                int mask = Integer.parseInt(fields[0], 16);
                double end = time + Integer.parseInt(fields[1]) / 16.0;
                for (int pitch = 0; pitch < 12; pitch++)
                    if ((mask & (1 << pitch)) != 0) notes.add(new Note(pitch, time, end));
                time = end;
            }
            var reference = new Reference(time, notes);
            double evidence = 0;
            for (double stretch : new double[]{.55, .7, .9, 1.15, 1.45, 1.85, 2.35, 3}) {
                double[][] score = features(reference, STEP / stretch);
                if (recording.size() < score.length) continue;
                var matched = attempt(new Recording(recording.subList(0, score.length), 0), score, 0, STEP / stretch, true);
                if (matched != null) evidence = Math.max(evidence, matched.evidence());
            }
            if (evidence > best) {
                runnerUp = best;
                best = evidence;
                movement = number + 1;
            } else runnerUp = Math.max(runnerUp, evidence);
        }
        return best >= .51 && best - runnerUp >= .07 ? movement : 0;
    }

    private static double[][] features(Reference reference) throws IOException {
        return features(reference, STEP);
    }

    private static double[][] features(Reference reference, double step) throws IOException {
        int frames = (int) Math.ceil(reference.duration() / step);
        if (frames < 10 || frames > MAX_FRAMES * 3) throw new IOException("The recognized score is too short or too long to align.");
        double[][] score = new double[frames][12];
        for (Note note : reference.notes()) {
            int from = (int) (note.start() / step), to = Math.min(frames, (int) Math.ceil(note.end() / step));
            for (int i = from; i < to; i++) {
                double overlap = Math.min(note.end(), (i + 1) * step) - Math.max(note.start(), i * step);
                score[i][note.pitch() % 12] += Math.max(0, overlap) * (i == from ? 1.35 : 1);
            }
        }
        for (double[] vector : score) normalize(vector);
        return score;
    }

    private static Alignment attempt(Recording recording, double[][] score, int skip, double scoreStep, boolean opening) throws IOException {
        try {
            var alignment = match(recording.frames(), score, recording.offset());
            if (opening && !matchesOpening(recording.frames(), score, alignment.scoreSeconds(), scoreStep)) return null;
            if (!locallySupported(recording.frames(), score, alignment.scoreSeconds())) return null;
            for (int i = 0; i < alignment.scoreSeconds().length; i++)
                alignment.scoreSeconds()[i] = (alignment.scoreSeconds()[i] / STEP + skip) * scoreStep;
            return alignment;
        } catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (IOException noMatch) { return null; }
    }

    private static boolean locallySupported(List<double[]> audio, double[][] score, double[] positions) {
        int window = (int) (8 / STEP);
        int supported = 0, checked = 0, weakRun = 0;
        for (int start = 0; start < positions.length; start += window) {
            double agreement = 0;
            int voiced = 0;
            for (int i = start; i < Math.min(start + window, positions.length); i++) {
                if (silent(audio.get(i))) continue;
                int index = (int) Math.round(positions[i] / STEP);
                double nearest = 0;
                for (int j = Math.max(0, index - 1); j <= Math.min(score.length - 1, index + 1); j++)
                    nearest = Math.max(nearest, dot(audio.get(i), score[j]));
                agreement += nearest;
                voiced++;
            }
            if (voiced < window / 3) continue;
            checked++;
            if (agreement / voiced >= .39) {
                supported++;
                weakRun = 0;
            } else if (++weakRun == 4) return false;
        }
        return checked < 4 || supported >= checked * .70;
    }

    private static boolean matchesOpening(List<double[]> audio, double[][] score, double[] positions, double scoreStep) {
        // An apparently good match later in the movement cannot compensate for a mismatched opening.
        if (positions[0] / STEP * scoreStep > 2.0) return false;
        int first = 0;
        while (first < score.length && silent(score[first])) first++;
        double onset = 0;
        for (int i = 0; i < Math.min(4, audio.size()); i++)
            for (int j = first; j < Math.min(first + 3, score.length); j++)
                onset = Math.max(onset, dot(audio.get(i), score[j]));
        if (onset < .32) return false;
        double agreement = 0;
        int voiced = 0;
        for (int i = 0; i < Math.min(positions.length, (int) (8 / STEP)); i++) {
            double[] notation = score[Math.min(score.length - 1, (int) Math.round(positions[i] / STEP))];
            if (!silent(audio.get(i)) && !silent(notation)) {
                agreement += dot(audio.get(i), notation);
                voiced++;
            }
        }
        return voiced >= 6 && agreement / voiced >= .33;
    }

    /** Finds a candidate ending, with a free start only when searching for a later movement. */
    private static int[] locatePassage(List<double[]> audio, double[][] score, boolean opening) throws IOException {
        final int stride = 5;
        int width = (score.length + stride - 1) / stride;
        double[] previous = new double[width + 1], current = new double[width + 1];
        int[] starts = new int[width + 1], next = new int[width + 1];
        if (opening) Arrays.fill(previous, 1, previous.length, Double.POSITIVE_INFINITY);
        else for (int j = 0; j <= width; j++) starts[j] = j;
        for (int i = 0; i < audio.size(); i += stride) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            current[0] = Double.POSITIVE_INFINITY;
            for (int j = 1; j <= width; j++) {
                double diagonal = previous[j - 1], vertical = previous[j] + .23, horizontal = current[j - 1] + .23;
                double best;
                if (diagonal <= vertical && diagonal <= horizontal) { best = diagonal; next[j] = starts[j - 1]; }
                else if (vertical <= horizontal) { best = vertical; next[j] = starts[j]; }
                else { best = horizontal; next[j] = next[j - 1]; }
                current[j] = best + 1 - dot(audio.get(i), score[(j - 1) * stride]);
            }
            var swap = previous; previous = current; current = swap;
            var origins = starts; starts = next; next = origins;
        }
        int end = 1;
        for (int j = 2; j <= width; j++) if (previous[j] < previous[end]) end = j;
        return new int[]{Math.max(0, (starts[end] - 1) * stride), Math.min(score.length, (end + 1) * stride)};
    }

    private static List<double[]> chroma(AudioInputStream pcm, int limit) throws IOException {
        var format = pcm.getFormat();
        int frames = Math.round(format.getSampleRate() * (float) STEP);
        int channels = format.getChannels();
        if (frames < 800 || frames > 76800 || channels < 1 || channels > 32)
            throw new IOException("This recording’s audio format is unsupported for score alignment.");
        byte[] samples = new byte[frames * channels * 2];
        var features = new ArrayList<double[]>();
        var real = new double[FFT];
        var imaginary = new double[FFT];
        var power = new double[FFT / 2 + 1];
        int sampled = Math.min(FFT, frames);
        double rate = sampled / STEP;
        while (features.size() < limit) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            int count = pcm.readNBytes(samples, 0, samples.length);
            if (count != samples.length) break;
            if (features.size() == MAX_FRAMES) throw new IOException("Align one movement at a time for recordings over 30 minutes.");
            Arrays.fill(real, 0);
            Arrays.fill(imaginary, 0);
            for (int i = 0; i < sampled; i++) {
                int from = i * frames / sampled, to = (i + 1) * frames / sampled;
                double value = 0;
                for (int j = from * channels * 2; j < to * channels * 2; j += 2)
                    value += (short) ((samples[j] & 255) | samples[j + 1] << 8);
                real[i] = value * WINDOW[i * FFT / sampled] / ((to - from) * channels * 32768.0);
            }
            SpectralFeatures.transform(real, imaginary);
            double maximum = 0;
            for (int i = 1; i < power.length; i++) {
                power[i] = real[i] * real[i] + imaginary[i] * imaginary[i];
                maximum = Math.max(maximum, power[i]);
            }
            double[] vector = new double[12];
            if (maximum > .04) SpectralFeatures.addPeaks(power, rate, maximum, vector);
            normalize(vector);
            features.add(vector);
        }
        return features;
    }

    static Alignment match(List<double[]> audio, double[][] score, double offset) throws IOException {
        int length = audio.size(), reference = score.length;
        if (length < reference / 3 || length > reference * 3) throw new IOException("The recording duration differs too much from this movement.");
        int radius = Math.max(16, reference / 4);
        var directions = new byte[length][];
        int[] starts = new int[length];
        float[] previous = new float[reference + 1], current = new float[reference + 1];
        Arrays.fill(previous, Float.POSITIVE_INFINITY);
        previous[0] = 0;
        for (int i = 0; i < length; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            int expected = i * (reference - 1) / (length - 1);
            int start = Math.max(0, expected - radius), end = Math.min(reference, expected + radius + 1);
            starts[i] = start;
            directions[i] = new byte[end - start];
            Arrays.fill(current, Float.POSITIVE_INFINITY);
            for (int j = start; j < end; j++) {
                float diagonal = previous[j];
                float holdScore = previous[j + 1] + .14f, holdAudio = current[j] + .14f;
                byte direction = diagonal <= holdScore && diagonal <= holdAudio ? 0 : holdScore <= holdAudio ? (byte) 1 : 2;
                float best = direction == 0 ? diagonal : direction == 1 ? holdScore : holdAudio;
                current[j + 1] = best + (float) (1 - dot(audio.get(i), score[j]));
                directions[i][j - start] = direction;
            }
            var swap = previous; previous = current; current = swap;
        }
        double[] positions = new double[length];
        int[] samples = new int[length];
        double agreement = 0, baseline = 0;
        int voiced = 0;
        for (int i = length - 1, j = reference - 1; i >= 0 && j >= 0;) {
            positions[i] += j * STEP;
            samples[i]++;
            if (!silent(audio.get(i)) && !silent(score[j])) {
                agreement += dot(audio.get(i), score[j]);
                baseline += dot(audio.get(i), score[(j + Math.max(5, reference / 9)) % reference]);
                voiced++;
            }
            byte direction = directions[i][j - starts[i]];
            if (direction != 2) i--;
            if (direction != 1) j--;
        }
        double evidence = agreement / Math.max(1, voiced);
        if (voiced < length / 3 || evidence < .43 || evidence - baseline / voiced < .10)
            throw new IOException("No reliable alignment was found for this edition and recording.");
        for (int i = 0; i < length; i++) positions[i] /= Math.max(1, samples[i]);
        return new Alignment(offset, STEP, positions, evidence);
    }

    private static boolean silent(double[] vector) { return dot(vector, vector) < .1; }

    private static double dot(double[] first, double[] second) {
        double sum = 0;
        for (int i = 0; i < 12; i++) sum += first[i] * second[i];
        return sum;
    }

    private static void normalize(double[] values) {
        double norm = Math.sqrt(dot(values, values));
        if (norm > 0) for (int i = 0; i < values.length; i++) values[i] /= norm;
    }
}
