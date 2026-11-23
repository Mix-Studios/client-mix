package com.mixclient.launcher.ui;

import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;

/**
 * Shows the Microsoft "enter this code" prompt for the device-code flow.
 * The code is filled in from the login thread via {@link #showCode(MsaDeviceCode)}.
 */
public final class DeviceCodeDialog extends JDialog {

    private final JLabel instruction = new JLabel("Signing in with Microsoft…");
    private final JTextField codeField = new JTextField();
    private final JLabel urlLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JProgressBar spinner = new JProgressBar();
    private final JButton copyButton = Theme.secondaryButton("Copy code");
    private final JButton openButton = Theme.primaryButton("Open sign-in page");
    private final JButton cancelButton = Theme.secondaryButton("Cancel");

    private Runnable onCancel = () -> {
    };
    private boolean finished;

    public DeviceCodeDialog(Window owner) {
        super(owner, "Sign in with Microsoft", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        buildUi();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancel();
            }
        });
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.SURFACE);
        root.setBorder(Theme.padding(10, 12, 10, 12));

        JLabel title = new JLabel("Sign in with Microsoft");
        Theme.styleLabel(title, Theme.BOLD_FONT.deriveFont(13f), Theme.ACCENT_2);

        JLabel subtitle = new JLabel("<html>Open the link below in any browser, then enter the code. "
                + "No password ever goes through MixClient.</html>");
        subtitle.setFont(Theme.TEXT_FONT);
        subtitle.setForeground(Theme.MUTED);

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(title);
        header.add(Box.createVerticalStrut(3));
        header.add(subtitle);
        root.add(header, BorderLayout.NORTH);

        codeField.setHorizontalAlignment(SwingConstants.CENTER);
        codeField.setEditable(false);
        codeField.setFont(new Font(Font.MONOSPACED, Font.BOLD, 20));
        codeField.setForeground(Theme.TEXT);
        codeField.setBackground(Theme.SURFACE_ALT);
        codeField.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLoweredBevelBorder(), Theme.padding(4, 6, 4, 6)));
        codeField.setPreferredSize(new Dimension(260, 34));
        codeField.setVisible(false);

        urlLabel.setFont(Theme.TEXT_FONT);
        urlLabel.setForeground(Theme.ACCENT_2);
        urlLabel.setHorizontalAlignment(SwingConstants.CENTER);

        instruction.setFont(Theme.SMALL_FONT);
        instruction.setForeground(Theme.MUTED);
        instruction.setHorizontalAlignment(SwingConstants.CENTER);

        spinner.setIndeterminate(true);
        spinner.setPreferredSize(new Dimension(260, 14));
        spinner.setBorder(Theme.fieldBorder());

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(instruction);
        center.add(Box.createVerticalStrut(6));
        center.add(codeField);
        center.add(Box.createVerticalStrut(4));
        center.add(urlLabel);
        center.add(Box.createVerticalStrut(8));
        center.add(spinner);
        root.add(center, BorderLayout.CENTER);

        statusLabel.setFont(Theme.SMALL_FONT);
        statusLabel.setForeground(Theme.ERROR);
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(copyButton);
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(openButton);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(cancelButton);

        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        footer.add(statusLabel, BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);

        copyButton.setEnabled(false);
        openButton.setEnabled(false);
        copyButton.addActionListener(e -> copyCode());
        openButton.addActionListener(e -> openBrowser());
        cancelButton.addActionListener(e -> cancel());

        setContentPane(root);
        setResizable(false);
        pack();
        setLocationRelativeTo(getOwner());
    }

    /** Called on the EDT once the library has a device code. */
    public void showCode(MsaDeviceCode code) {
        if (finished) {
            return;
        }
        codeField.setText(code.getUserCode());
        codeField.setVisible(true);
        instruction.setText("Enter this code on the Microsoft page:");
        urlLabel.setText(code.getDirectVerificationUri());
        copyButton.setEnabled(true);
        openButton.setEnabled(true);
        statusLabel.setText(" ");
        revalidate();
        pack();
        setLocationRelativeTo(getOwner());
        if (!isVisible()) {
            setVisible(true);
        }
    }

    /** Called on the EDT when sign-in fails. */
    public void showError(String message) {
        if (finished) {
            return;
        }
        spinner.setVisible(false);
        statusLabel.setForeground(Theme.ERROR);
        statusLabel.setText("<html>" + escape(message) + "</html>");
        cancelButton.setText("Close");
        if (!isVisible()) {
            setVisible(true);
        }
    }

    public void close() {
        finished = true;
        setVisible(false);
        dispose();
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel == null ? () -> {
        } : onCancel;
    }

    private void cancel() {
        if (finished) {
            return;
        }
        finished = true;
        onCancel.run();
        close();
    }

    private void copyCode() {
        String code = codeField.getText();
        if (code == null || code.isBlank()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(code), null);
        statusLabel.setForeground(Theme.SUCCESS);
        statusLabel.setText("Code copied to clipboard.");
    }

    private void openBrowser() {
        String url = urlLabel.getText();
        if (url == null || url.isBlank() || " ".equals(url)) {
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            statusLabel.setForeground(Theme.ERROR);
            statusLabel.setText("<html>Couldn't open a browser. Visit the link manually.</html>");
        }
    }

    private static String escape(String message) {
        return message == null ? "" : message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Convenience for callers that are already on a non-EDT thread. */
    public void showErrorAsync(String message) {
        SwingUtilities.invokeLater(() -> showError(message));
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
    }

    public Color accentColor() {
        return Theme.ACCENT;
    }
}
