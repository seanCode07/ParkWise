package parkwise;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;

/* =====================================================================
   FloorPlan : the parking floor drawn like the real plan.
   Stairs, mall entrance and elevator on top; PWD car / PWD motorcycle slots; car rows A and B;
   motorcycle rows M1 and M2; traffic lanes; fire exit; counter and pay booth on the ground floor.
   Every slot is clickable and shows the vehicle and a live countdown when it is booked.
   ===================================================================== */
class FloorPlan extends JComponent {
    private static final long serialVersionUID = 1L;

    interface Listener { void slotClicked(String slotId); }

    // logical drawing size; the plan is scaled to fit the component
    static final double LW = 1000, LH = 488;
    private static final double X0 = 118, CW = 66;           // first slot column and slot width of rows A, B, M1, M2
    private static final double A_Y = 170, ROW_H = 58;        // car rows
    private static final double M_Y = 338, MROW_H = 46;       // motorcycle rows

    private static final Color LINE_CAR  = new Color(70, 130, 220);
    private static final Color LINE_MOTO = new Color(46, 170, 120);
    private static final Color PWD_FILL  = new Color(40, 110, 235);

    private int floor = 1;
    private String username = "";
    private boolean userPwd = false;
    private final Map<String, DataStore.ReservationInfo> infos = new HashMap<String, DataStore.ReservationInfo>();
    private final Map<String, Rectangle2D.Double> cells = new LinkedHashMap<String, Rectangle2D.Double>();
    private Listener listener;
    private String hover = null;
    private boolean adminMode = false;        // admin monitoring: plate numbers in the slots and driver details in the tooltips
    private double scale = 1, offX = 0, offY = 0;

