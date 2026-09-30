import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public class MusicManagementGUI extends JFrame {

  // ============================================================
  // COLORS
  // ============================================================

  private static final Color BACKGROUND = new Color(7, 9, 12);

  private static final Color PANEL_BACKGROUND = new Color(12, 15, 20);

  private static final Color CARD_BACKGROUND = new Color(18, 22, 29);

  private static final Color INPUT_BACKGROUND = new Color(10, 13, 18);

  // Lighter blue
  private static final Color BLUE = new Color(70, 155, 255);

  private static final Color BLUE_HOVER = new Color(100, 180, 255);

  private static final Color BLUE_DARK = new Color(45, 115, 210);

  private static final Color TEXT = new Color(238, 242, 248);

  private static final Color SECONDARY_TEXT = new Color(155, 165, 180);

  private static final Color BORDER = new Color(42, 50, 62);

  private static final Color MENU_BACKGROUND = new Color(10, 13, 18);

  private static final Color MENU_ITEM_BACKGROUND = new Color(17, 21, 28);

  // ============================================================
  // FONTS
  // ============================================================

  private static final Font MENU_FONT = new Font("Segoe UI", Font.PLAIN, 15);

  private static final Font NORMAL_FONT = new Font("Segoe UI", Font.PLAIN, 14);

  private static final Font TITLE_FONT = new Font("Segoe UI", Font.BOLD, 25);

  private static final Font CARD_TITLE_FONT = new Font("Segoe UI", Font.BOLD, 18);

  // ============================================================
  // FIELDS
  // ============================================================

  private JPanel mainPanel;
  private CardLayout cardLayout;

  private JPanel playbackPanel;
  private JLabel playbackFileName;
  private JLabel playbackArtist;
  private JSlider playbackSlider;

  // ============================================================
  // CONSTRUCTOR
  // ============================================================

  public MusicManagementGUI() {

    configureLookAndFeel();

    configureWindow();

    createMenuBar();

    createMainContent();

    setVisible(true);
  }

  // ============================================================
  // LOOK AND FEEL
  // ============================================================

  private void configureLookAndFeel() {

    /*
     * IMPORTANT:
     *
     * The Look & Feel must be installed BEFORE changing
     * UIManager colours. Otherwise the Look & Feel can
     * overwrite the custom colours.
     */

    try {
      // Changed to CrossPlatform Look and Feel to support custom dark themes natively
      UIManager.setLookAndFeel(
          UIManager.getCrossPlatformLookAndFeelClassName());

    } catch (Exception ignored) {
    }

    // --------------------------------------------------------
    // GENERAL COMPONENTS
    // --------------------------------------------------------

    UIManager.put(
        "Panel.background",
        BACKGROUND);

    UIManager.put(
        "Label.foreground",
        TEXT);

    UIManager.put(
        "Button.background",
        CARD_BACKGROUND);

    UIManager.put(
        "Button.foreground",
        TEXT);

    UIManager.put(
        "Button.select",
        BLUE);

    UIManager.put(
        "Button.focus",
        new Color(0, 0, 0, 0));

    // --------------------------------------------------------
    // MENU BAR
    // --------------------------------------------------------

    UIManager.put(
        "MenuBar.background",
        MENU_BACKGROUND);

    UIManager.put(
        "MenuBar.foreground",
        TEXT);

    UIManager.put(
        "Menu.background",
        MENU_BACKGROUND);

    UIManager.put(
        "Menu.foreground",
        TEXT);

    UIManager.put(
        "Menu.selectionBackground",
        BLUE_DARK);

    UIManager.put(
        "Menu.selectionForeground",
        Color.WHITE);

    UIManager.put(
        "MenuItem.background",
        MENU_ITEM_BACKGROUND);

    UIManager.put(
        "MenuItem.foreground",
        TEXT);

    UIManager.put(
        "MenuItem.selectionBackground",
        BLUE_DARK);

    UIManager.put(
        "MenuItem.selectionForeground",
        Color.WHITE);

    // --------------------------------------------------------
    // OPTION PANE / NOTIFICATIONS
    // --------------------------------------------------------

    UIManager.put(
        "OptionPane.background",
        PANEL_BACKGROUND);

    UIManager.put(
        "OptionPane.messageForeground",
        TEXT);

    UIManager.put(
        "OptionPane.foreground",
        TEXT);

    UIManager.put(
        "OptionPane.buttonBackground",
        CARD_BACKGROUND);

    UIManager.put(
        "OptionPane.buttonForeground",
        TEXT);

    // --------------------------------------------------------
    // TEXT COMPONENTS
    // --------------------------------------------------------

    UIManager.put(
        "TextField.background",
        INPUT_BACKGROUND);

    UIManager.put(
        "TextField.foreground",
        TEXT);

    UIManager.put(
        "TextField.caretForeground",
        TEXT);

    UIManager.put(
        "TextArea.background",
        INPUT_BACKGROUND);

    UIManager.put(
        "TextArea.foreground",
        TEXT);

    // --------------------------------------------------------
    // COMBO BOX
    // --------------------------------------------------------

    UIManager.put(
        "ComboBox.background",
        CARD_BACKGROUND);

    UIManager.put(
        "ComboBox.foreground",
        TEXT);

    UIManager.put(
        "ComboBox.selectionBackground",
        BLUE_DARK);

    UIManager.put(
        "ComboBox.selectionForeground",
        Color.WHITE);

    // --------------------------------------------------------
    // SCROLL BAR
    // --------------------------------------------------------

    UIManager.put(
        "ScrollBar.background",
        PANEL_BACKGROUND);

    UIManager.put(
        "ScrollBar.thumb",
        new Color(55, 65, 80));

    UIManager.put(
        "ScrollBar.track",
        PANEL_BACKGROUND);
  }

  // ============================================================
  // WINDOW
  // ============================================================

  private void configureWindow() {

    setTitle("Music Management");

    setSize(1250, 800);

    setMinimumSize(
        new Dimension(
            950,
            650));

    setLocationRelativeTo(null);

    setDefaultCloseOperation(
        JFrame.EXIT_ON_CLOSE);

    getContentPane().setBackground(
        BACKGROUND);
  }

  // ============================================================
  // MENU BAR
  // ============================================================

  private void createMenuBar() {

    JMenuBar menuBar = new JMenuBar();

    menuBar.setBackground(
        MENU_BACKGROUND);

    menuBar.setOpaque(true);

    menuBar.setBorder(
        BorderFactory.createMatteBorder(
            0,
            0,
            1,
            0,
            BORDER));

    // --------------------------------------------------------
    // FILE
    // --------------------------------------------------------

    JMenu fileMenu = createMenu("File");

    JMenuItem addFileFolder = createMenuItem(
        "Add File/Folder...");

    addFileFolder.addActionListener(
        e -> openAddFileFolderWindow());

    fileMenu.add(addFileFolder);

    fileMenu.addSeparator();

    addNotificationMenuItem(
        fileMenu,
        "Open");

    addNotificationMenuItem(
        fileMenu,
        "Close");

    fileMenu.addSeparator();

    JMenuItem exit = createMenuItem("Exit");

    exit.addActionListener(
        e -> System.exit(0));

    fileMenu.add(exit);

    // --------------------------------------------------------
    // EDIT
    // --------------------------------------------------------

    JMenu editMenu = createMenu("Edit");

    addNotificationMenuItem(
        editMenu,
        "Undo");

    addNotificationMenuItem(
        editMenu,
        "Redo");

    editMenu.addSeparator();

    addNotificationMenuItem(
        editMenu,
        "Edit Metadata");

    addNotificationMenuItem(
        editMenu,
        "Edit Playlist");

    addNotificationMenuItem(
        editMenu,
        "Remove Song");

    // --------------------------------------------------------
    // VIEW
    // --------------------------------------------------------

    JMenu viewMenu = createMenu("View");

    addNotificationMenuItem(
        viewMenu,
        "Home");

    addNotificationMenuItem(
        viewMenu,
        "Albums");

    addNotificationMenuItem(
        viewMenu,
        "Playlists");

    addNotificationMenuItem(
        viewMenu,
        "Artists");

    addNotificationMenuItem(
        viewMenu,
        "Songs");

    viewMenu.addSeparator();

    addNotificationMenuItem(
        viewMenu,
        "Music Analysis");

    addNotificationMenuItem(
        viewMenu,
        "Equalizer");

    // --------------------------------------------------------
    // PLAYBACK
    // --------------------------------------------------------

    JMenu playbackMenu = createMenu("Playback");

    addNotificationMenuItem(
        playbackMenu,
        "Play");

    addNotificationMenuItem(
        playbackMenu,
        "Pause");

    addNotificationMenuItem(
        playbackMenu,
        "Stop");

    playbackMenu.addSeparator();

    addNotificationMenuItem(
        playbackMenu,
        "Next");

    addNotificationMenuItem(
        playbackMenu,
        "Previous");

    playbackMenu.addSeparator();

    addNotificationMenuItem(
        playbackMenu,
        "Shuffle");

    addNotificationMenuItem(
        playbackMenu,
        "Repeat");

    // --------------------------------------------------------
    // HELP
    // --------------------------------------------------------

    JMenu helpMenu = createMenu("Help");

    addNotificationMenuItem(
        helpMenu,
        "User Guide");

    addNotificationMenuItem(
        helpMenu,
        "Keyboard Shortcuts");

    helpMenu.addSeparator();

    addNotificationMenuItem(
        helpMenu,
        "About");

    menuBar.add(fileMenu);
    menuBar.add(editMenu);
    menuBar.add(viewMenu);
    menuBar.add(playbackMenu);

    menuBar.add(
        Box.createHorizontalGlue());

    menuBar.add(helpMenu);

    setJMenuBar(menuBar);
  }

  // ============================================================
  // MENU HELPERS
  // ============================================================

  private JMenu createMenu(
      String text) {

    JMenu menu = new JMenu(text);

    menu.setFont(MENU_FONT);

    menu.setForeground(TEXT);

    menu.setBackground(
        MENU_BACKGROUND);

    menu.setOpaque(true);

    return menu;
  }

  private JMenuItem createMenuItem(
      String text) {

    JMenuItem item = new JMenuItem(text);

    item.setFont(NORMAL_FONT);

    item.setForeground(TEXT);

    item.setBackground(
        MENU_ITEM_BACKGROUND);

    item.setOpaque(true);

    return item;
  }

  private void addNotificationMenuItem(
      JMenu menu,
      String name) {

    JMenuItem item = createMenuItem(name);

    item.addActionListener(
        e -> showNotImplemented(name));

    menu.add(item);
  }

  // ============================================================
  // MAIN CARD LAYOUT
  // ============================================================

  private void createMainContent() {

    cardLayout = new CardLayout();

    mainPanel = new JPanel(cardLayout);

    mainPanel.setBackground(
        BACKGROUND);

    mainPanel.add(
        createHomePanel(),
        "HOME");

    add(
        mainPanel,
        BorderLayout.CENTER);

    /*
     * Playback view is outside the CardLayout.
     *
     * Therefore it remains visible while changing
     * between Home, Albums, Playlists, Artists, etc.
     */

    playbackPanel = createPlaybackPanel();

    add(
        playbackPanel,
        BorderLayout.SOUTH);

    cardLayout.show(
        mainPanel,
        "HOME");
  }

  // ============================================================
  // HOME PANEL
  // ============================================================

  private JPanel createHomePanel() {

    JPanel panel = new JPanel(
        new BorderLayout());

    panel.setBackground(
        BACKGROUND);

    panel.setBorder(
        BorderFactory.createEmptyBorder(
            35,
            45,
            30,
            45));

    JPanel headerPanel = new JPanel(
        new BorderLayout());

    headerPanel.setOpaque(false);

    JLabel title = new JLabel(
        "Music Library");

    title.setFont(TITLE_FONT);

    title.setForeground(TEXT);

    JLabel subtitle = new JLabel(
        "Your playlists, albums and music collection");

    subtitle.setFont(
        new Font(
            "Segoe UI",
            Font.PLAIN,
            14));

    subtitle.setForeground(
        SECONDARY_TEXT);

    JPanel titlePanel = new JPanel();

    titlePanel.setOpaque(false);

    titlePanel.setLayout(
        new BoxLayout(
            titlePanel,
            BoxLayout.Y_AXIS));

    titlePanel.add(title);

    titlePanel.add(
        Box.createVerticalStrut(5));

    titlePanel.add(subtitle);

    headerPanel.add(
        titlePanel,
        BorderLayout.WEST);

    panel.add(
        headerPanel,
        BorderLayout.NORTH);

    JPanel playlistContainer = new JPanel(
        new GridLayout(
            1,
            4,
            20,
            0));

    playlistContainer.setOpaque(false);

    playlistContainer.setBorder(
        BorderFactory.createEmptyBorder(
            35,
            0,
            20,
            0));

    playlistContainer.add(
        createPlaylistCard(
            "My Favorites",
            "42 songs",
            0));

    playlistContainer.add(
        createPlaylistCard(
            "Classical",
            "28 songs",
            1));

    playlistContainer.add(
        createPlaylistCard(
            "Study",
            "35 songs",
            2));

    playlistContainer.add(
        createPlaylistCard(
            "Recently Added",
            "17 songs",
            3));

    panel.add(
        playlistContainer,
        BorderLayout.CENTER);

    return panel;
  }

  // ============================================================
  // PLAYLIST CARD
  // ============================================================

  private JPanel createPlaylistCard(
      String playlistName,
      String songCount,
      int iconNumber) {

    JPanel card = new JPanel(
        new BorderLayout());

    card.setBackground(
        CARD_BACKGROUND);

    card.setBorder(
        BorderFactory.createCompoundBorder(
            new RoundedBorder(
                BORDER,
                12),
            BorderFactory.createEmptyBorder(
                15,
                15,
                15,
                15)));

    JPanel iconPanel = new PlaylistIconPanel(
        iconNumber);

    iconPanel.setPreferredSize(
        new Dimension(
            100,
            145));

    card.add(
        iconPanel,
        BorderLayout.CENTER);

    JPanel information = new JPanel();

    information.setOpaque(false);

    information.setLayout(
        new BoxLayout(
            information,
            BoxLayout.Y_AXIS));

    JLabel name = new JLabel(
        playlistName);

    name.setFont(
        CARD_TITLE_FONT);

    name.setForeground(TEXT);

    JLabel count = new JLabel(
        songCount);

    count.setFont(
        new Font(
            "Segoe UI",
            Font.PLAIN,
            13));

    count.setForeground(
        SECONDARY_TEXT);

    information.add(name);

    information.add(
        Box.createVerticalStrut(4));

    information.add(count);

    information.add(
        Box.createVerticalStrut(12));

    JButton playButton = createBlueButton("Play");

    playButton.setAlignmentX(
        Component.LEFT_ALIGNMENT);

    playButton.addActionListener(
        e -> showNotification(
            "Play",
            "Play button pressed for:\n"
                + playlistName));

    information.add(
        playButton);

    card.add(
        information,
        BorderLayout.SOUTH);

    return card;
  }

  // ============================================================
  // PLAYBACK PANEL
  // ============================================================

  private JPanel createPlaybackPanel() {

    JPanel panel = new JPanel(
        new BorderLayout(
            18,
            0));

    panel.setBackground(
        new Color(
            9,
            12,
            17));

    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(
                1,
                0,
                0,
                0,
                BORDER),
            BorderFactory.createEmptyBorder(
                10,
                18,
                10,
                18)));

    // --------------------------------------------------------
    // SONG INFORMATION
    // --------------------------------------------------------

    JPanel songInformation = new JPanel(
        new BorderLayout(
            10,
            0));

    songInformation.setOpaque(false);

    songInformation.setPreferredSize(
        new Dimension(
            240,
            70));

    JPanel icon = new PlaybackIconPanel();

    icon.setPreferredSize(
        new Dimension(
            58,
            58));

    songInformation.add(
        icon,
        BorderLayout.WEST);

    JPanel songText = new JPanel();

    songText.setOpaque(false);

    songText.setLayout(
        new BoxLayout(
            songText,
            BoxLayout.Y_AXIS));

    playbackFileName = new JLabel(
        "No song selected");

    playbackFileName.setFont(
        new Font(
            "Segoe UI",
            Font.BOLD,
            14));

    playbackFileName.setForeground(
        TEXT);

    playbackArtist = new JLabel(
        "Artist / Album");

    playbackArtist.setFont(
        new Font(
            "Segoe UI",
            Font.PLAIN,
            12));

    playbackArtist.setForeground(
        SECONDARY_TEXT);

    songText.add(
        playbackFileName);

    songText.add(
        Box.createVerticalStrut(4));

    songText.add(
        playbackArtist);

    songInformation.add(
        songText,
        BorderLayout.CENTER);

    panel.add(
        songInformation,
        BorderLayout.WEST);

    // --------------------------------------------------------
    // CENTER PLAYBACK AREA
    // --------------------------------------------------------

    JPanel playbackCenter = new JPanel();

    playbackCenter.setOpaque(false);

    playbackCenter.setLayout(
        new BoxLayout(
            playbackCenter,
            BoxLayout.Y_AXIS));

    JPanel sliderPanel = new JPanel(
        new BorderLayout(
            5,
            0));

    sliderPanel.setOpaque(false);

    JLabel currentTime = new JLabel("00:00");

    currentTime.setFont(
        new Font(
            "Segoe UI",
            Font.PLAIN,
            11));

    currentTime.setForeground(
        SECONDARY_TEXT);

    JLabel totalTime = new JLabel("00:00");

    totalTime.setFont(
        new Font(
            "Segoe UI",
            Font.PLAIN,
            11));

    totalTime.setForeground(
        SECONDARY_TEXT);

    playbackSlider = new JSlider(
        0,
        100,
        0);

    playbackSlider.setOpaque(false);

    playbackSlider.setForeground(
        BLUE);

    playbackSlider.setFocusable(false);

    sliderPanel.add(
        currentTime,
        BorderLayout.WEST);

    sliderPanel.add(
        playbackSlider,
        BorderLayout.CENTER);

    sliderPanel.add(
        totalTime,
        BorderLayout.EAST);

    playbackCenter.add(
        sliderPanel);

    // --------------------------------------------------------
    // PLAYBACK BUTTONS
    // --------------------------------------------------------

    JPanel controls = new JPanel(
        new FlowLayout(
            FlowLayout.CENTER,
            8,
            2));

    controls.setOpaque(false);

    JButton previous = createPlaybackButton("⏮");

    JButton rewind = createPlaybackButton("◀◀");

    JButton play = createPlaybackButton("▶");

    JButton forward = createPlaybackButton("▶▶");

    JButton next = createPlaybackButton("⏭");

    JButton stop = createPlaybackButton("■");

    previous.addActionListener(
        e -> showNotification(
            "Playback",
            "Previous button pressed."));

    rewind.addActionListener(
        e -> showNotification(
            "Playback",
            "Rewind button pressed."));

    play.addActionListener(
        e -> showNotification(
            "Playback",
            "Play button pressed."));

    forward.addActionListener(
        e -> showNotification(
            "Playback",
            "Forward button pressed."));

    next.addActionListener(
        e -> showNotification(
            "Playback",
            "Next button pressed."));

    stop.addActionListener(
        e -> showNotification(
            "Playback",
            "Stop button pressed."));

    controls.add(previous);
    controls.add(rewind);
    controls.add(play);
    controls.add(forward);
    controls.add(next);
    controls.add(stop);

    playbackCenter.add(
        controls);

    panel.add(
        playbackCenter,
        BorderLayout.CENTER);

    return panel;
  }

  // ============================================================
  // ADD FILE / FOLDER WINDOW
  // ============================================================

  private void openAddFileFolderWindow() {

    final JDialog dialog = new JDialog(
        this,
        "Add File / Folder",
        true);

    dialog.setSize(
        780,
        720);

    dialog.setMinimumSize(
        new Dimension(
            700,
            650));

    dialog.setLocationRelativeTo(this);

    JPanel main = new JPanel(
        new BorderLayout());

    main.setBackground(
        BACKGROUND);

    main.setBorder(
        BorderFactory.createEmptyBorder(
            25,
            30,
            25,
            30));

    JLabel title = new JLabel(
        "Add Music File or Folder");

    title.setFont(
        new Font(
            "Segoe UI",
            Font.BOLD,
            24));

    title.setForeground(
        TEXT);

    main.add(
        title,
        BorderLayout.NORTH);

    JPanel content = new JPanel();

    content.setOpaque(false);

    content.setLayout(
        new BoxLayout(
            content,
            BoxLayout.Y_AXIS));

    content.add(
        Box.createVerticalStrut(25));

    content.add(
        createSectionLabel(
            "File / Folder Path"));

    content.add(
        Box.createVerticalStrut(7));

    JPanel pathPanel = new JPanel(
        new BorderLayout(
            8,
            0));

    pathPanel.setOpaque(false);

    JTextField pathField = createTextField();

    JButton browseButton = createBlueButton(
        "Browse...");

    pathPanel.add(
        pathField,
        BorderLayout.CENTER);

    pathPanel.add(
        browseButton,
        BorderLayout.EAST);

    content.add(pathPanel);

    content.add(
        Box.createVerticalStrut(20));

    content.add(
        createSectionLabel("Type"));

    content.add(
        Box.createVerticalStrut(7));

    JPanel typePanel = new JPanel(
        new FlowLayout(
            FlowLayout.LEFT,
            10,
            0));

    typePanel.setOpaque(false);

    JRadioButton fileRadio = new JRadioButton("File");

    JRadioButton folderRadio = new JRadioButton("Folder");

    styleRadioButton(fileRadio);
    styleRadioButton(folderRadio);

    ButtonGroup typeGroup = new ButtonGroup();

    typeGroup.add(fileRadio);
    typeGroup.add(folderRadio);

    fileRadio.setSelected(true);

    typePanel.add(fileRadio);
    typePanel.add(folderRadio);

    content.add(typePanel);

    /*
     * --------------------------------------------------------
     * BROWSE
     * --------------------------------------------------------
     *
     * The chooser mode is explicitly determined at the
     * moment Browse is pressed.
     *
     * File -> FILES_ONLY
     * Folder -> DIRECTORIES_ONLY
     */

    browseButton.addActionListener(
        e -> {

          JFileChooser chooser = new JFileChooser();

          if (folderRadio.isSelected()) {

            chooser.setDialogTitle(
                "Select Music Folder");

            chooser.setFileSelectionMode(
                JFileChooser.DIRECTORIES_ONLY);

            chooser.setAcceptAllFileFilterUsed(
                false);

          } else {

            chooser.setDialogTitle(
                "Select Music File");

            chooser.setFileSelectionMode(
                JFileChooser.FILES_ONLY);

            chooser.setAcceptAllFileFilterUsed(
                true);
          }

          chooser.setMultiSelectionEnabled(
              false);

          int result = chooser.showOpenDialog(
              dialog);

          if (result == JFileChooser.APPROVE_OPTION) {

            File selected = chooser.getSelectedFile();

            pathField.setText(
                selected.getAbsolutePath());
          }
        });

    /*
     * The radio buttons also update the file chooser
     * behaviour immediately. The chooser itself is created
     * fresh each time Browse is pressed.
     */

    content.add(
        Box.createVerticalStrut(20));

    content.add(
        createSectionLabel(
            "How should this be added?"));

    content.add(
        Box.createVerticalStrut(7));

    String[] addMethods = {

        "Add as a separate song",

        "Add to existing album",

        "Add to existing playlist",

        "Create a new album",

        "Create a new playlist"
    };

    JComboBox<String> addMethod = new JComboBox<>(
        addMethods);

    styleComboBox(addMethod);

    content.add(addMethod);

    // --------------------------------------------------------
    // METADATA
    // --------------------------------------------------------

    content.add(
        Box.createVerticalStrut(25));

    content.add(
        createSectionLabel(
            "Metadata"));

    content.add(
        Box.createVerticalStrut(8));

    JLabel hint = new JLabel(
        "Optional information. "
            + "It will not be processed yet.");

    hint.setFont(
        new Font(
            "Segoe UI",
            Font.ITALIC,
            12));

    hint.setForeground(
        SECONDARY_TEXT);

    content.add(hint);

    content.add(
        Box.createVerticalStrut(12));

    Map<String, JTextField> metadataFields = new LinkedHashMap<>();

    metadataFields.put(
        "Song / File Name",
        createTextField());

    metadataFields.put(
        "Owner / Composer",
        createTextField());

    metadataFields.put(
        "Album Name",
        createTextField());

    metadataFields.put(
        "Album Publishing Date",
        createTextField());

    metadataFields.put(
        "Singers / Co-Singers",
        createTextField());

    metadataFields.put(
        "Genre",
        createTextField());

    metadataFields.put(
        "Language",
        createTextField());

    metadataFields.put(
        "Description",
        createTextField());

    for (Map.Entry<String, JTextField> entry : metadataFields.entrySet()) {

      JPanel row = new JPanel(
          new BorderLayout(
              15,
              5));

      row.setOpaque(false);

      JLabel label = new JLabel(
          entry.getKey());

      label.setFont(NORMAL_FONT);

      label.setForeground(TEXT);

      label.setPreferredSize(
          new Dimension(
              180,
              32));

      row.add(
          label,
          BorderLayout.WEST);

      row.add(
          entry.getValue(),
          BorderLayout.CENTER);

      content.add(row);

      content.add(
          Box.createVerticalStrut(8));
    }

    JScrollPane scrollPane = new JScrollPane(content);

    scrollPane.setBorder(null);

    scrollPane.setBackground(
        BACKGROUND);

    scrollPane.getViewport().setBackground(
        BACKGROUND);

    scrollPane.getVerticalScrollBar()
        .setUnitIncrement(15);

    main.add(
        scrollPane,
        BorderLayout.CENTER);

    // --------------------------------------------------------
    // BOTTOM BUTTONS
    // --------------------------------------------------------

    JPanel bottomPanel = new JPanel(
        new FlowLayout(
            FlowLayout.RIGHT,
            10,
            0));

    bottomPanel.setOpaque(false);

    JButton cancelButton = createSecondaryButton(
        "Cancel");

    JButton saveButton = createBlueButton(
        "Save");

    cancelButton.addActionListener(
        e -> dialog.dispose());

    saveButton.addActionListener(
        e -> {

          String path = pathField
              .getText()
              .trim();

          String type = fileRadio.isSelected()
              ? "File"
              : "Folder";

          String method = String.valueOf(
              addMethod
                  .getSelectedItem());

          StringBuilder message = new StringBuilder();

          message.append(
              "Music item information:\n\n");

          message.append(
              "Type: ");

          message.append(type);

          message.append(
              "\nPath: ");

          message.append(
              path.isEmpty()
                  ? "(not specified)"
                  : path);

          message.append(
              "\nAdd Method: ");

          message.append(method);

          message.append(
              "\n\nMetadata:");

          for (Map.Entry<String, JTextField> entry : metadataFields.entrySet()) {

            String value = entry.getValue()
                .getText()
                .trim();

            if (!value.isEmpty()) {

              message.append("\n");

              message.append(
                  entry.getKey());

              message.append(": ");

              message.append(value);
            }
          }

          showDarkNotification(
              dialog,
              "Save",
              message.toString());
        });

    bottomPanel.add(
        cancelButton);

    bottomPanel.add(
        saveButton);

    main.add(
        bottomPanel,
        BorderLayout.SOUTH);

    dialog.setContentPane(main);

    dialog.setVisible(true);
  }

  // ============================================================
  // TEXT COMPONENTS
  // ============================================================

  private JLabel createSectionLabel(
      String text) {

    JLabel label = new JLabel(text);

    label.setFont(
        new Font(
            "Segoe UI",
            Font.BOLD,
            14));

    label.setForeground(TEXT);

    return label;
  }

  private JTextField createTextField() {

    JTextField field = new JTextField();

    field.setFont(NORMAL_FONT);

    field.setForeground(TEXT);

    field.setCaretColor(TEXT);

    field.setBackground(
        INPUT_BACKGROUND);

    field.setOpaque(true);

    field.setBorder(
        BorderFactory.createCompoundBorder(
            new RoundedBorder(
                BORDER,
                8),
            BorderFactory.createEmptyBorder(
                7,
                10,
                7,
                10)));

    field.setPreferredSize(
        new Dimension(
            100,
            35));

    return field;
  }

  private void styleComboBox(
      JComboBox<String> comboBox) {

    comboBox.setFont(NORMAL_FONT);

    comboBox.setForeground(TEXT);

    comboBox.setBackground(
        CARD_BACKGROUND);

    comboBox.setOpaque(true);

    comboBox.setPreferredSize(
        new Dimension(
            300,
            38));
  }

  private void styleRadioButton(
      JRadioButton radio) {

    radio.setFont(NORMAL_FONT);

    radio.setForeground(TEXT);

    radio.setBackground(
        PANEL_BACKGROUND);

    radio.setOpaque(false);

    radio.setFocusPainted(false);
  }

  // ============================================================
  // BUTTONS
  // ============================================================

  private JButton createBlueButton(
      String text) {

    JButton button = new JButton(text);

    button.setFont(
        new Font(
            "Segoe UI",
            Font.BOLD,
            14));

    button.setForeground(
        Color.WHITE);

    button.setBackground(
        BLUE);

    button.setOpaque(true);

    button.setContentAreaFilled(false); // Disables default native OS gradients

    button.setFocusPainted(false);

    button.setBorder(
        BorderFactory.createEmptyBorder(
            9,
            18,
            9,
            18));

    button.setCursor(
        new Cursor(
            Cursor.HAND_CURSOR));

    button.addMouseListener(
        new MouseAdapter() {

          @Override
          public void mouseEntered(
              MouseEvent e) {

            button.setBackground(
                BLUE_HOVER);
          }

          @Override
          public void mouseExited(
              MouseEvent e) {

            button.setBackground(
                BLUE);
          }
        });

    return button;
  }

  private JButton createSecondaryButton(
      String text) {

    JButton button = new JButton(text);

    button.setFont(NORMAL_FONT);

    button.setForeground(TEXT);

    button.setBackground(
        CARD_BACKGROUND);

    button.setOpaque(true);

    button.setContentAreaFilled(false); // Disables default native OS gradients

    button.setFocusPainted(false);

    button.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                BORDER),
            BorderFactory.createEmptyBorder(
                8,
                18,
                8,
                18)));

    button.setCursor(
        new Cursor(
            Cursor.HAND_CURSOR));

    return button;
  }

  private JButton createPlaybackButton(
      String text) {

    JButton button = new JButton(text);

    button.setFont(
        new Font(
            "Segoe UI Symbol",
            Font.PLAIN,
            16));

    button.setForeground(TEXT);

    button.setBackground(
        new Color(
            24,
            29,
            38));

    button.setOpaque(true);

    button.setContentAreaFilled(false); // Disables default native OS gradients

    button.setFocusPainted(false);

    button.setBorder(
        BorderFactory.createEmptyBorder(
            5,
            10,
            5,
            10));

    button.setCursor(
        new Cursor(
            Cursor.HAND_CURSOR));

    button.addMouseListener(
        new MouseAdapter() {

          @Override
          public void mouseEntered(
              MouseEvent e) {

            button.setBackground(
                BLUE_DARK);
          }

          @Override
          public void mouseExited(
              MouseEvent e) {

            button.setBackground(
                new Color(
                    24,
                    29,
                    38));
          }
        });

    return button;
  }

  // ============================================================
  // NOTIFICATIONS
  // ============================================================

  private void showNotImplemented(
      String action) {

    showDarkNotification(
        this,
        action,
        action
            + " was pressed.\n\n"
            + "This feature will be implemented later.");
  }

  private void showNotification(
      String title,
      String message) {
    showDarkNotification(
        this,
        title,
        message);
  }

  /*
   * A custom notification is used instead of the default
   * JOptionPane so Windows' Look & Feel cannot turn the
   * notification white.
   */

  private void showDarkNotification(
      Component parent,
      String title,
      String message) {

    Window owner = SwingUtilities.getWindowAncestor(
        parent);

    final JDialog dialog = new JDialog(
        owner,
        title,
        Dialog.ModalityType.APPLICATION_MODAL);

    dialog.setSize(
        430,
        230);

    dialog.setLocationRelativeTo(
        parent);

    JPanel root = new JPanel(
        new BorderLayout(
            15,
            15));

    root.setBackground(
        PANEL_BACKGROUND);

    root.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                BORDER),
            BorderFactory.createEmptyBorder(
                20,
                22,
                18,
                22)));

    JLabel titleLabel = new JLabel(title);

    titleLabel.setFont(
        new Font(
            "Segoe UI",
            Font.BOLD,
            18));

    titleLabel.setForeground(
        TEXT);

    JTextArea messageArea = new JTextArea(message);

    messageArea.setFont(
        NORMAL_FONT);

    messageArea.setForeground(
        TEXT);

    messageArea.setBackground(
        PANEL_BACKGROUND);

    messageArea.setEditable(false);

    messageArea.setFocusable(false);

    messageArea.setLineWrap(true);

    messageArea.setWrapStyleWord(true);

    messageArea.setBorder(null);

    JButton okButton = createBlueButton("OK");

    okButton.addActionListener(
        e -> dialog.dispose());

    JPanel buttonPanel = new JPanel(
        new FlowLayout(
            FlowLayout.RIGHT,
            0,
            0));

    buttonPanel.setOpaque(false);

    buttonPanel.add(okButton);

    root.add(
        titleLabel,
        BorderLayout.NORTH);

    root.add(
        messageArea,
        BorderLayout.CENTER);

    root.add(
        buttonPanel,
        BorderLayout.SOUTH);

    dialog.setContentPane(root);

    dialog.setResizable(false);

    dialog.setVisible(true);
  }

  // ============================================================
  // PLAYLIST ICON
  // ============================================================

  private static class PlaylistIconPanel
      extends JPanel {

    private final int iconNumber;

    public PlaylistIconPanel(
        int iconNumber) {

      this.iconNumber = iconNumber;

      setOpaque(false);
    }

    @Override
    protected void paintComponent(
        Graphics graphics) {

      super.paintComponent(
          graphics);

      Graphics2D g = (Graphics2D) graphics.create();

      g.setRenderingHint(
          RenderingHints.KEY_ANTIALIASING,
          RenderingHints.VALUE_ANTIALIAS_ON);

      int width = getWidth();

      int height = getHeight();

      int size = Math.min(
          width,
          height) - 35;

      int x = (width - size) / 2;

      int y = (height - size) / 2;

      int blueValue = Math.min(
          255,
          190 + iconNumber * 12);

      g.setColor(
          new Color(
              45,
              110 + iconNumber * 10,
              blueValue));

      g.fillRoundRect(
          x,
          y,
          size,
          size,
          20,
          20);

      g.setColor(
          new Color(
              240,
              246,
              255));

      g.setStroke(
          new BasicStroke(
              7,
              BasicStroke.CAP_ROUND,
              BasicStroke.JOIN_ROUND));

      int noteX = x + size / 2 + 5;

      int noteTop = y + size / 4;

      g.drawLine(
          noteX,
          noteTop,
          noteX,
          y + size * 2 / 3);

      g.drawLine(
          noteX,
          noteTop,
          noteX + size / 4,
          noteTop - 10);

      g.fillOval(
          noteX - 20,
          y + size * 2 / 3 - 5,
          25,
          25);

      g.fillOval(
          noteX + size / 4 - 20,
          y + size * 2 / 3 - 15,
          25,
          25);

      g.dispose();
    }
  }

  // ============================================================
  // PLAYBACK ICON
  // ============================================================

  private static class PlaybackIconPanel
      extends JPanel {

    public PlaybackIconPanel() {
      setOpaque(false);
    }

    @Override
    protected void paintComponent(
        Graphics graphics) {

      super.paintComponent(
          graphics);

      Graphics2D g = (Graphics2D) graphics.create();

      g.setRenderingHint(
          RenderingHints.KEY_ANTIALIASING,
          RenderingHints.VALUE_ANTIALIAS_ON);

      int width = getWidth();

      int height = getHeight();

      int size = Math.min(
          width,
          height) - 4;

      int x = (width - size) / 2;

      int y = (height - size) / 2;

      g.setColor(BLUE);

      g.fillRoundRect(
          x,
          y,
          size,
          size,
          12,
          12);

      g.setColor(
          new Color(
              240,
              246,
              255));

      g.setStroke(
          new BasicStroke(
              4,
              BasicStroke.CAP_ROUND,
              BasicStroke.JOIN_ROUND));

      int noteX = x + size / 2;

      int noteY = y + size / 4;

      g.drawLine(
          noteX,
          noteY,
          noteX,
          y + size * 2 / 3);

      g.drawLine(
          noteX,
          noteY,
          x + size * 3 / 4,
          noteY - 6);

      g.fillOval(
          noteX - 13,
          y + size * 2 / 3 - 2,
          17,
          17);

      g.fillOval(
          x + size * 3 / 4 - 13,
          y + size * 2 / 3 - 10,
          17,
          17);

      g.dispose();
    }
  }

  // ============================================================
  // ROUNDED BORDER
  // ============================================================

  private static class RoundedBorder
      extends AbstractBorder {

    private final Color color;
    private final int radius;

    public RoundedBorder(
        Color color,
        int radius) {

      this.color = color;

      this.radius = radius;
    }

    @Override
    public void paintBorder(
        Component component,
        Graphics graphics,
        int x,
        int y,
        int width,
        int height) {

      Graphics2D g = (Graphics2D) graphics.create();

      g.setRenderingHint(
          RenderingHints.KEY_ANTIALIASING,
          RenderingHints.VALUE_ANTIALIAS_ON);

      g.setColor(color);

      g.drawRoundRect(
          x,
          y,
          width - 1,
          height - 1,
          radius,
          radius);

      g.dispose();
    }

    @Override
    public Insets getBorderInsets(
        Component component) {

      return new Insets(
          1,
          1,
          1,
          1);
    }
  }

  // ============================================================
  // MAIN
  // ============================================================

  public static void main(
      String[] args) {

    SwingUtilities.invokeLater(
        MusicManagementGUI::new);
  }
}
