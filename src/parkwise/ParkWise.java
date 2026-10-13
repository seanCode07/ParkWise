package parkwise;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/* =====================================================================
   Park : theme, helpers and custom components used by every screen
   ===================================================================== */
class Park {
    static final Color BG_DARK        = new Color(9, 9, 22);
    static final Color BG_PANEL       = new Color(14, 14, 32);
    static final Color BG_CARD        = new Color(22, 22, 46);
    static final Color BG_CARD_HI     = new Color(32, 32, 64);
    static final Color ACCENT         = new Color(124, 92, 255);
    static final Color ACCENT_2       = new Color(70, 130, 255);
    static final Color TEXT_PRIMARY   = new Color(240, 240, 255);
    static final Color TEXT_MUTED     = new Color(138, 138, 178);
    static final Color SUCCESS        = new Color(52, 211, 153);
    static final Color WARNING        = new Color(251, 176, 64);
    static final Color DANGER         = new Color(244, 92, 120);
    static final Color BORDER         = new Color(48, 48, 88);
    static final Color SLOT_AVAILABLE = new Color(46, 204, 140);
    static final Color SLOT_RESERVED  = new Color(255, 170, 50);
    static final Color SLOT_OCCUPIED  = new Color(244, 85, 110);

    static final double RATE = 20.0;
    static final String PESO = "\u20B1";
    static final String DATE_PATTERN = "MMMM dd, yyyy";
    static final String[] SLOTS = buildSlots();

    /** The ParkWise logo (logo.png, kept in the same folder as these source files). */
    private static final BufferedImage LOGO = loadLogo();

    private static String[] buildSlots() {
        String[] s = new String[20];
        String rows = "ABCD";
        for (int i = 0; i < 20; i++) s[i] = rows.charAt(i / 5) + "" + (i % 5 + 1);
        return s;
    }

