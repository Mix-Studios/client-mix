package com.mixclient.launcher.ui;

import com.mixclient.launcher.auth.Account;
import com.mixclient.launcher.auth.Accounts;
import com.mixclient.launcher.auth.AuthException;
import com.mixclient.launcher.auth.PlayerSession;
import com.mixclient.launcher.client.ClientBundle;
import com.mixclient.launcher.core.AppPaths;
import com.mixclient.launcher.core.LauncherConfig;
import com.mixclient.launcher.core.Progress;
import com.mixclient.launcher.game.AssetInstaller;
import com.mixclient.launcher.game.GameLauncher;
import com.mixclient.launcher.game.JavaRuntime;
import com.mixclient.launcher.game.LegacyMacRuntime;
import com.mixclient.launcher.game.LoaderInstaller;
import com.mixclient.launcher.game.MojangApi;
import com.mixclient.launcher.game.PreparedInstall;
import com.mixclient.launcher.game.VersionResolver;
import com.google.gson.JsonObject;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/** The launch screen and the Microsoft account card. */
public final class HomePanel extends JPanel {

    private final Window owner;
    private final LauncherConfig config;
    private final Accounts accounts;
    private final LogPanel logPanel;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "launcher-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final Avatar avatar = new Avatar();
    private final JLabel nameLabel = new JLabel("Not signed in");
    private final JLabel detailLabel = new JLabel("Sign in with Microsoft to play.");
    private final JButton accountButton = Theme.secondaryButton("Sign in");
    private final JButton signOutButton = Theme.secondaryButton("Sign out");
    private final JComboBox<String> versionBox = new JComboBox<>();
    private final JButton refreshVersions = Theme.secondaryButton("Refresh");
    private final JLabel javaLabel = new JLabel(" ");
    private final JButton javaButton = Theme.secondaryButton("Java…");
    private final JButton playButton = Theme.primaryButton("PLAY");
    private final JProgressBar progressBar = new JProgressBar(0, 1000);
    private final JLabel statusLabel = new JLabel(" ");

    private volatile boolean busy;
    /** True while the version list is being replaced, so its events do not start Java lookups. */
    private boolean loadingVersions;

    public HomePanel(Window owner, LauncherConfig config, Accounts accounts, LogPanel logPanel) {
        super();
        this.owner = owner;
        this.config = config;
        this.accounts = accounts;
        this.logPanel = logPanel;

        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        buildUi();

        accounts.setChangeListener(() -> SwingUtilities.invokeLater(this::refreshAccount));
        refreshAccount();
        loadVersions();
    }

    private void buildUi() {
        add(header());
        add(Box.createVerticalStrut(10));

        add(sectionLabel("ACCOUNT"));
        add(Box.createVerticalStrut(3));
        add(accountCard());
        add(Box.createVerticalStrut(10));

        add(sectionLabel("VERSION"));
        add(Box.createVerticalStrut(3));
        add(versionRow());
        add(Box.createVerticalStrut(4));
        add(javaRow());
        add(Box.createVerticalStrut(12));

        playButton.setAlignmentX(LEFT_ALIGNMENT);
        playButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        playButton.setPreferredSize(new Dimension(0, 32));
        playButton.setFont(Theme.BOLD_FONT.deriveFont(12f));
        playButton.addActionListener(e -> {
            if (accounts.selected() == null) {
                signIn();
            } else {
                startGame();
            }
        });
        add(playButton);
        add(Box.createVerticalStrut(6));

        progressBar.setVisible(false);
        progressBar.setBorder(Theme.fieldBorder());
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
        progressBar.setPreferredSize(new Dimension(0, 16));
        progressBar.setAlignmentX(LEFT_ALIGNMENT);
        add(progressBar);
        add(Box.createVerticalStrut(4));

        Theme.styleLabel(statusLabel, Theme.SMALL_FONT, Theme.MUTED);
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        add(statusLabel);
        add(Box.createVerticalGlue());
    }

