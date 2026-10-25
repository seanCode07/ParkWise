package parkwise;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.plaf.FontUIResource;

/* =====================================================================
   Park : theme, helpers and custom components used by every screen
   ===================================================================== */
class Park {
    // ---- colors (dark design) ----
    static final Color BG_DARK = new Color(9, 9, 22), BG_PANEL = new Color(14, 14, 32), BG_CARD = new Color(22, 22, 46),
        BG_CARD_HI = new Color(32, 32, 64), ACCENT = new Color(124, 92, 255), ACCENT_2 = new Color(70, 130, 255),
        TEXT_PRIMARY = new Color(240, 240, 255), TEXT_MUTED = new Color(138, 138, 178),
        SUCCESS = new Color(52, 211, 153), WARNING = new Color(251, 176, 64), DANGER = new Color(244, 92, 120),
        BORDER = new Color(48, 48, 88),
        SLOT_AVAILABLE = new Color(46, 204, 140), SLOT_RESERVED = new Color(255, 170, 50), SLOT_OCCUPIED = new Color(244, 85, 110);
    // surfaces used by buttons, fields, lists and table rows
    static final Color BTN_IDLE = new Color(30, 30, 62), FIELD_BG = new Color(12, 12, 28), LIST_BG = new Color(18, 18, 40),
        ROW_ALT = new Color(24, 24, 50), SEL_BG = new Color(64, 52, 140), HOT_BG = new Color(58, 22, 40),
        FINE_BG = new Color(44, 16, 30), TRACK = new Color(40, 40, 80);

    // ---- rates (pesos per hour). Park Now and overtime use CAR_RATE / MOTO_RATE. ----
    static final double CAR_RATE = 15.0;
    static final double MOTO_RATE = 10.0;
    /** Reservation: first hour, then the normal rate for every extra hour. */
    static final double CAR_RESERVE_FIRST = 30.0;
    static final double MOTO_RESERVE_FIRST = 20.0;
    /** Reservation by a verified PWD / senior citizen: discounted first hour, then a discounted hourly rate. */
    static final double CAR_PRIORITY_FIRST = 20.0;
    static final double CAR_PRIORITY_RATE = 10.0;
    static final double MOTO_PRIORITY_FIRST = 10.0;
    static final double MOTO_PRIORITY_RATE = 5.0;
    /** Charged when a driver presses Leave while the car is still parked (abuse of the Leave button). */
    static final double LEAVE_PENALTY = 100.0;
    /** Charged to a driver (user or walk-in) who parks in a PWD / senior citizen slot without being a verified PWD / senior driver. */
    static final double PWD_VIOLATION = 100.0;
    static final String PESO = "\u20B1";
    static final String DATE_PATTERN = "MMMM dd, yyyy";

    // ---------- parking building: 4 floors, each with 30 car slots (24 regular + 6 PWD/Senior) and 30 motorcycle slots (24 regular + 6 PWD/Senior) ----------
    static final int FLOORS = 4;
    static final String[] FLOOR_NAMES = {"Ground Floor (1st)", "2nd Floor", "3rd Floor", "4th Floor"};
    static final int ROW_LEN = 12;          // slots in each of the rows A, B, M1 and M2 (24 regular car + 24 regular motorcycle)
    static final int PWD_COUNT = 6;         // PWD / senior car slots and PWD / senior motorcycle slots per floor (included in the 30)

    /** Slot id = floor + "F-" + local name, e.g. "2F-A5", "1F-PWD3", "4F-M1-7", "3F-PWM2". */
    static String slotId(int floor, String local) { return floor + "F-" + local; }

    /** Local slot names of one floor in map order. */
    static java.util.List<String> localNames() {
        java.util.List<String> l = new java.util.ArrayList<String>();
        for (int i = 1; i <= PWD_COUNT; i++) l.add("PWD" + i);
        for (int i = 1; i <= PWD_COUNT; i++) l.add("PWM" + i);
        for (int i = 1; i <= ROW_LEN; i++) l.add("A" + i);
        for (int i = 1; i <= ROW_LEN; i++) l.add("B" + i);
        for (int i = 1; i <= ROW_LEN; i++) l.add("M1-" + i);
        for (int i = 1; i <= ROW_LEN; i++) l.add("M2-" + i);
        return l;
    }

    /** Every slot of every floor. */
    static final String[] SLOTS = buildSlots();
    static final int SLOTS_PER_FLOOR = 2 * ROW_LEN + 2 * ROW_LEN + 2 * PWD_COUNT;   // = 60

