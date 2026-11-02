package sangitam.desktop.ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Searchable keyboard command palette for fast navigation.
 * IMPORTANT: Commands are supplied by MainFrame, keeping this dialog reusable and free of application logic.
 */
public final class CommandPaletteDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final transient Map<String, Runnable> commands;
    private final DefaultListModel<String> matches = new DefaultListModel<>();
    private final JList<String> commandList = new JList<>(matches);
    private final JTextField query = new JTextField();

    public CommandPaletteDialog(Window owner, Map<String, Runnable> commands) {
        super(owner, "Quick actions", ModalityType.APPLICATION_MODAL);
        this.commands = new LinkedHashMap<>(commands);
        setUndecorated(true);
        setSize(520, 390);
        setLocationRelativeTo(owner);
        setContentPane(buildContent());
        getRootPane().registerKeyboardAction(event -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        refresh("");
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible) SwingUtilities.invokeLater(query::requestFocusInWindow);
        super.setVisible(visible);
    }

    private JComponent buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.SURFACE);
        root.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.BLUE_STRONG, 18), Theme.padding(18, 18, 18, 18)));

        JLabel title = UiFactory.label("Quick actions", Theme.HEADING, Theme.TEXT);
        root.add(title, BorderLayout.NORTH);
        query.setFont(Theme.BODY);
        query.setToolTipText("Type a command");
        query.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 12), Theme.padding(11, 13, 11, 13)));

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.add(query, BorderLayout.NORTH);
        commandList.setFixedCellHeight(42);
        commandList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        commandList.setBorder(Theme.padding(4, 4, 4, 4));
        center.add(new JScrollPane(commandList), BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);
        root.add(UiFactory.label("Enter to run · Esc to close", Theme.SMALL, Theme.TEXT_MUTED), BorderLayout.SOUTH);

        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { refresh(query.getText()); }
            public void removeUpdate(DocumentEvent event) { refresh(query.getText()); }
            public void changedUpdate(DocumentEvent event) { refresh(query.getText()); }
        });
        query.addActionListener(event -> runSelected());
        commandList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "run");
        commandList.getActionMap().put("run", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { runSelected(); }
        });
        commandList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) runSelected();
            }
        });
        return root;
    }

    private void refresh(String text) {
        String needle = text.trim().toLowerCase();
        matches.clear();
        commands.keySet().stream()
            .filter(name -> name.toLowerCase().contains(needle))
            .forEach(matches::addElement);
        if (!matches.isEmpty()) commandList.setSelectedIndex(0);
    }

    private void runSelected() {
        String selected = commandList.getSelectedValue();
        if (selected == null) return;
        dispose();
        commands.get(selected).run();
    }
}
