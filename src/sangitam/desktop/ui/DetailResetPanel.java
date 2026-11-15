package sangitam.desktop.ui;

import sangitam.desktop.model.DetailReset;
import sangitam.desktop.model.TrackMetadata.Field;
import javax.swing.*;
import java.awt.*;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;

/** Explicit selection avoids discarding unrelated corrections when following file tags again. */
final class DetailResetPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final Map<DetailReset.Basic, JCheckBox> basic = new EnumMap<>(DetailReset.Basic.class);
    private final Map<Field, JCheckBox> credits = new EnumMap<>(Field.class);
    private final JCheckBox tags = new JCheckBox("Custom tags");

    DetailResetPanel(int count) {
        super(new BorderLayout(0, 12));
        JPanel heading = new JPanel(new GridLayout(0, 1, 0, 5)); heading.setOpaque(false);
        heading.add(UiFactory.label("Restore selected details for " + count + " tracks", Theme.BODY_BOLD, Theme.TEXT));
        heading.add(UiFactory.label("Use file tags and the usual filename/folder fallbacks. Other corrections stay in place.", Theme.SMALL, Theme.TEXT_MUTED));
        heading.add(UiFactory.label("Unreadable files keep their corrections. Energy and movement ratings are unaffected.", Theme.SMALL, Theme.TEXT_MUTED));
        add(heading, BorderLayout.NORTH);
        JPanel fields = new JPanel(new GridLayout(0, 2, 12, 4)); fields.setOpaque(false);
        for (DetailReset.Basic field : DetailReset.Basic.values()) {
            JCheckBox box = new JCheckBox(field.toString()); basic.put(field, box); fields.add(box);
        }
        for (Field field : Field.values()) {
            JCheckBox box = new JCheckBox(field.toString()); credits.put(field, box); fields.add(box);
        }
        fields.add(tags); add(fields, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); buttons.setOpaque(false);
        JButton all = UiFactory.button("Select all details", null, false);
        JButton clear = UiFactory.button("Clear selection", null, false);
        all.addActionListener(event -> selectAll(true)); clear.addActionListener(event -> selectAll(false));
        buttons.add(all); buttons.add(clear); add(buttons, BorderLayout.SOUTH);
    }
    DetailReset selection() {
        var selectedBasic = new HashSet<DetailReset.Basic>(); basic.forEach((field, box) -> { if (box.isSelected()) selectedBasic.add(field); });
        var selectedCredits = new HashSet<Field>(); credits.forEach((field, box) -> { if (box.isSelected()) selectedCredits.add(field); });
        return new DetailReset(selectedBasic, selectedCredits, tags.isSelected());
    }
    private void selectAll(boolean selected) {
        basic.values().forEach(box -> box.setSelected(selected)); credits.values().forEach(box -> box.setSelected(selected)); tags.setSelected(selected);
    }
}
