package sangitam.desktop.ui;

import sangitam.desktop.storage.ProviderCredentials;
import sangitam.desktop.storage.WindowsCredentialStore;
import sangitam.desktop.storage.AppPreferences;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provider credentials are saved only to the signed-in Windows user's Credential Manager. */
public final class SettingsDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final JPasswordField spotify = new JPasswordField(36);
    private final JPasswordField youtube = new JPasswordField(36);
    private final JPasswordField apple = new JPasswordField(36);
    private final JPasswordField lastfm = new JPasswordField(36);
    private final JTextField storefront = new JTextField(AppPreferences.appleStorefront(), 5);
    private final JCheckBox clearSpotify = new JCheckBox("Remove saved credential");
    private final JCheckBox clearYoutube = new JCheckBox("Remove saved credential");
    private final JCheckBox clearApple = new JCheckBox("Remove saved credential");
    private final JCheckBox clearLastfm = new JCheckBox("Remove saved credential");
    private final JLabel status = UiFactory.label("Credentials are stored in Windows Credential Manager.", Theme.SMALL, Theme.TEXT_MUTED);
    private JButton saveButton;

    public SettingsDialog(Window owner) {
        super(owner, "Sangitam settings", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(buildContent());
        pack();
        setMinimumSize(new Dimension(600, getHeight()));
        setLocationRelativeTo(owner);
    }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(12, 14));
        root.setBackground(Theme.BLACK);
        root.setBorder(Theme.padding(20, 22, 18, 22));
        root.add(UiFactory.label("Provider connections", Theme.TITLE, Theme.TEXT), BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Theme.SURFACE);
        form.setBorder(Theme.padding(16, 16, 16, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(5, 4, 5, 12);
        addCredential(form, c, "Spotify access token", spotify, clearSpotify,
            "Used for catalog search and optional recent-play history.");
        addCredential(form, c, "YouTube Data API key", youtube, clearYoutube,
            "Used for catalog search; Sangitam opens results on YouTube.");
        addCredential(form, c, "Apple Music developer token", apple, clearApple,
            "Used for Apple Music catalog search.");
        c.gridx = 0; c.gridy++;
        form.add(UiFactory.label("Apple storefront", Theme.BODY, Theme.TEXT), c);
        c.gridx = 1; c.gridwidth = 2; form.add(storefront, c); c.gridwidth = 1;
        addCredential(form, c, "Last.fm API key", lastfm, clearLastfm,
            "Used with the username entered on Rediscover.");
        root.add(form, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.setOpaque(false);
        bottom.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton cancel = UiFactory.button("Cancel", null, false);
        saveButton = UiFactory.button("Save securely", AppIcons.of(AppIcons.Kind.SETTINGS, 15, Theme.BLACK), true);
        cancel.addActionListener(event -> dispose());
        saveButton.addActionListener(event -> save());
        buttons.add(cancel); buttons.add(saveButton);
        bottom.add(buttons, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private void addCredential(JPanel form, GridBagConstraints c, String label, JPasswordField field,
                               JCheckBox clear, String help) {
        c.gridx = 0; c.gridy++;
        form.add(UiFactory.label(label, Theme.BODY, Theme.TEXT), c);
        c.gridx = 1; c.gridwidth = 2;
        form.add(field, c);
        c.gridy++;
        JLabel note = UiFactory.label(help, Theme.SMALL, Theme.TEXT_MUTED);
        form.add(note, c);
        c.gridy++;
        clear.setOpaque(false);
        clear.setForeground(Theme.TEXT_MUTED);
        clear.setFont(Theme.SMALL);
        form.add(clear, c);
        c.gridwidth = 1;
    }

    private void save() {
        if (!WindowsCredentialStore.isAvailable()) {
            status.setText("Secure credential storage is available on Windows only.");
            return;
        }
        String region = storefront.getText().strip();
        if (!region.matches("[a-zA-Z]{2}")) {
            status.setText("Apple storefront must be a two-letter region code.");
            return;
        }
        Map<String, String> updates = new LinkedHashMap<>();
        collect(updates, ProviderCredentials.SPOTIFY_TOKEN, spotify, clearSpotify);
        collect(updates, ProviderCredentials.YOUTUBE_KEY, youtube, clearYoutube);
        collect(updates, ProviderCredentials.APPLE_TOKEN, apple, clearApple);
        collect(updates, ProviderCredentials.LASTFM_KEY, lastfm, clearLastfm);
        saveButton.setEnabled(false);
        status.setText("Saving provider credentials securely…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                updates.forEach((key, value) -> {
                    if (value == null) WindowsCredentialStore.delete(key);
                    else WindowsCredentialStore.write(key, value);
                    ProviderCredentials.remember(key, value);
                });
                AppPreferences.setAppleStorefront(region.toLowerCase());
                return null;
            }
            @Override protected void done() {
                saveButton.setEnabled(true);
                try {
                    get();
                    status.setText("Provider settings saved securely.");
                    dispose();
                } catch (Exception error) {
                    status.setText("Could not save secure settings: " + error.getMessage());
                }
            }
        }.execute();
    }

    private void collect(Map<String, String> updates, String key, JPasswordField field, JCheckBox clear) {
        char[] chars = field.getPassword();
        try {
            if (clear.isSelected()) updates.put(key, null);
            else if (chars.length > 0) updates.put(key, new String(chars));
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }
}
