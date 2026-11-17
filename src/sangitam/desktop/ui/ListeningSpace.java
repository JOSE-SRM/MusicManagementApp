package sangitam.desktop.ui;

import sangitam.desktop.session.SessionPlanner;

import java.awt.Color;

/** The listener chooses the room; its visual language never claims to infer a song's emotion. */
public enum ListeningSpace {
    QUIET_ORBIT("Quiet Orbit", "Focus", "A steady place for clear thought", Theme.BLUE,
        new Color(66, 102, 178), SessionPlanner.Intent.FOCUS),
    THE_CURRENT("The Current", "Explore", "Follow a new thread through your library", Theme.VIOLET,
        new Color(117, 87, 194), SessionPlanner.Intent.EXPLORE),
    AFTERGLOW("Afterglow", "Unwind", "Let the day loosen its grip", Theme.CORAL,
        new Color(162, 91, 107), SessionPlanner.Intent.UNWIND);

    private final String name;
    private final String intentLabel;
    private final String description;
    private final Color accent;
    private final Color horizon;
    private final SessionPlanner.Intent intent;

    ListeningSpace(String name, String intentLabel, String description, Color accent,
                   Color horizon, SessionPlanner.Intent intent) {
        this.name = name;
        this.intentLabel = intentLabel;
        this.description = description;
        this.accent = accent;
        this.horizon = horizon;
        this.intent = intent;
    }

    public String displayName() { return name; }
    public String intentLabel() { return intentLabel; }
    public String description() { return description; }
    public Color accent() { return accent; }
    public Color horizon() { return horizon; }
    public SessionPlanner.Intent intent() { return intent; }

    public static ListeningSpace forIntent(SessionPlanner.Intent intent) {
        for (ListeningSpace space : values()) if (space.intent == intent) return space;
        return QUIET_ORBIT;
    }
}