    // ---------- logo ----------
    private static BufferedImage loadLogo() {
        try {
            java.net.URL u = Park.class.getResource("logo.png");
            if (u != null) return ImageIO.read(u);
            String[] paths = {"logo.png", "src/parkwise/logo.png", "parkwise/logo.png"};
            for (String p : paths) {
                File f = new File(p);
                if (f.exists()) return ImageIO.read(f);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    static boolean hasLogo() { return LOGO != null; }

    /** Window / taskbar icon (null if the logo file is missing, which Swing accepts). */
    static Image icon() { return scaleLogo(64); }

    private static BufferedImage resize(BufferedImage src, int s) {
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, s, s, null);
        g.dispose();
        return out;
    }

    /** Smooth downscale in halving steps so the logo stays crisp at small sizes. */
    static BufferedImage scaleLogo(int size) {
        if (LOGO == null) return null;
        BufferedImage cur = LOGO;
        while (cur.getWidth() / 2 >= size) cur = resize(cur, cur.getWidth() / 2);
        return cur.getWidth() == size ? cur : resize(cur, size);
    }

    // ---------- fonts ----------
    static Font bold(int sz)  { return new Font("Segoe UI", Font.BOLD,  sz); }
    static Font plain(int sz) { return new Font("Segoe UI", Font.PLAIN, sz); }
    static Font mono(int sz)  { return new Font("Consolas",  Font.PLAIN, sz); }

    // ---------- small helpers ----------
    static Border pad(int t, int l, int b, int r) { return BorderFactory.createEmptyBorder(t, l, b, r); }

    static Graphics2D aa(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g2;
    }

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), a); }

    static Color lighten(Color c, int amt) {
        return new Color(Math.min(255, c.getRed() + amt), Math.min(255, c.getGreen() + amt),
                         Math.min(255, c.getBlue() + amt), c.getAlpha());
    }

    static String money(double v) { return PESO + String.format(Locale.US, "%.2f", v); }

    static String fmt(Calendar c) { return new SimpleDateFormat(DATE_PATTERN, Locale.ENGLISH).format(c.getTime()); }

    static String today() { return fmt(Calendar.getInstance()); }

    static Date parseDate(String d) {
        try { return new SimpleDateFormat(DATE_PATTERN, Locale.ENGLISH).parse(d); }
        catch (Exception e) { return new Date(0); }
    }

    static String shortDate(String d) {
        try { return new SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).format(parseDate(d)); }
        catch (Exception e) { return d; }
    }

    /** True when the given booking date is before today. */
    static boolean isPast(String d) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);      c.set(Calendar.MILLISECOND, 0);
        return parseDate(d).before(c.getTime());
    }

    /** 3725000 ms -> "01:02:05" */
    static String hms(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /**
     * Multi-line label text with fixed line breaks. Unlike a width-based HTML label, a label
     * built this way is never cut off at the right edge, whatever the font or screen scaling.
     */
    static String lines(String... ls) {
        StringBuilder sb = new StringBuilder("<html>");
        for (int i = 0; i < ls.length; i++) {
            if (i > 0) sb.append("<br>");
            sb.append(ls[i]);
        }
        return sb.append("</html>").toString();
    }

    static void styleDialogs() {
        UIManager.put("OptionPane.background", BG_CARD);
        UIManager.put("Panel.background", BG_CARD);
        UIManager.put("OptionPane.messageForeground", TEXT_PRIMARY);
    }

    // ---------- factories ----------
    static JLabel lbl(String t, Font f, Color c) {
        JLabel l = new JLabel(t); l.setFont(f); l.setForeground(c); return l;
    }

    static RoundButton primaryBtn(String t) {
        RoundButton b = new RoundButton(t, ACCENT, Color.WHITE);
        b.setFont(bold(14)); b.setPreferredSize(new Dimension(220, 46)); b.setRadius(14);
        return b;
    }

    static RoundButton secondaryBtn(String t) {
        RoundButton b = new RoundButton(t, new Color(30, 30, 62), TEXT_PRIMARY);
        b.setOutline(BORDER); b.setFont(plain(13)); b.setPreferredSize(new Dimension(100, 38)); b.setRadius(12);
        return b;
    }

    static JTextField field(int cols) {
        JTextField tf = new JTextField(cols);
        styleInput(tf);
        return tf;
    }

    static JPasswordField pass(int cols) {
        JPasswordField pf = new JPasswordField(cols);
        styleInput(pf);
        return pf;
    }

    private static void styleInput(JTextField tf) {
        tf.setFont(plain(14)); tf.setBackground(new Color(12, 12, 28));
        tf.setForeground(TEXT_PRIMARY); tf.setCaretColor(ACCENT);
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 1), pad(8, 12, 8, 12)));
        tf.setPreferredSize(new Dimension(300, 42));
    }

    // ---------- background ----------
    static void paintBg(Graphics g, int w, int h) {
        Graphics2D g2 = aa(g);
        w = Math.max(w, 1); h = Math.max(h, 1);
        g2.setColor(BG_DARK); g2.fillRect(0, 0, w, h);
        float r1 = Math.max(w, h) * 0.6f;
        g2.setPaint(new RadialGradientPaint(new Point2D.Float(w * 0.85f, h * 0.05f), r1,
            new float[]{0f, 1f}, new Color[]{alpha(ACCENT, 70), alpha(ACCENT, 0)}));
        g2.fillRect(0, 0, w, h);
        g2.setPaint(new RadialGradientPaint(new Point2D.Float(w * 0.1f, h * 0.95f), r1 * 0.8f,
            new float[]{0f, 1f}, new Color[]{alpha(ACCENT_2, 45), alpha(ACCENT_2, 0)}));
        g2.fillRect(0, 0, w, h);
        Random rng = new Random(7);
        for (int i = 0; i < 60; i++) {
            int x = rng.nextInt(w), y = rng.nextInt(h), a = 40 + rng.nextInt(90);
            g2.setColor(new Color(255, 255, 255, a)); g2.fillOval(x, y, 2, 2);
        }
        g2.dispose();
    }

    // ================= custom components =================

    static class BgPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        BgPanel(LayoutManager lm) { super(lm); setOpaque(true); }
        protected void paintComponent(Graphics g) { Park.paintBg(g, getWidth(), getHeight()); }
    }

    static class Card extends JPanel {
        private static final long serialVersionUID = 1L;
        private final int radius;
        private Color fill = new Color(22, 22, 46, 230);
        private Color line = BORDER;
        Card(LayoutManager lm, int radius) { super(lm); this.radius = radius; setOpaque(false); }
        void setFill(Color c) { fill = c; repaint(); }
        void setLine(Color c) { line = c; repaint(); }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            g2.setColor(fill); g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.setColor(line); g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Flat rounded button. arrow = -1 draws a left arrow, 1 draws a right arrow, 0 draws text. */
    static class RoundButton extends JButton {
        private static final long serialVersionUID = 1L;
        private Color fill, outline;
        private int radius = 12, arrow = 0;
        private boolean hovering = false;

        RoundButton(String text, Color fill, Color fg) {
            super(text);
            this.fill = fill;
            setForeground(fg); setFont(bold(13));
            setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false);
            setMargin(new Insets(0, 0, 0, 0));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hovering = true; repaint(); }
                public void mouseExited(MouseEvent e)  { hovering = false; repaint(); }
            });
        }
        void setFill(Color c)    { fill = c; repaint(); }
        void setOutline(Color c) { outline = c; repaint(); }
        void setRadius(int r)    { radius = r; repaint(); }
        void setArrow(int a)     { arrow = a; repaint(); }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            int w = getWidth(), h = getHeight();
            Color base = fill;
            if (!isEnabled()) base = alpha(fill, 90);
            else if (hovering) base = lighten(fill, 22);
            g2.setColor(base); g2.fillRoundRect(0, 0, w - 1, h - 1, radius, radius);
            if (outline != null) {
                g2.setColor(isEnabled() ? outline : alpha(outline, 90));
                g2.drawRoundRect(0, 0, w - 1, h - 1, radius, radius);
            }
            g2.setColor(isEnabled() ? getForeground() : TEXT_MUTED);
            if (arrow != 0) {
                g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = w / 2, cy = h / 2, d = arrow;
                g2.drawLine(cx - 3 * d, cy - 6, cx + 3 * d, cy);
                g2.drawLine(cx + 3 * d, cy, cx - 3 * d, cy + 6);
            } else {
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                String t = getText();
                int tx = (w - fm.stringWidth(t)) / 2;
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(t, tx, ty);
            }
            g2.dispose();
        }
    }

    /** One parking slot tile. status: 0 available, 1 reserved, 2 occupied. */
    static class SlotButton extends JButton {
        private static final long serialVersionUID = 1L;
        private final String slot;
        private final int status;
        private final boolean mine;
        private boolean hovering = false;

        SlotButton(String slot, int status, boolean mine) {
            this.slot = slot; this.status = status; this.mine = mine;
            setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hovering = true; repaint(); }
                public void mouseExited(MouseEvent e)  { hovering = false; repaint(); }
            });
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            int w = getWidth(), h = getHeight();
            Color c = status == 1 ? SLOT_RESERVED : status == 2 ? SLOT_OCCUPIED : SLOT_AVAILABLE;
            String st = status == 1 ? "Reserved" : status == 2 ? "Occupied" : "Available";

            g2.setColor(hovering ? new Color(38, 38, 76) : new Color(24, 24, 50));
            g2.fillRoundRect(0, 0, w - 1, h - 1, 16, 16);
            g2.setColor(alpha(c, hovering ? 55 : 30));
            g2.fillRoundRect(0, 0, w - 1, h - 1, 16, 16);
            g2.setColor(alpha(c, mine ? 255 : 150));
            g2.setStroke(new BasicStroke(mine ? 2.2f : 1.2f));
            g2.drawRoundRect(1, 1, w - 3, h - 3, 16, 16);

            g2.setColor(c); g2.fillOval(12, 12, 8, 8);

            g2.setFont(bold(22)); FontMetrics fm = g2.getFontMetrics();
            g2.setColor(TEXT_PRIMARY);
            g2.drawString(slot, (w - fm.stringWidth(slot)) / 2, h / 2 + 4);

            g2.setFont(plain(11)); fm = g2.getFontMetrics();
            g2.setColor(c);
            g2.drawString(st, (w - fm.stringWidth(st)) / 2, h / 2 + 24);

            if (mine) {
                g2.setFont(bold(9)); fm = g2.getFontMetrics();
                int tw = fm.stringWidth("YOU") + 12;
                g2.setColor(c); g2.fillRoundRect(w - tw - 8, 8, tw, 16, 8, 8);
                g2.setColor(BG_DARK); g2.drawString("YOU", w - tw - 2, 20);
            }
            g2.dispose();
        }
    }

    /** Circular usage ring like the charts in the reference design. */
    static class RingPanel extends JComponent {
        private static final long serialVersionUID = 1L;
        private int percent = 0;
        void setPercent(int p) { percent = p; repaint(); }
        public Dimension getPreferredSize() { return new Dimension(150, 150); }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            int w = getWidth(), h = getHeight();
            int size = Math.min(w, h) - 24, x = (w - size) / 2, y = (h - size) / 2;
            g2.setStroke(new BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(40, 40, 80)); g2.drawOval(x, y, size, size);
            if (percent > 0) {
                g2.setPaint(new GradientPaint(x, y, ACCENT_2, x + size, y + size, ACCENT));
                g2.draw(new Arc2D.Double(x, y, size, size, 90, -360.0 * percent / 100.0, Arc2D.OPEN));
            }
            g2.setFont(bold(28)); FontMetrics fm = g2.getFontMetrics();
            String t = percent + "%";
            g2.setColor(TEXT_PRIMARY); g2.drawString(t, (w - fm.stringWidth(t)) / 2, h / 2 + 6);
            g2.setFont(plain(11)); fm = g2.getFontMetrics();
            g2.setColor(TEXT_MUTED); g2.drawString("in use", (w - fm.stringWidth("in use")) / 2, h / 2 + 24);
            g2.dispose();
        }
    }

    /** Thin progress bar for the duration picker. */
    static class Bar extends JComponent {
        private static final long serialVersionUID = 1L;
        private int value = 1;
        private final int max = 24;
        void setValue(int v) { value = v; repaint(); }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(36, 36, 72)); g2.fillRoundRect(0, 0, w, h, h, h);
            g2.setPaint(new GradientPaint(0, 0, ACCENT_2, w, 0, ACCENT));
            g2.fillRoundRect(0, 0, Math.max(h, (int) (w * (value / (double) max))), h, h, h);
            g2.dispose();
        }
    }

    /** The ParkWise logo at any size. Falls back to a plain "P" badge if logo.png cannot be found. */
    static class LogoView extends JComponent {
        private static final long serialVersionUID = 1L;
        private final BufferedImage img;
        private final int size;
        LogoView(int size) {
            this.size = size;
            this.img = scaleLogo(size);
            Dimension d = new Dimension(size, size);
            setPreferredSize(d); setMaximumSize(d); setMinimumSize(d);
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            if (img != null) {
                g2.drawImage(img, (getWidth() - size) / 2, (getHeight() - size) / 2, null);
            } else {
                int w = getWidth(), h = getHeight();
                g2.setPaint(new GradientPaint(0, 0, ACCENT_2, w, h, ACCENT));
                g2.fillOval(0, 0, w - 1, h - 1);
                g2.setFont(bold((int) (h * 0.45))); FontMetrics fm = g2.getFontMetrics();
                g2.setColor(Color.WHITE);
                g2.drawString("P", (w - fm.stringWidth("P")) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            }
            g2.dispose();
        }
    }

    /** Round gradient badge with one letter inside (user avatar). */
    static class Badge extends JComponent {
        private static final long serialVersionUID = 1L;
        private final String text;
        Badge(String text, int size) {
            this.text = text;
            Dimension d = new Dimension(size, size);
            setPreferredSize(d); setMaximumSize(d); setMinimumSize(d);
        }
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            int w = getWidth(), h = getHeight();
            g2.setPaint(new GradientPaint(0, 0, ACCENT_2, w, h, ACCENT));
            g2.fillOval(0, 0, w - 1, h - 1);
            g2.setFont(bold((int) (h * 0.45))); FontMetrics fm = g2.getFontMetrics();
            g2.setColor(Color.WHITE);
            g2.drawString(text, (w - fm.stringWidth(text)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }
}

/* =====================================================================
   LoginFrame : landscape split screen (branding on the left, form on the right)
   ===================================================================== */
class LoginFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private JTextField     userField;
    private JPasswordField passField;
    private JLabel statusLbl, modeLbl, togglePrompt, toggleLink;
    private Park.RoundButton actionBtn;
    private boolean loginMode = true;

    LoginFrame() {
        Park.styleDialogs();
        setTitle("ParkWise - Login");
        setIconImage(Park.icon());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1000, 620); setLocationRelativeTo(null); setResizable(false);
        buildUI();
    }

    private void buildUI() {
        Park.BgPanel root = new Park.BgPanel(new GridLayout(1, 2));
        root.add(buildBrand());
        root.add(buildFormSide());
        setContentPane(root);
        getRootPane().setDefaultButton(actionBtn);
    }

    private JPanel buildBrand() {
        JPanel p = new JPanel(new GridBagLayout()); p.setOpaque(false);
        JPanel col = new JPanel(); col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        boolean logoOk = Park.hasLogo();
        Park.LogoView logo = new Park.LogoView(logoOk ? 220 : 92);
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        col.add(logo);
        col.add(Box.createVerticalStrut(14));
        if (!logoOk) {   // the logo already contains the name; only add text when it is missing
            JLabel title = Park.lbl("ParkWise", Park.bold(44), Park.TEXT_PRIMARY);
            title.setAlignmentX(Component.CENTER_ALIGNMENT);
            col.add(title); col.add(Box.createVerticalStrut(6));
        }
        JLabel sub = Park.lbl("Real-Time Parking Slot Management", Park.plain(15), Park.TEXT_MUTED);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        col.add(sub);
        col.add(Box.createVerticalStrut(26));
        col.add(feature("Live slot availability", "See which slots are free, reserved or taken"));
        col.add(Box.createVerticalStrut(12));
        col.add(feature("Time-based billing", Park.money(Park.RATE) + " per hour, with a live parking timer"));
        col.add(Box.createVerticalStrut(12));
        col.add(feature("Everything is saved", "Your account and bookings stay after you close the app"));
        p.add(col);
        return p;
    }

    private JPanel feature(String title, String sub) {
        Park.Card c = new Park.Card(new BorderLayout(), 16);
        c.setBorder(Park.pad(12, 18, 12, 18));
        JPanel t = new JPanel(); t.setOpaque(false); t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.add(Park.lbl(title, Park.bold(14), Park.TEXT_PRIMARY));
        t.add(Park.lbl(sub, Park.plain(12), Park.TEXT_MUTED));
        c.add(t, BorderLayout.CENTER);
        c.setPreferredSize(new Dimension(380, 62)); c.setMaximumSize(new Dimension(380, 62));
        c.setAlignmentX(Component.CENTER_ALIGNMENT);
        return c;
    }

    private JPanel buildFormSide() {
        JPanel side = new JPanel(new GridBagLayout()); side.setOpaque(false);

        Park.Card card = new Park.Card(new GridBagLayout(), 24);
        card.setBorder(Park.pad(30, 36, 30, 36));
        card.setPreferredSize(new Dimension(420, 470));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.insets = new Insets(5, 0, 5, 0); gbc.gridx = 0;

        modeLbl = Park.lbl("Welcome Back", Park.bold(24), Park.TEXT_PRIMARY);
        gbc.gridy = 0; card.add(modeLbl, gbc);
        gbc.gridy = 1; card.add(Park.lbl("Log in to manage your parking", Park.plain(13), Park.TEXT_MUTED), gbc);
        gbc.gridy = 2; gbc.insets = new Insets(18, 0, 4, 0);
        card.add(Park.lbl("Username", Park.plain(12), Park.TEXT_MUTED), gbc);
        gbc.insets = new Insets(5, 0, 5, 0);
        userField = Park.field(20); gbc.gridy = 3; card.add(userField, gbc);
        gbc.gridy = 4; card.add(Park.lbl("Password", Park.plain(12), Park.TEXT_MUTED), gbc);
        passField = Park.pass(20); gbc.gridy = 5; card.add(passField, gbc);
        statusLbl = Park.lbl(" ", Park.plain(12), Park.DANGER);
        statusLbl.setHorizontalAlignment(SwingConstants.CENTER);
        gbc.gridy = 6; card.add(statusLbl, gbc);
        actionBtn = Park.primaryBtn("Login"); actionBtn.setPreferredSize(new Dimension(300, 46));
        gbc.gridy = 7; card.add(actionBtn, gbc);

        JPanel toggleRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0)); toggleRow.setOpaque(false);
        togglePrompt = Park.lbl("Don't have an account?", Park.plain(12), Park.TEXT_MUTED);
        toggleLink   = Park.lbl("Register here", Park.bold(12), Park.ACCENT);
        toggleLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggleRow.add(togglePrompt); toggleRow.add(toggleLink);
        gbc.gridy = 8; card.add(toggleRow, gbc);
        side.add(card);

        actionBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { handleAction(); }
        });
        toggleLink.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { setMode(!loginMode); }
        });
        return side;
    }

    private void showMsg(String msg, Color c) { statusLbl.setForeground(c); statusLbl.setText(msg); }

    private void handleAction() {
        String user = userField.getText().trim();
        String pw = new String(passField.getPassword());
        if (user.isEmpty() || pw.isEmpty()) { showMsg("Please fill in all fields.", Park.DANGER); return; }

        if (loginMode) {
            if (DataStore.loginUser(user, pw)) {
                dispose();
                if (DataStore.isAdmin(user)) new AdminFrame(user).setVisible(true);
                else new MainFrame(user).setVisible(true);
            }
            else showMsg("Invalid username or password.", Park.DANGER);
        } else {
            if (!user.matches("[A-Za-z0-9_.\\-]{3,20}")) {
                showMsg("Username: 3 to 20 letters, numbers, _ . -", Park.DANGER); return;
            }
            if (pw.length() < 4) { showMsg("Password must be at least 4 characters.", Park.DANGER); return; }
            if (DataStore.registerUser(user, pw)) {
                setMode(true);
                passField.setText("");
                showMsg("Registered! Please log in.", Park.SUCCESS);
            } else showMsg("Username already taken.", Park.DANGER);
        }
    }

    private void setMode(boolean login) {
        loginMode = login;
        statusLbl.setText(" ");
        modeLbl.setText(login ? "Welcome Back" : "Create Account");
        actionBtn.setText(login ? "Login" : "Register");
        togglePrompt.setText(login ? "Don't have an account?" : "Already have an account?");
        toggleLink.setText(login ? "Register here" : "Login here");
    }
}

