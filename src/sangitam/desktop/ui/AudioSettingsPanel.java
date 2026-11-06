package sangitam.desktop.ui;

import sangitam.desktop.playback.AudioSettings;
import sangitam.desktop.playback.AudioStatus;
import sangitam.desktop.playback.PlayerService;
import sangitam.desktop.storage.AudioSettingsStore;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Live EQ preview with transactional Save/Cancel; file I/O never runs on Swing's event thread. */
final class AudioSettingsPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final AudioSettings original;
    private final Runnable close;
    private final PlayerService player;
    private final AudioSettingsStore store;
    private final Consumer<AudioSettings> committed;
    private final JCheckBox enabled = new JCheckBox("Enable equalizer");
    private final JSlider[] bands = new JSlider[10];
    private final JLabel[] gains = new JLabel[10];
    private final JSlider preamp = new JSlider(-12, 0, 0);
    private final JComboBox<AudioSettings.ReplayGain> replay = new JComboBox<>(AudioSettings.ReplayGain.values());
    private final JComboBox<String> presets = new JComboBox<>(new String[] { "Custom", "Flat", "Bass lift", "Voice", "Soft treble" });
    private final JLabel preampValue = UiFactory.label("", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel backend = UiFactory.label("", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel status = UiFactory.label("Preview EQ while music plays. Cancel restores your saved sound.", Theme.SMALL, Theme.TEXT_MUTED);
    private final JButton save = UiFactory.button("Save", null, true);
    private final JButton cancel = UiFactory.button("Cancel", null, false);
    private final LiveAudioPreview preview;
    private final Timer backendTimer = new Timer(500, event -> updateBackend());
    private boolean updating;
    private boolean saving;
    private boolean saved;

    AudioSettingsPanel(AudioSettings initial, PlayerService player,
                       AudioSettingsStore store, Consumer<AudioSettings> committed, Runnable close) {
        super(new BorderLayout()); this.close = close;
        this.original = initial;
        this.player = player;
        this.store = store;
        this.committed = committed;
        preview = new LiveAudioPreview(player::setAudioSettings);
        add(buildContent());
        load(initial);
        updateBackend();
        backendTimer.start();
    }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(Theme.BLACK);
        root.setBorder(Theme.padding(22, 24, 20, 24));
        JPanel header = column();
        header.add(UiFactory.label("Make it sound like you", Theme.TITLE, Theme.TEXT));
        header.add(Box.createVerticalStrut(6));
        header.add(backend);
        root.add(header, BorderLayout.NORTH);

        JPanel content = column();
        JPanel toolbar = new JPanel(new BorderLayout(16, 0));
        toolbar.setOpaque(false);
        enabled.setOpaque(false);
        enabled.setForeground(Theme.TEXT);
        enabled.addActionListener(event -> changed(false));
        toolbar.add(enabled, BorderLayout.WEST);
        JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        presetRow.setOpaque(false);
        presetRow.add(UiFactory.label("Preset", Theme.SMALL, Theme.TEXT_MUTED));
        presets.getAccessibleContext().setAccessibleName("Equalizer preset");
        presets.addActionListener(event -> selectPreset());
        presetRow.add(presets);
        toolbar.add(presetRow, BorderLayout.EAST);
        content.add(toolbar);
        content.add(Box.createVerticalStrut(14));

        JPanel equalizer = UiFactory.surface(new GridLayout(1, 10, 8, 0));
        equalizer.setBorder(Theme.padding(14, 10, 12, 10));
        for (int i = 0; i < bands.length; i++) {
            JPanel band = column();
            double frequency = AudioSettings.FREQUENCIES.get(i);
            String label = frequency >= 1000 ? (int) (frequency / 1000) + "k" : String.valueOf(Math.round(frequency));
            gains[i] = UiFactory.label("0 dB", Theme.SMALL, Theme.TEXT);
            gains[i].setAlignmentX(Component.CENTER_ALIGNMENT);
            band.add(gains[i]);
            bands[i] = new JSlider(JSlider.VERTICAL, -12, 12, 0);
            bands[i].setOpaque(false);
            bands[i].setForeground(Theme.BLUE);
            bands[i].setUI(new RoundSliderUI(bands[i]));
            bands[i].setPreferredSize(new Dimension(40, 180));
            bands[i].setMajorTickSpacing(6);
            bands[i].setMinorTickSpacing(1);
            bands[i].setPaintTicks(true);
            bands[i].getAccessibleContext().setAccessibleName(label + " Hz gain in dB");
            bands[i].addChangeListener(event -> changed(true));
            band.add(bands[i]);
            JLabel hz = UiFactory.label(label + " Hz", Theme.SMALL, Theme.TEXT_MUTED);
            hz.setAlignmentX(Component.CENTER_ALIGNMENT);
            band.add(hz);
            equalizer.add(band);
        }
        content.add(equalizer);
        content.add(Box.createVerticalStrut(10));
        JPanel preampRow = new JPanel(new BorderLayout(12, 0));
        preampRow.setOpaque(false);
        preampRow.add(UiFactory.label("Preamp", Theme.BODY, Theme.TEXT), BorderLayout.WEST);
        preamp.setOpaque(false);
        preamp.setForeground(Theme.BLUE); preamp.setUI(new RoundSliderUI(preamp));
        preamp.getAccessibleContext().setAccessibleName("Preamp attenuation in dB");
        preamp.setToolTipText("Additional attenuation from −12 to 0 dB");
        preamp.addChangeListener(event -> { if (!updating) enabled.setSelected(true); changed(false); });
        preampRow.add(preamp, BorderLayout.CENTER);
        content.add(preampRow);
        content.add(preampValue);
        content.add(Box.createVerticalStrut(18));

        JPanel normalization = new JPanel(new BorderLayout(18, 0));
        normalization.setOpaque(false);
        JPanel replayLabel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); replayLabel.setOpaque(false);
        replayLabel.add(UiFactory.label("ReplayGain", Theme.BODY_BOLD, Theme.TEXT));
        JButton help = PlayerBar.control(AppIcons.Kind.HELP, "About ReplayGain", false);
        help.setToolTipText("<html>Track evens out songs; Album preserves the album's loudness differences.<br>VLC uses existing tags with peak protection. Untagged files keep their level.<br>Changes apply at the next track start after Save. JavaFX does not support ReplayGain.</html>");
        replayLabel.add(help); normalization.add(replayLabel, BorderLayout.WEST);
        replay.getAccessibleContext().setAccessibleName("ReplayGain mode");
        replay.addActionListener(event -> changed(false));
        normalization.add(replay, BorderLayout.EAST);
        content.add(normalization);
        for (Component child : content.getComponents()) {
            if (child instanceof JComponent component) component.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        root.add(content, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, 12));
        footer.setOpaque(false);
        footer.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton reset = UiFactory.button("Reset sound", null, false);
        reset.addActionListener(event -> { load(AudioSettings.defaults()); changed(false); });
        cancel.addActionListener(event -> cancel());
        save.addActionListener(event -> save());
        buttons.add(reset); buttons.add(cancel); buttons.add(save);
        footer.add(buttons, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private static JPanel column() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private AudioSettings value() {
        List<Double> values = Arrays.stream(bands).map(slider -> (double) slider.getValue()).toList();
        return new AudioSettings(enabled.isSelected(), values, preamp.getValue(), (AudioSettings.ReplayGain) replay.getSelectedItem());
    }

    private void load(AudioSettings settings) {
        updating = true;
        enabled.setSelected(settings.equalizerEnabled());
        for (int i = 0; i < bands.length; i++) bands[i].setValue((int) Math.round(settings.bands().get(i)));
        preamp.setValue((int) Math.round(settings.preampDb()));
        replay.setSelectedItem(settings.replayGain());
        presets.setSelectedItem(settings.bands().stream().allMatch(gain -> gain == 0) ? "Flat" : "Custom");
        updating = false;
        updateGains();
    }

    private void changed(boolean custom) {
        if (updating || saving) return;
        if (custom) { enabled.setSelected(true); updating = true; presets.setSelectedItem("Custom"); updating = false; }
        updateGains();
        preview.request(previewSettings(value()));
    }

    private void updateGains() {
        for (int i = 0; i < bands.length; i++) {
            int gain = bands[i].getValue();
            gains[i].setText((gain > 0 ? "+" : "") + gain + " dB");
            bands[i].setToolTipText(bands[i].getAccessibleContext().getAccessibleName() + ": " + gain);
            bands[i].setEnabled(!saving);
            bands[i].setForeground(enabled.isSelected() ? Theme.BLUE : Theme.TEXT_MUTED);
        }
        preamp.setEnabled(!saving);
        preampValue.setText("Preamp " + preamp.getValue() + " dB" + (enabled.isSelected() ? " · Applies live" : " (EQ bypassed)"));
    }

    private void selectPreset() {
        if (updating || saving || presets.getSelectedIndex() == 0) return;
        int[] values = switch (presets.getSelectedIndex()) {
            case 2 -> new int[] { 5, 4, 3, 1, 0, 0, 0, 0, 0, 0 };
            case 3 -> new int[] { -3, -3, -2, 0, 2, 3, 3, 1, -1, -2 };
            case 4 -> new int[] { 0, 0, 0, 0, 0, 0, -1, -3, -5, -6 };
            default -> new int[10];
        };
        updating = true;
        for (int i = 0; i < bands.length; i++) bands[i].setValue(values[i]);
        enabled.setSelected(true);
        updating = false;
        changed(false);
    }

    private AudioSettings previewSettings(AudioSettings settings) {
        // Normalization is committed only on Save, even if the queue advances during preview.
        return new AudioSettings(settings.equalizerEnabled(), settings.bands(),
            settings.preampDb(), original.replayGain());
    }

    private void updateBackend() {
        AudioStatus state = player.audioStatus();
        replay.setEnabled(!saving && state.backend() != AudioStatus.Backend.JAVA_FX);
        String label = switch (state.backend()) {
            case CHECKING -> "Checking audio backend…";
            case VLC -> "VLC · Current track ReplayGain: " + state.activeReplayGain();
            case JAVA_FX -> "JavaFX fallback · Volume up to 100% · ReplayGain unavailable";
        };
        backend.setText(state.error() == null ? label : state.error());
        backend.setToolTipText(label);
    }

    private void save() {
        preview.cancel();
        AudioSettings next = value();
        player.setAudioSettings(previewSettings(next));
        saving = true;
        setControlsEnabled(this, false);
        status.setText("Saving audio settings…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { store.save(next); return null; }
            @Override protected void done() {
                saving = false;
                try {
                    get();
                    saved = true;
                    player.setAudioSettings(next);
                    committed.accept(next);
                    AudioSettingsPanel.this.cancel();
                } catch (InterruptedException | ExecutionException error) {
                    status.setText("Could not save audio settings. Check the data folder, then retry or Cancel.");
                    setControlsEnabled(AudioSettingsPanel.this, true);
                    updateGains();
                    updateBackend();
                }
            }
        }.execute();
    }

    private static void setControlsEnabled(Container parent, boolean enabled) {
        for (Component child : parent.getComponents()) {
            if (child instanceof AbstractButton || child instanceof JComboBox<?> || child instanceof JSlider)
                child.setEnabled(enabled);
            if (child instanceof Container nested) setControlsEnabled(nested, enabled);
        }
    }

    void cancel() {
        if (saving) return;
        preview.cancel();
        backendTimer.stop();
        if (!saved) player.setAudioSettings(original);
        close.run();
    }
}
