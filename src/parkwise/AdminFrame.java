package parkwise;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/* =====================================================================
   AdminFrame : moderation screen. Approves counter payments, handles walk-ins, moves cars
   between slots, frees slots users forgot to leave, and tracks overtime fees.
   Everything refreshes by itself when a driver (or another admin window) changes something.
   ===================================================================== */
class AdminFrame extends JFrame {
    private static final long serialVersionUID = 1L;

    private JLabel parkedLbl, resvLbl, pendLbl, overLbl, unpaidLbl, bookCountLbl, fineCountLbl;
    private JTable bookTable, fineTable;
    private final BookingsModel bookModel = new BookingsModel();
    private final FinesModel fineModel = new FinesModel();
    private CardLayout views;
    private JPanel viewPanel;
    private Park.RoundButton tabBook, tabFine;
    private javax.swing.Timer timer;

    private int seenVersion = -1;
    private Set<String> knownPending = new HashSet<String>();
    private boolean pendInit = false;
    private String flashMsg = null;
    private long flashUntil = 0L;

    AdminFrame(String adminName) {
        setTitle("ParkWise - Admin Moderation");
        Park.applyIcon(this);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1320, scr.width - 20), Math.min(780, scr.height - 60));
        setMinimumSize(new Dimension(1100, 620));
        setLocationRelativeTo(null);
        buildUI(adminName);
        seenVersion = DataStore.version();
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
        titles.add(Park.lbl("Signed in as " + adminName + ". Approve counter payments, park walk-ins, move cars and free forgotten slots.",
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
        parkedLbl = new JLabel(); resvLbl = new JLabel(); pendLbl = new JLabel(); overLbl = new JLabel(); unpaidLbl = new JLabel();
        JPanel stats = new JPanel(new GridLayout(1, 5, 14, 0)); stats.setOpaque(false);
        stats.setPreferredSize(new Dimension(100, 84));
        stats.add(statCard("Parked now", Park.SLOT_OCCUPIED, parkedLbl));
        stats.add(statCard("Reserved", Park.SLOT_RESERVED, resvLbl));
        stats.add(statCard("Pending approval", Park.ACCENT_2, pendLbl));
        stats.add(statCard("Over paid time", Park.WARNING, overLbl));
        stats.add(statCard("Unpaid fees", Park.DANGER, unpaidLbl));

        // tabs
        tabBook = tabButton("Active Bookings");
        tabFine = tabButton("Overtime Fees");
        tabBook.addActionListener(Fmt.act(new Runnable() { public void run() { showView("book"); } }));
        tabFine.addActionListener(Fmt.act(new Runnable() { public void run() { refreshFines(); showView("fine"); } }));
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
        col.add(Park.lbl("\u2022  " + title, Park.plain(12), c));
        col.add(Box.createVerticalStrut(2)); col.add(value);
        card.add(col, BorderLayout.CENTER);
        return card;
    }

    private Park.RoundButton actionBtn(String text, Color fill, Color fg, int w, String tip, final Runnable r) {
        Park.RoundButton b = new Park.RoundButton(text, fill, fg);
        b.setFont(Park.bold(13)); b.setRadius(12); b.setPreferredSize(new Dimension(w, 40));
        if (fill.equals(new Color(30, 30, 62))) b.setOutline(Park.BORDER);
        b.setToolTipText(tip);
        b.addActionListener(Fmt.act(r));
        return b;
    }

    private JPanel buildBookView() {
        JPanel p = new JPanel(new BorderLayout(0, 12)); p.setOpaque(false);
        bookTable = new JTable(bookModel);
        styleTable(bookTable, new int[]{120, 100, 80, 90, 45, 150, 115, 105, 130, 80, 120});
        p.add(scroll(bookTable), BorderLayout.CENTER);

        bookCountLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        Color plain = new Color(30, 30, 62);
        Park.RoundButton approve = actionBtn("Approve Payment", Park.SUCCESS, Park.BG_DARK, 160,
            "Confirm that the counter payment was received. The driver can then park.", new Runnable() {
                public void run() { approvePayments(); } });
        Park.RoundButton walkin = actionBtn("Add Walk-in", Park.ACCENT, Color.WHITE, 130,
            "Park a driver who does not have the app. Shown as (walk-in).", new Runnable() {
                public void run() { addWalkIn(); } });
        Park.RoundButton move = actionBtn("Move Slot", plain, Park.TEXT_PRIMARY, 120,
            "Fix the system when a car parked in a different slot than the one booked.", new Runnable() {
                public void run() { moveSlot(); } });
        Park.RoundButton remove = actionBtn("Remove / Reject", plain, Park.TEXT_PRIMARY, 160,
            "Delete reservations that were never used, or reject counter payment requests. Select several with Ctrl/Shift.", new Runnable() {
                public void run() { removeReservations(); } });
        Park.RoundButton force = actionBtn("Force Check-out", Park.SLOT_OCCUPIED, Color.WHITE, 160,
            "Free parked slots (for example when drivers forgot to press Leave) and bill any overtime. Select several with Ctrl/Shift.", new Runnable() {
                public void run() { forceCheckout(); } });
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); btns.setOpaque(false);
        btns.add(approve); btns.add(walkin); btns.add(move); btns.add(remove); btns.add(force);
        JPanel south = new JPanel(new BorderLayout(0, 10)); south.setOpaque(false);
        south.add(bookCountLbl, BorderLayout.NORTH); south.add(btns, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildFineView() {
        JPanel p = new JPanel(new BorderLayout(0, 12)); p.setOpaque(false);
        fineTable = new JTable(fineModel);
        styleTable(fineTable, new int[]{130, 105, 85, 95, 50, 80, 80, 85, 85, 90});
        p.add(scroll(fineTable), BorderLayout.CENTER);

        fineCountLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        Park.RoundButton paid = actionBtn("Mark as Paid", Park.SUCCESS, Park.BG_DARK, 170, null, new Runnable() {
            public void run() { markFinePaid(); } });
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
        t.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);   // Ctrl/Shift-click to pick several
        t.setDefaultRenderer(Object.class, new CellRenderer());
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setPreferredSize(new Dimension(100, 38));
        t.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;
            public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, false, false, r, c);
                l.setOpaque(true); l.setBackground(new Color(30, 30, 62));
                l.setForeground(Park.TEXT_MUTED); l.setFont(Park.bold(12));
                l.setBorder(Park.pad(0, 8, 0, 8)); l.setHorizontalAlignment(SwingConstants.LEFT);
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
            setFont(Park.plain(12));
            setBorder(Park.pad(0, 8, 0, 8));
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

    private static String contactOf(String id) {
        String ph = DataStore.getPhone(id);
        return ph.isEmpty() ? "-" : ph;
    }

    private static String nameOf(String id) { return DataStore.getDisplayName(id); }

    private class BookingsModel extends StyledModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Driver", "Contact", "Plate", "Date", "Slot", "Time", "Status", "Paid",
                                       "Time Left / Overtime", "Overtime Fee", "Flag"};
        List<DataStore.ReservationInfo> rows = new ArrayList<DataStore.ReservationInfo>();

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }

        boolean sameKeys(List<DataStore.ReservationInfo> other) {
            if (other.size() != rows.size()) return false;
            for (int i = 0; i < rows.size(); i++)
                if (!rows.get(i).id.equals(other.get(i).id)) return false;
            return true;
        }

        private String flag(DataStore.ReservationInfo i, long now) {
            if (i.status == DataStore.PENDING) return i.endMillis < now ? "Stale request" : "Needs approval";
            if (i.status == DataStore.PARKED) {
                if (i.overStayMillis(now) >= DataStore.FORGOT_HOURS * DataStore.HOUR_MS) return "Possibly forgotten";
                if (i.remainingMillis(now) < 0) return "Overtime";
                return "OK";
            }
            return i.endMillis < now ? "Stale reservation" : "OK";
        }

        public Object getValueAt(int r, int c) {
            DataStore.ReservationInfo i = rows.get(r);
            long now = System.currentTimeMillis();
            boolean parked = i.status == DataStore.PARKED;
            switch (c) {
                case 0: return nameOf(i.user);
                case 1: return contactOf(i.user);
                case 2: return Fmt.formatPlate(i.plate);
                case 3: return Park.shortDate(i.date);
                case 4: return i.slot;
                case 5: return Fmt.timeRange(i.startMin, i.endMin());
                case 6: return parked ? "Parked" : i.status == DataStore.PENDING ? "Pending approval" : "Reserved";
                case 7: return i.hours + "h  (" + Park.money(i.price) + ")";
                case 8:
                    if (i.status == DataStore.PENDING) return "Awaiting approval";
                    if (!parked) return Park.isPast(i.date) ? "Date has passed" : "Not arrived yet";
                    long rem = i.remainingMillis(now);
                    return rem >= 0 ? Park.hms(rem) + " left" : "+" + Park.hms(-rem) + " over";
                case 9:
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
            if (c == 0) return DataStore.isWalkIn(i.user) ? Park.ACCENT_2 : Park.TEXT_PRIMARY;
            if (c == 6) return i.status == DataStore.PARKED ? Park.SLOT_OCCUPIED
                             : i.status == DataStore.PENDING ? Park.WARNING : Park.SLOT_RESERVED;
            if (c == 8 && i.status == DataStore.PARKED) {
                long rem = i.remainingMillis(now);
                if (rem < 0) return Park.DANGER;
                if (rem <= 5 * 60000L) return Park.WARNING;
                return Park.SUCCESS;
            }
            if (c == 9 && i.status == DataStore.PARKED && i.overtimeFee(now) > 0) return Park.DANGER;
            if (c == 10) {
                String f = flag(i, now);
                if ("OK".equals(f)) return Park.SUCCESS;
                return (f.equals("Overtime") || f.equals("Needs approval")) ? Park.WARNING : Park.DANGER;
            }
            return Park.TEXT_PRIMARY;
        }
    }

    private class FinesModel extends StyledModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Driver", "Contact", "Plate", "Date", "Slot", "Paid Time", "Overtime", "Fee", "Ended By", "Status"};
        List<DataStore.Fine> rows = new ArrayList<DataStore.Fine>();

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }

        public Object getValueAt(int r, int c) {
            DataStore.Fine f = rows.get(r);
            switch (c) {
                case 0: return nameOf(f.user);
                case 1: return contactOf(f.user);
                case 2: return Fmt.formatPlate(f.plate);
                case 3: return Park.shortDate(f.date);
                case 4: return f.slot;
                case 5: return f.paidHours + "h";
                case 6: return f.overtimeHours + "h";
                case 7: return Park.money(f.fee);
                case 8: return f.endedBy;
                default: return f.paid ? "Paid" : "UNPAID";
            }
        }

        boolean hot(int r) { return r < rows.size() && !rows.get(r).paid; }

        Color fg(int r, int c) {
            if (r >= rows.size()) return Park.TEXT_PRIMARY;
            if (c == 9) return rows.get(r).paid ? Park.SUCCESS : Park.DANGER;
            if (c == 7 && !rows.get(r).paid) return Park.DANGER;
            if (c == 0 && DataStore.isWalkIn(rows.get(r).user)) return Park.ACCENT_2;
            return Park.TEXT_PRIMARY;
        }
    }

    // ---------- refreshing (runs every second) ----------
    private void flash(String msg) {
        flashMsg = msg;
        flashUntil = System.currentTimeMillis() + 20000L;
        Toolkit.getDefaultToolkit().beep();
    }

    private void tick() {
        int v = DataStore.version();                 // also picks up changes saved by another running copy
        if (v != seenVersion) { seenVersion = v; refreshFines(); }

        long now = System.currentTimeMillis();
        final long fnow = now;
        List<DataStore.ReservationInfo> fresh = DataStore.getAllReservations();
        Collections.sort(fresh, new Comparator<DataStore.ReservationInfo>() {
            public int compare(DataStore.ReservationInfo a, DataStore.ReservationInfo b) {
                int pa = a.status == DataStore.PENDING ? 0 : 1, pb = b.status == DataStore.PENDING ? 0 : 1;
                if (pa != pb) return pa - pb;
                int c = Long.valueOf(b.overStayMillis(fnow)).compareTo(Long.valueOf(a.overStayMillis(fnow)));
                if (c != 0) return c;
                c = Park.parseDate(a.date).compareTo(Park.parseDate(b.date));
                if (c != 0) return c;
                c = a.startMin - b.startMin;
                return c != 0 ? c : a.slot.compareTo(b.slot);
            }
        });

        int parked = 0, resv = 0, pend = 0, over = 0;
        Set<String> pendNow = new HashSet<String>();
        for (DataStore.ReservationInfo r : fresh) {
            if (r.status == DataStore.PARKED) { parked++; if (r.remainingMillis(now) < 0) over++; }
            else if (r.status == DataStore.PENDING) {
                pend++; pendNow.add(r.id);
                if (pendInit && !knownPending.contains(r.id))
                    flash("New counter payment request: " + nameOf(r.user) + "  \u00B7  " + Fmt.formatPlate(r.plate)
                        + "  \u00B7  slot " + r.slot + "  \u00B7  " + Park.money(r.price));
            }
            else resv++;
        }
        knownPending = pendNow; pendInit = true;

        parkedLbl.setText(String.valueOf(parked));
        resvLbl.setText(String.valueOf(resv));
        pendLbl.setText(String.valueOf(pend));
        overLbl.setText(String.valueOf(over));
        unpaidLbl.setText(Park.money(DataStore.getUnpaidTotal()));
        if (flashMsg != null && now < flashUntil) {
            bookCountLbl.setForeground(Park.WARNING);
            bookCountLbl.setText(flashMsg);
        } else {
            bookCountLbl.setForeground(Park.TEXT_MUTED);
            bookCountLbl.setText(fresh.size() + " active booking" + (fresh.size() == 1 ? "" : "s")
                + ".  Rows in red need attention.  Ctrl/Shift-click to select several rows.");
        }

        Set<String> sel = selectedKeys();
        boolean same = bookModel.sameKeys(fresh);
        bookModel.rows = fresh;
        if (same) {
            if (!fresh.isEmpty()) bookModel.fireTableRowsUpdated(0, fresh.size() - 1);
        } else {
            bookModel.fireTableDataChanged();
            for (int i = 0; i < fresh.size(); i++)
                if (sel.contains(fresh.get(i).id)) bookTable.addRowSelectionInterval(i, i);
        }
    }

    private void refreshFines() {
        List<DataStore.Fine> all = DataStore.getAllFines();
        Collections.reverse(all);   // newest first
        Set<String> sel = new HashSet<String>();
        for (int r : fineTable.getSelectedRows())
            if (r < fineModel.rows.size()) sel.add(fineModel.rows.get(r).key());
        fineModel.rows = all;
        fineModel.fireTableDataChanged();
        for (int i = 0; i < all.size(); i++)
            if (sel.contains(all.get(i).key())) fineTable.addRowSelectionInterval(i, i);
        int unpaid = 0;
        for (DataStore.Fine f : all) if (!f.paid) unpaid++;
        fineCountLbl.setText(all.size() + " record" + (all.size() == 1 ? "" : "s") + ",  " + unpaid + " unpaid.");
        unpaidLbl.setText(Park.money(DataStore.getUnpaidTotal()));
    }

    private Set<String> selectedKeys() {
        Set<String> s = new HashSet<String>();
        for (int r : bookTable.getSelectedRows())
            if (r >= 0 && r < bookModel.rows.size()) s.add(bookModel.rows.get(r).id);
        return s;
    }

    private List<DataStore.ReservationInfo> selectedBookings() {
        List<DataStore.ReservationInfo> out = new ArrayList<DataStore.ReservationInfo>();
        for (int r : bookTable.getSelectedRows())
            if (r >= 0 && r < bookModel.rows.size()) out.add(bookModel.rows.get(r));
        return out;
    }

    // ---------- dialog helpers ----------
    private void info(String msg) { JOptionPane.showMessageDialog(this, msg, "Admin", JOptionPane.INFORMATION_MESSAGE); }

    private String describe(DataStore.ReservationInfo r) {
        return nameOf(r.user) + "  |  " + Fmt.formatPlate(r.plate) + "  |  Slot " + r.slot;
    }

    /** A message with a short heading, a scrolling list (one line per booking) and a footer. */
    private JComponent listMessage(String head, List<String> lines, String foot) {
        JPanel p = new JPanel(new BorderLayout(0, 8)); p.setOpaque(false);
        p.add(Park.lbl(Park.lines(head.split("\n")), Park.plain(13), Park.TEXT_PRIMARY), BorderLayout.NORTH);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        JTextArea ta = new JTextArea(sb.toString());
        ta.setEditable(false); ta.setFont(Park.mono(12));
        ta.setBackground(new Color(12, 12, 28)); ta.setForeground(Park.TEXT_PRIMARY);
        ta.setBorder(Park.pad(6, 8, 6, 8));
        JScrollPane sp = new JScrollPane(ta);
        sp.setBorder(BorderFactory.createLineBorder(Park.BORDER, 1));
        sp.setPreferredSize(new Dimension(640, Math.min(230, 28 + lines.size() * 18)));
        p.add(sp, BorderLayout.CENTER);
        if (foot != null && !foot.isEmpty())
            p.add(Park.lbl(Park.lines(foot.split("\n")), Park.plain(12), Park.TEXT_MUTED), BorderLayout.SOUTH);
        return p;
    }

    // ---------- admin actions ----------
    private void approvePayments() {
        List<DataStore.ReservationInfo> pend = new ArrayList<DataStore.ReservationInfo>();
        for (DataStore.ReservationInfo r : selectedBookings())
            if (r.status == DataStore.PENDING) pend.add(r);
        if (pend.isEmpty()) {
            info("Select one or more bookings marked \"Pending approval\" first.");
            return;
        }
        double total = 0;
        List<String> lines = new ArrayList<String>();
        for (DataStore.ReservationInfo r : pend) {
            total += r.price;
            lines.add(describe(r) + "  |  " + r.hours + "h  |  " + Park.money(r.price));
        }
        int ok = JOptionPane.showConfirmDialog(this,
            listMessage("Approve the counter payment of " + pend.size() + " booking" + (pend.size() == 1 ? "" : "s") + "?",
                lines, "Total received: " + Park.money(total) + "\nOnly approve once the payment has been received."),
            "Approve Payment", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        StringBuilder errs = new StringBuilder();
        for (DataStore.ReservationInfo r : pend) {
            String err = DataStore.approve(r.id);
            if (err != null) errs.append("Slot ").append(r.slot).append(": ").append(err).append('\n');
        }
        tick();
        if (errs.length() > 0) info(errs.toString());
    }

    private void addWalkIn() {
        String today = Park.today();
        int nowMin = Fmt.minuteOfDay(System.currentTimeMillis());
        List<String> free = new ArrayList<String>();
        for (String s : Park.SLOTS)
            if (!DataStore.hasParkedCar(s) && DataStore.maxHours(today, s, nowMin, true, null) >= 1) free.add(s);
        if (free.isEmpty()) { info("No slot is free right now."); return; }

        JComboBox<String> slotBox = new JComboBox<String>(free.toArray(new String[0]));
        Fmt.styleCombo(slotBox);
        JTextField plateF = Park.field(10);
        plateF.setPreferredSize(new Dimension(200, 38));
        JSpinner hrs = new JSpinner(new SpinnerNumberModel(1, 1, 24, 1));
        hrs.setFont(Park.plain(14));
        JTextField hrsText = ((JSpinner.DefaultEditor) hrs.getEditor()).getTextField();
        hrsText.setBackground(new Color(12, 12, 28)); hrsText.setForeground(Park.TEXT_PRIMARY);
        hrsText.setCaretColor(Park.ACCENT);

        JPanel grid = new JPanel(new GridLayout(0, 2, 12, 10)); grid.setOpaque(false);
        grid.add(Park.lbl("Parking slot", Park.plain(13), Park.TEXT_PRIMARY)); grid.add(slotBox);
        grid.add(Park.lbl("Plate number", Park.plain(13), Park.TEXT_PRIMARY)); grid.add(plateF);
        grid.add(Park.lbl("Paid hours", Park.plain(13), Park.TEXT_PRIMARY));   grid.add(hrs);
        JLabel errLbl = Park.lbl(" ", Park.plain(12), Park.DANGER);
        JPanel wrap = new JPanel(new BorderLayout(0, 10)); wrap.setOpaque(false);
        wrap.add(Park.lbl(Park.lines("<b>Walk-in driver (no app)</b>",
                "Paid at the counter (" + Park.money(Park.RATE) + "/hr). The timer starts now.",
                "Shown as " + DataStore.WALK_IN + " in the tables."), Park.plain(12), Park.TEXT_PRIMARY), BorderLayout.NORTH);
        wrap.add(grid, BorderLayout.CENTER);
        wrap.add(errLbl, BorderLayout.SOUTH);

        while (true) {
            int ok = JOptionPane.showConfirmDialog(this, wrap, "Add Walk-in",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return;
            String pl = Fmt.normalizePlate(plateF.getText());
            if (!Fmt.validPlate(pl)) { errLbl.setText("Enter a valid 7-character plate number (e.g. ABC 1234)."); continue; }
            int h = ((Number) hrs.getValue()).intValue();
            String slot = (String) slotBox.getSelectedItem();
            String err = DataStore.addWalkIn(slot, pl, h);
            if (err != null) { errLbl.setText("<html>" + err.replace("\n", "<br>") + "</html>"); continue; }
            tick();
            info("Walk-in parked in slot " + slot + ".\nPlate " + Fmt.formatPlate(pl) + ", " + h + "h, "
                + Park.money(h * Park.RATE) + " (counter).");
            return;
        }
    }

    private void moveSlot() {
        List<DataStore.ReservationInfo> sel = selectedBookings();
        if (sel.size() != 1) { info("Select exactly one booking to move."); return; }
        DataStore.ReservationInfo r = sel.get(0);
        List<String> free = DataStore.freeSlotsFor(r);
        if (free.isEmpty()) {
            info("No other slot is free during " + Fmt.timeRange(r.startMin, r.endMin()) + ".");
            return;
        }
        JComboBox<String> slotBox = new JComboBox<String>(free.toArray(new String[0]));
        Fmt.styleCombo(slotBox);
        JPanel wrap = new JPanel(new BorderLayout(0, 12)); wrap.setOpaque(false);
        wrap.add(Park.lbl(Park.lines("<b>Move this booking to a different slot in the system.</b>",
                "Use it when the car is physically in another slot than the one booked.",
                "",
                "Driver:   " + nameOf(r.user) + "  (" + Fmt.formatPlate(r.plate) + ")",
                "Now in:   slot " + r.slot + "  \u00B7  " + Fmt.timeRange(r.startMin, r.endMin()),
                "The driver's screen updates automatically."), Park.plain(12), Park.TEXT_PRIMARY), BorderLayout.NORTH);
        JPanel row = new JPanel(new GridLayout(1, 2, 12, 0)); row.setOpaque(false);
        row.add(Park.lbl("New slot (only free ones are listed)", Park.plain(13), Park.TEXT_PRIMARY)); row.add(slotBox);
        wrap.add(row, BorderLayout.CENTER);
        int ok = JOptionPane.showConfirmDialog(this, wrap, "Move to Another Slot",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        String err = DataStore.moveBooking(r.id, (String) slotBox.getSelectedItem());
        if (err != null) info(err);
        tick();
    }

    private void removeReservations() {
        List<DataStore.ReservationInfo> all = selectedBookings();
        if (all.isEmpty()) { info("Select one or more reservations in the table first."); return; }
        List<DataStore.ReservationInfo> rem = new ArrayList<DataStore.ReservationInfo>();
        for (DataStore.ReservationInfo r : all) if (r.status != DataStore.PARKED) rem.add(r);
        if (rem.isEmpty()) {
            info("Those cars are parked. Use Force Check-out so any overtime gets billed.");
            return;
        }
        int skipped = all.size() - rem.size();
        List<String> lines = new ArrayList<String>();
        for (DataStore.ReservationInfo r : rem)
            lines.add(describe(r) + "  |  " + Fmt.timeRange(r.startMin, r.endMin())
                + "  |  " + (r.status == DataStore.PENDING ? "PENDING" : "RESERVED"));
        String head = "Remove " + rem.size() + " reservation" + (rem.size() == 1 ? "" : "s") + "?"
            + (skipped > 0 ? "\n(" + skipped + " parked booking" + (skipped == 1 ? " is" : "s are") + " skipped. Use Force Check-out for those.)" : "");
        int ok = JOptionPane.showConfirmDialog(this,
            listMessage(head, lines, "The slots become available again. Pending counter requests are rejected\nand the drivers are notified."),
            "Remove / Reject", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        for (DataStore.ReservationInfo r : rem) DataStore.clearBooking(r.id);
        tick();
    }

    private void forceCheckout() {
        List<DataStore.ReservationInfo> all = selectedBookings();
        if (all.isEmpty()) { info("Select one or more parked bookings in the table first."); return; }
        List<DataStore.ReservationInfo> parked = new ArrayList<DataStore.ReservationInfo>();
        for (DataStore.ReservationInfo r : all) if (r.status == DataStore.PARKED) parked.add(r);
        if (parked.isEmpty()) {
            info("None of the selected bookings is parked yet.\nUse Remove / Reject for reservations and pending requests.");
            return;
        }
        int skipped = all.size() - parked.size();
        long now = System.currentTimeMillis();
        double totalFee = 0;
        List<String> lines = new ArrayList<String>();
        for (DataStore.ReservationInfo r : parked) {
            double fee = r.overtimeFee(now);
            totalFee += fee;
            lines.add(describe(r) + "  |  parked " + Park.hms(now - r.startMillis) + "  |  "
                + (fee > 0 ? "overtime " + Park.money(fee) : "no overtime"));
        }
        String head = "Force check-out " + parked.size() + " parked car" + (parked.size() == 1 ? "" : "s") + "?"
            + (skipped > 0 ? "\n(" + skipped + " selected booking" + (skipped == 1 ? " is" : "s are") + " not parked and skipped.)" : "");

        boolean paid = true;
        if (totalFee > 0) {
            String foot = "Total overtime: " + Park.money(totalFee) + "\n"
                + "Collected - Mark Paid: the fees were collected now.\n"
                + "Bill to Account: drivers see the fee as unpaid in their dashboard.\n"
                + "(" + DataStore.WALK_IN + " drivers have no account, so their fee stays UNPAID in the Overtime Fees tab.)";
            Object[] opts = {"Collected - Mark Paid", "Bill to Account", "Cancel"};
            int ch = JOptionPane.showOptionDialog(this, listMessage(head, lines, foot), "Force Check-out",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
            if (ch != 0 && ch != 1) return;
            paid = (ch == 0);
        } else {
            int ok = JOptionPane.showConfirmDialog(this,
                listMessage(head, lines, "No overtime charge applies."), "Force Check-out", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) return;
        }
        for (DataStore.ReservationInfo r : parked) DataStore.checkout(r.id, "Admin", paid);
        tick(); refreshFines();
    }

    private void markFinePaid() {
        List<DataStore.Fine> pick = new ArrayList<DataStore.Fine>();
        for (int r : fineTable.getSelectedRows())
            if (r >= 0 && r < fineModel.rows.size() && !fineModel.rows.get(r).paid) pick.add(fineModel.rows.get(r));
        if (pick.isEmpty()) { info("Select one or more unpaid fee records first."); return; }
        double total = 0;
        List<String> lines = new ArrayList<String>();
        for (DataStore.Fine f : pick) {
            total += f.fee;
            lines.add(nameOf(f.user) + "  |  " + Fmt.formatPlate(f.plate) + "  |  Slot " + f.slot + "  |  " + Park.money(f.fee));
        }
        int ok = JOptionPane.showConfirmDialog(this,
            listMessage("Mark " + pick.size() + " overtime fee" + (pick.size() == 1 ? "" : "s") + " as paid?",
                lines, "Total: " + Park.money(total)),
            "Mark as Paid", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        for (DataStore.Fine f : pick) DataStore.payFine(f);
        refreshFines();
    }
}