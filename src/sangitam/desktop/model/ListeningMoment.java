package sangitam.desktop.model;

import java.nio.file.Path;
import java.util.List;

/** A locally saved listening session with its title, note, chosen room, and ordered tracks. */
public record ListeningMoment(long id, String title, String note, String room,
                              long createdAtMillis, List<Path> tracks) {
    public ListeningMoment {
        title = title == null || title.isBlank() ? "Untitled moment" : title.strip();
        note = note == null ? "" : note.strip();
        room = room == null || room.isBlank() ? "Quiet Orbit" : room.strip();
        tracks = List.copyOf(tracks);
    }
}
