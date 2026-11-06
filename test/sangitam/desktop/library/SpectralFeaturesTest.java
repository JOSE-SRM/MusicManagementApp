package sangitam.desktop.library;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpectralFeaturesTest {
    @Test void periodicChordHasMeasuredTempoSpectrumDynamicsAndTonalEvidence() {
        int rate=11025;float[] signal=new float[rate*20];var envelope=new ArrayList<Double>();
        for(int i=0;i<signal.length;i++){double t=i/(double)rate,amp=(t%.5)<.15?.4:.02;signal[i]=(float)(amp*(Math.sin(2*Math.PI*261.63*t)+Math.sin(2*Math.PI*329.63*t)+Math.sin(2*Math.PI*392*t))/3);}
        for(int i=0;i<400;i++)envelope.add(i%10<3?.3:.01);
        var f=SpectralFeatures.measure(signal,signal.length,rate,envelope,70,60,1,2);
        assertEquals(120,f.bpm(),1);assertTrue(f.tempoConfidence()>.8);assertTrue(f.centroidHz()>200&&f.centroidHz()<600);assertTrue(f.rmsDb()<0);assertTrue(f.dynamicRangeDb()>15);assertEquals(12,f.energyCurve().size());assertFalse(f.key().isEmpty());
    }
    @Test void silenceDoesNotBecomeConfidentHappyMusic() {
        var f=SpectralFeatures.measure(new float[22050],22050,11025,Collections.nCopies(40,0d),0,0,1,2);
        assertEquals("Unknown",f.mood());assertEquals(0,f.confidence());assertEquals(0,f.bpm());assertTrue(f.key().isEmpty());
    }
}
