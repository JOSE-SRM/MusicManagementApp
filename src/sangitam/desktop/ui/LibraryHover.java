package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata.Field;

import java.util.stream.Collectors;

/** Builds tooltips only for the hovered row using its existing snapshot. */
final class LibraryHover {
    private LibraryHover() { }

    static String describe(LibraryEntry entry, LibraryService.Catalog catalog, boolean pinned) {
        StringBuilder text = new StringBuilder("<html><div width='295'><b>")
            .append(escape(entry.name())).append("</b>");
        if (pinned) text.append(" · Pinned");
        if (entry.kind() == sangitam.desktop.model.LibraryCollections.Kind.SONG) {
            Track track = entry.tracks().getFirst();
            line(text, track.artist());
            line(text, track.album() + " · " + track.durationLabel() + " · " + track.format());
            line(text, track.genre() + " · " + catalog.playsFor(track) + " plays");
            String composer = track.metadata().text(Field.COMPOSER);
            if (!composer.isBlank() && !composer.equalsIgnoreCase(track.artist())) line(text, "Composer · " + composer);
            if (catalog.unavailable().contains(track.path())) line(text, "File unavailable");
        } else {
            line(text, entry.tracks().size() + " tracks · " + entry.plays() + " plays");
            if (!entry.detail().isBlank() && !entry.detail().equals(entry.tracks().size() + " tracks")) line(text, entry.detail());
            String examples = entry.tracks().stream().limit(3).map(Track::title).collect(Collectors.joining(" · "));
            if (!examples.isBlank()) line(text, "Includes " + examples);
        }
        return text.append("</div></html>").toString();
    }

    private static void line(StringBuilder text, String line) {
        text.append("<br>").append(escape(line));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
