package com.mixclient.launcher.ui;

import com.mixclient.launcher.core.AppPaths;
import com.mixclient.launcher.core.LauncherConfig;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Launcher settings: where the game lives, which Java runs it, and how much memory it gets. */
public final class SettingsPanel extends JPanel {

    private final LauncherConfig config;
    private final Consumer<LauncherConfig> onSaved;

    private final JTextField gameDirField = new JTextField();
    private final JTextField javaField = new JTextField();
    private final JSpinner maxMemory = new JSpinner(new SpinnerNumberModel(4096, 1024, 65536, 512));
    private final JSpinner minMemory = new JSpinner(new SpinnerNumberModel(1024, 512, 32768, 512));
    private final JSpinner width = new JSpinner(new SpinnerNumberModel(1280, 640, 7680, 10));
    private final JSpinner height = new JSpinner(new SpinnerNumberModel(720, 480, 4320, 10));
    private final JTextArea jvmArgs = new JTextArea(4, 40);
    private final JCheckBox pvpFlags = new JCheckBox("Apply PvP-tuned G1 garbage collector flags");
    private final JLabel savedLabel = new JLabel(" ");

    public SettingsPanel(LauncherConfig config, Consumer<LauncherConfig> onSaved) {
        super(new BorderLayout(0, 8));
        this.config = config;
        this.onSaved = onSaved;
        setOpaque(false);
        buildUi();
        loadValues();
    }

    private void buildUi() {
        JLabel title = new JLabel("Settings");
        Theme.styleLabel(title, Theme.BOLD_FONT.deriveFont(13f), Theme.ACCENT_2);
        title.setBorder(Theme.padding(0, 0, 2, 0));
        add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);

        gameDirField.setFont(Theme.TEXT_FONT);
        javaField.setFont(Theme.TEXT_FONT);
        jvmArgs.setFont(Theme.MONO_FONT);
        jvmArgs.setLineWrap(true);

        int row = 0;
        row = addRow(form, row, "Game directory",
                withBrowse(gameDirField, true), null);
        row = addRow(form, row, "Java executable",
                withBrowse(javaField, false), "Leave empty to use the Java runtime running this launcher.");
        row = addRow(form, row, "Memory (MB)",
                memoryRow(), "Minimum and maximum heap for the game.");
        row = addRow(form, row, "Window size",
                resolutionRow(), "Used for --width/--height when the version supports it.");
        row = addRow(form, row, "PvP JVM flags",
                pvpFlags, "Adds Aikar-style G1 flags that reduce GC hitches in fights.");
        addRow(form, row, "Extra JVM arguments", new JScrollPane(jvmArgs), null);

        JPanel group = Theme.card(new BorderLayout());
        group.setBorder(Theme.padding(8, 8, 8, 8));
        group.add(form, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(group);
        scroll.setBorder(null);
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        add(scroll, BorderLayout.CENTER);

        JButton save = Theme.primaryButton("Save settings");
        save.addActionListener(e -> save());

        JButton restore = Theme.secondaryButton("Reset PvP flags");
        restore.addActionListener(e -> jvmArgs.setText(LauncherConfig.DEFAULT_PVP_JVM_ARGS));

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        savedLabel.setFont(Theme.SMALL_FONT);
        savedLabel.setForeground(Theme.SUCCESS);
        actions.add(restore);
        actions.add(Box.createHorizontalStrut(4));
        actions.add(save);
        actions.add(Box.createHorizontalStrut(8));
        actions.add(savedLabel);
        actions.add(Box.createHorizontalGlue());
        add(actions, BorderLayout.SOUTH);
    }

