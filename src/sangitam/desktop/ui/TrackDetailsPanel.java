package sangitam.desktop.ui;

import sangitam.desktop.library.TagVocabulary;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata;
import sangitam.desktop.model.TrackMetadata.Field;
import sangitam.desktop.model.TrackTags;
import sangitam.desktop.storage.LibraryStore.DetailChanges;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Function;

/** A headless-testable editor; the owner handles confirmation and background persistence. */
final class TrackDetailsPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final List<Track> tracks;
    private final boolean group;
    private final JTextField title, artist, album, genre;
    private final Map<JTextField, String> initial = new IdentityHashMap<>();
    private final Map<Field, JTextField> metadata = new EnumMap<>(Field.class);
    private final Map<Field, JCheckBox> apply = new EnumMap<>(Field.class);
    private final JTextArea tags = new JTextArea(3, 38);
    private final String originalTags;
    private final JCheckBox replaceTags = new JCheckBox("Replace tags on all selected tracks");
    private final JCheckBox ratings = new JCheckBox("Set energy and movement for selected tracks");
    private final JSlider energy, movement;

    TrackDetailsPanel(List<Track> tracks, boolean group, TrackTags profile) {
        super(new BorderLayout());
        this.tracks = List.copyOf(tracks);
        this.group = group;
        Track first = tracks.getFirst();
        title = text(first.title(), "Title");
        artist = text(common(Track::artist), "Artist");
        album = text(common(Track::album), "Album");
        genre = text(common(Track::genre), "Genre");
        energy = new JSlider(0, 100, profile.energy());
        movement = new JSlider(0, 100, profile.activity());
        JPanel basic = form();
        if (!group) field(basic, "Title", title);
        field(basic, "Artist", artist); field(basic, "Album", album); field(basic, "Genre", genre);
        basic.add(ratings);
        field(basic, "Energy", energy); field(basic, "Movement", movement);
        for (JSlider slider : List.of(energy, movement)) {
            slider.setEnabled(false); slider.setMajorTickSpacing(25); slider.setPaintLabels(true);
        }
        ratings.addActionListener(event -> { energy.setEnabled(ratings.isSelected()); movement.setEnabled(ratings.isSelected()); });
        if (group) basic.add(UiFactory.label("Blank basic fields keep each track's value.", Theme.SMALL, Theme.TEXT_MUTED));

        JPanel classical = form();
        classical.add(UiFactory.label(group ? "Select Apply for each change. An applied blank clears that field." : "File tags and suggested hints can be corrected here.", Theme.SMALL, Theme.TEXT_MUTED));
        for (Field role : Field.values()) {
            JTextField input = text(common(track -> track.metadata().text(role)), role.toString());
            metadata.put(role, input);
            JCheckBox checkbox = new JCheckBox("Apply " + role);
            apply.put(role, checkbox);
            var value = first.metadata().fields().get(role);
            if (!group && value != null) input.setToolTipText(switch (value.origin()) {
                case EMBEDDED -> "Read from the audio file";
                case TITLE_HINT -> "Suggested from the title; you can correct it";
                case FOLDER_HINT -> "Suggested from the folder name; you can correct it";
                case USER -> "Edited in your library";
            });
            if (group) { classical.add(checkbox); input.setEnabled(false); checkbox.addActionListener(event -> input.setEnabled(checkbox.isSelected())); }
            else classical.add(UiFactory.label(role.toString(), Theme.SMALL, Theme.TEXT));
            classical.add(input);
        }

        originalTags = common(track -> String.join(", ", track.metadata().tags()));
        tags.setText(originalTags); tags.setLineWrap(true); tags.setWrapStyleWord(true);
        tags.getAccessibleContext().setAccessibleName("Custom tags");
        JPanel tagging = new JPanel(new BorderLayout(0, 10));
        tagging.setOpaque(false);
        JPanel tagTop = form();
        tagTop.add(UiFactory.label("Comma-separated tags. Suggestions are optional; write your own labels too.", Theme.SMALL, Theme.TEXT_MUTED));
        if (group) tagTop.add(replaceTags);
        tagTop.add(new JScrollPane(tags));
        tagging.add(tagTop, BorderLayout.NORTH);
        JTextField filter = new JTextField();
        filter.getAccessibleContext().setAccessibleName("Find tag suggestion");
        filter.setToolTipText("Find a tag suggestion");
        DefaultListModel<String> suggested = new DefaultListModel<>();
        suggested.addAll(TagVocabulary.ALL);
        JList<String> suggestions = new JList<>(suggested);
        suggestions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel picker = new JPanel(new BorderLayout(0, 6)); picker.setOpaque(false);
        picker.add(filter, BorderLayout.NORTH); picker.add(new JScrollPane(suggestions), BorderLayout.CENTER);
        JButton add = UiFactory.button("Add selected tag", null, false);
        add.addActionListener(event -> {
            String selected = suggestions.getSelectedValue();
            if (selected == null) return;
            try {
                var values = new ArrayList<>(TrackMetadata.parseTags(tags.getText())); values.add(selected);
                tags.setText(String.join(", ", TrackMetadata.normalizeTags(values)));
                if (group) replaceTags.setSelected(true);
            } catch (IllegalArgumentException error) {
                JOptionPane.showMessageDialog(this, error.getMessage(), "Check tags", JOptionPane.ERROR_MESSAGE);
            }
        });
        picker.add(add, BorderLayout.SOUTH); tagging.add(picker, BorderLayout.CENTER);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            private void refresh() { suggested.clear(); suggested.addAll(TagVocabulary.matching(filter.getText())); }
            public void insertUpdate(DocumentEvent e) { refresh(); } public void removeUpdate(DocumentEvent e) { refresh(); } public void changedUpdate(DocumentEvent e) { refresh(); }
        });

        JTextArea technical = new JTextArea(); technical.setEditable(false); technical.setLineWrap(true); technical.setWrapStyleWord(true);
        technical.getAccessibleContext().setAccessibleName("Audio file details");
        technical.setFont(Theme.BODY);
        StringBuilder details = new StringBuilder("These are library edits. The original audio file is not rewritten.\n\n");
        for (Track track : tracks.stream().limit(20).toList()) {
            TrackMetadata data = track.metadata();
            details.append(track.title()).append("\n").append(track.path()).append("\n").append(track.format()).append(" · ").append(track.durationLabel());
            if (data.bitrateKbps() > 0) details.append(" · ").append(data.bitrateKbps()).append(" kbps");
            if (data.sampleRateHz() > 0) details.append(" · ").append(data.sampleRateHz()).append(" Hz");
            if (data.bitsPerSample() > 0) details.append(" · ").append(data.bitsPerSample()).append(" bit");
            if (!data.channels().isEmpty()) details.append(" · ").append(data.channels());
            details.append(data.embeddedArtwork() ? "\nEmbedded artwork present" : "\nNo embedded artwork indexed").append("\n\n");
        }
        if (tracks.size() > 20) details.append("Showing technical details for the first 20 tracks.");
        technical.setText(details.toString()); technical.setCaretPosition(0);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Basics", new JScrollPane(basic)); tabs.addTab("Classical / credits", new JScrollPane(classical));
        tabs.addTab("Tags", tagging); tabs.addTab("Audio file", new JScrollPane(technical));
        var features=profile.features();
        JTextArea analysis=new JTextArea(); analysis.setEditable(false); analysis.setLineWrap(true); analysis.setWrapStyleWord(true); analysis.setFont(Theme.BODY);
        analysis.getAccessibleContext().setAccessibleName("Local audio analysis");
        analysis.setText((group ? "Analysis of the first selected recording\n\n" : "") + features.summary()
            + (features.version()>0 ? String.format(Locale.ROOT,"\n\nDecoded passage: %.1f seconds\nRMS: %.1f dBFS · Peak: %.1f dBFS\nEnvelope dynamic range (10–90%%): %.1f dB\nSpectral centroid: %.0f Hz\nArousal: %d/100 · Valence: %d/100\nTempo evidence: %.0f%% · Key evidence: %.0f%%",features.sampledSeconds(),features.rmsDb(),features.peakDb(),features.dynamicRangeDb(),features.centroidHz(),features.arousal(),features.valence(),features.tempoConfidence()*100,features.keyConfidence()*100) : "\n\nUse Deep analyse library on Listen Now to measure supported local recordings.")
            + "\n\nThese are passage-based estimates. Major/minor key and tempo do not determine emotion. Instrumentation comes from editable metadata; it is not recognized from the audio. Energy and movement can be corrected in Basics. Unsupported decoders leave analysis pending.");
        analysis.setCaretPosition(0); tabs.addTab("Analysis",new JScrollPane(analysis));
        tabs.setPreferredSize(new Dimension(640, 450)); add(tabs);
    }
    DetailChanges changes() {
        var changes = new EnumMap<Field, String>(Field.class);
        metadata.forEach((field, input) -> {
            if (group ? apply.get(field).isSelected() : !input.getText().strip().equals(initial.get(input))) changes.put(field, input.getText().strip());
        });
        Set<String> newTags = (group ? replaceTags.isSelected() : !tags.getText().equals(originalTags)) ? TrackMetadata.parseTags(tags.getText()) : null;
        return new DetailChanges(group ? null : changed(title, null), changed(artist, group ? null : "Unknown artist"),
            changed(album, group ? null : "Unknown album"), changed(genre, group ? null : "Unknown genre"),
            ratings.isSelected() ? energy.getValue() : null, ratings.isSelected() ? movement.getValue() : null, changes, newTags);
    }
    private String changed(JTextField input, String blank) {
        String value = input.getText().strip();
        return value.equals(initial.get(input)) ? null : value.isEmpty() ? blank : value;
    }
    private String common(Function<Track, String> value) {
        String first = value.apply(tracks.getFirst());
        return tracks.stream().allMatch(track -> value.apply(track).equals(first)) ? first : "";
    }
    private JTextField text(String value, String name) {
        JTextField input = new JTextField(value, 32); initial.put(input, value);
        input.getAccessibleContext().setAccessibleName(name); return input;
    }
    private static JPanel form() { JPanel panel = new JPanel(new GridLayout(0, 1, 0, 6)); panel.setOpaque(false); panel.setBorder(Theme.padding(10, 10, 10, 10)); return panel; }
    private static void field(JPanel panel, String label, JComponent input) {
        JLabel name = UiFactory.label(label, Theme.SMALL, Theme.TEXT); name.setLabelFor(input); panel.add(name); panel.add(input);
    }
}
