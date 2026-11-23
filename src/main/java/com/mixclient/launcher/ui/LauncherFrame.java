package com.mixclient.launcher.ui;

import com.mixclient.launcher.auth.Accounts;
import com.mixclient.launcher.core.AppPaths;
import com.mixclient.launcher.core.BuildInfo;
import com.mixclient.launcher.core.LauncherConfig;
import com.mixclient.launcher.update.Updater;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;

/** MixClient main window: 2007-style sidebar navigation plus the play / settings / log views. */
public final class LauncherFrame extends JFrame {

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    /** AppKit's title bar on macOS 11+; the traffic lights sit in this band. */
    private static final int MAC_TITLE_BAR_HEIGHT = 28;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final LogPanel logPanel = new LogPanel();
    private final HomePanel homePanel;
    private final SettingsPanel settingsPanel;
    private final JLabel footer = new JLabel(" ");
    private final JLabel updateLabel = new JLabel(" ");
    private final JButton updateButton = Theme.secondaryButton("Update");

    public LauncherFrame(LauncherConfig config, Accounts accounts) {
        super("MixClient");

        // macOS already draws a title bar; let the content cover it so the blue strip below
        // is the only title bar. The traffic lights stay, drawn over the strip's left inset.
        if (MAC) {
            getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            // Without this AppKit still draws its centred title over the blue strip.
            getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
        }

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(860, 560));
        setSize(940, 640);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG);

        homePanel = new HomePanel(this, config, accounts, logPanel);
        settingsPanel = new SettingsPanel(config, updated -> refreshFooter());

        cards.setOpaque(false);
        cards.add(homePanel, "home");
        cards.add(settingsPanel, "settings");
        cards.add(logPanel, "logs");

        JPanel main = new JPanel(new BorderLayout());
        main.setOpaque(false);
        main.setBorder(Theme.padding(8, 8, 4, 8));
        main.add(cards, BorderLayout.CENTER);
        main.add(statusBar(), BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(titleBar(), BorderLayout.NORTH);
        add(sidebar(), BorderLayout.WEST);
        add(main, BorderLayout.CENTER);

        refreshFooter();
        cardLayout.show(cards, "home");
    }

    private JPanel titleBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, Theme.ACCENT_2, getWidth(), 0, Theme.ACCENT));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Theme.ACCENT_DARK);
                g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
                g2.dispose();
            }
        };
        bar.setPreferredSize(new Dimension(0, MAC ? MAC_TITLE_BAR_HEIGHT : 22));
        // On macOS the left inset keeps the title clear of the traffic lights.
        bar.setBorder(Theme.padding(MAC ? 5 : 2, MAC ? 78 : 8, 2, 8));
        makeDraggable(bar);

        JLabel title = new JLabel("MixClient " + BuildInfo.VERSION);
        Theme.styleLabel(title, Theme.BOLD_FONT, Color.WHITE);

        JLabel hint = new JLabel("PvP launcher");
        Theme.styleLabel(hint, Theme.SMALL_FONT, new Color(0xD8E2F5));

        bar.add(title, BorderLayout.WEST);
        bar.add(hint, BorderLayout.EAST);
        return bar;
    }

    private JPanel sidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setBackground(Theme.SURFACE);
        sidebar.setPreferredSize(new Dimension(148, 0));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER),
                Theme.padding(6, 4, 6, 4)));

        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.X_AXIS));
        brand.add(new Theme.Logo(20));
        brand.add(Box.createHorizontalStrut(5));

        JLabel name = new JLabel("MixClient");
        Theme.styleLabel(name, Theme.BOLD_FONT.deriveFont(12f), Theme.ACCENT_2);
        brand.add(name);
        brand.add(Box.createHorizontalGlue());
        brand.setAlignmentX(LEFT_ALIGNMENT);
        brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        sidebar.add(brand);
        sidebar.add(Box.createVerticalStrut(8));

        ButtonGroup group = new ButtonGroup();
        JToggleButton home = navButton("Play", "home", group);
        JToggleButton settings = navButton("Settings", "settings", group);
        JToggleButton logs = navButton("Logs", "logs", group);

        sidebar.add(home);
        sidebar.add(Box.createVerticalStrut(2));
        sidebar.add(settings);
        sidebar.add(Box.createVerticalStrut(2));
        sidebar.add(logs);
        sidebar.add(Box.createVerticalGlue());

        JLabel signIn = new JLabel("Microsoft sign-in");
        Theme.styleLabel(signIn, Theme.SMALL_FONT, Theme.MUTED);
        signIn.setAlignmentX(LEFT_ALIGNMENT);
        sidebar.add(signIn);

        JLabel built = new JLabel("v" + BuildInfo.VERSION);
        Theme.styleLabel(built, Theme.SMALL_FONT, Theme.MUTED);
        built.setAlignmentX(LEFT_ALIGNMENT);
        sidebar.add(Box.createVerticalStrut(1));
        sidebar.add(built);

        home.setSelected(true);
        return sidebar;
    }

    private JToggleButton navButton(String text, String card, ButtonGroup group) {
        JToggleButton button = Theme.navToggle(text);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        button.setPreferredSize(new Dimension(140, 20));
        button.setAlignmentX(LEFT_ALIGNMENT);

        button.addItemListener(e -> {
            if (button.isSelected()) {
                cardLayout.show(cards, card);
                if ("logs".equals(card)) {
                    refreshFooter();
                }
            }
        });
        group.add(button);
        return button;
    }

    private JPanel statusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(Theme.padding(4, 0, 0, 0));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Theme.SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLoweredBevelBorder(),
                Theme.padding(1, 4, 1, 4)));
        Theme.styleLabel(footer, Theme.SMALL_FONT, Theme.MUTED);
        panel.add(footer, BorderLayout.WEST);

        JPanel updates = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        updates.setOpaque(false);
        Theme.styleLabel(updateLabel, Theme.SMALL_FONT, Theme.MUTED);
        updateButton.setPreferredSize(new Dimension(58, 17));
        updateButton.setFont(Theme.SMALL_FONT);
        updateButton.setVisible(false);
        updates.add(updateLabel);
        updates.add(updateButton);
        panel.add(updates, BorderLayout.EAST);
        // 21 leaves room for a 10px line inside the lowered bevel; 18 clipped descenders.
        panel.setPreferredSize(new Dimension(0, 21));

        bar.add(panel, BorderLayout.CENTER);
        return bar;
    }

    /**
     * Drags the window from the title strip. With the native title bar covered the OS may not do
     * this for us; if it did move the window already, the target equals the current location and
     * nothing is applied twice.
     */
    private void makeDraggable(JComponent handle) {
        MouseAdapter drag = new MouseAdapter() {
            private Point pressScreen;
            private Point pressWindow;

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1 || !isShowing()) {
                    return;
                }
                pressScreen = e.getLocationOnScreen();
                pressWindow = getLocationOnScreen();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressScreen == null) {
                    return;
                }
                Point now = e.getLocationOnScreen();
                int x = pressWindow.x + (now.x - pressScreen.x);
                int y = pressWindow.y + (now.y - pressScreen.y);
                Point current = getLocationOnScreen();
                if (Math.abs(current.x - x) > 1 || Math.abs(current.y - y) > 1) {
                    setLocation(x, y);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                pressScreen = null;
            }
        };
        handle.addMouseListener(drag);
        handle.addMouseMotionListener(drag);
    }

    private void refreshFooter() {
        footer.setText("Data: " + AppPaths.appDir());
    }

    /** Shows what the updater is doing in the status bar. */
    public void setUpdateStatus(String text) {
        SwingUtilities.invokeLater(() -> {
            updateLabel.setText(text == null || text.isBlank() ? " " : text);
            updateButton.setVisible(false);
        });
    }

    /** Offers a new release, with the button running {@code onInstall} on the caller's thread. */
    public void setUpdateAvailable(Updater.Release release, Runnable onInstall) {
        SwingUtilities.invokeLater(() -> {
            updateLabel.setText("MixClient " + release.version() + " is available");
            updateButton.setVisible(true);
            updateButton.setEnabled(true);
            updateButton.setText("Update");
            for (java.awt.event.ActionListener listener : updateButton.getActionListeners()) {
                updateButton.removeActionListener(listener);
            }
            updateButton.addActionListener(e -> {
                updateButton.setEnabled(false);
                updateButton.setText("Updating…");
                onInstall.run();
            });
        });
    }
}