/* =====================================================================
   MainFrame : one landscape dashboard (calendar | slots | usage + my parking)
   ===================================================================== */
class MainFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private final String username;
    private Calendar selectedCal = Calendar.getInstance();
    private Calendar displayCal  = Calendar.getInstance();
    private JLabel monthYearLbl, selectedDateLbl, slotsTitleLbl, availLbl, resvLbl, occLbl, usageCaption;
    private JPanel calGrid, slotGrid, myList;
    private Park.RingPanel ring;

    // live parking timer
    private javax.swing.Timer liveTimer;
    private final List<LiveRow> live = new ArrayList<LiveRow>();
    private final Set<String> notified = new HashSet<String>();

    private static class LiveRow {
        final DataStore.ReservationInfo info;
        final JLabel lbl;
        LiveRow(DataStore.ReservationInfo info, JLabel lbl) { this.info = info; this.lbl = lbl; }
    }

    MainFrame(String username) {
        this.username = username;
        setTitle("ParkWise - Dashboard");
        setIconImage(Park.icon());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1320, scr.width - 20), Math.min(760, scr.height - 60));
        setMinimumSize(new Dimension(1240, 660));
        setLocationRelativeTo(null);
        buildUI();
        refreshAll();

        liveTimer = new javax.swing.Timer(1000, new ActionListener() {
            public void actionPerformed(ActionEvent e) { tickLive(); }
        });
        liveTimer.start();
        SwingUtilities.invokeLater(new Runnable() {
            public void run() { remindUnpaid(); }
        });
    }

    public void dispose() {
        if (liveTimer != null) liveTimer.stop();
        super.dispose();
    }

    // ---------- layout ----------
    private void buildUI() {
        Park.BgPanel root = new Park.BgPanel(new BorderLayout());
        root.add(buildSidebar(), BorderLayout.WEST);

        JPanel content = new JPanel(new BorderLayout(0, 18)); content.setOpaque(false);
        content.setBorder(Park.pad(22, 26, 22, 26));
        content.add(buildHeader(), BorderLayout.NORTH);

        JPanel main = new JPanel(new GridBagLayout()); main.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.BOTH; gc.weighty = 1; gc.gridy = 0;
        gc.gridx = 0; gc.weightx = 0; gc.insets = new Insets(0, 0, 0, 18); main.add(buildLeft(), gc);
        gc.gridx = 1; gc.weightx = 1;                                      main.add(buildCenter(), gc);
        gc.gridx = 2; gc.weightx = 0; gc.insets = new Insets(0, 18, 0, 0); main.add(buildRight(), gc);
        content.add(main, BorderLayout.CENTER);

        root.add(content, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel buildSidebar() {
        JPanel sb = new JPanel(new BorderLayout()) {
            private static final long serialVersionUID = 1L;
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = Park.aa(g);
                g2.setColor(new Color(12, 12, 28, 205)); g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Park.BORDER); g2.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
                g2.dispose();
            }
        };
        sb.setOpaque(false); sb.setPreferredSize(new Dimension(76, 100));
        sb.setBorder(Park.pad(20, 0, 20, 0));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0)); top.setOpaque(false);
        top.add(new Park.LogoView(56));

        JComponent navIcon = new JComponent() {
            private static final long serialVersionUID = 1L;
            public Dimension getPreferredSize() { return new Dimension(48, 48); }
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = Park.aa(g);
                g2.setColor(Park.alpha(Park.ACCENT, 60));
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.setColor(Park.ACCENT);
                int s = 9, gap = 5;
                int x0 = (getWidth() - (2 * s + gap)) / 2, y0 = (getHeight() - (2 * s + gap)) / 2;
                for (int r = 0; r < 2; r++) for (int c = 0; c < 2; c++)
                    g2.fillRoundRect(x0 + c * (s + gap), y0 + r * (s + gap), s, s, 3, 3);
                g2.dispose();
            }
        };
        JPanel mid = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 30)); mid.setOpaque(false);
        mid.add(navIcon);

        JPanel bottom = new JPanel(); bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        Park.Badge avatar = new Park.Badge(username.substring(0, 1).toUpperCase(), 40);
        avatar.setAlignmentX(Component.CENTER_ALIGNMENT);
        avatar.setToolTipText(username);
        Park.RoundButton logout = new Park.RoundButton("Logout", new Color(30, 30, 62), Park.TEXT_PRIMARY);
        logout.setFont(Park.plain(11)); logout.setRadius(10); logout.setOutline(Park.BORDER);
        logout.setPreferredSize(new Dimension(56, 28)); logout.setMaximumSize(new Dimension(56, 28));
        logout.setAlignmentX(Component.CENTER_ALIGNMENT);
        logout.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { dispose(); new LoginFrame().setVisible(true); }
        });
        bottom.add(avatar); bottom.add(Box.createVerticalStrut(10)); bottom.add(logout);

        sb.add(top, BorderLayout.NORTH); sb.add(mid, BorderLayout.CENTER); sb.add(bottom, BorderLayout.SOUTH);
        return sb;
    }

    private JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout()); h.setOpaque(false);
        JPanel left = new JPanel(); left.setOpaque(false); left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(Park.lbl("ParkWise - Parking Overview", Park.bold(24), Park.TEXT_PRIMARY));
        left.add(Box.createVerticalStrut(2));
        left.add(Park.lbl("Hello, " + username + ". Pick a date, then choose a slot.", Park.plain(13), Park.TEXT_MUTED));
        h.add(left, BorderLayout.WEST);
        h.add(Park.lbl("Today  \u00B7  " + Park.today(), Park.plain(13), Park.TEXT_MUTED), BorderLayout.EAST);
        return h;
    }

    // ----- left column: calendar + selected date -----
    private JPanel buildLeft() {
        JPanel p = new JPanel(new BorderLayout(0, 16)); p.setOpaque(false);
        p.setPreferredSize(new Dimension(280, 100));
        p.add(buildCalCard(), BorderLayout.NORTH);
        p.add(buildDateCard(), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildCalCard() {
        Park.Card card = new Park.Card(new BorderLayout(0, 10), 20);
        card.setBorder(Park.pad(16, 14, 14, 14));
        card.setPreferredSize(new Dimension(280, 322));

        Park.RoundButton prev = new Park.RoundButton("", new Color(30, 30, 62), Park.ACCENT);
        Park.RoundButton next = new Park.RoundButton("", new Color(30, 30, 62), Park.ACCENT);
        prev.setArrow(-1); next.setArrow(1);
        prev.setPreferredSize(new Dimension(34, 34)); next.setPreferredSize(new Dimension(34, 34));
        monthYearLbl = Park.lbl("", Park.bold(15), Park.TEXT_PRIMARY);
        monthYearLbl.setHorizontalAlignment(SwingConstants.CENTER);
        prev.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { displayCal.add(Calendar.MONTH, -1); refreshCal(); }
        });
        next.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { displayCal.add(Calendar.MONTH, 1); refreshCal(); }
        });
        JPanel nav = new JPanel(new BorderLayout()); nav.setOpaque(false);
        nav.add(prev, BorderLayout.WEST); nav.add(monthYearLbl, BorderLayout.CENTER); nav.add(next, BorderLayout.EAST);

        JPanel dowRow = new JPanel(new GridLayout(1, 7, 4, 0)); dowRow.setOpaque(false);
        for (String d : new String[]{"Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"}) {
            JLabel dl = Park.lbl(d, Park.plain(11), Park.TEXT_MUTED);
            dl.setHorizontalAlignment(SwingConstants.CENTER); dowRow.add(dl);
        }
        calGrid = new JPanel(new GridLayout(6, 7, 4, 4)); calGrid.setOpaque(false);

        JPanel body = new JPanel(new BorderLayout(0, 6)); body.setOpaque(false);
        body.add(dowRow, BorderLayout.NORTH); body.add(calGrid, BorderLayout.CENTER);
        card.add(nav, BorderLayout.NORTH); card.add(body, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildDateCard() {
        Park.Card card = new Park.Card(new BorderLayout(), 20);
        card.setBorder(Park.pad(16, 20, 14, 16));
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        JLabel l1 = Park.lbl("SELECTED DATE", Park.bold(11), Park.TEXT_MUTED); l1.setAlignmentX(Component.LEFT_ALIGNMENT);
        selectedDateLbl = Park.lbl(" ", Park.bold(19), Park.TEXT_PRIMARY); selectedDateLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel l2 = Park.lbl("RATE", Park.bold(11), Park.TEXT_MUTED); l2.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel rate = Park.lbl(Park.money(Park.RATE) + " / hour", Park.bold(19), Park.ACCENT); rate.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Fixed line breaks (no width-based HTML) so none of this text can be cut off.
        JLabel tip = Park.lbl(Park.lines(
                "Pick a green slot to book it.",
                "Parked past your paid time?",
                "Extra hours are billed at " + Park.money(Park.RATE) + "/hr",
                "after a " + DataStore.GRACE_MINUTES + "-min grace period."),
            Park.plain(11), Park.TEXT_MUTED);
        tip.setAlignmentX(Component.LEFT_ALIGNMENT);

        col.add(l1); col.add(Box.createVerticalStrut(3)); col.add(selectedDateLbl);
        col.add(Box.createVerticalStrut(10));
        col.add(l2); col.add(Box.createVerticalStrut(3)); col.add(rate);
        col.add(Box.createVerticalStrut(10)); col.add(tip);
        card.add(col, BorderLayout.NORTH);
        return card;
    }

    // ----- center column: stats + slot grid -----
    private JPanel buildCenter() {
        JPanel p = new JPanel(new BorderLayout(0, 18)); p.setOpaque(false);

        JPanel stats = new JPanel(new GridLayout(1, 3, 14, 0)); stats.setOpaque(false);
        stats.setPreferredSize(new Dimension(100, 84));
        availLbl = new JLabel(); resvLbl = new JLabel(); occLbl = new JLabel();
        stats.add(statCard("Available", Park.SLOT_AVAILABLE, availLbl));
        stats.add(statCard("Reserved",  Park.SLOT_RESERVED,  resvLbl));
        stats.add(statCard("Occupied",  Park.SLOT_OCCUPIED,  occLbl));
        p.add(stats, BorderLayout.NORTH);

        Park.Card slotsCard = new Park.Card(new BorderLayout(0, 14), 22);
        slotsCard.setBorder(Park.pad(18, 20, 20, 20));
        slotsTitleLbl = Park.lbl("Parking Slots", Park.bold(17), Park.TEXT_PRIMARY);

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0)); legend.setOpaque(false);
        legend.add(Park.lbl("\u25CF Available", Park.plain(12), Park.SLOT_AVAILABLE));
        legend.add(Park.lbl("\u25CF Reserved",  Park.plain(12), Park.SLOT_RESERVED));
        legend.add(Park.lbl("\u25CF Occupied",  Park.plain(12), Park.SLOT_OCCUPIED));
        JPanel head = new JPanel(new BorderLayout()); head.setOpaque(false);
        head.add(slotsTitleLbl, BorderLayout.WEST); head.add(legend, BorderLayout.EAST);

        slotGrid = new JPanel(new GridLayout(4, 5, 12, 12)); slotGrid.setOpaque(false);
        slotsCard.add(head, BorderLayout.NORTH); slotsCard.add(slotGrid, BorderLayout.CENTER);
        p.add(slotsCard, BorderLayout.CENTER);
        return p;
    }

    private JPanel statCard(String title, Color c, JLabel value) {
        Park.Card card = new Park.Card(new BorderLayout(), 18);
        card.setBorder(Park.pad(12, 18, 12, 18));
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        value.setFont(Park.bold(28)); value.setForeground(Park.TEXT_PRIMARY);
        col.add(Park.lbl("\u25CF  " + title, Park.plain(12), c));
        col.add(Box.createVerticalStrut(2)); col.add(value);
        card.add(col, BorderLayout.CENTER);
        return card;
    }

    // ----- right column: usage ring + my parking -----
    private JPanel buildRight() {
        JPanel p = new JPanel(new BorderLayout(0, 18)); p.setOpaque(false);
        p.setPreferredSize(new Dimension(310, 100));

        Park.Card usage = new Park.Card(new BorderLayout(0, 4), 20);
        usage.setBorder(Park.pad(16, 16, 16, 16));
        usage.setPreferredSize(new Dimension(310, 250));
        ring = new Park.RingPanel();
        usageCaption = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        usageCaption.setHorizontalAlignment(SwingConstants.CENTER);
        usage.add(Park.lbl("Slot Usage", Park.bold(15), Park.TEXT_PRIMARY), BorderLayout.NORTH);
        usage.add(ring, BorderLayout.CENTER); usage.add(usageCaption, BorderLayout.SOUTH);
        p.add(usage, BorderLayout.NORTH);

        Park.Card mine = new Park.Card(new BorderLayout(0, 10), 20);
        mine.setBorder(Park.pad(16, 16, 16, 16));
        myList = new JPanel(); myList.setOpaque(false); myList.setLayout(new BoxLayout(myList, BoxLayout.Y_AXIS));
        JPanel wrapper = new JPanel(new BorderLayout()); wrapper.setOpaque(false);
        wrapper.add(myList, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrapper);
        sp.setOpaque(false); sp.getViewport().setOpaque(false);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(14);
        mine.add(Park.lbl("My Parking", Park.bold(15), Park.TEXT_PRIMARY), BorderLayout.NORTH);
        mine.add(sp, BorderLayout.CENTER);
        p.add(mine, BorderLayout.CENTER);
        return p;
    }

    // ---------- refreshing ----------
    private void refreshAll() { refreshCal(); refreshSlots(); refreshStats(); refreshMine(); }

    private void refreshCal() {
        calGrid.removeAll();
        Calendar tmp = (Calendar) displayCal.clone(); tmp.set(Calendar.DAY_OF_MONTH, 1);
        String[] months = {"January","February","March","April","May","June","July","August","September","October","November","December"};
        monthYearLbl.setText(months[tmp.get(Calendar.MONTH)] + " " + tmp.get(Calendar.YEAR));
        int firstDow = tmp.get(Calendar.DAY_OF_WEEK) - 1;
        int days = tmp.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar today = Calendar.getInstance();
        int cells = 0;
        for (int i = 0; i < firstDow; i++) { calGrid.add(new JLabel("")); cells++; }
        for (int d = 1; d <= days; d++) {
            final Calendar dc = (Calendar) displayCal.clone(); dc.set(Calendar.DAY_OF_MONTH, d);
            boolean past = dc.before(today) && !sameDay(dc, today);
            Park.RoundButton btn = new Park.RoundButton(String.valueOf(d), new Color(30, 30, 62), Park.TEXT_PRIMARY);
            btn.setFont(Park.plain(12)); btn.setRadius(10);
            if (sameDay(dc, selectedCal)) { btn.setFill(Park.ACCENT); btn.setForeground(Color.WHITE); btn.setFont(Park.bold(12)); }
            else if (sameDay(dc, today))  { btn.setOutline(Park.ACCENT); }
            if (past) {
                btn.setEnabled(false);
            } else {
                btn.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) { selectedCal = (Calendar) dc.clone(); refreshAll(); }
                });
            }
            calGrid.add(btn); cells++;
        }
        while (cells < 42) { calGrid.add(new JLabel("")); cells++; }
        calGrid.revalidate(); calGrid.repaint();
    }

    private boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
               a.get(Calendar.MONTH) == b.get(Calendar.MONTH) &&
               a.get(Calendar.DAY_OF_MONTH) == b.get(Calendar.DAY_OF_MONTH);
    }

    private void refreshSlots() {
        String date = Park.fmt(selectedCal);
        selectedDateLbl.setText(date);
        slotsTitleLbl.setText("Parking Slots  \u00B7  " + date);
        slotGrid.removeAll();
        for (String slot : Park.SLOTS) slotGrid.add(buildSlotBtn(date, slot));
        slotGrid.revalidate(); slotGrid.repaint();
    }

    private Park.SlotButton buildSlotBtn(final String date, final String slot) {
        int status = DataStore.getSlotStatus(date, slot);
        DataStore.ReservationInfo info = DataStore.getReservation(date, slot);
        boolean mine = info != null && info.user.equals(username);
        Park.SlotButton btn = new Park.SlotButton(slot, status, mine);
        if (status == 0) {
            btn.setToolTipText("Click to book slot " + slot);
            btn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { openBooking(date, slot); }
            });
        } else if (mine) {
            btn.setToolTipText("Your slot. Click to manage it.");
            btn.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) { manageSlot(date, slot); }
            });
        } else {
            btn.setToolTipText("Taken by another driver");
            btn.setCursor(Cursor.getDefaultCursor());
        }
        return btn;
    }

    private void refreshStats() {
        String date = Park.fmt(selectedCal);
        int resv = 0, occ = 0;
        for (String s : Park.SLOTS) {
            int st = DataStore.getSlotStatus(date, s);
            if (st == 1) resv++; else if (st == 2) occ++;
        }
        int total = Park.SLOTS.length;
        availLbl.setText(String.valueOf(total - resv - occ));
        resvLbl.setText(String.valueOf(resv));
        occLbl.setText(String.valueOf(occ));
        ring.setPercent((resv + occ) * 100 / total);
        usageCaption.setText((resv + occ) + " of " + total + " slots taken");
    }

    private JLabel sectionLabel(String t) {
        JLabel l = Park.lbl(t, Park.bold(11), Park.TEXT_MUTED);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(Park.pad(0, 2, 6, 0));
        return l;
    }

    private void refreshMine() {
        myList.removeAll();
        live.clear();

        List<DataStore.Fine> unpaid = DataStore.getUnpaidFinesForUser(username);
        List<DataStore.ReservationInfo> list = DataStore.getReservationsForUser(username);
        Collections.sort(list, new Comparator<DataStore.ReservationInfo>() {
            public int compare(DataStore.ReservationInfo a, DataStore.ReservationInfo b) {
                int c = Park.parseDate(a.date).compareTo(Park.parseDate(b.date));
                return c != 0 ? c : a.slot.compareTo(b.slot);
            }
        });

        if (!unpaid.isEmpty()) {
            myList.add(sectionLabel("UNPAID OVERTIME FEES"));
            for (DataStore.Fine f : unpaid) { myList.add(buildFineRow(f)); myList.add(Box.createVerticalStrut(8)); }
            if (!list.isEmpty()) { myList.add(Box.createVerticalStrut(4)); myList.add(sectionLabel("BOOKINGS")); }
        }
        if (list.isEmpty() && unpaid.isEmpty()) {
            JLabel empty = Park.lbl(Park.lines("No bookings yet.", "Choose a green slot to get started."),
                Park.plain(12), Park.TEXT_MUTED);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            myList.add(empty);
        }
        for (DataStore.ReservationInfo info : list) {
            myList.add(buildRow(info)); myList.add(Box.createVerticalStrut(8));
        }
        myList.revalidate(); myList.repaint();
        tickLive();
    }

    private JPanel buildRow(final DataStore.ReservationInfo info) {
        boolean occupied = info.status == 2;
        int height = occupied ? 80 : 62;
        Park.Card row = new Park.Card(new BorderLayout(8, 0), 14);
        row.setFill(new Color(14, 14, 32)); row.setBorder(Park.pad(8, 12, 8, 10));
        row.setPreferredSize(new Dimension(10, height)); row.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        Color c = occupied ? Park.SLOT_OCCUPIED : Park.SLOT_RESERVED;

        JPanel text = new JPanel(); text.setOpaque(false); text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(Park.lbl("Slot " + info.slot + "  \u00B7  " + (occupied ? "Parked" : "Reserved"), Park.bold(13), c));
        text.add(Park.lbl(Park.shortDate(info.date) + "  \u00B7  " + info.hours + "h  \u00B7  " + Park.money(info.price),
            Park.plain(11), Park.TEXT_MUTED));
        if (occupied) {
            JLabel timeLbl = Park.lbl(" ", Park.bold(12), Park.SUCCESS);
            text.add(Box.createVerticalStrut(2));
            text.add(timeLbl);
            live.add(new LiveRow(info, timeLbl));
        }

        Park.RoundButton btn = new Park.RoundButton(occupied ? "Leave" : "Manage",
            occupied ? Park.SLOT_OCCUPIED : new Color(30, 30, 62), occupied ? Color.WHITE : Park.TEXT_PRIMARY);
        if (!occupied) btn.setOutline(Park.BORDER);
        btn.setFont(Park.bold(11)); btn.setRadius(10); btn.setPreferredSize(new Dimension(62, 30));
        btn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { manageSlot(info.date, info.slot); }
        });
        JPanel east = new JPanel(new GridBagLayout()); east.setOpaque(false); east.add(btn);

        row.add(text, BorderLayout.CENTER); row.add(east, BorderLayout.EAST);
        return row;
    }

    private JPanel buildFineRow(final DataStore.Fine f) {
        Park.Card row = new Park.Card(new BorderLayout(8, 0), 14);
        row.setFill(new Color(44, 16, 30)); row.setLine(Park.DANGER);
        row.setBorder(Park.pad(8, 12, 8, 10));
        row.setPreferredSize(new Dimension(10, 62)); row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel text = new JPanel(); text.setOpaque(false); text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(Park.lbl("Overtime fee  " + Park.money(f.fee), Park.bold(13), Park.DANGER));
        text.add(Park.lbl("Slot " + f.slot + "  \u00B7  " + Park.shortDate(f.date) + "  \u00B7  " + f.overtimeHours + "h over",
            Park.plain(11), Park.TEXT_MUTED));

        Park.RoundButton pay = new Park.RoundButton("Pay", Park.ACCENT, Color.WHITE);
        pay.setFont(Park.bold(11)); pay.setRadius(10); pay.setPreferredSize(new Dimension(62, 30));
        pay.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int ok = JOptionPane.showConfirmDialog(MainFrame.this,
                    "Pay the overtime fee of " + Park.money(f.fee) + " for slot " + f.slot
                    + " (" + f.date + ")?\nPay it at the counter, then confirm here.",
                    "Pay Overtime Fee", JOptionPane.YES_NO_OPTION);
                if (ok == JOptionPane.YES_OPTION) { DataStore.payFine(f); refreshMine(); }
            }
        });
        JPanel east = new JPanel(new GridBagLayout()); east.setOpaque(false); east.add(pay);

        row.add(text, BorderLayout.CENTER); row.add(east, BorderLayout.EAST);
        return row;
    }

    // ---------- live parking timer ----------
    private void tickLive() {
        long now = System.currentTimeMillis();
        for (LiveRow lr : live) {
            long rem = lr.info.remainingMillis(now);
            if (rem >= 0) {
                lr.lbl.setText(Park.hms(rem) + " left");
                lr.lbl.setForeground(rem <= 5 * 60000L ? Park.WARNING : Park.SUCCESS);
            } else {
                double fee = lr.info.overtimeFee(now);
                lr.lbl.setText("+" + Park.hms(-rem) + " over  \u00B7  " + (fee > 0 ? Park.money(fee) : "grace"));
                lr.lbl.setForeground(Park.DANGER);
            }
        }
        // one-time alert when the paid time runs out
        for (DataStore.ReservationInfo r : DataStore.getReservationsForUser(username)) {
            if (r.status != 2 || r.remainingMillis(now) > 0) continue;
            final String key = r.date + "||" + r.slot + "||" + r.startMillis;
            if (notified.add(key)) {
                final String slot = r.slot;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        JOptionPane.showMessageDialog(MainFrame.this,
                            "Your paid parking time for slot " + slot + " has ended.\n"
                            + "After a " + DataStore.GRACE_MINUTES + "-minute grace period, every extra hour is billed at "
                            + Park.money(Park.RATE) + ".\nPlease leave the slot from My Parking when you drive out.",
                            "Parking Time Is Up", JOptionPane.WARNING_MESSAGE);
                    }
                });
            }
        }
    }

    private void remindUnpaid() {
        List<DataStore.Fine> unpaid = DataStore.getUnpaidFinesForUser(username);
        if (unpaid.isEmpty()) return;
        double total = 0;
        for (DataStore.Fine f : unpaid) total += f.fee;
        JOptionPane.showMessageDialog(this,
            "You have " + unpaid.size() + " unpaid overtime fee" + (unpaid.size() == 1 ? "" : "s")
            + " totalling " + Park.money(total) + ".\nYou can settle them under My Parking.",
            "Unpaid Overtime Fees", JOptionPane.WARNING_MESSAGE);
    }

    // ---------- actions ----------
    private void openBooking(String date, String slot) {
        new BookingDialog(this, username, date, slot).setVisible(true);
        refreshAll();
    }

    /** Leave a parked slot, check in to a reservation, or cancel a reservation. */
    private void manageSlot(String date, String slot) {
        DataStore.ReservationInfo info = DataStore.getReservation(date, slot);
        if (info == null || !info.user.equals(username)) { refreshAll(); return; }

        if (info.status == 2) {
            long now = System.currentTimeMillis();
            double fee = info.overtimeFee(now);
            if (fee > 0) {
                String msg = "Slot " + slot + " on " + date + "\n\n"
                    + "Paid time:       " + info.hours + "h\n"
                    + "Time parked:    " + Park.hms(now - info.startMillis) + "\n"
                    + "Overtime:         " + Park.hms(info.overStayMillis(now)) + "  ("
                    + info.billableHours(now) + "h billed at " + Park.money(Park.RATE) + ")\n\n"
                    + "Overtime charge: " + Park.money(fee) + "\n\n"
                    + "Pay the overtime charge at the counter and leave?";
                Object[] opts = {"Pay " + Park.money(fee) + " & Leave", "Keep Parking"};
                int ch = JOptionPane.showOptionDialog(this, msg, "Overtime Charge",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
                if (ch == 0) {
                    DataStore.Fine f = DataStore.checkout(date, slot, "User", true);
                    double paid = f == null ? fee : f.fee;
                    JOptionPane.showMessageDialog(this,
                        "Overtime of " + Park.money(paid) + " has been paid. Slot " + slot
                        + " is now free.\nThank you for using ParkWise!");
                }
            } else {
                int ch = JOptionPane.showConfirmDialog(this,
                    "Leave slot " + slot + " on " + date + "?\nThe slot will become available for other drivers.",
                    "Leave Parking", JOptionPane.YES_NO_OPTION);
                if (ch == JOptionPane.YES_OPTION) DataStore.checkout(date, slot, "User", true);
            }
        } else {
            boolean isToday = date.equals(Park.today());
            String[] opts = isToday ? new String[]{"Park Now", "Cancel Reservation", "Close"}
                                    : new String[]{"Cancel Reservation", "Close"};
            int ch = JOptionPane.showOptionDialog(this,
                "Slot " + slot + " is reserved for you on " + date + ".",
                "Manage Reservation", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, opts, opts[0]);
            String pick = ch < 0 ? "Close" : opts[ch];
            if (pick.equals("Park Now")) {
                DataStore.setStatus(date, slot, 2);      // the parking timer starts here
            } else if (pick.equals("Cancel Reservation")) {
                int ok = JOptionPane.showConfirmDialog(this,
                    "Cancel reservation for slot " + slot + " on " + date + "?",
                    "Cancel Reservation", JOptionPane.YES_NO_OPTION);
                if (ok == JOptionPane.YES_OPTION) DataStore.clearSlot(date, slot);
            }
        }
        refreshAll();
    }
}

