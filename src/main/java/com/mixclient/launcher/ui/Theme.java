package com.mixclient.launcher.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.Arrays;
import java.util.List;

/**
 * MixClient's look: circa 2007 Windows. Grey faces, hard 2px bevels, Tahoma at
 * 11px, square corners and tight spacing.
 */
public final class Theme {

    /** Desktop / window grey. */
    public static final Color BG = new Color(0xD4D0C8);
    /** Dialog and panel face. */
    public static final Color SURFACE = new Color(0xECE9D8);
    /** Text fields and selected list rows. */
    public static final Color SURFACE_ALT = Color.WHITE;
    public static final Color BORDER = new Color(0x808080);
    public static final Color TEXT = Color.BLACK;
    public static final Color MUTED = new Color(0x4A4A4A);
    public static final Color ACCENT = new Color(0x316AC5);
    public static final Color ACCENT_DARK = new Color(0x1C3C78);
    public static final Color ACCENT_2 = new Color(0x0A246A);
    public static final Color SUCCESS = new Color(0x007A00);
    public static final Color ERROR = new Color(0xCC0000);

    public static final Color HIGHLIGHT = Color.WHITE;
    public static final Color SHADOW = new Color(0x404040);
    public static final Color TRACK = new Color(0xB8B4A8);

    public static final String FONT_FAMILY =
            findFamily(Font.DIALOG, "MS Sans Serif", "Tahoma", "Verdana", "Geneva");
    public static final String MONO_FAMILY =
            findFamily(Font.MONOSPACED, "Courier New", "Courier", "Lucida Console");

    public static final Font TEXT_FONT = new Font(FONT_FAMILY, Font.PLAIN, 11);
    public static final Font SMALL_FONT = new Font(FONT_FAMILY, Font.PLAIN, 10);
    public static final Font BOLD_FONT = new Font(FONT_FAMILY, Font.BOLD, 11);
    public static final Font TITLE_FONT = new Font(FONT_FAMILY, Font.BOLD, 15);
    public static final Font MONO_FONT = new Font(MONO_FAMILY, Font.PLAIN, 11);

    private Theme() {
    }

