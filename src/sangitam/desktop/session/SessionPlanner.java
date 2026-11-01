package sangitam.desktop.session;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic, explainable song selection. It uses measured traits and supplied genre tags, not AI mood guesses. */
public final class SessionPlanner {
    public enum Intent { FOCUS, EXPLORE, UNWIND }
    public record Request(Intent intent, int energy, int minutes, boolean familiarFirst) {
        public Request {
            energy = Math.max(0, Math.min(100, energy));
            minutes = Math.max(1, minutes);
        }
    }
    public record Choice(Track track, String reason) { }
    public record Plan(List<Choice> choices, int requestedMinutes, int estimatedSeconds) {
        public Plan { choices = List.copyOf(choices); }
    }

    public Plan build(Request request, List<Track> tracks, Map<Path, TrackTags> tags,
                      Map<Path, Integer> playCounts) {
        List<Choice> chosen = new ArrayList<>();
        Set<Path> used = new HashSet<>();
        Set<String> artists = new HashSet<>();
        Set<String> genres = new HashSet<>();
        int total = 0;
        int target = request.minutes() * 60;

        while (used.size() < tracks.size() && total < target * 0.95) {
            int remaining = target - total;
            Track best = tracks.stream()
                .filter(track -> !used.contains(track.path()))
                .filter(track -> duration(track) <= remaining + Math.max(75, target / 12))
                .max(Comparator.comparingInt((Track track) -> score(request, track,
                    tags.getOrDefault(track.path(), TrackTags.pending()),
                    playCounts.getOrDefault(track.path(), 0), artists, genres))
                    .thenComparing(track -> track.title().toLowerCase(Locale.ROOT), Comparator.reverseOrder()))
                .orElse(null);
            if (best == null) break;
            TrackTags profile = tags.getOrDefault(best.path(), TrackTags.pending());
            chosen.add(new Choice(best, explain(request, best, profile,
                playCounts.getOrDefault(best.path(), 0))));
            used.add(best.path());
            artists.add(best.artist().toLowerCase(Locale.ROOT));
            genres.add(best.genre().toLowerCase(Locale.ROOT));
            total += duration(best);
        }
        return new Plan(chosen, request.minutes(), total);
    }

    private int score(Request request, Track track, TrackTags tags, int plays,
                      Set<String> artists, Set<String> genres) {
        int targetActivity = switch (request.intent()) {
            case FOCUS -> 25;
            case UNWIND -> 18;
            case EXPLORE -> 60;
        };
        int score = tags.analyzed() ? 90 - Math.abs(tags.energy() - request.energy())
            - Math.abs(tags.activity() - targetActivity) / 3 : 35;
        String genre = track.genre().toLowerCase(Locale.ROOT);
        if (request.intent() == Intent.FOCUS && hasAny(genre, "ambient", "classical", "instrumental", "soundtrack")) score += 24;
        if (request.intent() == Intent.UNWIND && hasAny(genre, "ambient", "acoustic", "jazz", "classical", "chill")) score += 24;
        if (request.intent() == Intent.EXPLORE && !genres.contains(genre)) score += 20;
        if (artists.contains(track.artist().toLowerCase(Locale.ROOT))) score -= 24;
        score += request.familiarFirst() ? Math.min(plays * 8, 24) : (plays == 0 ? 10 : 0);
        return score;
    }

    private String explain(Request request, Track track, TrackTags tags, int plays) {
        List<String> facts = new ArrayList<>();
        if (tags.analyzed()) {
            facts.add(tags.energyLabel().toLowerCase(Locale.ROOT) + " energy " + tags.energy()
                + "/100 (target " + request.energy() + ")");
            facts.add(tags.activityLabel().toLowerCase(Locale.ROOT));
        } else {
            facts.add("audio traits unavailable");
        }
        if (!track.genre().equalsIgnoreCase("Unknown genre")) facts.add(track.genre() + " genre tag");
        if (request.familiarFirst() && plays > 0) facts.add("played " + plays + " times");
        if (!request.familiarFirst() && plays == 0) facts.add("new to your listening history");
        return String.join(" · ", facts);
    }

    private boolean hasAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private int duration(Track track) {
        return track.durationSeconds() > 0 ? Math.max(1, (int) Math.round(track.durationSeconds())) : 180;
    }
}