/* =====================================================================
   AdminFrame : moderation screen. Lists every booking with its live timer,
   lets the admin free slots users forgot to leave, and tracks overtime fees.
   ===================================================================== */
class AdminFrame extends JFrame {
    private static final long serialVersionUID = 1L;

    private JLabel parkedLbl, resvLbl, overLbl, unpaidLbl, bookCountLbl, fineCountLbl;
    private JTable bookTable, fineTable;
    private final BookingsModel bookModel = new BookingsModel();
    private final FinesModel fineModel = new FinesModel();
    private CardLayout views;
    private JPanel viewPanel;
    private Park.RoundButton tabBook, tabFine;
    private javax.swing.Timer timer;

    AdminFrame(String adminName) {
        setTitle("ParkWise - Admin Moderation");
        setIconImage(Park.icon());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1180, scr.width - 20), Math.min(740, scr.height - 60));
        setMinimumSize(new Dimension(980, 600));
        setLocationRelativeTo(null);
        buildUI(adminName);
        refreshFines();
        tick();
        timer = new javax.swing.Timer(1000, new ActionListener() {
            public void actionPerformed(ActionEvent e) { tick(); }
        });
        timer.start();
    }

    public void dispose() {
        if (timer != null) timer.stop();
        super.dispose();
    }

    // ---------- layout ----------
    private void buildUI(String adminName) {
        Park.BgPanel root = new Park.BgPanel(new BorderLayout());
        JPanel content = new JPanel(new BorderLayout(0, 16)); content.setOpaque(false);
        content.setBorder(Park.pad(22, 26, 22, 26));

        // header
        JPanel header = new JPanel(new BorderLayout(14, 0)); header.setOpaque(false);
        JPanel titles = new JPanel(); titles.setOpaque(false); titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(Park.lbl("Admin Moderation", Park.bold(24), Park.TEXT_PRIMARY));
        titles.add(Box.createVerticalStrut(2));
        titles.add(Park.lbl("Signed in as " + adminName + ". Free slots that drivers forgot to leave and bill their overtime.",
            Park.plain(13), Park.TEXT_MUTED));
        JPanel titleRow = new JPanel(new BorderLayout(14, 0)); titleRow.setOpaque(false);
        titleRow.add(new Park.LogoView(60), BorderLayout.WEST);
        titleRow.add(titles, BorderLayout.CENTER);
        Park.RoundButton logout = Park.secondaryBtn("Logout");
        logout.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { dispose(); new LoginFrame().setVisible(true); }
        });
        JPanel logoutWrap = new JPanel(new GridBagLayout()); logoutWrap.setOpaque(false); logoutWrap.add(logout);
        header.add(titleRow, BorderLayout.WEST); header.add(logoutWrap, BorderLayout.EAST);

        // stats
        parkedLbl = new JLabel(); resvLbl = new JLabel(); overLbl = new JLabel(); unpaidLbl = new JLabel();
        JPanel stats = new JPanel(new GridLayout(1, 4, 14, 0)); stats.setOpaque(false);
        stats.setPreferredSize(new Dimension(100, 84));
        stats.add(statCard("Parked now", Park.SLOT_OCCUPIED, parkedLbl));
        stats.add(statCard("Reserved", Park.SLOT_RESERVED, resvLbl));
        stats.add(statCard("Over paid time", Park.WARNING, overLbl));
        stats.add(statCard("Unpaid fees", Park.DANGER, unpaidLbl));

        // tabs
        tabBook = tabButton("Active Bookings");
        tabFine = tabButton("Overtime Fees");
        tabBook.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { showView("book"); }
        });
        tabFine.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { refreshFines(); showView("fine"); }
        });
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)); tabs.setOpaque(false);
        tabs.add(tabBook); tabs.add(tabFine);

        JPanel statsTabs = new JPanel(new BorderLayout(0, 16)); statsTabs.setOpaque(false);
        statsTabs.add(stats, BorderLayout.NORTH); statsTabs.add(tabs, BorderLayout.CENTER);
        JPanel north = new JPanel(new BorderLayout(0, 16)); north.setOpaque(false);
        north.add(header, BorderLayout.NORTH); north.add(statsTabs, BorderLayout.CENTER);
        content.add(north, BorderLayout.NORTH);

        // views
        views = new CardLayout();
        viewPanel = new JPanel(views); viewPanel.setOpaque(false);
        viewPanel.add(buildBookView(), "book");
        viewPanel.add(buildFineView(), "fine");
        Park.Card card = new Park.Card(new BorderLayout(), 22);
        card.setBorder(Park.pad(16, 16, 16, 16));
        card.add(viewPanel, BorderLayout.CENTER);
        content.add(card, BorderLayout.CENTER);

        root.add(content, BorderLayout.CENTER);
        setContentPane(root);
        showView("book");
    }

    private Park.RoundButton tabButton(String t) {
        Park.RoundButton b = new Park.RoundButton(t, new Color(30, 30, 62), Park.TEXT_MUTED);
        b.setFont(Park.bold(13)); b.setRadius(12); b.setPreferredSize(new Dimension(170, 38));
        return b;
    }

    private void showView(String name) {
        boolean book = name.equals("book");
        tabBook.setFill(book ? Park.ACCENT : new Color(30, 30, 62));
        tabBook.setForeground(book ? Color.WHITE : Park.TEXT_MUTED);
        tabFine.setFill(book ? new Color(30, 30, 62) : Park.ACCENT);
        tabFine.setForeground(book ? Park.TEXT_MUTED : Color.WHITE);
        views.show(viewPanel, name);
    }

    private JPanel statCard(String title, Color c, JLabel value) {
        Park.Card card = new Park.Card(new BorderLayout(), 18);
        card.setBorder(Park.pad(12, 18, 12, 18));
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        value.setFont(Park.bold(26)); value.setForeground(Park.TEXT_PRIMARY);
        col.add(Park.lbl("\u25CF  " + title, Park.plain(12), c));
        col.add(Box.createVerticalStrut(2)); col.add(value);
        card.add(col, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildBookView() {
        JPanel p = new JPanel(new BorderLayout(0, 12)); p.setOpaque(false);
        bookTable = new JTable(bookModel);
        styleTable(bookTable, new int[]{110, 110, 60, 90, 120, 170, 110, 160});
        p.add(scroll(bookTable), BorderLayout.CENTER);

        bookCountLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        Park.RoundButton force = new Park.RoundButton("Force Check-out", Park.SLOT_OCCUPIED, Color.WHITE);
        force.setFont(Park.bold(13)); force.setRadius(12); force.setPreferredSize(new Dimension(170, 40));
        force.setToolTipText("Free a parked slot (for example when the driver forgot to press Leave) and bill any overtime.");
        force.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { forceCheckout(); }
        });
        Park.RoundButton remove = Park.secondaryBtn("Remove Reservation");
        remove.setPreferredSize(new Dimension(190, 40));
        remove.setToolTipText("Delete a reservation that was never used.");
        remove.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { removeReservation(); }
        });
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); btns.setOpaque(false);
        btns.add(remove); btns.add(force);
        JPanel south = new JPanel(new BorderLayout()); south.setOpaque(false);
        south.add(bookCountLbl, BorderLayout.WEST); south.add(btns, BorderLayout.EAST);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildFineView() {
        JPanel p = new JPanel(new BorderLayout(0, 12)); p.setOpaque(false);
        fineTable = new JTable(fineModel);
        styleTable(fineTable, new int[]{120, 120, 70, 90, 100, 100, 100, 100});
        p.add(scroll(fineTable), BorderLayout.CENTER);

        fineCountLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        Park.RoundButton paid = new Park.RoundButton("Mark as Paid", Park.SUCCESS, Park.BG_DARK);
        paid.setFont(Park.bold(13)); paid.setRadius(12); paid.setPreferredSize(new Dimension(170, 40));
        paid.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { markFinePaid(); }
        });
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); btns.setOpaque(false);
        btns.add(paid);
        JPanel south = new JPanel(new BorderLayout()); south.setOpaque(false);
        south.add(fineCountLbl, BorderLayout.WEST); south.add(btns, BorderLayout.EAST);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JScrollPane scroll(JTable t) {
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createLineBorder(Park.BORDER, 1));
        sp.getViewport().setBackground(new Color(18, 18, 40));
        sp.getVerticalScrollBar().setUnitIncrement(14);
        return sp;
    }

    private void styleTable(JTable t, int[] widths) {
        t.setRowHeight(38);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setFillsViewportHeight(true);
        t.setBackground(new Color(18, 18, 40));
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setDefaultRenderer(Object.class, new CellRenderer());
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setPreferredSize(new Dimension(100, 38));
        t.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;
            public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, false, false, r, c);
                l.setOpaque(true); l.setBackground(new Color(30, 30, 62));
                l.setForeground(Park.TEXT_MUTED); l.setFont(Park.bold(12));
                l.setBorder(Park.pad(0, 12, 0, 12)); l.setHorizontalAlignment(SwingConstants.LEFT);
                return l;
            }
        });
        for (int i = 0; i < widths.length && i < t.getColumnCount(); i++)
            t.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    }

    // ---------- table models ----------
    private abstract static class StyledModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        abstract Color fg(int row, int col);
        abstract boolean hot(int row);
        public boolean isCellEditable(int r, int c) { return false; }
    }

    private static class CellRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, false, row, col);
            StyledModel m = (StyledModel) t.getModel();
            setOpaque(true);
            setFont(Park.plain(13));
            setBorder(Park.pad(0, 12, 0, 12));
            Color bg;
            if (sel)                bg = new Color(64, 52, 140);
            else if (m.hot(row))    bg = new Color(58, 22, 40);
            else if (row % 2 == 0)  bg = new Color(18, 18, 40);
            else                    bg = new Color(24, 24, 50);
            setBackground(bg);
            setForeground(m.fg(row, col));
            return this;
        }
    }

    private static String keyOf(DataStore.ReservationInfo r) { return r.date + "||" + r.slot; }

    private class BookingsModel extends StyledModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"User", "Date", "Slot", "Status", "Paid", "Time Left / Overtime", "Overtime Fee", "Flag"};
        List<DataStore.ReservationInfo> rows = new ArrayList<DataStore.ReservationInfo>();

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }

        boolean sameKeys(List<DataStore.ReservationInfo> other) {
            if (other.size() != rows.size()) return false;
            for (int i = 0; i < rows.size(); i++)
                if (!keyOf(rows.get(i)).equals(keyOf(other.get(i)))) return false;
            return true;
        }

        private String flag(DataStore.ReservationInfo i, long now) {
            if (i.status == 2) {
                if (i.overStayMillis(now) >= DataStore.FORGOT_HOURS * DataStore.HOUR_MS) return "Possibly forgotten";
                if (i.remainingMillis(now) < 0) return "Overtime";
                return "OK";
            }
            return Park.isPast(i.date) ? "Stale reservation" : "OK";
        }

        public Object getValueAt(int r, int c) {
            DataStore.ReservationInfo i = rows.get(r);
            long now = System.currentTimeMillis();
            boolean parked = i.status == 2;
            switch (c) {
                case 0: return i.user;
                case 1: return Park.shortDate(i.date);
                case 2: return i.slot;
                case 3: return parked ? "Parked" : "Reserved";
                case 4: return i.hours + "h  (" + Park.money(i.price) + ")";
                case 5:
                    if (!parked) return Park.isPast(i.date) ? "Date has passed" : "Not arrived yet";
                    long rem = i.remainingMillis(now);
                    return rem >= 0 ? Park.hms(rem) + " left" : "+" + Park.hms(-rem) + " over";
                case 6:
                    double fee = parked ? i.overtimeFee(now) : 0;
                    return fee > 0 ? Park.money(fee) : "-";
                default: return flag(i, now);
            }
        }

        boolean hot(int r) {
            if (r >= rows.size()) return false;
            return !"OK".equals(flag(rows.get(r), System.currentTimeMillis()));
        }

        Color fg(int r, int c) {
            if (r >= rows.size()) return Park.TEXT_PRIMARY;
            DataStore.ReservationInfo i = rows.get(r);
            long now = System.currentTimeMillis();
            if (c == 3) return i.status == 2 ? Park.SLOT_OCCUPIED : Park.SLOT_RESERVED;
            if (c == 5 && i.status == 2) {
                long rem = i.remainingMillis(now);
                if (rem < 0) return Park.DANGER;
                if (rem <= 5 * 60000L) return Park.WARNING;
                return Park.SUCCESS;
            }
            if (c == 6 && i.status == 2 && i.overtimeFee(now) > 0) return Park.DANGER;
            if (c == 7) {
                String f = flag(i, now);
                if ("OK".equals(f)) return Park.SUCCESS;
                return f.equals("Overtime") ? Park.WARNING : Park.DANGER;
            }
            return Park.TEXT_PRIMARY;
        }
    }

    private class FinesModel extends StyledModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"User", "Date", "Slot", "Paid Time", "Overtime", "Fee", "Ended By", "Status"};
        List<DataStore.Fine> rows = new ArrayList<DataStore.Fine>();

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }

        public Object getValueAt(int r, int c) {
            DataStore.Fine f = rows.get(r);
            switch (c) {
                case 0: return f.user;
                case 1: return Park.shortDate(f.date);
                case 2: return f.slot;
                case 3: return f.paidHours + "h";
                case 4: return f.overtimeHours + "h";
                case 5: return Park.money(f.fee);
                case 6: return f.endedBy;
                default: return f.paid ? "Paid" : "UNPAID";
            }
        }

        boolean hot(int r) { return r < rows.size() && !rows.get(r).paid; }

        Color fg(int r, int c) {
            if (r >= rows.size()) return Park.TEXT_PRIMARY;
            if (c == 7) return rows.get(r).paid ? Park.SUCCESS : Park.DANGER;
            if (c == 5 && !rows.get(r).paid) return Park.DANGER;
            return Park.TEXT_PRIMARY;
        }
    }

    // ---------- refreshing ----------
    private void tick() {
        long now = System.currentTimeMillis();
        final long fnow = now;
        List<DataStore.ReservationInfo> fresh = DataStore.getAllReservations();
        Collections.sort(fresh, new Comparator<DataStore.ReservationInfo>() {
            public int compare(DataStore.ReservationInfo a, DataStore.ReservationInfo b) {
                int c = Long.valueOf(b.overStayMillis(fnow)).compareTo(Long.valueOf(a.overStayMillis(fnow)));
                if (c != 0) return c;
                c = Park.parseDate(a.date).compareTo(Park.parseDate(b.date));
                return c != 0 ? c : a.slot.compareTo(b.slot);
            }
        });

        int parked = 0, resv = 0, over = 0;
        for (DataStore.ReservationInfo r : fresh) {
            if (r.status == 2) { parked++; if (r.remainingMillis(now) < 0) over++; }
            else resv++;
        }
        parkedLbl.setText(String.valueOf(parked));
        resvLbl.setText(String.valueOf(resv));
        overLbl.setText(String.valueOf(over));
        unpaidLbl.setText(Park.money(DataStore.getUnpaidTotal()));
        bookCountLbl.setText(fresh.size() + " active booking" + (fresh.size() == 1 ? "" : "s")
            + ".  Rows in red need attention.");

        String sel = selectedKey();
        boolean same = bookModel.sameKeys(fresh);
        bookModel.rows = fresh;
        if (same) {
            if (!fresh.isEmpty()) bookModel.fireTableRowsUpdated(0, fresh.size() - 1);
        } else {
            bookModel.fireTableDataChanged();
            if (sel != null) {
                for (int i = 0; i < fresh.size(); i++) {
                    if (keyOf(fresh.get(i)).equals(sel)) { bookTable.setRowSelectionInterval(i, i); break; }
                }
            }
        }
    }

    private void refreshFines() {
        List<DataStore.Fine> all = DataStore.getAllFines();
        Collections.reverse(all);   // newest first
        fineModel.rows = all;
        fineModel.fireTableDataChanged();
        int unpaid = 0;
        for (DataStore.Fine f : all) if (!f.paid) unpaid++;
        fineCountLbl.setText(all.size() + " record" + (all.size() == 1 ? "" : "s") + ",  " + unpaid + " unpaid.");
        unpaidLbl.setText(Park.money(DataStore.getUnpaidTotal()));
    }

    private String selectedKey() {
        int r = bookTable.getSelectedRow();
        if (r < 0 || r >= bookModel.rows.size()) return null;
        return keyOf(bookModel.rows.get(r));
    }

    private DataStore.ReservationInfo selectedBooking() {
        int r = bookTable.getSelectedRow();
        if (r < 0 || r >= bookModel.rows.size()) return null;
        return bookModel.rows.get(r);
    }

    // ---------- admin actions ----------
    private void info(String msg) { JOptionPane.showMessageDialog(this, msg, "Admin", JOptionPane.INFORMATION_MESSAGE); }

    private void forceCheckout() {
        DataStore.ReservationInfo r = selectedBooking();
        if (r == null) { info("Select a parked booking in the table first."); return; }
        if (r.status != 2) {
            info("That booking is only reserved (the car is not parked).\nUse Remove Reservation instead.");
            return;
        }
        long now = System.currentTimeMillis();
        double fee = r.overtimeFee(now);
        String head = "Force check-out slot " + r.slot + " of " + r.user + " (" + r.date + ")?\n\n"
            + "Parked for:  " + Park.hms(now - r.startMillis) + "\n"
            + "Paid time:    " + r.hours + "h\n";
        if (fee > 0) {
            String msg = head + "Overtime:      " + Park.hms(r.overStayMillis(now)) + "  ("
                + r.billableHours(now) + "h billed at " + Park.money(Park.RATE) + ")\n"
                + "Overtime fee: " + Park.money(fee) + "\n\n"
                + "Mark Paid = the fee was collected now.\n"
                + "Bill to Account = the driver sees it as unpaid in their dashboard.";
            Object[] opts = {"Collected - Mark Paid", "Bill to Account", "Cancel"};
            int ch = JOptionPane.showOptionDialog(this, msg, "Force Check-out",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
            if (ch != 0 && ch != 1) return;
            DataStore.checkout(r.date, r.slot, "Admin", ch == 0);
        } else {
            int ok = JOptionPane.showConfirmDialog(this, head + "\nNo overtime charge applies.",
                "Force Check-out", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) return;
            DataStore.checkout(r.date, r.slot, "Admin", true);
        }
        tick(); refreshFines();
    }

    private void removeReservation() {
        DataStore.ReservationInfo r = selectedBooking();
        if (r == null) { info("Select a reservation in the table first."); return; }
        if (r.status == 2) {
            info("That car is parked. Use Force Check-out so any overtime gets billed.");
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
            "Remove the reservation of slot " + r.slot + " by " + r.user + " on " + r.date + "?\nThe slot becomes available again.",
            "Remove Reservation", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        DataStore.clearSlot(r.date, r.slot);
        tick();
    }

    private void markFinePaid() {
        int r = fineTable.getSelectedRow();
        if (r < 0 || r >= fineModel.rows.size()) { info("Select a fee record first."); return; }
        DataStore.Fine f = fineModel.rows.get(r);
        if (f.paid) { info("That fee is already paid."); return; }
        int ok = JOptionPane.showConfirmDialog(this,
            "Mark the " + Park.money(f.fee) + " overtime fee of " + f.user + " (slot " + f.slot + ", " + f.date + ") as paid?",
            "Mark as Paid", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        DataStore.payFine(f);
        refreshFines();
    }
}

/* =====================================================================
   BookingDialog : booking type + duration -> payment -> (QR) -> receipt
   ===================================================================== */
class BookingDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final String username, date, slot;
    private final boolean isToday;
    private int hours = 1;
    private int bookingStatus = 1;          // 1 = reserve, 2 = park now

    private JLabel sumType, sumHours, sumTotal, hoursLbl, noteLbl, timerLbl;
    private Park.RoundButton reserveBtn, parkBtn, paidBtn;
    private Park.Bar bar;
    private CardLayout steps;
    private JPanel stepPanel, qrHolder, receiptHolder;
    private javax.swing.Timer timer;
    private int countdown = 60;

    BookingDialog(Frame owner, String username, String date, String slot) {
        super(owner, "ParkWise - Book Slot " + slot, true);
        this.username = username; this.date = date; this.slot = slot;
        this.isToday = date.equals(Park.today());
        setIconImage(Park.icon());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(860, 590); setLocationRelativeTo(owner); setResizable(false);
        addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent e) { stopTimer(); }
        });
        buildUI();
    }

    private double total() { return hours * Park.RATE; }

    private void buildUI() {
        Park.BgPanel root = new Park.BgPanel(new BorderLayout(18, 0));
        root.setBorder(Park.pad(22, 22, 22, 22));
        root.add(buildSummary(), BorderLayout.WEST);

        steps = new CardLayout();
        stepPanel = new JPanel(steps); stepPanel.setOpaque(false);
        stepPanel.add(buildFormStep(), "form");
        stepPanel.add(buildPayStep(), "pay");

        Park.Card qrCard = new Park.Card(new BorderLayout(), 22);
        qrHolder = new JPanel(new BorderLayout()); qrHolder.setOpaque(false);
        qrHolder.setBorder(Park.pad(22, 30, 22, 30));
        qrCard.add(qrHolder, BorderLayout.CENTER);
        stepPanel.add(qrCard, "qr");

        Park.Card rcCard = new Park.Card(new BorderLayout(), 22);
        receiptHolder = new JPanel(new BorderLayout()); receiptHolder.setOpaque(false);
        receiptHolder.setBorder(Park.pad(18, 30, 18, 30));
        rcCard.add(receiptHolder, BorderLayout.CENTER);
        stepPanel.add(rcCard, "receipt");

        root.add(stepPanel, BorderLayout.CENTER);
        setContentPane(root);
        selectType(1);
        steps.show(stepPanel, "form");
    }

    private JComponent left(JComponent c) { c.setAlignmentX(Component.LEFT_ALIGNMENT); return c; }

    // ---------- summary panel ----------
    private JPanel buildSummary() {
        Park.Card c = new Park.Card(new BorderLayout(), 22);
        c.setBorder(Park.pad(26, 24, 26, 24));
        c.setPreferredSize(new Dimension(250, 100));
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        col.add(left(Park.lbl("PARKING SLOT", Park.bold(11), Park.TEXT_MUTED)));
        col.add(left(Park.lbl(slot, Park.bold(56), Park.ACCENT)));
        col.add(Box.createVerticalStrut(2));
        col.add(left(Park.lbl(date, Park.plain(13), Park.TEXT_PRIMARY)));
        col.add(Box.createVerticalStrut(24));

        sumType = Park.lbl(" ", Park.bold(16), Park.TEXT_PRIMARY);
        sumHours = Park.lbl(" ", Park.bold(16), Park.TEXT_PRIMARY);
        sumTotal = Park.lbl(" ", Park.bold(30), Park.ACCENT);
        col.add(left(Park.lbl("BOOKING TYPE", Park.bold(11), Park.TEXT_MUTED))); col.add(left(sumType));
        col.add(Box.createVerticalStrut(14));
        col.add(left(Park.lbl("DURATION", Park.bold(11), Park.TEXT_MUTED))); col.add(left(sumHours));
        col.add(Box.createVerticalStrut(14));
        col.add(left(Park.lbl("RATE", Park.bold(11), Park.TEXT_MUTED)));
        col.add(left(Park.lbl(Park.money(Park.RATE) + " / hour", Park.bold(16), Park.TEXT_PRIMARY)));
        col.add(Box.createVerticalStrut(24));
        col.add(left(Park.lbl("TOTAL", Park.bold(11), Park.TEXT_MUTED))); col.add(left(sumTotal));
        c.add(col, BorderLayout.NORTH);
        return c;
    }

    private void updateSummary() {
        sumType.setText(bookingStatus == 1 ? "Reserve" : "Park Now");
        sumType.setForeground(bookingStatus == 1 ? Park.SLOT_RESERVED : Park.SLOT_OCCUPIED);
        sumHours.setText(hours + (hours == 1 ? " hour" : " hours"));
        sumTotal.setText(Park.money(total()));
    }

    // ---------- step 1: details ----------
    private JPanel buildFormStep() {
        Park.Card c = new Park.Card(new BorderLayout(), 22);
        c.setBorder(Park.pad(28, 30, 28, 30));
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        col.add(left(Park.lbl("Booking Details", Park.bold(24), Park.TEXT_PRIMARY)));
        col.add(left(Park.lbl("Choose how you want to use this slot.", Park.plain(13), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(24));
        col.add(left(Park.lbl("BOOKING TYPE", Park.bold(11), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(8));

        reserveBtn = new Park.RoundButton("Reserve", Park.SLOT_RESERVED, Park.BG_DARK);
        parkBtn = new Park.RoundButton("Park Now", new Color(30, 30, 62), Park.TEXT_MUTED);
        reserveBtn.setFont(Park.bold(14)); parkBtn.setFont(Park.bold(14));
        reserveBtn.setRadius(14); parkBtn.setRadius(14);
        reserveBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { selectType(1); }
        });
        parkBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { selectType(2); }
        });
        if (!isToday) { parkBtn.setEnabled(false); parkBtn.setToolTipText("Park Now is only available for today's date."); }
        JPanel typeRow = new JPanel(new GridLayout(1, 2, 12, 0)); typeRow.setOpaque(false);
        typeRow.add(reserveBtn); typeRow.add(parkBtn);
        typeRow.setPreferredSize(new Dimension(300, 52)); typeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        col.add(left(typeRow));
        col.add(Box.createVerticalStrut(10));
        noteLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        col.add(left(noteLbl));
        col.add(Box.createVerticalStrut(24));

        col.add(left(Park.lbl("DURATION", Park.bold(11), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(8));
        Park.RoundButton minus = new Park.RoundButton("-", new Color(30, 30, 62), Park.TEXT_PRIMARY);
        Park.RoundButton plus  = new Park.RoundButton("+", new Color(30, 30, 62), Park.TEXT_PRIMARY);
        minus.setFont(Park.bold(20)); plus.setFont(Park.bold(20));
        minus.setOutline(Park.BORDER); plus.setOutline(Park.BORDER);
        minus.setPreferredSize(new Dimension(44, 44)); plus.setPreferredSize(new Dimension(44, 44));
        hoursLbl = Park.lbl("1 hour", Park.bold(22), Park.ACCENT);
        hoursLbl.setPreferredSize(new Dimension(110, 44)); hoursLbl.setHorizontalAlignment(SwingConstants.CENTER);
        minus.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { if (hours > 1)  { hours--; updateHours(); } }
        });
        plus.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { if (hours < 24) { hours++; updateHours(); } }
        });
        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0)); ctrl.setOpaque(false);
        ctrl.add(minus); ctrl.add(hoursLbl); ctrl.add(plus);
        ctrl.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        col.add(left(ctrl));
        col.add(Box.createVerticalStrut(14));
        bar = new Park.Bar();
        bar.setPreferredSize(new Dimension(300, 8)); bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        col.add(left(bar));
        col.add(Box.createVerticalGlue());

        Park.RoundButton go = Park.primaryBtn("Continue to Payment");
        go.setPreferredSize(new Dimension(300, 50)); go.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        go.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { steps.show(stepPanel, "pay"); }
        });
        col.add(left(go));
        c.add(col, BorderLayout.CENTER);
        return c;
    }

    private void updateHours() {
        hoursLbl.setText(hours + (hours == 1 ? " hour" : " hours"));
        bar.setValue(hours);
        updateSummary();
    }

    private void selectType(int t) {
        bookingStatus = t;
        if (t == 1) {
            reserveBtn.setFill(Park.SLOT_RESERVED); reserveBtn.setForeground(Park.BG_DARK);
            parkBtn.setFill(new Color(30, 30, 62));  parkBtn.setForeground(Park.TEXT_MUTED);
            noteLbl.setText(Park.lines(
                "The slot will show as RESERVED (orange) on " + date + ".",
                isToday ? "Your timer starts when you press Park Now in My Parking."
                        : "Park Now is only available for today's date."));
        } else {
            parkBtn.setFill(Park.SLOT_OCCUPIED);     parkBtn.setForeground(Color.WHITE);
            reserveBtn.setFill(new Color(30, 30, 62)); reserveBtn.setForeground(Park.TEXT_MUTED);
            noteLbl.setText(Park.lines(
                "The slot will show as OCCUPIED (red) and your timer starts now.",
                "Staying past your paid hours is billed at " + Park.money(Park.RATE) + "/hr",
                "after a " + DataStore.GRACE_MINUTES + "-minute grace period. Leave anytime from My Parking."));
        }
        updateSummary();
    }

    // ---------- step 2: payment method ----------
    private JPanel buildPayStep() {
        Park.Card c = new Park.Card(new BorderLayout(), 22);
        c.setBorder(Park.pad(28, 30, 28, 30));
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        col.add(left(Park.lbl("Payment Method", Park.bold(24), Park.TEXT_PRIMARY)));
        col.add(left(Park.lbl("Select how you would like to pay.", Park.plain(13), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(26));
        col.add(optionCard("QR Code Payment", "GCash, Maya, or any e-wallet", Park.ACCENT, new Runnable() {
            public void run() { showQr(); }
        }));
        col.add(Box.createVerticalStrut(14));
        col.add(optionCard("Pay at Counter", "Pay cash or card at the parking booth", Park.SUCCESS, new Runnable() {
            public void run() { confirmBooking("Counter"); }
        }));
        col.add(Box.createVerticalGlue());

        Park.RoundButton back = Park.secondaryBtn("Back");
        back.setPreferredSize(new Dimension(300, 46)); back.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        back.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { steps.show(stepPanel, "form"); }
        });
        col.add(left(back));
        c.add(col, BorderLayout.CENTER);
        return c;
    }

    private JPanel optionCard(String title, String sub, final Color accent, final Runnable action) {
        final Park.Card oc = new Park.Card(new BorderLayout(), 16);
        oc.setFill(Park.BG_PANEL);
        oc.setBorder(Park.pad(14, 18, 14, 18));
        JPanel t = new JPanel(); t.setOpaque(false); t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.add(Park.lbl(title, Park.bold(16), Park.TEXT_PRIMARY));
        t.add(Park.lbl(sub, Park.plain(12), Park.TEXT_MUTED));
        oc.add(t, BorderLayout.CENTER);
        oc.setPreferredSize(new Dimension(300, 76)); oc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));
        oc.setAlignmentX(Component.LEFT_ALIGNMENT);
        oc.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        oc.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { oc.setLine(accent); }
            public void mouseExited(MouseEvent e)  { oc.setLine(Park.BORDER); }
            public void mouseClicked(MouseEvent e) { action.run(); }
        });
        return oc;
    }

    // ---------- step 3: QR ----------
    private void showQr() {
        qrHolder.removeAll();
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        JLabel t = Park.lbl("Scan QR Code to Pay", Park.bold(22), Park.TEXT_PRIMARY); t.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel am = Park.lbl("Amount due:  " + Park.money(total()), Park.bold(15), Park.ACCENT); am.setAlignmentX(Component.CENTER_ALIGNMENT);
        QRCanvas qr = new QRCanvas(slot + date + username + (int) total()); qr.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel inst = Park.lbl("Open GCash / Maya  ->  Scan QR  ->  Confirm", Park.plain(12), Park.TEXT_MUTED);
        inst.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel timerRow = new JPanel(); timerRow.setOpaque(false);
        timerRow.add(Park.lbl("QR expires in  ", Park.plain(12), Park.TEXT_MUTED));
        timerLbl = Park.lbl("60s", Park.bold(12), Park.WARNING); timerRow.add(timerLbl);
        timerRow.setAlignmentX(Component.CENTER_ALIGNMENT);

        paidBtn = Park.primaryBtn("I've Paid - Show Receipt");
        paidBtn.setPreferredSize(new Dimension(340, 46)); paidBtn.setMaximumSize(new Dimension(340, 46));
        paidBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        paidBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { stopTimer(); confirmBooking("QR Code"); }
        });
        Park.RoundButton cancel = Park.secondaryBtn("Cancel");
        cancel.setPreferredSize(new Dimension(340, 40)); cancel.setMaximumSize(new Dimension(340, 40));
        cancel.setAlignmentX(Component.CENTER_ALIGNMENT);
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { stopTimer(); steps.show(stepPanel, "pay"); }
        });

        col.add(t); col.add(Box.createVerticalStrut(4)); col.add(am); col.add(Box.createVerticalStrut(14));
        col.add(qr); col.add(Box.createVerticalStrut(8)); col.add(inst); col.add(Box.createVerticalStrut(4));
        col.add(timerRow); col.add(Box.createVerticalGlue());
        col.add(paidBtn); col.add(Box.createVerticalStrut(8)); col.add(cancel);
        qrHolder.add(col, BorderLayout.CENTER);
        qrHolder.revalidate(); qrHolder.repaint();
        steps.show(stepPanel, "qr");
        startTimer();
    }

    private void startTimer() {
        stopTimer(); countdown = 60;
        timer = new javax.swing.Timer(1000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                countdown--; timerLbl.setText(countdown + "s");
                if (countdown <= 0) {
                    stopTimer(); timerLbl.setText("Expired"); timerLbl.setForeground(Park.DANGER);
                    paidBtn.setEnabled(false);
                }
            }
        });
        timer.start();
    }

    private void stopTimer() { if (timer != null) timer.stop(); }

    // ---------- step 4: receipt ----------
    private void confirmBooking(String mode) {
        if (DataStore.getSlotStatus(date, slot) != 0) {
            JOptionPane.showMessageDialog(this, "Sorry, slot " + slot + " was just taken. Please pick another slot.");
            dispose(); return;
        }
        // For "Park Now" the parking timer starts at this moment.
        DataStore.reserveSlot(date, slot,
            new DataStore.ReservationInfo(username, date, slot, hours, total(), bookingStatus, mode));
        buildReceipt(mode);
        steps.show(stepPanel, "receipt");
    }

    private void buildReceipt(String mode) {
        receiptHolder.removeAll();
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        JLabel conf = Park.lbl(bookingStatus == 1 ? "Slot Reserved!" : "You're Parked!", Park.bold(24), Park.SUCCESS);
        conf.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sub = Park.lbl(bookingStatus == 1 ? "Slot " + slot + " is reserved for " + date + "."
            : "Slot " + slot + " is yours. Use Leave in My Parking when you are done.", Park.plain(12), Park.TEXT_MUTED);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel receipt = buildReceiptCard(mode); receipt.setAlignmentX(Component.CENTER_ALIGNMENT);

        Park.RoundButton done = Park.primaryBtn("Done");
        done.setPreferredSize(new Dimension(380, 46)); done.setMaximumSize(new Dimension(380, 46));
        done.setAlignmentX(Component.CENTER_ALIGNMENT);
        done.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { dispose(); }
        });

        col.add(conf); col.add(Box.createVerticalStrut(4)); col.add(sub); col.add(Box.createVerticalStrut(14));
        col.add(receipt); col.add(Box.createVerticalGlue()); col.add(done);
        receiptHolder.add(col, BorderLayout.CENTER);
        receiptHolder.revalidate(); receiptHolder.repaint();
    }

    private JPanel buildReceiptCard(String mode) {
        final boolean reserved = bookingStatus == 1;
        JPanel card = new JPanel() {
            private static final long serialVersionUID = 1L;
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = Park.aa(g);
                g2.setColor(new Color(255, 250, 235)); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.setColor(reserved ? Park.SLOT_RESERVED : Park.SLOT_OCCUPIED); g2.fillRect(0, 0, getWidth(), 5);
                g2.setColor(new Color(200, 185, 130));
                g2.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 6}, 0));
                g2.drawLine(18, getHeight() - 44, getWidth() - 18, getHeight() - 44);
                g2.dispose();
            }
        };
        card.setOpaque(false); card.setLayout(new BorderLayout());
        card.setPreferredSize(new Dimension(380, 300)); card.setMaximumSize(new Dimension(380, 300));
        card.setBorder(Park.pad(14, 20, 12, 20));

        JPanel rows = new JPanel(new GridLayout(10, 2, 6, 2)); rows.setOpaque(false);
        String txn = "TXN" + (System.currentTimeMillis() % 100000);
        String now = new SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.ENGLISH).format(new Date());
        row(rows, "PARKWISE RECEIPT", "", true);
        row(rows, "Transaction ID:", txn, false);  row(rows, "Issued:", now, false);
        row(rows, "Parking Date:", date, false);   row(rows, "Slot:", slot, false);
        row(rows, "Booking Type:", reserved ? "RESERVED" : "OCCUPIED", false);
        row(rows, "Duration:", hours + (hours == 1 ? " hour" : " hours"), false);
        row(rows, "Rate:", Park.money(Park.RATE) + "/hr", false);
        row(rows, "Payment:", mode, false);
        row(rows, "TOTAL PAID:", Park.money(total()), true);
        card.add(rows, BorderLayout.CENTER);

        JLabel footer = new JLabel("Thank you for using ParkWise!", SwingConstants.CENTER);
        footer.setFont(new Font("Segoe UI", Font.ITALIC, 11)); footer.setForeground(new Color(140, 120, 70));
        footer.setPreferredSize(new Dimension(100, 30));
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    private void row(JPanel grid, String k, String v, boolean bold) {
        JLabel kl = new JLabel(k);
        kl.setFont(bold ? new Font("Consolas", Font.BOLD, 12) : new Font("Consolas", Font.PLAIN, 11));
        kl.setForeground(bold ? Park.BG_DARK : new Color(110, 95, 55));
        JLabel vl = new JLabel(v, SwingConstants.RIGHT);
        vl.setFont(bold ? new Font("Consolas", Font.BOLD, 13) : new Font("Consolas", Font.BOLD, 11));
        vl.setForeground(bold ? new Color(0, 130, 80) : Park.BG_DARK);
        grid.add(kl); grid.add(vl);
    }

    // ---------- decorative QR code ----------
    static class QRCanvas extends JPanel {
        private static final long serialVersionUID = 1L;
        private static final int SZ = 25, CELL = 8;
        private final boolean[][] grid = new boolean[SZ][SZ];

        QRCanvas(String seed) {
            Random rng = new Random(seed.hashCode());
            for (int r = 0; r < SZ; r++) for (int c = 0; c < SZ; c++) grid[r][c] = rng.nextBoolean();
            finder(0, 0); finder(0, SZ - 7); finder(SZ - 7, 0);
            setOpaque(false);
            int px = SZ * CELL + 24;
            setPreferredSize(new Dimension(px, px)); setMaximumSize(new Dimension(px, px));
        }

        private void finder(int row, int col) {
            for (int r = 0; r < 7; r++) for (int c = 0; c < 7; c++) {
                if (row + r < SZ && col + c < SZ)
                    grid[row + r][col + c] = (r == 0 || r == 6 || c == 0 || c == 6) || (r >= 2 && r <= 4 && c >= 2 && c <= 4);
            }
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = Park.aa(g);
            int tot = SZ * CELL, ox = (getWidth() - tot) / 2, oy = (getHeight() - tot) / 2;
            g2.setColor(Color.WHITE); g2.fillRoundRect(ox - 10, oy - 10, tot + 20, tot + 20, 14, 14);
            g2.setColor(new Color(20, 18, 40));
            for (int r = 0; r < SZ; r++) for (int c = 0; c < SZ; c++)
                if (grid[r][c]) g2.fillRoundRect(ox + c * CELL + 1, oy + r * CELL + 1, CELL - 2, CELL - 2, 2, 2);
            g2.dispose();
        }
    }
}