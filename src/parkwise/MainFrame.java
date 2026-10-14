package parkwise;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.*;

/* =====================================================================
   MainFrame : one landscape dashboard (calendar | slots | usage + my parking)
   Updates itself in real time when the admin (or another copy of the app) changes the data.
   ===================================================================== */
class MainFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private final String username;
    private Calendar selectedCal = Calendar.getInstance();
    private Calendar displayCal  = Calendar.getInstance();
    private JLabel monthYearLbl, selectedDateLbl, slotsTitleLbl, availLbl, resvLbl, occLbl, usageCaption, plateLbl;
    private JPanel calGrid, slotGrid, myList;
    private Park.RingPanel ring;
    private String plate;                       // the driver's plate number for this session

    // live parking timer + real-time sync
    private javax.swing.Timer liveTimer;
    private final List<LiveRow> live = new ArrayList<LiveRow>();
    private final Set<String> notified = new HashSet<String>();
    private int seenVersion = -1;
    private final Map<String, String> seen = new HashMap<String, String>();   // booking id -> "slot|status"
    private boolean seenInit = false;

    private static class LiveRow {
        final DataStore.ReservationInfo info;
        final JLabel lbl;
        LiveRow(DataStore.ReservationInfo info, JLabel lbl) { this.info = info; this.lbl = lbl; }
    }

    MainFrame(String username) {
        this.username = username;
        String last = DataStore.lastPlate(username);
        this.plate = last.isEmpty() ? null : last;
        setTitle("ParkWise - Dashboard");
        Park.applyIcon(this);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1320, scr.width - 20), Math.min(760, scr.height - 60));
        setMinimumSize(new Dimension(1240, 660));
        setLocationRelativeTo(null);
        buildUI();
        seenVersion = DataStore.version();
        refreshAll();

        liveTimer = new javax.swing.Timer(1000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                int v = DataStore.version();            // picks up changes made by other running copies
                if (v != seenVersion) { seenVersion = v; refreshAll(); }
                tickLive();
            }
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

    /** Non-blocking pop-up, so the screen keeps updating while it is open. */
    private void notice(String title, String msg, int type) {
        JOptionPane pane = new JOptionPane(msg, type);
        JDialog d = pane.createDialog(this, title);
        d.setModal(false);
        Park.applyIcon(d);
        d.setVisible(true);
        Toolkit.getDefaultToolkit().beep();
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
        final String shownName = DataStore.getDisplayName(username);
        Park.Badge avatar = new Park.Badge(shownName.substring(0, 1).toUpperCase(), 40);
        avatar.setAlignmentX(Component.CENTER_ALIGNMENT);
        avatar.setToolTipText(shownName);
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
        left.add(Park.lbl("Hello, " + DataStore.getDisplayName(username).split(" ")[0]
                + ". Pick a date, then choose a slot and a time.", Park.plain(13), Park.TEXT_MUTED));
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
        prev.addActionListener(Fmt.act(new Runnable() {
            public void run() { displayCal.add(Calendar.MONTH, -1); refreshCal(); }
        }));
        next.addActionListener(Fmt.act(new Runnable() {
            public void run() { displayCal.add(Calendar.MONTH, 1); refreshCal(); }
        }));
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
        card.setBorder(Park.pad(14, 20, 12, 16));
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        JLabel l1 = Park.lbl("SELECTED DATE", Park.bold(11), Park.TEXT_MUTED); l1.setAlignmentX(Component.LEFT_ALIGNMENT);
        selectedDateLbl = Park.lbl(" ", Park.bold(17), Park.TEXT_PRIMARY); selectedDateLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel l2 = Park.lbl("PLATE NUMBER", Park.bold(11), Park.TEXT_MUTED); l2.setAlignmentX(Component.LEFT_ALIGNMENT);
        plateLbl = Park.lbl(" ", Park.bold(17), Park.TEXT_PRIMARY);
        JLabel change = Park.lbl("Change", Park.bold(12), Park.ACCENT);
        change.setBorder(Park.pad(0, 12, 0, 0));
        change.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        change.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { promptPlate(); }
        });
        JPanel plateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); plateRow.setOpaque(false);
        plateRow.add(plateLbl); plateRow.add(change);
        plateRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        plateRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        updatePlateLabel();

        JLabel l3 = Park.lbl("RATE", Park.bold(11), Park.TEXT_MUTED); l3.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel rate = Park.lbl(Park.money(Park.RATE) + " / hour", Park.bold(17), Park.ACCENT); rate.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel tip = Park.lbl(Park.lines(
                "Pick a slot, then choose your time.",
                "Overtime is billed at " + Park.money(Park.RATE) + "/hr after",
                "a " + DataStore.GRACE_MINUTES + "-min grace period."),
            Park.plain(11), Park.TEXT_MUTED);
        tip.setAlignmentX(Component.LEFT_ALIGNMENT);

        col.add(l1); col.add(Box.createVerticalStrut(2)); col.add(selectedDateLbl);
        col.add(Box.createVerticalStrut(8));
        col.add(l2); col.add(Box.createVerticalStrut(2)); col.add(plateRow);
        col.add(Box.createVerticalStrut(8));
        col.add(l3); col.add(Box.createVerticalStrut(2)); col.add(rate);
        col.add(Box.createVerticalStrut(8)); col.add(tip);
        card.add(col, BorderLayout.NORTH);
        return card;
    }

    private void updatePlateLabel() {
        if (plate == null) { plateLbl.setText("Not set"); plateLbl.setForeground(Park.TEXT_MUTED); }
        else { plateLbl.setText(Fmt.formatPlate(plate)); plateLbl.setForeground(Park.TEXT_PRIMARY); }
    }

    private boolean promptPlate() {
        String p = Fmt.askPlate(this, plate);
        if (p == null) return false;
        plate = p;
        updatePlateLabel();
        return true;
    }

    private boolean ensurePlate() { return plate != null || promptPlate(); }

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
        legend.add(Park.lbl("\u2022 Free all day", Park.plain(12), Park.SLOT_AVAILABLE));
        legend.add(Park.lbl("\u2022 Has bookings",  Park.plain(12), Park.SLOT_RESERVED));
        legend.add(Park.lbl("\u2022 Car parked",  Park.plain(12), Park.SLOT_OCCUPIED));
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
        col.add(Park.lbl("\u2022  " + title, Park.plain(12), c));
        col.add(Box.createVerticalStrut(2)); col.add(value);
        card.add(col, BorderLayout.CENTER);
        return card;
    }

    // ----- right column: usage ring + my parking -----
    private JPanel buildRight() {
        JPanel p = new JPanel(new BorderLayout(0, 18)); p.setOpaque(false);
        p.setPreferredSize(new Dimension(320, 100));

        Park.Card usage = new Park.Card(new BorderLayout(0, 4), 20);
        usage.setBorder(Park.pad(16, 16, 16, 16));
        usage.setPreferredSize(new Dimension(320, 250));
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
    private void refreshAll() {
        detectChanges();
        refreshCal(); refreshSlots(); refreshStats(); refreshMine();
    }

    /** Compares my bookings with the last look and tells the driver what the admin changed. */
    private void detectChanges() {
        Map<String, String> now = new HashMap<String, String>();
        for (DataStore.ReservationInfo r : DataStore.getReservationsForUser(username))
            now.put(r.id, r.slot + "|" + r.status);
        if (seenInit) {
            for (Map.Entry<String, String> e : seen.entrySet()) {
                String[] old = e.getValue().split("\\|");
                String curS = now.get(e.getKey());
                if (curS == null) {
                    if (old[1].equals("3"))
                        notice("Payment Not Approved", "Your counter payment request for slot " + old[0]
                                + " was declined by the admin.\nThe slot has been released.", JOptionPane.WARNING_MESSAGE);
                    else if (old[1].equals("2"))
                        notice("Parking Ended", "An administrator ended your parking session in slot " + old[0]
                                + ".\nAny overtime is shown under My Parking.", JOptionPane.WARNING_MESSAGE);
                    else
                        notice("Reservation Removed", "Your reservation for slot " + old[0]
                                + " was removed by an administrator.", JOptionPane.WARNING_MESSAGE);
                } else {
                    String[] cur = curS.split("\\|");
                    if (old[1].equals("3") && cur[1].equals("1"))
                        notice("Payment Approved", "The admin approved your counter payment for slot " + cur[0]
                                + ".\nYou may now park. Press \"Park Now\" under My Parking when you are in the slot.",
                                JOptionPane.INFORMATION_MESSAGE);
                    if (!old[0].equals(cur[0]))
                        notice("Slot Changed", "An administrator moved your booking from slot " + old[0]
                                + " to slot " + cur[0] + " to match where your car is.", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }
        seen.clear(); seen.putAll(now); seenInit = true;
    }

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
        slotsTitleLbl.setText("Parking Slots  \u00B7  " + Park.shortDate(date));
        slotGrid.removeAll();
        for (String slot : Park.SLOTS) slotGrid.add(buildSlotBtn(date, slot));
        slotGrid.revalidate(); slotGrid.repaint();
    }

    private TimeSlotButton buildSlotBtn(final String date, final String slot) {
        List<DataStore.ReservationInfo> list = DataStore.getBookings(date, slot);
        int status = DataStore.getSlotStatus(date, slot);
        boolean mine = false;
        StringBuilder tip = new StringBuilder("<html>");
        if (list.isEmpty()) {
            tip.append("Free all day. Click to book slot ").append(slot).append(".");
        } else {
            tip.append("<b>Slot ").append(slot).append(" is booked at:</b>");
            for (DataStore.ReservationInfo r : list) {
                boolean me = r.user.equals(username);
                if (me) mine = true;
                tip.append("<br>").append(Fmt.timeRange(r.startMin, r.endMin())).append(me ? "  (you)" : "");
            }
            tip.append("<br>Click to book another time.");
        }
        tip.append("</html>");
        TimeSlotButton btn = new TimeSlotButton(slot, status, mine, list.size());
        btn.setToolTipText(tip.toString());
        btn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { onSlotClick(date, slot); }
        });
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
        usageCaption.setText((resv + occ) + " of " + total + " slots have bookings");
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
                if (c != 0) return c;
                c = a.startMin - b.startMin;
                return c != 0 ? c : a.slot.compareTo(b.slot);
            }
        });

        if (!unpaid.isEmpty()) {
            myList.add(sectionLabel("UNPAID OVERTIME FEES"));
            for (DataStore.Fine f : unpaid) { myList.add(buildFineRow(f)); myList.add(Box.createVerticalStrut(8)); }
            if (!list.isEmpty()) { myList.add(Box.createVerticalStrut(4)); myList.add(sectionLabel("BOOKINGS")); }
        }
        if (list.isEmpty() && unpaid.isEmpty()) {
            JLabel empty = Park.lbl(Park.lines("No bookings yet.", "Choose a slot to get started."),
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
        boolean parked = info.status == DataStore.PARKED;
        boolean pending = info.status == DataStore.PENDING;
        int height = parked ? 96 : 80;
        Park.Card row = new Park.Card(new BorderLayout(8, 0), 14);
        row.setFill(new Color(14, 14, 32)); row.setBorder(Park.pad(8, 12, 8, 10));
        row.setPreferredSize(new Dimension(10, height)); row.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        Color c = parked ? Park.SLOT_OCCUPIED : pending ? Park.WARNING : Park.SLOT_RESERVED;
        String word = parked ? "Parked" : pending ? "Awaiting approval" : "Reserved";

        JPanel text = new JPanel(); text.setOpaque(false); text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(Park.lbl("Slot " + info.slot + "  \u00B7  " + word, Park.bold(13), c));
        text.add(Park.lbl(Fmt.dayMonth(info.date) + "  \u00B7  " + Fmt.timeShort(info.startMin, info.endMin()),
            Park.plain(11), Park.TEXT_MUTED));
        text.add(Park.lbl(Fmt.formatPlate(info.plate) + "  \u00B7  " + info.hours + "h  \u00B7  " + Park.money(info.price),
            Park.plain(11), Park.TEXT_MUTED));
        if (parked) {
            JLabel timeLbl = Park.lbl(" ", Park.bold(12), Park.SUCCESS);
            text.add(Box.createVerticalStrut(2));
            text.add(timeLbl);
            live.add(new LiveRow(info, timeLbl));
        }

        String label; Color fill; Color fg;
        if (parked)       { label = "Leave";    fill = Park.SLOT_OCCUPIED; fg = Color.WHITE; }
        else if (pending) { label = "Cancel";   fill = new Color(30, 30, 62); fg = Park.TEXT_PRIMARY; }
        else if (info.canCheckIn(System.currentTimeMillis()))
                          { label = "Park Now"; fill = Park.ACCENT; fg = Color.WHITE; }
        else              { label = "Manage";   fill = new Color(30, 30, 62); fg = Park.TEXT_PRIMARY; }
        Park.RoundButton btn = new Park.RoundButton(label, fill, fg);
        if (fill.equals(new Color(30, 30, 62))) btn.setOutline(Park.BORDER);
        btn.setFont(Park.bold(11)); btn.setRadius(10); btn.setPreferredSize(new Dimension(70, 30));
        btn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { manageBooking(info.id); }
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
        for (DataStore.ReservationInfo r : DataStore.getReservationsForUser(username)) {
            if (r.status != DataStore.PARKED || r.remainingMillis(now) > 0) continue;
            if (notified.add(r.id)) {
                notice("Parking Time Is Up",
                    "Your paid parking time for slot " + r.slot + " has ended.\n"
                    + "After a " + DataStore.GRACE_MINUTES + "-minute grace period, every extra hour is billed at "
                    + Park.money(Park.RATE) + ".\nPlease leave the slot from My Parking when you drive out.",
                    JOptionPane.WARNING_MESSAGE);
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
    private List<DataStore.ReservationInfo> myBookings(String date, String slot) {
        List<DataStore.ReservationInfo> mine = new ArrayList<DataStore.ReservationInfo>();
        for (DataStore.ReservationInfo r : DataStore.getBookings(date, slot))
            if (r.user.equals(username)) mine.add(r);
        return mine;
    }

    private void onSlotClick(String date, String slot) {
        List<DataStore.ReservationInfo> mine = myBookings(date, slot);
        if (!mine.isEmpty()) {
            StringBuilder sb = new StringBuilder("You already have a booking on slot " + slot + ":\n");
            for (DataStore.ReservationInfo r : mine) sb.append("  ").append(Fmt.timeRange(r.startMin, r.endMin())).append('\n');
            Object[] opts = {"Manage My Booking", "Book Another Time", "Close"};
            int ch = JOptionPane.showOptionDialog(this, sb.toString(), "Slot " + slot,
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
            if (ch == 0) {
                DataStore.ReservationInfo pick = mine.get(0);
                if (mine.size() > 1) {
                    String[] labels = new String[mine.size()];
                    for (int i = 0; i < labels.length; i++)
                        labels[i] = Fmt.timeRange(mine.get(i).startMin, mine.get(i).endMin());
                    Object sel = JOptionPane.showInputDialog(this, "Which booking?", "Slot " + slot,
                        JOptionPane.QUESTION_MESSAGE, null, labels, labels[0]);
                    if (sel == null) return;
                    for (int i = 0; i < labels.length; i++) if (labels[i].equals(sel)) pick = mine.get(i);
                }
                manageBooking(pick.id);
                return;
            }
            if (ch != 1) return;
        }
        if (!ensurePlate()) return;
        openBooking(date, slot);
    }

    private void openBooking(String date, String slot) {
        boolean today = date.equals(Park.today());
        int nowMin = Fmt.minuteOfDay(System.currentTimeMillis());
        boolean canStart = !DataStore.availableStarts(date, slot, Fmt.startFloor(date)).isEmpty();
        boolean canParkNow = today && !DataStore.hasParkedCar(slot)
                && DataStore.maxHours(date, slot, nowMin, true, null) >= 1;
        if (!canStart && !canParkNow) {
            StringBuilder sb = new StringBuilder("Slot " + slot + " has no free time left on " + date + ".\nBooked:\n");
            for (DataStore.ReservationInfo r : DataStore.getBookings(date, slot))
                sb.append("  ").append(Fmt.timeRange(r.startMin, r.endMin())).append('\n');
            JOptionPane.showMessageDialog(this, sb.toString(), "Slot Fully Booked", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new BookingDialog(this, username, date, slot, plate).setVisible(true);
        refreshAll();
    }

    /** Leave a parked slot, check in to a reservation, or cancel a reservation. */
    private void manageBooking(String id) {
        DataStore.ReservationInfo info = DataStore.getById(id);
        if (info == null || !info.user.equals(username)) { refreshAll(); return; }
        String slot = info.slot, date = info.date;
        String when = date + ", " + Fmt.timeRange(info.startMin, info.endMin());

        if (info.status == DataStore.PARKED) {
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
                    seen.remove(id);
                    DataStore.Fine f = DataStore.checkout(id, "User", true);
                    double paid = f == null ? fee : f.fee;
                    JOptionPane.showMessageDialog(this,
                        "Overtime of " + Park.money(paid) + " has been paid. Slot " + slot
                        + " is now free.\nThank you for using ParkWise!");
                }
            } else {
                int ch = JOptionPane.showConfirmDialog(this,
                    "Leave slot " + slot + "?\nThe slot will become available for other drivers.",
                    "Leave Parking", JOptionPane.YES_NO_OPTION);
                if (ch == JOptionPane.YES_OPTION) { seen.remove(id); DataStore.checkout(id, "User", true); }
            }
        } else if (info.status == DataStore.PENDING) {
            Object[] opts = {"Cancel Request", "Keep Waiting"};
            int ch = JOptionPane.showOptionDialog(this,
                "Your counter payment for slot " + slot + " (" + Fmt.time(info.startMin) + ", " + info.hours
                + "h) is waiting for the admin to approve it.\nPay " + Park.money(info.price)
                + " at the counter. This screen updates by itself once it is approved.\n\nCancel the request instead?",
                "Waiting for Approval", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[1]);
            if (ch == 0) { seen.remove(id); DataStore.clearBooking(id); }
        } else {
            long now = System.currentTimeMillis();
            boolean can = info.canCheckIn(now);
            String[] opts = can ? new String[]{"Park Now", "Cancel Reservation", "Close"}
                                : new String[]{"Cancel Reservation", "Close"};
            int ch = JOptionPane.showOptionDialog(this,
                "Slot " + slot + " is reserved for you:\n" + when + "\n"
                + (can ? "" : "\nPark Now opens " + DataStore.CHECKIN_EARLY_MIN + " minutes before your start time."),
                "Manage Reservation", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, opts, opts[0]);
            String pick = ch < 0 ? "Close" : opts[ch];
            if (pick.equals("Park Now")) {
                String err = DataStore.startParking(id);      // the parking timer starts here
                if (err != null) JOptionPane.showMessageDialog(this, err, "Cannot Park Yet", JOptionPane.WARNING_MESSAGE);
            } else if (pick.equals("Cancel Reservation")) {
                int ok = JOptionPane.showConfirmDialog(this,
                    "Cancel reservation for slot " + slot + " (" + when + ")?",
                    "Cancel Reservation", JOptionPane.YES_NO_OPTION);
                if (ok == JOptionPane.YES_OPTION) { seen.remove(id); DataStore.clearBooking(id); }
            }
        }
        refreshAll();
    }

    /** Slot tile. Same look as Park.SlotButton, plus a small "x2" when a slot has several bookings that day. */
    private static class TimeSlotButton extends JButton {
        private static final long serialVersionUID = 1L;
        private final String slot;
        private final int status, count;
        private final boolean mine;
        private boolean hovering = false;

        TimeSlotButton(String slot, int status, boolean mine, int count) {
            this.slot = slot; this.status = status; this.mine = mine; this.count = count;
            setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hovering = true; repaint(); }
                public void mouseExited(MouseEvent e)  { hovering = false; repaint(); }
            });
        }

        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Park.aa(g);
            int w = getWidth(), h = getHeight();
            Color c = status == 1 ? Park.SLOT_RESERVED : status == 2 ? Park.SLOT_OCCUPIED : Park.SLOT_AVAILABLE;
            String st = status == 1 ? "Booked" : status == 2 ? "Parked" : "Available";

            g2.setColor(hovering ? new Color(38, 38, 76) : new Color(24, 24, 50));
            g2.fillRoundRect(0, 0, w - 1, h - 1, 16, 16);
            g2.setColor(Park.alpha(c, hovering ? 55 : 30));
            g2.fillRoundRect(0, 0, w - 1, h - 1, 16, 16);
            g2.setColor(Park.alpha(c, mine ? 255 : 150));
            g2.setStroke(new BasicStroke(mine ? 2.2f : 1.2f));
            g2.drawRoundRect(1, 1, w - 3, h - 3, 16, 16);

            g2.setColor(c); g2.fillOval(12, 12, 8, 8);
            if (count > 1) {
                g2.setFont(Park.bold(10));
                g2.setColor(Park.TEXT_MUTED);
                g2.drawString("x" + count, 25, 20);
            }

            g2.setFont(Park.bold(22)); FontMetrics fm = g2.getFontMetrics();
            g2.setColor(Park.TEXT_PRIMARY);
            g2.drawString(slot, (w - fm.stringWidth(slot)) / 2, h / 2 + 4);

            g2.setFont(Park.plain(11)); fm = g2.getFontMetrics();
            g2.setColor(c);
            g2.drawString(st, (w - fm.stringWidth(st)) / 2, h / 2 + 24);

            if (mine) {
                g2.setFont(Park.bold(9)); fm = g2.getFontMetrics();
                int tw = fm.stringWidth("YOU") + 12;
                g2.setColor(c); g2.fillRoundRect(w - tw - 8, 8, tw, 16, 8, 8);
                g2.setColor(Park.BG_DARK); g2.drawString("YOU", w - tw - 2, 20);
            }
            g2.dispose();
        }
    }
}