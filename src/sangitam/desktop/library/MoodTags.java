package sangitam.desktop.library;

import sangitam.desktop.model.AudioFeatures;

import java.util.ArrayList;
import java.util.List;

/** Suggests descriptors from the sampled sound. Expressive tags can also be chosen by the listener. */
public final class MoodTags {
    public static final List<String> CHOICES = List.of("Happy", "Sad", "Energetic", "Romantic", "Sorrowful",
        "Anger", "Peace", "Melodious", "Tense", "Reflective", "Dramatic", "Flowing");

    private MoodTags() { }

    static List<String> suggest(AudioFeatures audio) {
        if (audio.sampledSeconds() < 4 || audio.rmsDb() < -55) return List.of();
        var tags = new ArrayList<String>();
        boolean steadyPulse = audio.tempoConfidence() >= .26;
        if (audio.arousal() >= 65) {
            tags.add("Energetic");
            if (audio.dynamicRangeDb() > 21) tags.add("Tense");
            else if (steadyPulse && audio.bpm() >= 100 && audio.centroidHz() >= 900) tags.add("Happy");
        } else if (audio.arousal() <= 40) {
            tags.add("Peace");
            if (audio.bpm() > 0 && audio.bpm() <= 85 && audio.centroidHz() < 650) {
                tags.add("Reflective");
                if (audio.dynamicRangeDb() >= 16) tags.add(audio.bpm() <= 65 ? "Sorrowful" : "Sad");
            }
        } else if (audio.dynamicRangeDb() >= 24) {
            tags.add("Dramatic");
        } else if (audio.arousal() <= 55 && audio.centroidHz() < 650) {
            tags.add("Reflective");
        } else if (steadyPulse) {
            tags.add("Flowing");
        }
        if (audio.keyConfidence() >= .25 && !audio.key().isBlank() && audio.arousal() < 65) tags.add("Melodious");
        return tags;
    }
}
