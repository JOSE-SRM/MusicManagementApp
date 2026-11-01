package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.Component;
import java.nio.file.Path;
import java.util.Map;

/** One readable Swing list style for track metadata and analysis tags. */
final class TrackCellRenderer extends DefaultListCellRenderer {
    private static final long serialVersionUID = 1L;
    private Map<Path, TrackTags> tags = Map.of();

    void setTags(Map<Path, TrackTags> tags) { this.tags = Map.copyOf(tags); }

    @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean selected, boolean focused) {
        super.getListCellRendererComponent(list, value, index, selected, focused);
        if (value instanceof Track track) {
            TrackTags profile = tags.getOrDefault(track.path(), TrackTags.pending());
            String genre = track.genre().equalsIgnoreCase("Unknown genre") ? "Genre untagged" : track.genre();
            setText("<html><b>" + escape(track.title()) + "</b> — " + escape(track.artist())
                + "<br><small>" + escape(profile.energyLabel()) + " · "
                + escape(profile.activityLabel()) + " · " + escape(genre) + "</small></html>");
            setBorder(Theme.padding(5, 11, 5, 11));
        }
        return this;
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