    private JPanel withBrowse(JTextField field, boolean directory) {
        JPanel panel = new JPanel(new BorderLayout(4, 0));
        panel.setOpaque(false);
        field.setPreferredSize(new Dimension(320, 22));
        field.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        panel.add(field, BorderLayout.CENTER);

        JButton browse = Theme.secondaryButton("Browse…");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(directory
                    ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    private JPanel memoryRow() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.add(compact(minMemory));
        panel.add(Box.createHorizontalStrut(4));
        panel.add(new JLabel("to"));
        panel.add(Box.createHorizontalStrut(4));
        panel.add(compact(maxMemory));
        panel.add(Box.createHorizontalStrut(6));
        panel.add(Box.createHorizontalGlue());
        return panel;
    }

    private JPanel resolutionRow() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.add(compact(width));
        panel.add(Box.createHorizontalStrut(4));
        panel.add(new JLabel("x"));
        panel.add(Box.createHorizontalStrut(4));
        panel.add(compact(height));
        panel.add(Box.createHorizontalStrut(6));
        panel.add(Box.createHorizontalGlue());
        return panel;
    }

    /** Fixed-width number field; BoxLayout would otherwise stretch it across the row. */
    private JSpinner compact(JSpinner spinner) {
        Dimension size = new Dimension(84, 22);
        spinner.setPreferredSize(size);
        spinner.setMinimumSize(size);
        spinner.setMaximumSize(size);
        spinner.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return spinner;
    }

    private int addRow(JPanel form, int row, String label, java.awt.Component field, String hint) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.NORTHWEST;
        left.insets = new Insets(0, 0, 7, 10);
        JLabel name = new JLabel(label);
        Theme.styleLabel(name, Theme.TEXT_FONT, Theme.MUTED);
        form.add(name, left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.anchor = GridBagConstraints.NORTHWEST;
        right.insets = new Insets(0, 0, 7, 0);

        if (hint != null) {
            JPanel wrapper = new JPanel();
            wrapper.setOpaque(false);
            wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
            if (field instanceof javax.swing.JComponent) {
                ((javax.swing.JComponent) field).setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            }
            wrapper.add(field);
            JLabel hintLabel = new JLabel(hint);
            Theme.styleLabel(hintLabel, Theme.SMALL_FONT, Theme.MUTED);
            hintLabel.setBorder(Theme.padding(2, 0, 0, 0));
            hintLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            wrapper.add(hintLabel);
            form.add(wrapper, right);
        } else {
            form.add(field, right);
        }
        return row + 1;
    }

    private void loadValues() {
        gameDirField.setText(config.gameDir == null ? "" : config.gameDir);
        gameDirField.setToolTipText("Empty means " + AppPaths.gameDir());
        javaField.setText(config.javaPath == null ? "" : config.javaPath);
        maxMemory.setValue(config.maxMemoryMb);
        minMemory.setValue(config.minMemoryMb);
        width.setValue(config.windowWidth);
        height.setValue(config.windowHeight);
        jvmArgs.setText(config.jvmArgs == null ? "" : config.jvmArgs);
        jvmArgs.setCaretPosition(0);
        pvpFlags.setSelected(config.pvpOptimizations);
        pvpFlags.setForeground(Theme.TEXT);
        pvpFlags.setOpaque(false);
    }

    private void save() {
        config.gameDir = gameDirField.getText().trim();
        config.javaPath = javaField.getText().trim();
        config.maxMemoryMb = (Integer) maxMemory.getValue();
        config.minMemoryMb = (Integer) minMemory.getValue();
        config.windowWidth = (Integer) width.getValue();
        config.windowHeight = (Integer) height.getValue();
        config.jvmArgs = jvmArgs.getText().trim();
        config.pvpOptimizations = pvpFlags.isSelected();
        if (config.pvpOptimizations && (config.jvmArgs == null || config.jvmArgs.isBlank())) {
            config.jvmArgs = LauncherConfig.DEFAULT_PVP_JVM_ARGS;
            jvmArgs.setText(config.jvmArgs);
        }
        config.save();
        savedLabel.setText("Saved.");
        onSaved.accept(config);
    }

    public Path resolvedGameDir() {
        return gameDirField.getText().isBlank()
                ? AppPaths.gameDir() : Path.of(gameDirField.getText().trim());
    }
}
