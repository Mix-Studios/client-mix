package com.mixclient.launcher;

import com.mixclient.launcher.auth.Accounts;
import com.mixclient.launcher.core.AppPaths;
import com.mixclient.launcher.core.LauncherConfig;
import com.mixclient.launcher.ui.LauncherFrame;
import com.mixclient.launcher.ui.Theme;

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
            } catch (RuntimeException e) {
                logCrash(e);
                JOptionPane.showMessageDialog(null,
                        "MixClient couldn't start: " + e.getMessage(),
                        "MixClient", JOptionPane.ERROR_MESSAGE);
            }
        });
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