    private JComponent header() {
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.setAlignmentX(LEFT_ALIGNMENT);
        header.add(new Theme.Logo(26), BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("MIXCLIENT");
        Theme.styleLabel(title, Theme.TITLE_FONT, Theme.ACCENT_2);

        JLabel subtitle = new JLabel("PvP launcher for Minecraft: Java Edition");
        Theme.styleLabel(subtitle, Theme.SMALL_FONT, Theme.MUTED);

        text.add(title);
        text.add(subtitle);
        header.add(text, BorderLayout.CENTER);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return header;
    }

    private JComponent sectionLabel(String text) {
        JLabel label = new JLabel(text);
        Theme.styleLabel(label, Theme.BOLD_FONT.deriveFont(9f), new Color(0x3A3A3A));
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setBorder(Theme.padding(0, 0, 1, 0));
        return label;
    }

    private JComponent accountCard() {
        JPanel card = Theme.card(new BorderLayout(8, 0));
        card.setBorder(Theme.padding(6, 6, 6, 6));
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        card.add(avatar, BorderLayout.WEST);

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        Theme.styleLabel(nameLabel, Theme.BOLD_FONT.deriveFont(12f), Theme.TEXT);
        Theme.styleLabel(detailLabel, Theme.SMALL_FONT, Theme.MUTED);
        info.add(Box.createVerticalGlue());
        info.add(nameLabel);
        info.add(Box.createVerticalStrut(1));
        info.add(detailLabel);
        info.add(Box.createVerticalGlue());
        card.add(info, BorderLayout.CENTER);

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));

        accountButton.addActionListener(e -> {
            if (accounts.all().isEmpty()) {
                signIn();
            } else {
                showAccountMenu();
            }
        });
        signOutButton.addActionListener(e -> {
            Account account = accounts.selected();
            if (account != null) {
                accounts.signOut(account);
                logPanel.append("[auth] Signed out " + account.name());
            }
        });

