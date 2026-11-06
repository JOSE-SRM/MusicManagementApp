package sangitam.desktop.model;

import java.util.List;

/** Versioned local measurements and transparent heuristic mood hints, not emotion recognition. */
public record AudioFeatures(int version,long size,long modified,double sampledSeconds,double rmsDb,double peakDb,double dynamicRangeDb,
                            double centroidHz,double bpm,double tempoConfidence,String key,String mode,double keyConfidence,
                            int arousal,int valence,String mood,double confidence,List<Integer> energyCurve) {
    public static final int VERSION=1;
    public AudioFeatures { energyCurve=List.copyOf(energyCurve); }
    public static AudioFeatures empty(){return new AudioFeatures(0,-1,-1,0,-120,-120,0,0,0,0,"","",0,50,50,"Unknown",0,List.of());}
    public boolean current(long size,long modified){return version==VERSION&&this.size==size&&this.modified==modified;}
    public String summary(){return version==0?"Analysis pending":mood+" estimate · "+(bpm>0?Math.round(bpm)+" BPM":"tempo uncertain")+(key.isBlank()?"":" · "+key+" "+mode)+" · confidence "+Math.round(confidence*100)+"%";}
}