    public static void install() {
        try {
            MetalLookAndFeel.setCurrentTheme(new DefaultMetalTheme());
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (Exception ignored) {
            // Fall back to whatever the JVM picked; the widgets below still apply.
        }

        UIManager.put("Panel.background", BG);
        UIManager.put("Viewport.background", SURFACE_ALT);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("OptionPane.messageFont", TEXT_FONT);
        UIManager.put("OptionPane.buttonFont", BOLD_FONT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.font", TEXT_FONT);
        UIManager.put("Button.font", BOLD_FONT);
        UIManager.put("Button.background", SURFACE);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("ToggleButton.font", BOLD_FONT);
        UIManager.put("CheckBox.font", TEXT_FONT);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("RadioButton.font", TEXT_FONT);
        UIManager.put("TextField.font", TEXT_FONT);
        UIManager.put("TextField.background", SURFACE_ALT);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("TextArea.font", TEXT_FONT);
        UIManager.put("TextArea.background", SURFACE_ALT);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("PasswordField.font", TEXT_FONT);
        UIManager.put("PasswordField.background", SURFACE_ALT);
        UIManager.put("ComboBox.font", TEXT_FONT);
        UIManager.put("ComboBox.background", SURFACE_ALT);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", ACCENT);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("Spinner.font", TEXT_FONT);
        UIManager.put("Menu.font", TEXT_FONT);
        UIManager.put("MenuItem.font", TEXT_FONT);
        UIManager.put("MenuItem.background", SURFACE);
        UIManager.put("MenuItem.selectionBackground", ACCENT);
        UIManager.put("MenuItem.selectionForeground", Color.WHITE);
        UIManager.put("PopupMenu.background", SURFACE);
        UIManager.put("List.selectionBackground", ACCENT);
        UIManager.put("List.selectionForeground", Color.WHITE);
        UIManager.put("ProgressBar.background", TRACK);
        UIManager.put("ProgressBar.foreground", ACCENT);
        UIManager.put("ProgressBar.font", SMALL_FONT);
        UIManager.put("ScrollBar.width", 16);
        UIManager.put("ToolTip.font", SMALL_FONT);
        UIManager.put("ToolTip.background", new Color(0xFFFFE1));
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(TEXT));
        UIManager.put("FileChooser.font", TEXT_FONT);
        UIManager.put("defaultFont", TEXT_FONT);

        Border field = fieldBorder();
        UIManager.put("TextField.border", field);
        UIManager.put("PasswordField.border", field);
        UIManager.put("TextArea.border", BorderFactory.createCompoundBorder(
                BorderFactory.createLoweredBevelBorder(), BorderFactory.createEmptyBorder(1, 1, 1, 1)));
        UIManager.put("ComboBox.border", field);
        UIManager.put("ScrollPane.border", field);
    }

    public static JButton primaryButton(String text) {
        return new BeveledButton(text, ACCENT, ACCENT_DARK, Color.WHITE, true);
    }

    public static JButton secondaryButton(String text) {
        return new BeveledButton(text, new Color(0xFBFBF7), new Color(0xDCD8CC), TEXT, false);
    }

    /** Sunken border used by inputs and scroll panes. */
    public static Border fieldBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLoweredBevelBorder(),
                BorderFactory.createEmptyBorder(1, 2, 1, 2));
    }

    public static JPanel card(LayoutManager layout) {
        return new BeveledPanel(layout);
    }

    /** White field, sunken edge and a grey beveled arrow button instead of Metal's flat triangle. */
    public static void styleCombo(JComboBox<?> combo) {
        combo.setUI(new BeveledComboUI());
        combo.setBorder(fieldBorder());
        combo.setBackground(SURFACE_ALT);
        combo.setForeground(TEXT);
        combo.setFont(TEXT_FONT);
        combo.setRenderer(new ComboRenderer());
    }

    public static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    public static void styleLabel(JComponent component, Font font, Color color) {
        component.setFont(font);
        component.setForeground(color);
    }

    /** Left-aligned, flat list entry that looks sunken when selected. */
    public static JToggleButton navToggle(String text) {
        JToggleButton button = new JToggleButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                int w = getWidth();
                int h = getHeight();
                g2.setColor(isSelected() ? ACCENT : SURFACE);
                g2.fillRect(0, 0, w, h);
                if (!isSelected() && getModel().isRollover()) {
                    g2.setColor(new Color(0xF7F5EC));
                    g2.fillRect(1, 1, w - 2, h - 2);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setFont(BOLD_FONT);
        button.setForeground(TEXT);
        button.addItemListener(e -> button.setForeground(button.isSelected() ? Color.WHITE : TEXT));
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setRolloverEnabled(true);
        button.setBorder(padding(0, 6, 0, 6));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    /** Two-tone hard bevel, like a Win95/2000 control edge. */
    public static void paintBevel(Graphics g, int x, int y, int w, int h, boolean raised) {
        Color outerTopLeft = raised ? HIGHLIGHT : SHADOW;
        Color innerTopLeft = raised ? Color.WHITE : HIGHLIGHT;
        Color outerBottomRight = raised ? SHADOW : HIGHLIGHT;
        Color innerBottomRight = raised ? BORDER : new Color(0xF5F4EF);

        g.setColor(outerTopLeft);
        g.drawLine(x, y, x + w - 1, y);
        g.drawLine(x, y, x, y + h - 1);

        g.setColor(outerBottomRight);
        g.drawLine(x, y + h - 1, x + w - 1, y + h - 1);
        g.drawLine(x + w - 1, y, x + w - 1, y + h - 1);

        g.setColor(innerTopLeft);
        g.drawLine(x + 1, y + 1, x + w - 2, y + 1);
        g.drawLine(x + 1, y + 1, x + 1, y + h - 2);

        g.setColor(innerBottomRight);
        g.drawLine(x + 1, y + h - 2, x + w - 2, y + h - 2);
        g.drawLine(x + w - 2, y + 1, x + w - 2, y + h - 2);
    }

    private static Color lighten(Color color, int amount) {
        return new Color(
                Math.min(255, color.getRed() + amount),
                Math.min(255, color.getGreen() + amount),
                Math.min(255, color.getBlue() + amount));
    }

    private static String findFamily(String fallback, String... candidates) {
        try {
            List<String> available = Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (String candidate : candidates) {
                for (String family : available) {
                    if (family.equalsIgnoreCase(candidate)) {
                        return family;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Headless or sandboxed: the logical family still works.
        }
        return fallback;
    }

    /** Basic combo UI so only our arrow button supplies the drop-down affordance. */
    private static final class BeveledComboUI extends BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            return new ComboArrowButton();
        }
    }

    /** Flat white rows in the drop-down and in the closed field. */
    private static final class ComboRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            setOpaque(true);
            setFont(TEXT_FONT);
            setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
            if (!selected) {
                setBackground(SURFACE_ALT);
                setForeground(TEXT);
            }
            return this;
        }
    }

    /** 17px grey arrow button with the same 2px bevel as the rest of the controls. */
    private static final class ComboArrowButton extends JButton {
        ComboArrowButton() {
            setFocusable(false);
            setRequestFocusEnabled(false);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorder(BorderFactory.createEmptyBorder());
            setRolloverEnabled(true);
            setPreferredSize(new Dimension(17, 20));
            setMinimumSize(new Dimension(17, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth();
            int h = getHeight();
            boolean pressed = getModel().isArmed() && getModel().isPressed();

            Color top = pressed ? new Color(0xD4D0C8) : new Color(0xF7F6F1);
            Color bottom = pressed ? new Color(0xBDBAB0) : new Color(0xDCD8CC);
            if (!isEnabled()) {
                top = new Color(0xE6E3DA);
                bottom = new Color(0xD4D0C6);
            } else if (getModel().isRollover() && !pressed) {
                top = lighten(top, 6);
                bottom = lighten(bottom, 4);
            }

            g2.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
            g2.fillRect(0, 0, w, h);
            paintBevel(g2, 0, 0, w, h, !pressed);

            // Classic 7x4 drop arrow, centred in the face.
            int cx = w / 2;
            int cy = h / 2;
            int[] xs = {cx - 3, cx + 3, cx};
            int[] ys = {cy - 2, cy - 2, cy + 2};
            g2.setColor(isEnabled() ? TEXT : new Color(0x8C8C8C));
            g2.fillPolygon(xs, ys, 3);
            g2.dispose();
        }
    }

    /** Panel with the classic raised grey face. */
    public static final class BeveledPanel extends JPanel {
        public BeveledPanel(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(SURFACE);
            g.fillRect(0, 0, getWidth(), getHeight());
            paintBevel(g, 0, 0, getWidth(), getHeight(), true);
            super.paintComponent(g);
        }
    }

    /** Button drawn by hand so the gradient face and 2px bevel are always the same. */
    public static final class BeveledButton extends JButton {
        private final Color faceTop;
        private final Color faceBottom;
        private final boolean strong;

        BeveledButton(String text, Color faceTop, Color faceBottom, Color foreground, boolean strong) {
            super(text);
            this.faceTop = faceTop;
            this.faceBottom = faceBottom;
            this.strong = strong;
            setFont(BOLD_FONT);
            setForeground(foreground);
            setBackground(faceTop);
            setOpaque(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setMargin(new Insets(0, 0, 0, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, Math.max(size.height, 22));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth();
            int h = getHeight();
            boolean pressed = getModel().isArmed() && getModel().isPressed();

            Color top = faceTop;
            Color bottom = faceBottom;
            if (!isEnabled()) {
                top = new Color(0xE6E3DA);
                bottom = new Color(0xD4D0C6);
            } else if (pressed) {
                top = faceBottom;
                bottom = faceTop;
            } else if (getModel().isRollover()) {
                top = lighten(faceTop, 16);
                bottom = lighten(faceBottom, 12);
            }

            g2.setPaint(new GradientPaint(0, 0, top, 0, h, bottom));
            g2.fillRect(0, 0, w, h);
            paintBevel(g2, 0, 0, w, h, !pressed);
            if (strong && isFocusOwner()) {
                g2.setColor(Color.WHITE);
                g2.drawRect(3, 3, w - 7, h - 7);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Square brand mark with the 2007 blue gradient and a raised edge. */
    public static final class Logo extends JComponent {
        public Logo() {
            this(26);
        }

        public Logo(int size) {
            Dimension preferred = new Dimension(size, size);
            setPreferredSize(preferred);
            setMinimumSize(preferred);
            setMaximumSize(preferred);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(new GradientPaint(0, 0, ACCENT, 0, getHeight(), ACCENT_2));
            g2.fillRect(0, 0, getWidth(), getHeight());
            paintBevel(g2, 0, 0, getWidth(), getHeight(), true);
            g2.setColor(Color.WHITE);
            g2.setFont(BOLD_FONT.deriveFont(getHeight() * 0.62f));
            var metrics = g2.getFontMetrics();
            String mark = "M";
            int x = (getWidth() - metrics.stringWidth(mark)) / 2;
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(mark, x, y);
            g2.dispose();
        }
    }
}
