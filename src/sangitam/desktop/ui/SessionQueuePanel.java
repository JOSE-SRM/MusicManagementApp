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
        default void replace(int index,List<SessionPlanner.Choice> choices) { }
        default void taste(Track track,int rating) { }
    }

    private final Actions actions;
    private final DefaultListModel<SessionPlanner.Choice> model = new DefaultListModel<>();
    private final JList<SessionPlanner.Choice> list = new JList<>(model);
    private List<Track> available=List.of();
    private final JourneyCanvas journey=new JourneyCanvas();
    private final JTextArea why=new JTextArea(3,40);
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
        heading.add(UiFactory.label("Your listening journey", Theme.HEADING, Theme.TEXT), BorderLayout.WEST);
        heading.add(summary, BorderLayout.EAST);
        JPanel north=new JPanel(new BorderLayout(0,8));north.setOpaque(false);north.add(heading,BorderLayout.NORTH);north.add(journey,BorderLayout.CENTER);add(north,BorderLayout.NORTH);
        list.addListSelectionListener(event->{updateReason();journey.repaint();});
        why.setLineWrap(true);why.setWrapStyleWord(true);why.setEditable(false);why.setOpaque(false);why.setForeground(Theme.TEXT_MUTED);why.setFont(Theme.SMALL);

        list.setFixedCellHeight(58);
        list.setFixedCellWidth(300);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((valueList, value, index, selected, focused) -> {
            JLabel cell = new JLabel("<html><small>" + position(index) + "</small> <b>" + escape(value.track().title()) + "</b> — "
                + escape(value.track().artist()) + "<br><small>" + escape(value.track().durationLabel()+" · "+(value.reason().length()>140?value.reason().substring(0,140)+"…":value.reason())) + "</small></html>");
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
        UiFactory.styleScroll(scroll);
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
        JButton play = UiFactory.button("Play journey", AppIcons.of(AppIcons.Kind.PLAY, 15, Theme.BLACK), true);
        play.addActionListener(event -> { if (!model.isEmpty()) actions.start(choices()); });
        footer.add(play, BorderLayout.EAST);

        JPanel addRow = new JPanel(new GridLayout(0,3,6,6));addRow.setOpaque(false);
        button(addRow,"Replace slot",()->{if(list.getSelectedIndex()>=0)actions.replace(list.getSelectedIndex(),choices());});
        button(addRow,"More like this",()->rate(1));button(addRow,"Less like this",()->rate(-1));button(addRow,"Avoid",()->rate(-2));button(addRow,"Reset taste",()->rate(0));
        button(addRow,"Add recording…",this::addRecording);
        JPanel playlists = new JPanel(new BorderLayout(8, 0));
        playlists.setOpaque(false);
        savedPlaylists.setToolTipText("Saved playlists");
        savedPlaylists.getAccessibleContext().setAccessibleName("Saved playlists");
        playlists.add(savedPlaylists, BorderLayout.CENTER);
        JPanel playlistActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        playlistActions.setOpaque(false);
        button(playlistActions, "Save journey", this::savePlaylist);
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
        JPanel explanation=new JPanel(new BorderLayout());explanation.setOpaque(false);explanation.add(why,BorderLayout.NORTH);explanation.add(footer,BorderLayout.SOUTH);bottom.add(explanation, BorderLayout.NORTH);
        JPanel lower = new JPanel(new BorderLayout(0, 8));
        lower.setOpaque(false);
        lower.add(addRow, BorderLayout.NORTH);
        lower.add(playlists, BorderLayout.SOUTH);
        bottom.add(lower, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
    }

    public void setAvailableTracks(List<Track> tracks) { available=tracks; }
    private java.util.Map<java.nio.file.Path,Integer> taste=java.util.Map.of();
    public void setTaste(java.util.Map<java.nio.file.Path,Integer> ratings) { taste=java.util.Map.copyOf(ratings); updateReason(); }
    private void updateReason() {
        var selected=list.getSelectedValue();
        if (selected==null) { why.setText("Select a recording to see why it fits and tune your taste profile."); return; }
        String preference=switch(taste.getOrDefault(selected.track().path(),0)) { case -2 -> "Avoid"; case -1 -> "Less"; case 1 -> "More"; case 2 -> "Love"; default -> "Neutral"; };
        why.setText("Taste: " + preference + " · " + selected.reason()); why.setCaretPosition(0);
    }
    private void rate(int rating) { var choice=list.getSelectedValue();if(choice!=null)actions.taste(choice.track(),rating); }
    private void addRecording() {
        JTextField query=new JTextField();DefaultListModel<Track> matches=new DefaultListModel<>();JList<Track> results=new JList<>(matches);results.setCellRenderer(new TrackCellRenderer());
        JPanel picker=new JPanel(new BorderLayout(0,8));picker.add(query,BorderLayout.NORTH);picker.add(new JScrollPane(results));picker.setPreferredSize(new Dimension(500,330));
        final SwingWorker<?,?>[] pending={null};
        Runnable filter=()->{if(pending[0]!=null)pending[0].cancel(true);String text=LibraryEntry.lower(query.getText());pending[0]=new SwingWorker<List<Track>,Void>(){protected List<Track> doInBackground(){return available.stream().filter(t->LibraryEntry.lower(t.toString()).contains(text)).limit(100).toList();}protected void done(){if(isCancelled()||pending[0]!=this)return;try{matches.clear();matches.addAll(get());}catch(Exception ignored){}}};pending[0].execute();};
        LibraryPanel.listen(query,filter);filter.run();
        int answer=JOptionPane.showConfirmDialog(this,picker,"Add recording",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);
        if(pending[0]!=null)pending[0].cancel(true);
        if(answer==JOptionPane.OK_OPTION&&results.getSelectedValue()!=null){Track selected=results.getSelectedValue();model.addElement(new SessionPlanner.Choice(selected,"Added by you"));changed();}
    }
    private String position(int index){long seconds=0;for(int i=0;i<index;i++)seconds+=Math.max(0,model.get(i).track().durationSeconds());return seconds/60+":"+String.format("%02d",seconds%60);}

    public void setPlan(SessionPlanner.Plan plan) {
        model.clear();
        plan.choices().forEach(model::addElement);
        updateSummary(); journey.repaint();
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
        String name = JOptionPane.showInputDialog(this, "Playlist name", "Save journey",
            JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        name = name.strip();
        if (name.isEmpty()) return;
        for (int i = 0; i < savedPlaylists.getItemCount(); i++) {
            if (savedPlaylists.getItemAt(i).name().equalsIgnoreCase(name)
                && JOptionPane.showConfirmDialog(this, "Replace the existing playlist?",
                    "Save journey", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
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
        journey.repaint(); actions.edited(choices());
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

    private final class JourneyCanvas extends JComponent {
        JourneyCanvas(){setPreferredSize(new Dimension(0,80));setToolTipText("Click the energy journey to select a recording");addMouseListener(new java.awt.event.MouseAdapter(){public void mousePressed(java.awt.event.MouseEvent event){if(model.size()>0){int i=Math.max(0,Math.min(model.size()-1,event.getX()*model.size()/Math.max(1,getWidth())));list.setSelectedIndex(i);list.ensureIndexIsVisible(i);}}});}
        protected void paintComponent(Graphics graphics){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int w=getWidth(),h=getHeight();g.setColor(Theme.DIVIDER);g.drawLine(10,h-10,w-10,h-10);if(model.isEmpty()){g.setColor(Theme.TEXT_MUTED);g.drawString("Build a journey to see its energy contour",14,40);}else{int oldX=0,oldY=0;for(int i=0;i<model.size();i++){var track=model.get(i).track();int e=energyFor(track);int x=15+(w-30)*(2*i+1)/(2*model.size()),y=h-14-(h-28)*e/100;g.setColor(Theme.BLUE);g.setStroke(new BasicStroke(2));if(i>0)g.drawLine(oldX,oldY,x,y);g.setColor(i==list.getSelectedIndex()?Theme.SUCCESS:Theme.BLUE);g.fillOval(x-4,y-4,8,8);oldX=x;oldY=y;}}
            g.dispose();}
    }
    private java.util.Map<java.nio.file.Path,sangitam.desktop.model.TrackTags> analysis=java.util.Map.of();
    private int energyFor(Track track) {
        var tags = analysis.get(track.path());
        return tags != null && tags.analyzed() ? tags.energy() : 50;
    }
    public void setAnalysis(java.util.Map<java.nio.file.Path,sangitam.desktop.model.TrackTags> tags){analysis=java.util.Map.copyOf(tags);journey.repaint();}

    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
