package com.mixclient.launcher.ui;

import com.mixclient.launcher.core.AppPaths;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Launcher and game output. */
public final class LogPanel extends JPanel {

    private static final int MAX_CHARACTERS = 400_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextArea area = new JTextArea();
    private final JLabel hint = new JLabel("Game output appears here.");

    public LogPanel() {
        super(new BorderLayout(0, 4));
        setOpaque(false);

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setBorder(Theme.padding(0, 0, 2, 0));

        JLabel title = new JLabel("Logs");
        Theme.styleLabel(title, Theme.BOLD_FONT.deriveFont(13f), Theme.ACCENT_2);

        JButton clear = Theme.secondaryButton("Clear");
        clear.addActionListener(e -> area.setText(""));

        JButton openFolder = Theme.secondaryButton("Open launcher folder");
        openFolder.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(AppPaths.appDir().toFile());
            } catch (Exception ex) {
                hint.setText("Couldn't open " + AppPaths.appDir());
            }
        });

        header.add(title);
        header.add(Box.createHorizontalGlue());
        header.add(clear);
        header.add(Box.createHorizontalStrut(4));
        header.add(openFolder);
        add(header, BorderLayout.NORTH);

        area.setEditable(false);
        area.setFont(Theme.MONO_FONT);
        area.setBackground(Theme.SURFACE_ALT);
        area.setForeground(Theme.TEXT);
        area.setCaretColor(Theme.TEXT);
        area.setLineWrap(false);
        area.setBorder(Theme.padding(2, 3, 2, 3));

        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(Theme.fieldBorder());
        scroll.getViewport().setBackground(Theme.SURFACE_ALT);
        scroll.setPreferredSize(new Dimension(600, 340));
        add(scroll, BorderLayout.CENTER);

        Theme.styleLabel(hint, Theme.SMALL_FONT, Theme.MUTED);
        add(hint, BorderLayout.SOUTH);
    }

    /** Thread-safe: the game process writes from its own threads. */
    public void append(String line) {
        if (line == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            area.append("[" + LocalTime.now().format(TIME) + "] " + line + "\n");
            int length = area.getDocument().getLength();
            if (length > MAX_CHARACTERS) {
                area.replaceRange("", 0, length - MAX_CHARACTERS);
            }
            area.setCaretPosition(area.getDocument().getLength());
        });
    }
}
