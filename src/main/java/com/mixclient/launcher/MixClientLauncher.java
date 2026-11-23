package com.mixclient.launcher;

import com.mixclient.launcher.auth.Accounts;
import com.mixclient.launcher.core.AppPaths;
import com.mixclient.launcher.core.BuildInfo;
import com.mixclient.launcher.core.LauncherConfig;
import com.mixclient.launcher.ui.LauncherFrame;
import com.mixclient.launcher.ui.Theme;
import com.mixclient.launcher.update.Updater;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/** Starts the MixClient launcher. */
public final class MixClientLauncher {

    private MixClientLauncher() {
    }

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "MixClient");
        installCrashLogHandler();

        AppPaths.ensureDirectories();
        Theme.install();

        LauncherConfig config = LauncherConfig.load();
        Accounts accounts = new Accounts();
        accounts.load();

        SwingUtilities.invokeLater(() -> {
            try {
                LauncherFrame frame = new LauncherFrame(config, accounts);
                frame.setVisible(true);
                startUpdateCheck(frame, config);
            } catch (RuntimeException e) {
                logCrash(e);
                JOptionPane.showMessageDialog(null,
                        "MixClient couldn't start: " + e.getMessage(),
                        "MixClient", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /**
     * Looks for a newer release in the background. With {@code autoUpdate} on (the default) the
     * release is downloaded, verified and started right away; otherwise the launcher just offers it.
     */
    private static void startUpdateCheck(LauncherFrame frame, LauncherConfig config) {
        Thread thread = new Thread(() -> {
            try {
                Updater.Release release = Updater.check();
                if (!Updater.isNewer(release.version(), BuildInfo.VERSION)) {
                    frame.setUpdateStatus("Up to date · MixClient " + BuildInfo.VERSION);
                    return;
                }

                if (config.autoUpdate) {
                    installUpdate(frame, release);
                } else {
                    frame.setUpdateAvailable(release, () -> {
                        Thread worker = new Thread(() -> installUpdate(frame, release), "mixclient-update");
                        worker.setDaemon(true);
                        worker.start();
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                frame.setUpdateStatus("Update check failed");
                System.err.println("[update] " + e);
            }
        }, "mixclient-update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private static void installUpdate(LauncherFrame frame, Updater.Release release) {
        try {
            frame.setUpdateStatus("Downloading MixClient " + release.version() + "…");
            Updater.apply(release, AppPaths.appDir().resolve("updates"), frame::setUpdateStatus);

            // The installer was opened, or a new launcher jar is already starting: this process is done.
            SwingUtilities.invokeLater(() -> System.exit(0));
        } catch (Exception e) {
            frame.setUpdateStatus("Update failed: " + e.getMessage());
            System.err.println("[update] " + e);
        }
    }

    private static void installCrashLogHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> logCrash(error));
    }

    private static void logCrash(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        try {
            Path log = AppPaths.logFile();
            Files.createDirectories(AppPaths.appDir());
            Files.writeString(log,
                    "[" + LocalDateTime.now() + "] " + writer + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Nothing else we can do; keep the launcher alive.
        }
        System.err.println(writer);
    }
}
