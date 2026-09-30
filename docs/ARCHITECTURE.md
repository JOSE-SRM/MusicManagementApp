# Sangitam UI architecture

Sangitam’s product idea is simple: instead of making people choose a playlist first, ask how they want the next listening session to feel. The UI turns an intent, energy level and available time into a session recipe. Audio playback and recommendation logic can be connected later without redesigning the interface.

## How to explain the project

1. `SangitamApp` starts Swing on the Event Dispatch Thread and opens `MainFrame`.
2. `MainFrame` composes the navigation, current screen, library, player and notification bar.
3. `CardLayout` switches between the dashboard and search without creating new windows.
4. `SessionComposerPanel` reads standard Swing input models and calculates a preview.
5. `SoundscapeCanvas` demonstrates custom painting with Java2D. A short `Timer` reveals the new wave after the user builds a session.
6. `LibraryPanel` uses another `Timer` to animate its width safely on the Event Dispatch Thread.
7. `CommandPaletteDialog` filters a `DefaultListModel` and runs actions supplied by `MainFrame`.
8. `Theme`, `UiFactory` and `AppIcons` keep visual decisions out of the feature panels.

## Swing concepts used with a purpose

| Swing concept | Where it is used | Why it is used |
|---|---|---|
| `BorderLayout`, `BoxLayout`, `GridLayout`, `GridBagLayout` | Screens and dialogs | Responsive composition at different levels |
| `CardLayout` | Main workspace | Fast screen navigation in one window |
| `ButtonGroup` and `JToggleButton` | Session intent | Exactly one intent can be selected |
| `JSlider`, `JComboBox`, `JCheckBox` | Session controls | Energy, duration and familiarity preferences |
| `JTable`, `JList`, `JTree`, `JTabbedPane` | History, search and library | Appropriate views for different collection shapes |
| `JFileChooser` | Media import | Accepts files, multiple files and directories |
| `Timer` | Soundscape and library | Safe, non-blocking UI animation |
| Custom `paintComponent` | Icons and soundscape | Sharp visuals without external assets |
| Key bindings and `Action` | Command palette | Keyboard-first navigation that works beyond focused controls |

## Separation of responsibilities

Feature panels own only their local controls and interaction state. `MainFrame` owns navigation between features. Reusable appearance stays in `Theme` and `UiFactory`. This keeps every file small enough to explain independently and leaves clear connection points for a real media service, metadata repository and recommendation engine.
