package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Immutable path/track index. It contains no Swing nodes and never touches the filesystem. */
final class LocationIndex {
    static final int PAGE_SIZE = 100;
    static final Comparator<Path> PATH_ORDER = Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(Comparator.naturalOrder());
    record Page(List<Object> children, int offset, int total) {
        Page { children = List.copyOf(children); }
        boolean hasNext() { return offset + children.size() < total; }
    }
    private final List<Path> roots;
    private final Map<Path, List<Path>> folders;
    private final Map<Path, List<Track>> songs;
    private final Map<Path, Integer> counts;
    private final int matches;
    private LocationIndex(List<Path> roots, Map<Path, List<Path>> folders, Map<Path, List<Track>> songs,
                          Map<Path, Integer> counts, int matches) {
        this.roots = List.copyOf(roots); this.folders = Map.copyOf(folders); this.songs = Map.copyOf(songs);
        this.counts = Map.copyOf(counts); this.matches = matches;
    }
    int matches() { return matches; }
    int matches(Path folder) { return folder == null ? matches : counts.getOrDefault(folder, 0); }
    static LocationIndex build(List<Track> input, String text) {
        String query = LibraryEntry.lower(text.strip());
        var songs = new HashMap<Path, List<Track>>(); var common = new HashMap<Path, Path>(); int matches = 0;
        for (Track track : input) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (!query.isEmpty() && !LibraryEntry.lower(track + " " + track.album() + " " + track.path() + " " + track.metadata().searchableText()).contains(query)) continue;
            Path folder = track.path().getParent(); if (folder == null) continue;
            songs.computeIfAbsent(folder, ignored -> new ArrayList<>()).add(track); matches++;
            common.merge(folder.getRoot(), folder, (a, b) -> { while (!b.startsWith(a)) a = a.getParent(); return a; });
        }
        var children = new HashMap<Path, SortedSet<Path>>();
        var counts = new HashMap<Path, Integer>();
        for (Path folder : songs.keySet()) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            Path top = common.get(folder.getRoot());
            for (Path at = folder; ; at = at.getParent()) {
                counts.merge(at, songs.get(folder).size(), Integer::sum);
                children.computeIfAbsent(at, ignored -> new TreeSet<>(PATH_ORDER));
                if (at.equals(top)) break;
                children.computeIfAbsent(at.getParent(), ignored -> new TreeSet<>(PATH_ORDER)).add(at);
            }
        }
        var folders = new HashMap<Path, List<Path>>(); children.forEach((path, list) -> folders.put(path, List.copyOf(list)));
        Comparator<Track> trackOrder = Comparator.comparingInt(LocationIndex::movementOrder)
            .thenComparing(Track::title, String.CASE_INSENSITIVE_ORDER).thenComparing(track -> track.path().toString());
        songs.replaceAll((path, list) -> { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); list.sort(trackOrder); return List.copyOf(list); });
        return new LocationIndex(common.values().stream().sorted(PATH_ORDER).toList(), folders, songs, counts, matches);
    }

    private static int movementOrder(Track track) {
        String filename = track.path().getFileName().toString();
        if (filename.length() > 5 && filename.startsWith(" - ", 2)
            && filename.charAt(0) >= '0' && filename.charAt(0) <= '9'
            && filename.charAt(1) >= '0' && filename.charAt(1) <= '9')
            return Integer.parseInt(filename.substring(0, 2));
        return Integer.MAX_VALUE;
    }
    Page page(Path parent, int requested) {
        if (requested < 0) throw new IllegalArgumentException("Invalid location page");
        List<Path> directories = parent == null ? roots : folders.getOrDefault(parent, List.of());
        List<Track> tracks = parent == null ? List.of() : songs.getOrDefault(parent, List.of());
        int total = directories.size() + tracks.size(), offset = total == 0 ? 0 : Math.min(requested, ((total - 1) / PAGE_SIZE) * PAGE_SIZE);
        var items = new ArrayList<Object>();
        for (int at = offset; at < Math.min(total, offset + PAGE_SIZE); at++) items.add(at < directories.size() ? directories.get(at) : tracks.get(at - directories.size()));
        return new Page(items, offset, total);
    }
    /** Page containing the immediate child toward a restored selection, even if past page one. */
    int offsetToward(Path parent, Path selection) {
        List<Path> directories = parent == null ? roots : folders.getOrDefault(parent, List.of());
        for (int i = 0; i < directories.size(); i++) if (selection.startsWith(directories.get(i))) return i / PAGE_SIZE * PAGE_SIZE;
        List<Track> tracks = parent == null ? List.of() : songs.getOrDefault(parent, List.of());
        for (int i = 0; i < tracks.size(); i++) if (selection.equals(tracks.get(i).path())) return (directories.size() + i) / PAGE_SIZE * PAGE_SIZE;
        return 0;
    }
}