    FloorPlan() {
        setOpaque(false);
        setToolTipText("plan");          // registers the tooltip; the text is built in getToolTipText(MouseEvent)
        setPreferredSize(new Dimension(760, 400));
        buildLayout();
        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) { setHover(slotAt(e.getPoint())); }
        });
        addMouseListener(new MouseAdapter() {
            public void mouseExited(MouseEvent e) { setHover(null); }
            public void mouseClicked(MouseEvent e) {
                String id = slotAt(e.getPoint());
                if (id != null && listener != null) listener.slotClicked(id);
            }
        });
    }

    void setListener(Listener l) { listener = l; }

    /** In admin mode every booked slot shows the plate number, and the tooltip names the driver. */
    void setAdminMode(boolean on) { adminMode = on; repaint(); }

    void setFloor(int f) { floor = f; hover = null; buildLayout(); repaint(); }

    int getFloor() { return floor; }

    /** infoBySlot: the booking that holds each slot on the shown date (a slot with no entry is free). */
    void setData(String username, boolean userPwd, Map<String, DataStore.ReservationInfo> infoBySlot) {
        this.username = username; this.userPwd = userPwd;
        infos.clear(); infos.putAll(infoBySlot);
        repaint();
    }

    private void setHover(String id) {
        if ((id == null && hover == null) || (id != null && id.equals(hover))) return;
        hover = id;
        setCursor(id == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        repaint();
    }

    // ---------- layout ----------
    private void put(String local, double x, double y, double w, double h) {
        cells.put(Park.slotId(floor, local), new Rectangle2D.Double(x, y, w, h));
    }

    private void buildLayout() {
        cells.clear();
        // top strip: 3 PWD/Senior motorcycle slots on the left, 6 PWD/Senior car slots in the middle, 3 PWD/Senior motorcycle slots on the right
        for (int i = 0; i < Park.PWD_COUNT; i++) put("PWD" + (i + 1), 326 + i * 58, 80, 58, 48);
        double rowEnd = X0 + Park.ROW_LEN * CW;
        for (int i = 0; i < 3; i++) put("PWM" + (i + 1), X0 + i * 52, 80, 52, 48);
        for (int i = 0; i < 3; i++) put("PWM" + (i + 4), rowEnd - 156 + i * 52, 80, 52, 48);
        for (int i = 0; i < Park.ROW_LEN; i++) put("A" + (i + 1),  X0 + i * CW, A_Y,         CW, ROW_H);
        for (int i = 0; i < Park.ROW_LEN; i++) put("B" + (i + 1),  X0 + i * CW, A_Y + ROW_H, CW, ROW_H);
        for (int i = 0; i < Park.ROW_LEN; i++) put("M1-" + (i + 1), X0 + i * CW, M_Y,          CW, MROW_H);
        for (int i = 0; i < Park.ROW_LEN; i++) put("M2-" + (i + 1), X0 + i * CW, M_Y + MROW_H, CW, MROW_H);
    }

    private void transform() {
        scale = Math.min(getWidth() / LW, getHeight() / LH);
        if (scale <= 0) scale = 1;
        offX = (getWidth() - LW * scale) / 2;
        offY = (getHeight() - LH * scale) / 2;
    }

    private String slotAt(Point p) {
        transform();
        double x = (p.x - offX) / scale, y = (p.y - offY) / scale;
        for (Map.Entry<String, Rectangle2D.Double> e : cells.entrySet())
            if (e.getValue().contains(x, y)) return e.getKey();
        return null;
    }

    // ---------- text helpers (also used by tooltips) ----------
    static String timerText(DataStore.ReservationInfo info, long now) {
        if (info == null) return null;
        if (info.status == DataStore.PARKED) {
            if (info.hasLeaveRequest()) return "leaving soon";
            long rem = info.remainingMillis(now);
            return rem >= 0 ? Park.hms(rem) : "+" + Park.hms(-rem);
        }
        if (info.status == DataStore.PENDING) return "pending";
        long toStart = info.schedStartMillis() - now;
        return toStart > 0 ? "in " + Park.hms(toStart) : Park.hms(info.endMillis - now);
    }

    static Color timerColor(DataStore.ReservationInfo info, long now) {
        if (info == null) return Park.TEXT_MUTED;
        if (info.status == DataStore.PARKED) {
            if (info.hasLeaveRequest()) return Park.WARNING;
            long rem = info.remainingMillis(now);
            return rem < 0 ? Park.DANGER : rem <= 5 * 60000L ? Park.WARNING : Park.SUCCESS;
        }
        return info.status == DataStore.PENDING ? Park.WARNING : Park.TEXT_PRIMARY;
    }

    public String getToolTipText(MouseEvent ev) {
        String id = slotAt(ev.getPoint());
        if (id == null) return null;
        long now = System.currentTimeMillis();
        DataStore.ReservationInfo info = infos.get(id);
        boolean pwd = Park.isPwdSlot(id);
        StringBuilder sb = new StringBuilder("<html><b>Slot " + id + "</b>  -  " + Park.slotKind(id));
        if (info == null) {
            sb.append("<br>Available all day. Click to book.");
            if (pwd) sb.append(userPwd ? "<br>PWD / senior citizen priority slot." : "<br><font color='#FFAA32'>PWD / senior citizen drivers only (verified ID).</font>");
            return sb.append("</html>").toString();
        }
        if (adminMode) {
            String who = DataStore.isWalkIn(info.user) ? "Walk-in driver" : DataStore.getDisplayName(info.user)
                    + (DataStore.getPhone(info.user).isEmpty() ? "" : "  (" + DataStore.getPhone(info.user) + ")");
            sb.append("<br>Driver: ").append(who);
            sb.append("<br>Plate: ").append(Fmt.formatPlate(info.plate));
            String st = info.status == DataStore.PARKED ? (info.hasLeaveRequest() ? "Parked - overtime payment sent" : "Parked")
                      : info.status == DataStore.PENDING ? "Waiting for payment approval" : "Reserved";
            sb.append("<br>Status: ").append(st);
            sb.append("<br>Time: ").append(Fmt.timeRange(info.startMin, info.endMin())).append("  (").append(info.hours).append("h paid)");
            if (info.status == DataStore.PARKED) {
                long rem = info.remainingMillis(now);
                sb.append("<br>").append(rem >= 0 ? "<b>Time left: " + Park.hms(rem) + "</b>"
                                                   : "<b><font color='#F45C78'>OVER by " + Park.hms(-rem) + "</font></b>");
            } else if (info.status == DataStore.RESERVED) {
                long toStart = info.schedStartMillis() - now;
                sb.append("<br>").append(toStart > 0 ? "Starts in: " + Park.hms(toStart) : "Time left: " + Park.hms(info.endMillis - now));
            }
            sb.append("<br>Click the slot for more details.</html>");
            return sb.toString();
        }
        boolean mine = info.user.equals(username);
        if (info.status == DataStore.PARKED) {
            sb.append("<br>Vehicle parked").append(mine ? " (you)" : "");
            sb.append("<br>Paid time ends: ").append(Fmt.time(info.endMin() % 1440));
            long rem = info.remainingMillis(now);
            sb.append("<br>").append(rem >= 0 ? "Time remaining: " + Park.hms(rem) : "Over the paid time by " + Park.hms(-rem));
        } else if (info.status == DataStore.PENDING) {
            sb.append("<br>Held, waiting for payment approval").append(mine ? " (you)" : "");
            sb.append("<br>Requested: ").append(Fmt.timeRange(info.startMin, info.endMin()));
        } else {
            sb.append("<br>Reserved").append(mine ? " (you)" : "");
            sb.append("<br>Reserved time: ").append(Fmt.timeRange(info.startMin, info.endMin()));
            long toStart = info.schedStartMillis() - now;
            sb.append("<br>").append(toStart > 0 ? "Starts in: " + Park.hms(toStart) : "Time remaining: " + Park.hms(info.endMillis - now));
        }
        sb.append("<br>Not available for other drivers today.</html>");
        return sb.toString();
    }

    // ---------- painting ----------
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Park.aa(g);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        transform();
        g2.translate(offX, offY);
        g2.scale(scale, scale);
        long now = System.currentTimeMillis();

        drawStructure(g2);
        for (Map.Entry<String, Rectangle2D.Double> e : cells.entrySet())
            drawCell(g2, e.getKey(), e.getValue(), now);
        drawPillars(g2);
        g2.dispose();
    }

    private void text(Graphics2D g2, String s, double cx, double baseY, Font f, Color c) {
        g2.setFont(f); g2.setColor(c);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) baseY);
    }

    private void textLeft(Graphics2D g2, String s, double x, double baseY, Font f, Color c) {
        g2.setFont(f); g2.setColor(c);
        g2.drawString(s, (float) x, (float) baseY);
    }

    private static final BasicStroke THIN = new BasicStroke(1f);
    private static final BasicStroke DASH = new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
            new float[]{7, 6}, 0);

    private void lane(Graphics2D g2, double x1, double x2, double y, boolean right) {
        g2.setColor(Park.alpha(LINE_CAR, 150)); g2.setStroke(DASH);
        g2.draw(new Line2D.Double(x1, y, x2, y));
        g2.setStroke(new BasicStroke(1.6f));
        for (double x = x1 + 160; x < x2 - 40; x += 240) {
            int d = right ? 1 : -1;
            g2.draw(new Line2D.Double(x - 6 * d, y - 6, x, y));
            g2.draw(new Line2D.Double(x - 6 * d, y + 6, x, y));
        }
        g2.setStroke(THIN);
    }

    private void drawStructure(Graphics2D g2) {
        // outer frame and the strip with stairs / mall entrance / elevator
        g2.setColor(Park.alpha(LINE_CAR, 190)); g2.setStroke(new BasicStroke(1.6f));
        g2.draw(new RoundRectangle2D.Double(1, 1, LW - 2, LH - 2, 10, 10));
        g2.setStroke(THIN);
        g2.draw(new Line2D.Double(1, 66, LW - 1, 66));

        // stairs
        g2.setColor(LINE_CAR);
        g2.draw(new Rectangle2D.Double(40, 10, 90, 36));
        for (int i = 1; i < 9; i++) g2.draw(new Line2D.Double(40 + i * 10, 10, 40 + i * 10, 46));
        text(g2, "Stairs", 85, 60, Park.plain(11), Park.TEXT_PRIMARY);
        // mall entrance
        g2.setColor(Park.alpha(Park.TEXT_MUTED, 200)); g2.setStroke(DASH);
        g2.draw(new RoundRectangle2D.Double(340, 8, 260, 50, 12, 12));
        g2.setStroke(THIN);
        text(g2, "Mall entrance", 470, 30, Park.bold(13), Park.TEXT_PRIMARY);
        text(g2, "to shops and stores", 470, 47, Park.plain(10), Park.TEXT_MUTED);
        // elevator
        g2.setColor(new Color(90, 70, 210)); g2.fill(new Rectangle2D.Double(800, 10, 46, 36));
        g2.setColor(new Color(170, 150, 255));
        g2.draw(new Rectangle2D.Double(800, 10, 46, 36));
        g2.draw(new Line2D.Double(800, 10, 846, 46)); g2.draw(new Line2D.Double(846, 10, 800, 46));
        text(g2, "Elevator", 823, 60, Park.plain(11), Park.TEXT_PRIMARY);

        // zone captions
        textLeft(g2, "Car zone", 12, 92, Park.bold(12), Park.TEXT_PRIMARY);
        textLeft(g2, "24 regular", 12, 106, Park.plain(10), Park.TEXT_MUTED);
        textLeft(g2, "+ 6 PWD/Senior", 12, 118, Park.plain(10), Park.TEXT_MUTED);
        double rowEnd = X0 + Park.ROW_LEN * CW;
        text(g2, "PWD / Senior motorcycles", X0 + 78, 142, Park.plain(10), Park.TEXT_MUTED);
        text(g2, "PWD / Senior cars", 326 + 87, 142, Park.plain(10), Park.TEXT_MUTED);
        text(g2, "PWD / Senior motorcycles", rowEnd - 78, 142, Park.plain(10), Park.TEXT_MUTED);
        textLeft(g2, "Motorcycle zone", 12, 318, Park.bold(12), Park.TEXT_PRIMARY);
        textLeft(g2, "24 regular", 12, 331, Park.plain(10), Park.TEXT_MUTED);
        textLeft(g2, "+ 6 PWD/Senior", 12, 343, Park.plain(10), Park.TEXT_MUTED);

        // traffic lanes
        double ly1 = 158, ly2 = 312, ly3 = 456;
        g2.setColor(Park.TEXT_MUTED); g2.setStroke(new BasicStroke(1.6f));
        g2.draw(new Line2D.Double(12, ly1, 34, ly1)); g2.draw(new Line2D.Double(28, ly1 - 4, 34, ly1)); g2.draw(new Line2D.Double(28, ly1 + 4, 34, ly1));
        g2.setStroke(THIN);
        lane(g2, 40, 940, ly1, true);
        lane(g2, 100, 940, ly2, false);
        lane(g2, 40, 940, ly3, true);
        textLeft(g2, "Entrance", 12, 176 - 6, Park.bold(11), Park.SLOT_AVAILABLE);
        g2.setColor(Park.TEXT_MUTED); g2.setStroke(new BasicStroke(1.6f));
        g2.draw(new Line2D.Double(950, ly3, 986, ly3)); g2.draw(new Line2D.Double(980, ly3 - 4, 986, ly3)); g2.draw(new Line2D.Double(980, ly3 + 4, 986, ly3));
        g2.setStroke(THIN);
        textLeft(g2, "Exit", 948, 478, Park.bold(11), Park.WARNING);

        // row letters
        text(g2, "A", 100, A_Y + ROW_H / 2 + 5, Park.bold(15), Park.TEXT_PRIMARY);
        text(g2, "B", 100, A_Y + ROW_H * 1.5 + 5, Park.bold(15), Park.TEXT_PRIMARY);
        text(g2, "M1", 98, M_Y + MROW_H / 2 + 5, Park.bold(14), Park.TEXT_PRIMARY);
        text(g2, "M2", 98, M_Y + MROW_H * 1.5 + 5, Park.bold(14), Park.TEXT_PRIMARY);

        // fire exit
        g2.setColor(LINE_CAR);
        g2.draw(new Rectangle2D.Double(948, 182, 40, 52));
        for (int i = 1; i < 4; i++) g2.draw(new Line2D.Double(948 + i * 10, 182, 948 + i * 10, 234));
        text(g2, "Fire exit", 968, 250, Park.plain(10), Park.TEXT_PRIMARY);

        // the ground floor also has the counter and the pay booth
        if (floor == 1) {
            g2.setColor(new Color(34, 76, 140)); g2.fill(new Rectangle2D.Double(10, 190, 70, 76));
            g2.setColor(LINE_CAR); g2.draw(new Rectangle2D.Double(10, 190, 70, 76));
            text(g2, "Counter", 45, 232, Park.bold(11), Color.WHITE);
            g2.setColor(Park.alpha(Park.WARNING, 60)); g2.fill(new Rectangle2D.Double(936, 392, 54, 40));
            g2.setColor(Park.WARNING); g2.setStroke(new BasicStroke(1.6f));
            g2.draw(new Rectangle2D.Double(936, 392, 54, 40)); g2.setStroke(THIN);
            text(g2, "Pay booth", 963, 416, Park.bold(10), Park.TEXT_PRIMARY);
        }
    }

    private void drawPillars(Graphics2D g2) {
        int[] pil = {0, 4, 8, 12};
        for (int k : pil) {
            double x = X0 + k * CW;
            g2.setColor(new Color(110, 180, 255)); g2.fill(new Rectangle2D.Double(x - 5, A_Y + ROW_H - 5, 10, 10));
            g2.setColor(new Color(46, 204, 140));  g2.fill(new Rectangle2D.Double(x - 5, M_Y + MROW_H - 5, 10, 10));
        }

    }

    private void vehicle(Graphics2D g2, boolean moto, double cx, double top, double h, Color fill, Color line, boolean dashed) {
        double w = moto ? 10 : 26;
        RoundRectangle2D.Double r = new RoundRectangle2D.Double(cx - w / 2, top, w, h, moto ? 9 : 10, moto ? 9 : 10);
        if (fill != null) { g2.setColor(fill); g2.fill(r); }
        g2.setColor(line);
        g2.setStroke(dashed ? new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{3, 3}, 0)
                            : new BasicStroke(1.4f));
        g2.draw(r);
        if (!moto) {                                    // windscreen and rear window
            g2.setStroke(THIN);
            g2.draw(new Line2D.Double(cx - w / 2 + 4, top + h * 0.28, cx + w / 2 - 4, top + h * 0.28));
            g2.draw(new Line2D.Double(cx - w / 2 + 4, top + h * 0.78, cx + w / 2 - 4, top + h * 0.78));
        }
        g2.setStroke(THIN);
    }

    private void drawCell(Graphics2D g2, String id, Rectangle2D.Double r, long now) {
        DataStore.ReservationInfo info = infos.get(id);
        boolean pwd = Park.isPwdSlot(id), moto = Park.isMotoSlot(id);
        boolean free = info == null;
        boolean parked = !free && info.status == DataStore.PARKED;
        boolean mine = !free && info.user.equals(username);
        boolean hov = id.equals(hover);
        Color base = moto ? LINE_MOTO : LINE_CAR;
        Color status = free ? Park.SLOT_AVAILABLE : parked ? Park.SLOT_OCCUPIED : Park.SLOT_RESERVED;

        // cell body
        if (pwd) { g2.setColor(Park.alpha(PWD_FILL, free ? 235 : 170)); g2.fill(r); }
        else     { g2.setColor(new Color(20, 34, 70, 200)); g2.fill(r); }
        if (!free) { g2.setColor(Park.alpha(status, parked ? 85 : 70)); g2.fill(r); }
        if (hov)   { g2.setColor(new Color(255, 255, 255, 38)); g2.fill(r); }
        g2.setColor(Park.alpha(pwd && moto ? Park.SLOT_AVAILABLE : base, 190));
        g2.setStroke(new BasicStroke(pwd ? 1.6f : 1f)); g2.draw(r); g2.setStroke(THIN);
        if (!free) {
            g2.setColor(mine ? status : Park.alpha(status, 190));
            g2.setStroke(new BasicStroke(mine ? 2.6f : 1.6f));
            g2.draw(new Rectangle2D.Double(r.x + 1, r.y + 1, r.width - 2, r.height - 2));
            g2.setStroke(THIN);
        }

        // label
        String local = Park.localOf(id);
        String lab = pwd ? local.substring(local.length() - 1)
                   : local.startsWith("M") ? local.substring(local.indexOf('-') + 1) : local;
        textLeft(g2, lab, r.x + 4, r.y + 12, Park.bold(10), free && !pwd ? Park.TEXT_MUTED : Park.TEXT_PRIMARY);
        if (pwd && free) {
            text(g2, "PWD", r.getCenterX(), r.y + r.height / 2 + 1, Park.bold(13), Color.WHITE);
            text(g2, "SENIOR", r.getCenterX(), r.y + r.height / 2 + 14, Park.bold(8), new Color(220, 235, 255));
        } else if (pwd) {
            text(g2, "PWD/SR", r.getCenterX() + 2, r.y + 12, Park.bold(8), Color.WHITE);
        }

        if (free) {
            if (!pwd) {                       // small green dot: open
                g2.setColor(Park.alpha(Park.SLOT_AVAILABLE, 170));
                g2.fill(new Ellipse2D.Double(r.getCenterX() - 3, r.y + r.height / 2 - 2, 6, 6));
            }
            return;
        }

        // vehicle + countdown
        double iconTop = r.y + 14, iconH = r.height - 14 - 15;
        if (adminMode) {
            String pl = Fmt.formatPlate(info.plate);
            int ps = 10;
            g2.setFont(Park.bold(ps));
            while (g2.getFontMetrics().stringWidth(pl) > r.width - 4 && ps > 7) { ps--; g2.setFont(Park.bold(ps)); }
            text(g2, pl, r.getCenterX(), r.y + r.height / 2 + 3, Park.bold(ps), Park.TEXT_PRIMARY);
        } else {
            vehicle(g2, moto, r.getCenterX(), iconTop, Math.max(14, iconH),
                    parked ? Park.alpha(Park.SLOT_OCCUPIED, 150) : null, parked ? Park.SLOT_OCCUPIED : Park.SLOT_RESERVED, !parked);
        }
        String tm = timerText(info, now);
        int size = moto || pwd ? 9 : 10;
        g2.setFont(Park.bold(size));
        while (g2.getFontMetrics().stringWidth(tm) > r.width - 3 && size > 7) { size--; g2.setFont(Park.bold(size)); }
        text(g2, tm, r.getCenterX(), r.y + r.height - 4, Park.bold(size), timerColor(info, now));
        if (mine && !pwd) {
            g2.setColor(status); g2.fill(new RoundRectangle2D.Double(r.x + r.width - 26, r.y + 2, 24, 12, 6, 6));
            text(g2, "YOU", r.x + r.width - 14, r.y + 11.5, Park.bold(9), Park.BG_DARK);
        }
    }
}