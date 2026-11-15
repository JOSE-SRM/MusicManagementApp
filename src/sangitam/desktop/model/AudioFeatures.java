package sangitam.desktop.model;

import java.util.List;

/** Versioned local measurements and transparent heuristic mood hints, not emotion recognition. */
public record AudioFeatures(int version,long size,long modified,double sampledSeconds,double rmsDb,double peakDb,double dynamicRangeDb,
                            double centroidHz,double bpm,double tempoConfidence,String key,String mode,double keyConfidence,
                            int arousal,int valence,String mood,double confidence,List<Integer> energyCurve,int passageCount,
                            double tempoLowBpm,double tempoHighBpm,int tempoWindows,List<String> keyCandidates,
                            List<String> moodTags) {
    public static final int VERSION=4;
    public AudioFeatures {
        energyCurve = List.copyOf(energyCurve);
        keyCandidates = keyCandidates == null ? List.of() : List.copyOf(keyCandidates);
        moodTags = moodTags == null ? List.of() : List.copyOf(TrackMetadata.normalizeTags(moodTags));
    }
    public AudioFeatures(int version, long size, long modified, double sampledSeconds, double rmsDb, double peakDb,
                         double dynamicRangeDb, double centroidHz, double bpm, double tempoConfidence, String key,
                         String mode, double keyConfidence, int arousal, int valence, String mood, double confidence,
                         List<Integer> energyCurve) {
        this(version, size, modified, sampledSeconds, rmsDb, peakDb, dynamicRangeDb, centroidHz, bpm, tempoConfidence,
            key, mode, keyConfidence, arousal, valence, mood, confidence, energyCurve, version > 0 ? 1 : 0, 0, 0, 0, List.of());
    }
    public AudioFeatures(int version, long size, long modified, double sampledSeconds, double rmsDb, double peakDb,
                         double dynamicRangeDb, double centroidHz, double bpm, double tempoConfidence, String key,
                         String mode, double keyConfidence, int arousal, int valence, String mood, double confidence,
                         List<Integer> energyCurve, int passageCount) {
        this(version, size, modified, sampledSeconds, rmsDb, peakDb, dynamicRangeDb, centroidHz, bpm, tempoConfidence,
            key, mode, keyConfidence, arousal, valence, mood, confidence, energyCurve, passageCount, 0, 0, 0, List.of());
    }
    public AudioFeatures(int version, long size, long modified, double sampledSeconds, double rmsDb, double peakDb,
                         double dynamicRangeDb, double centroidHz, double bpm, double tempoConfidence, String key,
                         String mode, double keyConfidence, int arousal, int valence, String mood, double confidence,
                         List<Integer> energyCurve, int passageCount, double tempoLowBpm, double tempoHighBpm,
                         int tempoWindows, List<String> keyCandidates) {
        this(version, size, modified, sampledSeconds, rmsDb, peakDb, dynamicRangeDb, centroidHz, bpm, tempoConfidence,
            key, mode, keyConfidence, arousal, valence, mood, confidence, energyCurve, passageCount,
            tempoLowBpm, tempoHighBpm, tempoWindows, keyCandidates, List.of());
    }

    public AudioFeatures withMoodTags(List<String> tags) {
        return new AudioFeatures(version, size, modified, sampledSeconds, rmsDb, peakDb, dynamicRangeDb, centroidHz,
            bpm, tempoConfidence, key, mode, keyConfidence, arousal, valence, mood, confidence, energyCurve,
            passageCount, tempoLowBpm, tempoHighBpm, tempoWindows, keyCandidates, tags);
    }
    public static AudioFeatures empty(){return new AudioFeatures(0,-1,-1,0,-120,-120,0,0,0,0,"","",0,50,50,"Unknown",0,List.of());}
    public boolean current(long size,long modified){return version==VERSION&&this.size==size&&this.modified==modified;}
    public int recommendationValence() { return version == VERSION ? valence : 50; }
    public String summary() {
        return summary(TrackMetadata.empty());
    }

    public String summary(TrackMetadata metadata) {
        String movementKey = MusicalKey.display(metadata.text(TrackMetadata.Field.KEY));
        String workKey = MusicalKey.display(metadata.text(TrackMetadata.Field.WORK_KEY));
        String keyDescription = !movementKey.isBlank() ? " · " + movementKey + " (musical key)"
            : !workKey.isBlank() ? " · " + workKey + " (work key)"
            : key.isBlank() ? " · key uncertain"
            : " · " + MusicalKey.display(key) + " " + mode + " estimated key";
        if (version == 0) return "Analysis pending";
        if (version != VERSION) return "Analysis update pending";
        return mood + " estimate · " + (bpm > 0
            ? "about " + Math.round(bpm) + " BPM" + (variablePulse() ? " (variable)" : "") : "tempo uncertain")
            + keyDescription;
    }

    public String audioKeyDescription() {
        return key.isBlank() ? tonalEvidence() : MusicalKey.display(key)
            + " " + mode + " · " + tonalEvidence() + " evidence";
    }

    public String tempoDescription() {
        if (bpm <= 0) return "Pulse inconclusive";
        String description = "About " + Math.round(bpm) + " BPM";
        if (variablePulse()) {
            description += " · typical local estimates " + Math.round(tempoLowBpm) + "–" + Math.round(tempoHighBpm) + " BPM";
        }
        return description;
    }

    private boolean variablePulse() { return tempoWindows > 1 && tempoHighBpm - tempoLowBpm > bpm * .08; }

    public String tonalEvidence() {
        if (key.isBlank()) return keyCandidates.isEmpty() ? "Inconclusive" : "Competing estimates · "
            + String.join(" or ", keyCandidates.stream().map(MusicalKey::display).toList());
        if (keyConfidence >= .65) return "Strong";
        return keyConfidence >= .25 ? "Moderate" : "Weak";
    }
}
