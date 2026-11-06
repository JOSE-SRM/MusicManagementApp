package sangitam.desktop.library;

import sangitam.desktop.model.AudioFeatures;
import java.util.*;

/** Bounded FFT/chroma and envelope analysis; all inputs are decoded locally. */
final class SpectralFeatures {
    private static final double[] MAJOR={6.35,2.23,3.48,2.33,4.38,4.09,2.52,5.19,2.39,3.66,2.29,2.88};
    private static final double[] MINOR={6.33,2.68,3.52,5.38,2.60,3.53,2.54,4.75,3.98,2.69,3.34,3.17};
    static AudioFeatures measure(float[] signal,int length,double rate,List<Double> envelope,int energy,int activity,long size,long modified){
        double squares=0,peak=0,weighted=0,powerSum=0;double[] chroma=new double[12];int fft=2048,frames=0;double[] re=new double[fft],im=new double[fft];
        for(int i=0;i<length;i++){squares+=signal[i]*signal[i];peak=Math.max(peak,Math.abs(signal[i]));}
        for(int start=0;start+fft<=length;start+=1024){
            if(Thread.currentThread().isInterrupted())return AudioFeatures.empty();
            for(int i=0;i<fft;i++){re[i]=signal[start+i]*(.5-.5*Math.cos(2*Math.PI*i/(fft-1)));im[i]=0;} transform(re,im);frames++;
            for(int i=1;i<fft/2;i++){double hz=i*rate/fft,p=re[i]*re[i]+im[i]*im[i];weighted+=hz*p;powerSum+=p;
                double before=re[i-1]*re[i-1]+im[i-1]*im[i-1],after=re[i+1]*re[i+1]+im[i+1]*im[i+1];
                if(hz>=55&&hz<=4000&&p>before&&p>=after&&p>1e-6){int midi=(int)Math.round(69+12*Math.log(hz/440)/Math.log(2));chroma[Math.floorMod(midi,12)]+=Math.sqrt(p);}}
        }
        double best=-2,runner=-2;int bestKey=0;boolean minor=false;
        for(int root=0;root<12;root++)for(int scale=0;scale<2;scale++){double score=correlation(chroma,scale==0?MAJOR:MINOR,root);if(score>best){runner=best;best=score;bestKey=root;minor=scale==1;}else runner=Math.max(runner,score);}
        double keyConfidence=Math.max(0,Math.min(1,(best-.25)*Math.max(0,best-runner)*8));
        String key=keyConfidence>.12?new String[]{"C","C♯","D","E♭","E","F","F♯","G","A♭","A","B♭","B"}[bestKey]:"";
        double[] flux=new double[envelope.size()];for(int i=1;i<flux.length;i++)flux[i]=Math.max(0,envelope.get(i)-envelope.get(i-1));
        double bestTempo=0,tempoConfidence=0;for(int lag=6;lag<=30;lag++){double a=0,b=0,c=0;for(int i=lag;i<flux.length;i++){a+=flux[i]*flux[i-lag];b+=flux[i]*flux[i];c+=flux[i-lag]*flux[i-lag];}double score=a/Math.sqrt(Math.max(1e-20,b*c));if(score>tempoConfidence+.005){tempoConfidence=score;bestTempo=1200d/lag;}}
        if(tempoConfidence<.2||envelope.size()<80)bestTempo=0;
        double rms=Math.sqrt(squares/Math.max(1,length)),rmsDb=20*Math.log10(Math.max(1e-6,rms)),peakDb=20*Math.log10(Math.max(1e-6,peak));
        var sorted=new ArrayList<>(envelope);Collections.sort(sorted);double low=sorted.isEmpty()?0:sorted.get(sorted.size()/10),high=sorted.isEmpty()?0:sorted.get(Math.min(sorted.size()-1,sorted.size()*9/10));
        double dynamics=Math.max(0,20*Math.log10(Math.max(1e-5,high)/Math.max(1e-5,low)));
        int arousal=bound(energy*.7+activity*.2+(bestTempo>0?(bestTempo-60)/2:energy)*.1);
        int valence=bound(50+(key.isBlank()?0:(minor?-22:22))+(bestTempo>0?(bestTempo-110)*.15:0));
        String mood=rms<.001?"Unknown":arousal<34?"Calm":valence>60?"Happy":valence<40?(arousal>68?"Tense":"Sad"):arousal>68?"Energetic":"Balanced";
        double confidence=rms<.001?0:Math.min(.85,.2+Math.min(length/rate,90)/300+keyConfidence*.2+tempoConfidence*.15);
        List<Integer> curve=new ArrayList<>();for(int block=0;block<12;block++){int a=block*envelope.size()/12,b=(block+1)*envelope.size()/12;double avg=0;for(int i=a;i<b;i++)avg+=envelope.get(i);curve.add(bound((20*Math.log10(Math.max(1e-6,avg/Math.max(1,b-a)))+50)*2));}
        return new AudioFeatures(AudioFeatures.VERSION,size,modified,length/rate,rmsDb,peakDb,dynamics,powerSum==0?0:weighted/powerSum,bestTempo,tempoConfidence,key,key.isBlank()?"":minor?"minor":"major",keyConfidence,arousal,valence,mood,confidence,curve);
    }
    private static int bound(double value){return(int)Math.max(0,Math.min(100,Math.round(value)));}
    private static double correlation(double[] a,double[] b,int root){double ma=Arrays.stream(a).average().orElse(0),mb=Arrays.stream(b).average().orElse(0),cross=0,aa=0,bb=0;for(int i=0;i<12;i++){double x=a[(i+root)%12]-ma,y=b[i]-mb;cross+=x*y;aa+=x*x;bb+=y*y;}return cross/Math.sqrt(Math.max(1e-20,aa*bb));}
    private static void transform(double[] re,double[] im){int n=re.length;for(int i=1,j=0;i<n;i++){int bit=n>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double t=re[i];re[i]=re[j];re[j]=t;}}
        for(int len=2;len<=n;len*=2){double angle=-2*Math.PI/len;for(int start=0;start<n;start+=len){double wr=1,wi=0;for(int j=0;j<len/2;j++){int a=start+j,b=a+len/2;double tr=wr*re[b]-wi*im[b],ti=wr*im[b]+wi*re[b];re[b]=re[a]-tr;im[b]=im[a]-ti;re[a]+=tr;im[a]+=ti;double nr=wr*Math.cos(angle)-wi*Math.sin(angle);wi=wr*Math.sin(angle)+wi*Math.cos(angle);wr=nr;}}}}
}