    static int floorOf(String slot) {
        try { return Integer.parseInt(slot.substring(0, slot.indexOf('F'))); } catch (Exception e) { return 1; }
    }
    static String localOf(String slot) { int i = slot.indexOf("F-"); return i < 0 ? slot : slot.substring(i + 2); }
    static boolean isPwdSlot(String slot) { String l = localOf(slot); return l.startsWith("PWD") || l.startsWith("PWM"); }
    static boolean isMotoSlot(String slot) { String l = localOf(slot); return l.startsWith("M") || l.startsWith("PWM"); }
    /** "car slot", "motorcycle slot", "PWD/Senior car slot" or "PWD/Senior motorcycle slot". */
    static String slotKind(String slot) {
        return (isPwdSlot(slot) ? "PWD/Senior " : "") + (isMotoSlot(slot) ? "motorcycle" : "car") + " slot";
    }
    static String floorName(int floor) { return FLOOR_NAMES[Math.max(1, Math.min(FLOORS, floor)) - 1]; }
    /** Old save files only had "A1"-style names: those belong to the ground floor. */
    static String normalizeSlot(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.matches("\\d+F-.+") ? s : "1F-" + s;
    }
    static String[] slotsOnFloor(int floor) {
        java.util.List<String> l = new java.util.ArrayList<String>();
        for (String n : localNames()) l.add(slotId(floor, n));
        return l.toArray(new String[0]);
    }

    /** Images and fonts live next to the source files (see loadImage / loadFont). */
    private static final BufferedImage LOGO   = loadImage("logo.png");
    private static final BufferedImage PAY_QR = loadImage("payment_qr.png");
    private static final Font REG_FONT  = loadFont("SpaceGrotesk-Regular.ttf");
    private static final Font BOLD_FONT = loadFont("SpaceGrotesk-Bold.ttf");

    private static String[] buildSlots() {
        java.util.List<String> all = new java.util.ArrayList<String>();
        for (int f = 1; f <= FLOORS; f++) for (String n : localNames()) all.add(slotId(f, n));
        return all.toArray(new String[0]);
    }

