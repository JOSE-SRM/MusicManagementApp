package sangitam.desktop.session;

import sangitam.desktop.model.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** A recommendation context, not a claim about a listener's mental state. */
public record SessionContext(ZonedDateTime now,Map<Path,Integer> taste,List<Listen> history) {
    public record Listen(Path path,long at,double seconds) { }
    public record Hint(int energy,int valence,String explanation) { }
    public SessionContext {taste=Map.copyOf(taste);history=List.copyOf(history);}
    public Hint hint(Map<Path,TrackTags> tags){
        double energy=0,valence=0,weight=0;long current=now.toInstant().toEpochMilli(),last=current;int recent=0;
        for(var listen:history.stream().sorted(Comparator.comparingLong(Listen::at).reversed()).toList()){
            if(listen.at()>current)continue; if(current-listen.at()>90*60000L||last-listen.at()>30*60000L)break;
            last=listen.at();var t=tags.get(listen.path());if(t==null||!t.analyzed()||taste.getOrDefault(listen.path(),0)==-2)continue;
            double w=Math.min(listen.seconds(),120)*Math.exp(-(current-listen.at())/1800000d);energy+=t.energy()*w;valence+=t.features().valence()*w;weight+=w;recent++;
        }
        if(weight>0)return new Hint((int)(energy/weight),(int)(valence/weight),"Recent listening: "+recent+" playback windows within the last 90 minutes; older sessions excluded. Editable suggestion.");
        // Calendar similarity only learns from this device's own listening. No month/day mood stereotypes.
        for(var listen:history){if(listen.at()>current||current-listen.at()>180L*86400000||!Double.isFinite(listen.seconds())||listen.seconds()<=0)continue;var t=tags.get(listen.path());if(t==null||!t.analyzed()||taste.getOrDefault(listen.path(),0)==-2)continue;var at=Instant.ofEpochMilli(listen.at()).atZone(now.getZone());
            int hour=Math.abs(at.getHour()-now.getHour());hour=Math.min(hour,24-hour);double similarity=hour<=2?1:.05;
            if(at.getDayOfWeek()==now.getDayOfWeek())similarity+=.3;if(at.getDayOfMonth()==now.getDayOfMonth())similarity+=.15;if(at.getMonth()==now.getMonth())similarity+=.15;
            double w=Math.min(120,listen.seconds())*similarity;energy+=t.energy()*w;valence+=t.features().valence()*w;weight+=w;
        }
        return weight>0?new Hint((int)(energy/weight),(int)(valence/weight),"Learned from your own listening at similar local hours/days/months; no fresh listening session. Editable suggestion."):
            new Hint(now.getHour()<7||now.getHour()>21?30:50,50,"No recent measured listening. Local time supplies a gentle energy default; date alone does not infer mood.");
    }
    public Map<Path,Double> affinities(List<Track> library){
        if(taste.isEmpty())return Map.of();
        var artists=new HashMap<String,Double>();var genres=new HashMap<String,Double>();
        for(Track track:library){int rating=taste.getOrDefault(track.path(),0);if(rating==0)continue;for(var credit:ArtistCredits.of(track))artists.merge(ArtistCredits.key(credit.name()),rating*2d,Double::sum);if(!track.genre().equalsIgnoreCase("Unknown genre"))genres.merge(track.genre().toLowerCase(Locale.ROOT),rating*.3,Double::sum);}
        var result=new HashMap<Path,Double>();
        for(Track track:library){double related=ArtistCredits.of(track).stream().mapToDouble(c->artists.getOrDefault(ArtistCredits.key(c.name()),0d)).max().orElse(0);double score=taste.getOrDefault(track.path(),0)*20+related+genres.getOrDefault(track.genre().toLowerCase(Locale.ROOT),0d);result.put(track.path(),Math.max(-60,Math.min(60,score)));}
        return Map.copyOf(result);
    }
}
