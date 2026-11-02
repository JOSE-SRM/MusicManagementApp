package sangitam.desktop.ui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;

/**
 * Lightweight, non-blocking feedback bar for placeholder commands.
 * IMPORTANT: Menu and button actions report here so prototype interactions never interrupt the workflow.
 */
public final class NotificationCenter extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JLabel message = UiFactory.label("Ready", Theme.SMALL, Theme.TEXT_MUTED);
    private final Timer resetTimer;

    public NotificationCenter() {
        super(new BorderLayout());
        setBackground(Theme.SURFACE);
        setBorder(Theme.padding(7, 20, 7, 20));
        add(message, BorderLayout.WEST);
        resetTimer = new Timer(3200, event -> message.setText("Ready"));
        resetTimer.setRepeats(false);
    }

    public void show(String text) {
        message.setForeground(Theme.BLUE);
        message.setText(text);
        resetTimer.restart();
    }
}
