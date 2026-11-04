package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.Playlist;
import sangitam.desktop.session.SessionPlanner;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/** Editable Swing queue. Reasons remain visible next to every selected track. */
public final class SessionQueuePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    public interface Actions {
        void start(List<SessionPlanner.Choice> choices);
        void play(SessionPlanner.Choice choice, List<SessionPlanner.Choice> choices);
        void edited(List<SessionPlanner.Choice> choices);
        void save(String name, List<SessionPlanner.Choice> choices);
        void load(Playlist playlist);
        void delete(Playlist playlist);
    }

    private final Actions actions;
    private final DefaultListModel<SessionPlanner.Choice> model = new DefaultListModel<>();
    private final JList<SessionPlanner.Choice> list = new JList<>(model);
    private final JComboBox<Track> addTrack = new JComboBox<>();
    private final JComboBox<Playlist> savedPlaylists = new JComboBox<>();
    private final JLabel summary = UiFactory.label("Build a session to see its queue.", Theme.SMALL, Theme.TEXT_MUTED);

    public SessionQueuePanel(Actions actions) {
        super(new BorderLayout(0, 12));
        this.actions = actions;
        setBackground(Theme.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 16), Theme.padding(18, 20, 18, 20)));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(UiFactory.label("Session queue", Theme.HEADING, Theme.TEXT), BorderLayout.WEST);
        heading.add(summary, BorderLayout.EAST);
        add(heading, BorderLayout.NORTH);

        list.setFixedCellHeight(58);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((valueList, value, index, selected, focused) -> {
            JLabel cell = new JLabel("<html><b>" + escape(value.track().title()) + "</b> — "
                + escape(value.track().artist()) + "<br><small>" + escape(value.reason()) + "</small></html>");
            cell.setFont(Theme.SMALL);
            cell.setOpaque(true);
            cell.setBackground(selected ? Theme.BLUE_STRONG : Theme.SURFACE);
            cell.setForeground(selected ? Theme.BLACK : Theme.TEXT);
            cell.setBorder(Theme.padding(5, 12, 5, 12));
            return cell;
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) playSelected();
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "playChoice");
        list.getActionMap().put("playChoice", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { playSelected(); }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(UiFactory.roundedBorder(Theme.DIVIDER, 10));
        add(scroll, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(10, 8));
        footer.setOpaque(false);
        JPanel edit = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        edit.setOpaque(false);
        button(edit, "Move up", () -> move(-1));
        button(edit, "Move down", () -> move(1));
        button(edit, "Remove", this::removeSelected);
        footer.add(edit, BorderLayout.WEST);
        JButton play = UiFactory.button("Play session", AppIcons.of(AppIcons.Kind.PLAY, 15, Theme.BLACK), true);
        play.addActionListener(event -> { if (!model.isEmpty()) actions.start(choices()); });
        footer.add(play, BorderLayout.EAST);

        JPanel addRow = new JPanel(new BorderLayout(8, 0));
        addRow.setOpaque(false);
        addTrack.setFont(Theme.SMALL);
        addTrack.setToolTipText("Choose an imported track to add to this session");
        addRow.add(addTrack, BorderLayout.CENTER);
        JButton add = UiFactory.button("Add track", null, false);
        add.addActionListener(event -> {
            Track track = (Track) addTrack.getSelectedItem();
            if (track != null && choices().stream().noneMatch(choice -> choice.track().path().equals(track.path()))) {
                model.addElement(new SessionPlanner.Choice(track, "Added manually"));
                changed();
            }
        });
        addRow.add(add, BorderLayout.EAST);
        JPanel playlists = new JPanel(new BorderLayout(8, 0));
        playlists.setOpaque(false);
        savedPlaylists.setToolTipText("Saved playlists");
        savedPlaylists.getAccessibleContext().setAccessibleName("Saved playlists");
        playlists.add(savedPlaylists, BorderLayout.CENTER);
        JPanel playlistActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        playlistActions.setOpaque(false);
        button(playlistActions, "Save playlist", this::savePlaylist);
        button(playlistActions, "Load", () -> {
            Playlist selected = (Playlist) savedPlaylists.getSelectedItem();
            if (selected != null) actions.load(selected);
        });
        button(playlistActions, "Delete", () -> {
            Playlist selected = (Playlist) savedPlaylists.getSelectedItem();
            if (selected != null && JOptionPane.showConfirmDialog(this,
                "Delete playlist ‘" + selected.name() + "’?", "Delete playlist",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) actions.delete(selected);
        });
        playlists.add(playlistActions, BorderLayout.EAST);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        bottom.add(footer, BorderLayout.NORTH);
        JPanel lower = new JPanel(new BorderLayout(0, 8));
        lower.setOpaque(false);
        lower.add(addRow, BorderLayout.NORTH);
        lower.add(playlists, BorderLayout.SOUTH);
        bottom.add(lower, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
    }

    public void setAvailableTracks(List<Track> tracks) {
        Track selected = (Track) addTrack.getSelectedItem();
        addTrack.removeAllItems();
        tracks.forEach(addTrack::addItem);
        if (selected != null) {
            tracks.stream().filter(track -> track.path().equals(selected.path())).findFirst()
                .ifPresent(addTrack::setSelectedItem);
        }
    }

    public void setPlan(SessionPlanner.Plan plan) {
        model.clear();
        plan.choices().forEach(model::addElement);
        updateSummary();
    }

    public void setPlaylists(List<Playlist> playlists) {
        Playlist selected = (Playlist) savedPlaylists.getSelectedItem();
        savedPlaylists.removeAllItems();
        playlists.forEach(savedPlaylists::addItem);
        if (selected != null) playlists.stream().filter(item -> item.id() == selected.id())
            .findFirst().ifPresent(savedPlaylists::setSelectedItem);
    }

    public void selectPlaylist(String name) {
        for (int i = 0; i < savedPlaylists.getItemCount(); i++) {
            if (savedPlaylists.getItemAt(i).name().equalsIgnoreCase(name)) {
                savedPlaylists.setSelectedIndex(i);
                return;
            }
        }
    }

    public List<SessionPlanner.Choice> currentChoices() { return choices(); }
    public void focusSavedPlaylists() { savedPlaylists.requestFocusInWindow(); }

    private void savePlaylist() {
        if (model.isEmpty()) return;
        String name = JOptionPane.showInputDialog(this, "Playlist name", "Save playlist",
            JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        name = name.strip();
        if (name.isEmpty()) return;
        for (int i = 0; i < savedPlaylists.getItemCount(); i++) {
            if (savedPlaylists.getItemAt(i).name().equalsIgnoreCase(name)
                && JOptionPane.showConfirmDialog(this, "Replace the existing playlist?",
                    "Save playlist", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        }
        actions.save(name, choices());
    }

    private void button(JPanel row, String label, Runnable action) {
        JButton button = UiFactory.button(label, null, false);
        button.addActionListener(event -> action.run());
        row.add(button);
    }

    private void move(int amount) {
        int index = list.getSelectedIndex();
        int destination = index + amount;
        if (index < 0 || destination < 0 || destination >= model.size()) return;
        SessionPlanner.Choice choice = model.remove(index);
        model.add(destination, choice);
        list.setSelectedIndex(destination);
        changed();
    }

    private void removeSelected() {
        int index = list.getSelectedIndex();
        if (index < 0) return;
        model.remove(index);
        if (!model.isEmpty()) list.setSelectedIndex(Math.min(index, model.size() - 1));
        changed();
    }

    private void playSelected() {
        SessionPlanner.Choice choice = list.getSelectedValue();
        if (choice != null) actions.play(choice, choices());
    }

    private void changed() {
        updateSummary();
        actions.edited(choices());
    }

    private void updateSummary() {
        int seconds = choices().stream().mapToInt(choice -> choice.track().durationSeconds() > 0
            ? (int) Math.round(choice.track().durationSeconds()) : 180).sum();
        summary.setText(model.size() + " tracks · about " + Math.round(seconds / 60.0) + " min");
    }

    private List<SessionPlanner.Choice> choices() {
        List<SessionPlanner.Choice> choices = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) choices.add(model.get(i));
        return List.copyOf(choices);
    }

    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
