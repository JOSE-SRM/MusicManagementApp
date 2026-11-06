package sangitam.desktop.ui;

import sangitam.desktop.model.*;
import sangitam.desktop.storage.SmartPlaylistStore.Preview;
import java.nio.file.Path;
import java.util.List;

/** Minimal callbacks for headless library interaction checks. */
class LibraryTestActions implements LibraryPanel.Actions {
    public void importMusic(long folder) { } public void rescan() { } public void play(List<Track> tracks) { }
    public void queue(List<Track> tracks) { } public void editDetails(List<Track> tracks, boolean group) { } public void restoreDetails(List<Track> tracks) { }
    public void remove(List<Path> paths, Path scope) { } public void open(LibraryEntry entry) { } public void organize(String message, LibraryPanel.CollectionEdit edit) { }
    public void deletePlaylist(long id) { } public void entriesChanged() { } public Preview previewSmart(SmartPlaylistRule rule) { return new Preview(0, 0, List.of()); }
}