    // ---------- images ----------
    private static BufferedImage loadImage(String name) {
        try {
            java.net.URL u = Park.class.getResource(name);
            if (u != null) return ImageIO.read(u);
            String[] dirs = {"src/parkwise/", "parkwise/", ""};
            for (String d : dirs) {
                File f = new File(d + name);
                if (f.exists()) return ImageIO.read(f);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    static boolean hasLogo()          { return LOGO != null; }
    static BufferedImage payQr()      { return PAY_QR; }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();
        return out;
    }

    /** High quality downscale: halves step by step so fine detail is not lost. */
    static BufferedImage fit(BufferedImage src, int w, int h) {
        if (src == null) return null;
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h)
            cur = resize(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        return (cur.getWidth() == w && cur.getHeight() == h) ? cur : resize(cur, w, h);
    }

    /** Window / taskbar icons in several sizes (null if the logo is missing). */
    static List<Image> icons() {
        if (LOGO == null) return null;
        List<Image> l = new ArrayList<Image>();
        for (int s : new int[]{16, 32, 48, 64, 128, 256}) l.add(fit(LOGO, s, s));
        return l;
    }

    static void applyIcon(Window w) {
        List<Image> l = icons();
        if (l != null) w.setIconImages(l);
    }

    // ---------- fonts (Space Grotesk, with Segoe UI as a fallback) ----------
    private static Font loadFont(String file) {
        try {
            InputStream in = Park.class.getResourceAsStream("fonts/" + file);
            if (in == null) {
                String[] dirs = {"src/parkwise/fonts/", "parkwise/fonts/", "fonts/"};
                for (String d : dirs) {
                    File f = new File(d + file);
                    if (f.exists()) { in = new java.io.FileInputStream(f); break; }
                }
            }
            if (in == null) return null;
            try {
                Font f = Font.createFont(Font.TRUETYPE_FONT, in);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
                return f;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    static Font bold(int sz)  { return BOLD_FONT != null ? BOLD_FONT.deriveFont((float) sz) : new Font("Segoe UI", Font.BOLD, sz); }
    static Font plain(int sz) { return REG_FONT  != null ? REG_FONT.deriveFont((float) sz)  : new Font("Segoe UI", Font.PLAIN, sz); }
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

    /** Pesos without ".00" when it is a whole number: 15 -> "\u20B115". */
    static String moneyShort(double v) {
        return v == Math.floor(v) ? PESO + (long) v : money(v);
    }

    /** Park Now / walk-in / overtime rate per hour for this slot (car or motorcycle). */
    static double rateOf(String slot) { return isMotoSlot(slot) ? MOTO_RATE : CAR_RATE; }

    /** Hourly rate of a verified PWD / senior citizen for Park Now, and for the hours after the first one of a reservation. */
    static double priorityRateOf(String slot) { return isMotoSlot(slot) ? MOTO_PRIORITY_RATE : CAR_PRIORITY_RATE; }

    /**
     * Price of a stay in this slot.
     * Park Now / walk-in: the normal hourly rate. Reservation: a higher first hour, then the normal rate;
     * a verified PWD / senior citizen pays the discounted reservation rates.
     */
    static double priceFor(int hours, boolean reservation, String slot, boolean priority) {
        if (hours <= 0) return 0;
        boolean moto = isMotoSlot(slot);
        if (!reservation) return hours * (priority ? priorityRateOf(slot) : rateOf(slot));
        double first = priority ? (moto ? MOTO_PRIORITY_FIRST : CAR_PRIORITY_FIRST) : (moto ? MOTO_RESERVE_FIRST : CAR_RESERVE_FIRST);
        double more  = priority ? (moto ? MOTO_PRIORITY_RATE : CAR_PRIORITY_RATE) : rateOf(slot);
        return first + (hours - 1) * more;
    }

    /** Short rate description, e.g. "30.00 1st hr, then 15.00/hr" (reservation) or "15.00 / hour" (Park Now). */
    static String rateText(String slot, boolean reservation, boolean priority) {
        boolean moto = isMotoSlot(slot);
        if (!reservation) return money(priority ? priorityRateOf(slot) : rateOf(slot)) + " / hour";
        double first = priority ? (moto ? MOTO_PRIORITY_FIRST : CAR_PRIORITY_FIRST) : (moto ? MOTO_RESERVE_FIRST : CAR_RESERVE_FIRST);
        double more  = priority ? (moto ? MOTO_PRIORITY_RATE : CAR_PRIORITY_RATE) : rateOf(slot);
        return money(first) + " 1st hr, then " + money(more) + "/hr";
    }

    /** A clock label with a fixed width that fits the widest possible text, so it never gets cut off or pushes other text. */
    static JLabel clockLabel() {
        JLabel l = new JLabel(clockText(), SwingConstants.CENTER);
        l.setFont(bold(14));
        l.setForeground(TEXT_PRIMARY);
        FontMetrics fm = l.getFontMetrics(l.getFont());
        int w = (int) (fm.stringWidth("Wed, Sep 30 2026   12:59:59 PM") * 1.08) + 6;
        l.setPreferredSize(new Dimension(w, fm.getHeight() + 4));
        l.setMinimumSize(new Dimension(w, fm.getHeight() + 4));
        return l;
    }

    /** "Thu, Oct 15 2026   3:45:12 PM" for the live clocks. */
    static String clockText() {
        return new SimpleDateFormat("EEE, MMM dd yyyy   h:mm:ss a", Locale.ENGLISH).format(new Date());
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
        UIManager.put("Label.foreground", TEXT_PRIMARY);
        UIManager.put("CheckBox.foreground", TEXT_PRIMARY);
        UIManager.put("CheckBox.background", BG_CARD);
        UIManager.put("RadioButton.foreground", TEXT_PRIMARY);
        UIManager.put("RadioButton.background", BG_CARD);
        UIManager.put("TextField.background", FIELD_BG);
        UIManager.put("TextField.foreground", TEXT_PRIMARY);
        UIManager.put("TextField.caretForeground", TEXT_PRIMARY);
        UIManager.put("PasswordField.background", FIELD_BG);
        UIManager.put("PasswordField.foreground", TEXT_PRIMARY);
        UIManager.put("PasswordField.caretForeground", TEXT_PRIMARY);
        UIManager.put("TextArea.background", FIELD_BG);
        UIManager.put("TextArea.foreground", TEXT_PRIMARY);
        UIManager.put("ComboBox.background", FIELD_BG);
        UIManager.put("ComboBox.foreground", TEXT_PRIMARY);
        UIManager.put("ComboBox.selectionBackground", SEL_BG);
        UIManager.put("ComboBox.selectionForeground", TEXT_PRIMARY);
        UIManager.put("List.background", LIST_BG);
        UIManager.put("List.foreground", TEXT_PRIMARY);
        UIManager.put("List.selectionBackground", SEL_BG);
        UIManager.put("List.selectionForeground", TEXT_PRIMARY);
        UIManager.put("ToolTip.background", BG_CARD_HI);
        UIManager.put("ToolTip.foreground", TEXT_PRIMARY);
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(BORDER));
        UIManager.put("Button.background", BTN_IDLE);
        UIManager.put("Button.foreground", TEXT_PRIMARY);
        UIManager.put("ScrollPane.background", BG_CARD);
        UIManager.put("Viewport.background", BG_CARD);
        FontUIResource reg = new FontUIResource(plain(13));
        String[] keys = {"OptionPane.messageFont", "OptionPane.buttonFont", "Button.font", "Label.font",
                         "TextField.font", "PasswordField.font", "CheckBox.font", "ToolTip.font",
                         "Table.font", "TextArea.font", "EditorPane.font", "ComboBox.font", "RadioButton.font"};
        for (String k : keys) UIManager.put(k, reg);
        UIManager.put("TableHeader.font", new FontUIResource(bold(12)));
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
        RoundButton b = new RoundButton(t, BTN_IDLE, TEXT_PRIMARY);
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
        tf.setFont(plain(14)); tf.setBackground(FIELD_BG);
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
        private Color fill = alpha(BG_CARD, 235);
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
                Font f = getFont();
                g2.setFont(f);
                FontMetrics fm = g2.getFontMetrics();
                String t = getText();
                int sz = f.getSize();
                while (fm.stringWidth(t) > w - 10 && sz > 9) {       // shrink the text instead of cutting it off
                    sz--; g2.setFont(f.deriveFont((float) sz)); fm = g2.getFontMetrics();
                }
                int tx = (w - fm.stringWidth(t)) / 2;
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(t, tx, ty);
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
            g2.setColor(TRACK); g2.drawOval(x, y, size, size);
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
            g2.setColor(TRACK); g2.fillRoundRect(0, 0, w, h, h, h);
            g2.setPaint(new GradientPaint(0, 0, ACCENT_2, w, 0, ACCENT));
            g2.fillRoundRect(0, 0, Math.max(h, (int) (w * (value / (double) max))), h, h, h);
            g2.dispose();
        }
    }

    /**
     * Draws an image sharply at any size. The picture is scaled to the screen's real pixel
     * size (so it stays crisp on Windows display scaling of 125%, 150% and so on).
     */
    static class ImgView extends JComponent {
        private static final long serialVersionUID = 1L;
        final BufferedImage src;
        final int w, h;
        private final boolean whiteBg;
        private BufferedImage cache;
        private int cachePx = -1;

        ImgView(BufferedImage src, int w, int h, boolean whiteBg) {
            this.src = src; this.w = w; this.h = h; this.whiteBg = whiteBg;
            Dimension d = new Dimension(w, h);
            setPreferredSize(d); setMaximumSize(d); setMinimumSize(d);
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = aa(g);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            int ox = (getWidth() - w) / 2, oy = (getHeight() - h) / 2;
            if (whiteBg) { g2.setColor(Color.WHITE); g2.fillRoundRect(ox, oy, w, h, 14, 14); }
            if (src == null) {
                paintMissing(g2, ox, oy);
            } else {
                double sc = g2.getTransform().getScaleX();
                if (sc <= 0) sc = 1;
                int pw = (int) Math.round(w * sc), ph = (int) Math.round(h * sc);
                if (cache == null || cachePx != pw) { cache = fit(src, pw, ph); cachePx = pw; }
                g2.drawImage(cache, ox, oy, w, h, null);
            }
            g2.dispose();
        }

        void paintMissing(Graphics2D g2, int ox, int oy) {
            g2.setColor(TEXT_MUTED); g2.setFont(plain(11));
            g2.drawString("image not found", ox + 8, oy + h / 2);
        }
    }

    /** The ParkWise logo. Falls back to a plain "P" badge if logo.png cannot be found. */
    static class LogoView extends ImgView {
        private static final long serialVersionUID = 1L;
        LogoView(int size) { super(LOGO, size, size, false); }
        void paintMissing(Graphics2D g2, int ox, int oy) {
            g2.setPaint(new GradientPaint(0, 0, ACCENT_2, w, h, ACCENT));
            g2.fillOval(ox, oy, w - 1, h - 1);
            g2.setFont(bold((int) (h * 0.45))); FontMetrics fm = g2.getFontMetrics();
            g2.setColor(Color.WHITE);
            g2.drawString("P", ox + (w - fm.stringWidth("P")) / 2, oy + (h - fm.getHeight()) / 2 + fm.getAscent());
        }
    }

    /** Rounded check box icon that matches the dark theme. */
    static class CheckIcon implements Icon {
        private final boolean on;
        CheckIcon(boolean on) { this.on = on; }
        public int getIconWidth()  { return 20; }
        public int getIconHeight() { return 20; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = aa(g);
            g2.translate(x, y);
            if (on) {
                g2.setColor(ACCENT); g2.fillRoundRect(0, 0, 19, 19, 6, 6);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(5, 10, 8, 14); g2.drawLine(8, 14, 15, 6);
            } else {
                g2.setColor(FIELD_BG); g2.fillRoundRect(0, 0, 19, 19, 6, 6);
                g2.setColor(BORDER); g2.drawRoundRect(0, 0, 19, 19, 6, 6);
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

    /**
     * A panel for use inside a JScrollPane that always takes the width of the viewport. Without this, content that is a little
     * wider than the viewport is silently cut off on the right (letters and fields disappear).
     */
    static class ScrollView extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;
        ScrollView(LayoutManager lm) { super(lm); setOpaque(false); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(40, r.height - 24); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // runs last so that fonts and everything else it needs already exist
    static { styleDialogs(); }
}