        actions.add(signOutButton);
        actions.add(Box.createHorizontalStrut(4));
        actions.add(accountButton);
        card.add(actions, BorderLayout.EAST);
        return card;
    }

    private JComponent versionRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));

        Theme.styleCombo(versionBox);
        versionBox.addActionListener(e -> {
            if (!loadingVersions) {
                refreshJava();
            }
        });
        versionBox.setPreferredSize(new Dimension(180, 22));
        versionBox.setMaximumSize(new Dimension(180, 22));
        versionBox.setMinimumSize(new Dimension(120, 22));

        refreshVersions.addActionListener(e -> loadVersions());

        row.add(versionBox);
        row.add(Box.createHorizontalStrut(4));
        row.add(refreshVersions);
        row.add(Box.createHorizontalGlue());
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    /** The Java line under the version picker, plus the per-version picker. */
    private JComponent javaRow() {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));

        Theme.styleLabel(javaLabel, Theme.SMALL_FONT, Theme.MUTED);
        javaButton.setFont(Theme.SMALL_FONT);
        javaButton.setPreferredSize(new Dimension(64, 20));
        javaButton.setMaximumSize(new Dimension(64, 20));
        javaButton.addActionListener(e -> showJavaMenu());

        row.add(javaLabel);
        row.add(Box.createHorizontalStrut(6));
        row.add(javaButton);
        row.add(Box.createHorizontalGlue());
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    // ------------------------------------------------------------- account

    public void refreshAccount() {
        Account account = accounts.selected();

        if (account == null) {
            avatar.setInitial("?");
            nameLabel.setText("Not signed in");
            detailLabel.setText("Sign in with Microsoft to play.");
            accountButton.setText("Sign in");
            signOutButton.setVisible(false);
            playButton.setText(busy ? "LAUNCHING…" : "SIGN IN TO PLAY");
            playButton.setEnabled(!busy);
        } else {
            avatar.setInitial(account.initial());
            nameLabel.setText(account.name());
            detailLabel.setText(account.uuid().isEmpty() ? "Microsoft account" : account.uuid());
            accountButton.setText(accounts.all().size() > 1 ? "Switch" : "Add account");
            signOutButton.setVisible(true);
            signOutButton.setEnabled(!busy);
            accountButton.setEnabled(!busy);
            playButton.setText(busy ? "LAUNCHING…" : "PLAY");
            playButton.setEnabled(!busy);
        }
        playButton.repaint();
    }

    private void showAccountMenu() {
        JPopupMenu menu = new JPopupMenu();
        Account selected = accounts.selected();

        for (Account account : accounts.all()) {
            JMenuItem item = new JMenuItem(account.name()
                    + (account == selected ? "   (active)" : ""));
            item.addActionListener(e -> {
                accounts.select(account);
                refreshAccount();
            });
            menu.add(item);
        }
        if (!accounts.all().isEmpty()) {
            menu.addSeparator();
        }
        JMenuItem add = new JMenuItem("Add Microsoft account…");
        add.addActionListener(e -> signIn());
        menu.add(add);
        menu.show(accountButton, 0, accountButton.getHeight());
    }

    /** Runs the device-code flow and shows the code dialog while MinecraftAuth polls. */
    public void signIn() {
        if (busy) {
            return;
        }
        DeviceCodeDialog dialog = new DeviceCodeDialog(owner);
        Thread worker = new Thread(() -> {
            try {
                Account account = accounts.login(code -> SwingUtilities.invokeLater(() -> dialog.showCode(code)));
                SwingUtilities.invokeLater(() -> {
                    dialog.close();
                    refreshAccount();
                    statusLabel.setText("Signed in as " + account.name());
                    logPanel.append("[auth] Signed in as " + account.name() + " (" + account.uuid() + ") with the Minecraft launcher client id.");
                });
            } catch (AuthException e) {
                SwingUtilities.invokeLater(() -> {
                    if (e.isCancelled()) {
                        dialog.close();
                        statusLabel.setText("Sign-in cancelled.");
                        logPanel.append("[auth] Sign-in cancelled.");
                    } else {
                        dialog.showError(e.getMessage());
                        logPanel.append("[auth] " + e.getMessage());
                    }
                });
            }
        }, "microsoft-login");
        worker.setDaemon(true);
        dialog.setOnCancel(worker::interrupt);
        worker.start();
    }

    // -------------------------------------------------------------- launch

    private void startGame() {
        if (busy) {
            return;
        }
        Account account = accounts.selected();
        if (account == null) {
            signIn();
            return;
        }
        String versionId = selectedVersion();
        if (versionId == null || versionId.isBlank()) {
            showError("No version selected", "Choose a Minecraft version first.");
            return;
        }

        config.selectedVersion = versionId;
        config.save();

        setBusy(true, "Checking your Microsoft session…");

        executor.submit(() -> {
            try {
                // Refresh the Minecraft token + profile if they expired before handing them to the game.
                PlayerSession session = accounts.prepareLaunch(account);
                SwingUtilities.invokeLater(this::refreshAccount);

                Path gameDir = gameDir();
                Progress reporter = new Progress() {
                    @Override
                    public void update(String message, double fraction) {
                        SwingUtilities.invokeLater(() -> updateStatus(message, fraction));
                    }
                };

                // Vanilla ignores mods/, so the bundled client only runs through its loader.
                LoaderInstaller.Loader loader = LoaderInstaller.ensure(versionId, gameDir, reporter);
                if (!loader.name().isEmpty()) {
                    logPanel.append("[loader] " + versionId + " runs on " + loader.name()
                            + " as " + loader.versionId());
                }

                // The client ships inside the launcher: put the matching build in mods/ first.
                try {
                    ClientBundle.Result bundled = ClientBundle.install(versionId, gameDir);
                    for (String file : bundled.installed()) {
                        logPanel.append("[client] Installed bundled " + file);
                    }
                    for (String file : bundled.removed()) {
                        logPanel.append("[client] Removed old " + file);
                    }
                } catch (IOException e) {
                    logPanel.append("[client] Could not install the bundled client: " + e.getMessage());
                }

                logPanel.append("[launch] Preparing " + loader.versionId() + " in " + gameDir);
                PreparedInstall install = new AssetInstaller(new MojangApi(AppPaths.cacheDir()), gameDir)
                        .prepare(loader.versionId(), reporter);

                // Java is chosen per version (1.8.9 needs Java 8, 26.3 needs 25+); a missing
                // runtime fails here with an actionable message instead of exit code 1. On an
                // Apple-silicon Mac, 1.8.9 gets an arm64 Java 8 and the patched LWJGL 2 natives,
                // so it runs natively instead of through Rosetta (where Apple's OpenGL driver
                // sometimes aborts it with SIGABRT / exit 134).
                String javaOverride = config.javaFor(versionId);
                JavaRuntime.Match java;
                Path nativesOverride = null;
                LegacyMacRuntime.Runtime nativeRuntime = null;
                if (javaOverride.isBlank() && LegacyMacRuntime.isNeeded(install.requiredJavaMajor())) {
                    try {
                        nativeRuntime = LegacyMacRuntime.ensure(reporter);
                    } catch (IOException e) {
                        logPanel.append("[java] Could not install the Apple-silicon runtime: "
                                + e.getMessage() + "; falling back to Rosetta");
                    }
                }
                if (nativeRuntime != null) {
                    java = new JavaRuntime.Match(nativeRuntime.java(), 8, "aarch64");
                    nativesOverride = nativeRuntime.nativesDir();
                    logPanel.append("[java] Using arm64 Java 8 with patched LWJGL 2 (no Rosetta)");
                } else {
                    java = JavaRuntime.resolve(versionId,
                            install.requiredJavaMajor(), javaOverride);
                }
                logPanel.append("[java] " + loader.versionId() + " needs Java " + install.requiredJavaMajor()
                        + "+; using Java " + java.major() + " (" + java.arch() + "): " + java.java()
                        + (javaOverride.isBlank() ? "" : " (set for " + versionId + ")"));

                SwingUtilities.invokeLater(() -> updateStatus("Starting Minecraft…", 1.0));
                GameLauncher.start(install, session, config, java.java(), nativesOverride,
                        logPanel::append,
                        code -> SwingUtilities.invokeLater(() -> onGameExit(code)));

                logPanel.append("[launch] Minecraft started as " + session.username()
                        + " (uuid " + session.uuid() + ")");
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, "Game running. Press PLAY again for another instance.");
                    if (config.closeLauncherOnLaunch && owner != null) {
                        owner.setVisible(false);
                    }
                });
            } catch (AuthException e) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, " ");
                    refreshAccount();
                    showError("Microsoft sign-in problem", e.getMessage());
                });
                logPanel.append("[auth] " + e.getMessage());
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, " ");
                    showError("Couldn't launch Minecraft", message);
                });
                logPanel.append("[launch] " + message);
            }
        });
    }

    private void onGameExit(int exitCode) {
        logPanel.append("[launch] Minecraft exited with code " + exitCode);
        setBusy(false, exitCode == 0 ? "Minecraft closed." : "Minecraft exited with code " + exitCode + ".");
    }

    private void setBusy(boolean value, String message) {
        this.busy = value;
        progressBar.setVisible(value);
        progressBar.setIndeterminate(false);
        if (value) {
            progressBar.setValue(0);
        }
        if (message != null) {
            statusLabel.setText(message);
        }
        refreshAccount();
    }

    private void updateStatus(String message, double fraction) {
        statusLabel.setText(message);
        if (fraction >= 0) {
            progressBar.setIndeterminate(false);
            progressBar.setValue((int) Math.round(fraction * 1000));
        } else {
            progressBar.setIndeterminate(true);
        }
    }

    private void showError(String title, String message) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private String selectedVersion() {
        Object selected = versionBox.getSelectedItem();
        return selected == null ? null : selected.toString();
    }

    private Path gameDir() {
        return config.gameDir == null || config.gameDir.isBlank()
                ? AppPaths.gameDir() : Path.of(config.gameDir.trim());
    }

    // ---------------------------------------------------------------- java

    /**
     * Shows the Java the selected version will start with, before Play is pressed. Resolution runs
     * off the UI thread because it may probe several JVMs and read the version metadata.
     */
    private void refreshJava() {
        String versionId = selectedVersion();
        if (versionId == null || versionId.isBlank()) {
            javaLabel.setText(" ");
            javaButton.setEnabled(false);
            return;
        }
        javaButton.setEnabled(true);
        javaLabel.setText("Checking Java…");
        javaLabel.setForeground(Theme.MUTED);
        javaLabel.setToolTipText(null);

        executor.submit(() -> {
            String text;
            String tip;
            Color color = Theme.MUTED;
            int required = 8;
            try {
                required = requiredJavaMajor(versionId);
                String override = config.javaFor(versionId);
                LegacyMacRuntime.Runtime nativeRuntime = override.isBlank()
                        && LegacyMacRuntime.isNeeded(required) ? LegacyMacRuntime.installed() : null;
                if (nativeRuntime != null) {
                    text = "Java 8 (aarch64), native";
                    tip = nativeRuntime.java() + " with patched LWJGL 2 (no Rosetta)";
                } else if (override.isBlank() && LegacyMacRuntime.isNeeded(required)) {
                    text = "Java 8 (aarch64), installs on first Play";
                    tip = "First Play downloads an Apple-silicon Java 8 and patched LWJGL 2 "
                            + "(about 99 MB) so 1.8.9 does not need Rosetta.";
                } else {
                    JavaRuntime.Match match = JavaRuntime.resolve(versionId, required, override);
                    text = JavaRuntime.describe(match, override);
                    tip = match.java().toString()
                            + (override.isBlank() ? " (chosen automatically)" : " (set for " + versionId + ")");
                    if (match.major() < required) {
                        text = "Java " + match.major() + " (" + match.arch() + ") — needs " + required + "+";
                        color = Theme.ERROR;
                    }
                }
            } catch (JavaRuntime.MissingJavaException e) {
                text = "No Java " + required + "+ found — pick one with Java…";
                tip = e.getMessage();
                color = Theme.ERROR;
            } catch (Exception e) {
                text = "Java unknown";
                tip = e.getMessage() == null ? e.toString() : e.getMessage();
            }

            String shown = text;
            String tooltip = tip;
            Color shade = color;
            SwingUtilities.invokeLater(() -> {
                if (!versionId.equals(selectedVersion())) {
                    return;
                }
                javaLabel.setText(shown);
                javaLabel.setForeground(shade);
                javaLabel.setToolTipText(tooltip);
            });
        });
    }

    /** The Java the version itself asks for; versions before 1.8.9's metadata default to 8. */
    private int requiredJavaMajor(String versionId) throws IOException {
        VersionResolver.Resolved resolved = VersionResolver.resolve(
                new MojangApi(AppPaths.cacheDir()), gameDir(), versionId, (message, fraction) -> { });
        JsonObject javaVersion = resolved.json().getAsJsonObject("javaVersion");
        return javaVersion != null && javaVersion.has("majorVersion")
                ? javaVersion.get("majorVersion").getAsInt() : 8;
    }

    private void showJavaMenu() {
        String versionId = selectedVersion();
        if (versionId == null || versionId.isBlank()) {
            return;
        }
        String current = config.javaFor(versionId);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem choose = new JMenuItem(current.isBlank() ? "Choose Java…" : "Change Java…");
        choose.addActionListener(e -> chooseJava(versionId));
        menu.add(choose);

        JMenuItem automatic = new JMenuItem("Use automatic Java");
        automatic.setEnabled(!current.isBlank());
        automatic.addActionListener(e -> setJavaOverride(versionId, ""));
        menu.add(automatic);

        if (!current.isBlank()) {
            menu.addSeparator();
            JMenuItem shown = new JMenuItem(current);
            shown.setEnabled(false);
            menu.add(shown);
        }
        menu.show(javaButton, 0, javaButton.getHeight());
    }

    private void chooseJava(String versionId) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setDialogTitle("Java executable for " + versionId);
        String current = config.javaFor(versionId);
        if (!current.isBlank()) {
            Path parent = Path.of(current).getParent();
            if (parent != null && Files.isDirectory(parent)) {
                chooser.setCurrentDirectory(parent.toFile());
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setJavaOverride(versionId, chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void setJavaOverride(String versionId, String path) {
        if (path == null || path.isBlank()) {
            config.javaPaths.remove(versionId);
        } else {
            config.javaPaths.put(versionId, path.trim());
        }
        config.save();
        logPanel.append("[java] " + versionId
                + (path == null || path.isBlank() ? " uses automatic Java" : " now uses " + path.trim()));
        refreshJava();
    }

    // -------------------------------------------------------------- versions

    private void loadVersions() {
        refreshVersions.setEnabled(false);
        executor.submit(() -> {
            List<String> ids = new ArrayList<>();

            Path versionsDir = gameDir().resolve("versions");
            if (Files.isDirectory(versionsDir)) {
                try (Stream<Path> stream = Files.list(versionsDir)) {
                    stream.filter(Files::isDirectory)
                            .filter(path -> Files.exists(path.resolve(path.getFileName() + ".json")))
                            .map(path -> path.getFileName().toString())
                            // Loader profiles are installed by the launcher, not picked by hand.
                            .filter(id -> !LoaderInstaller.isLoaderProfile(id))
                            .sorted()
                            .forEach(ids::add);
                } catch (IOException ignored) {
                    // A missing or unreadable versions folder just means nothing is installed yet.
                }
            }

            String latestRelease = null;
            try {
                MojangApi api = new MojangApi(AppPaths.cacheDir());
                latestRelease = api.latestRelease();
                int added = 0;
                for (MojangApi.VersionSummary version : api.versions()) {
                    if (!version.isRelease()) {
                        continue;
                    }
                    if (!ids.contains(version.id())) {
                        ids.add(version.id());
                    }
                    if (++added >= 100) {
                        break;
                    }
                }
            } catch (IOException e) {
                logPanel.append("[versions] " + e.getMessage());
            }

            final String fallback = latestRelease;
            SwingUtilities.invokeLater(() -> {
                loadingVersions = true;
                versionBox.removeAllItems();
                for (String id : ids) {
                    versionBox.addItem(id);
                }
                String wanted = config.selectedVersion != null && !config.selectedVersion.isBlank()
                        ? config.selectedVersion : fallback;
                if (wanted != null) {
                    versionBox.setSelectedItem(wanted);
                }
                loadingVersions = false;
                if (versionBox.getItemCount() == 0) {
                    statusLabel.setText("No Minecraft versions found yet — check your connection.");
                }
                refreshVersions.setEnabled(true);
                refreshJava();
            });
        });
    }

    /** Square initial avatar with the classic blue gradient and a raised edge. */
    private static final class Avatar extends JComponent {
        private String initial = "?";

        Avatar() {
            setPreferredSize(new Dimension(36, 36));
            setMinimumSize(new Dimension(36, 36));
            setMaximumSize(new Dimension(36, 36));
        }

        void setInitial(String value) {
            this.initial = value == null || value.isBlank() ? "?" : value.substring(0, 1).toUpperCase();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(new GradientPaint(0, 0, Theme.ACCENT, 0, getHeight(), Theme.ACCENT_2));
            g2.fillRect(0, 0, getWidth(), getHeight());
            Theme.paintBevel(g2, 0, 0, getWidth(), getHeight(), true);
            g2.setColor(Color.WHITE);
            g2.setFont(Theme.BOLD_FONT.deriveFont(15f));
            var metrics = g2.getFontMetrics();
            int x = (getWidth() - metrics.stringWidth(initial)) / 2;
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(initial, x, y);
            g2.dispose();
        }
    }
}
