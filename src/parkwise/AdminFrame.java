package parkwise;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/* =====================================================================
   AdminFrame : moderation screen. Validates QR / counter payments (with the reference numbers drivers typed),
   approves overtime payments before a driver may leave, handles walk-ins, moves cars between slots,
   frees slots users forgot to leave, tracks fees and shows an Activity Log of reservations, online
   payments and Leave presses (with the leave penalty).
   Everything refreshes by itself when a driver (or another admin window) changes something.
   ===================================================================== */
class AdminFrame extends JFrame {
    private static final long serialVersionUID = 1L;

    private JLabel parkedLbl, resvLbl, pendLbl, overLbl, unpaidLbl, bookCountLbl, fineCountLbl, evtCountLbl, clockLbl, alertLbl;
    private JTable bookTable, fineTable, evtTable;
    private final BookingsModel bookModel = new BookingsModel();
    private final FinesModel fineModel = new FinesModel();
    private final EventsModel evtModel = new EventsModel();
    private CardLayout views;
    private JPanel viewPanel;
    private Park.RoundButton tabBook, tabFine, tabEvt, tabPri;
    private JTable priTable;
    private JLabel priCountLbl;
    private final PriorityModel priModel = new PriorityModel();
    private javax.swing.Timer timer;

    private int seenVersion = -1;
    private Set<String> knownEvents = new HashSet<String>();
    private final Map<String, JDialog> alertDialogs = new HashMap<String, JDialog>();
    private boolean evtInit = false;
    private String flashMsg = null;
    private long flashUntil = 0L;

    private String adminId;
    private Park.RoundButton tabAdm, tabRec;
    private JTable admTable, recTable;
    private final AdminsModel admModel = new AdminsModel();
    private final RecordsModel recModel = new RecordsModel();
    private JLabel admCountLbl, recCountLbl;
    private JTextField recSearch;
    private boolean recHistory = true;
    private Park.RoundButton recBtnHist, recBtnAudit;

    AdminFrame(String adminName) {
        this.adminId = adminName;
        DataStore.setActor(adminName);
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
        refreshPriority();
        refreshAdmins();
        refreshEvents();
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
        titles.add(Park.lbl("Admin Moderation", Park.bold(22), Park.TEXT_PRIMARY));
        titles.add(Box.createVerticalStrut(2));
        JLabel signed = Park.lbl("Signed in as " + DataStore.getDisplayName(adminName) + " (" + adminName + ")", Park.plain(13), Park.TEXT_MUTED);
        signed.setToolTipText("Validate payments, approve overtime, park walk-ins, move cars and watch the alerts");
        titles.add(signed);
        JPanel titleRow = new JPanel(new BorderLayout(14, 0)); titleRow.setOpaque(false);
        titleRow.add(new Park.LogoView(60), BorderLayout.WEST);
        titleRow.add(titles, BorderLayout.CENTER);
        Park.RoundButton logout = Park.secondaryBtn("Logout");
        logout.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                DataStore.audit("LOGOUT", "Administrator signed out");
                DataStore.setActor(null);
                dispose(); new LoginFrame().setVisible(true);
            }
        });
        Park.RoundButton settings = Park.secondaryBtn("Settings");
        settings.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                new SettingsDialog(AdminFrame.this, adminId, true,
                    new Runnable() { public void run() { dispose(); new AdminFrame(adminId).setVisible(true); } }, null).setVisible(true);
            }
        });
        clockLbl = Park.clockLabel();
        clockLbl.setToolTipText("Current date and time");
        Park.Card clockCard = new Park.Card(new BorderLayout(), 16);
        clockCard.setBorder(Park.pad(6, 12, 6, 12));
        clockCard.add(clockLbl, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0)); right.setOpaque(false);
        right.add(clockCard); right.add(settings); right.add(logout);
        JPanel logoutWrap = new JPanel(new GridBagLayout()); logoutWrap.setOpaque(false); logoutWrap.add(right);
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
        tabBook = tabButton("Bookings");
        tabFine = tabButton("Fees & Penalties");
        tabPri = tabButton("PWD/Senior IDs");
        tabAdm = tabButton("Admins");
        tabRec = tabButton("Records");
        tabEvt = tabButton("Alerts");
        tabBook.addActionListener(Fmt.act(new Runnable() { public void run() { showView("book"); } }));
        tabFine.addActionListener(Fmt.act(new Runnable() { public void run() { refreshFines(); showView("fine"); } }));
        tabPri.addActionListener(Fmt.act(new Runnable() { public void run() { refreshPriority(); showView("pri"); } }));
        tabAdm.addActionListener(Fmt.act(new Runnable() { public void run() { refreshAdmins(); showView("adm"); } }));
        tabRec.addActionListener(Fmt.act(new Runnable() { public void run() { refreshRecords(); showView("rec"); } }));
        tabEvt.addActionListener(Fmt.act(new Runnable() { public void run() { refreshEvents(); showView("evt"); } }));
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); tabs.setOpaque(false);
        tabs.add(tabBook); tabs.add(tabFine); tabs.add(tabPri); tabs.add(tabAdm); tabs.add(tabRec); tabs.add(tabEvt);
        alertLbl = Park.lbl(" ", Park.bold(12), Park.WARNING);
        JPanel tabRow = new JPanel(new BorderLayout(0, 6)); tabRow.setOpaque(false);
        tabRow.add(tabs, BorderLayout.NORTH); tabRow.add(alertLbl, BorderLayout.CENTER);

        JPanel statsTabs = new JPanel(new BorderLayout(0, 16)); statsTabs.setOpaque(false);
        statsTabs.add(stats, BorderLayout.NORTH); statsTabs.add(tabRow, BorderLayout.CENTER);
        JPanel north = new JPanel(new BorderLayout(0, 16)); north.setOpaque(false);
        north.add(header, BorderLayout.NORTH); north.add(statsTabs, BorderLayout.CENTER);
        content.add(north, BorderLayout.NORTH);

        // views
        views = new CardLayout();
        viewPanel = new JPanel(views); viewPanel.setOpaque(false);
        viewPanel.add(buildBookView(), "book");
        viewPanel.add(buildFineView(), "fine");
        viewPanel.add(buildPriorityView(), "pri");
        viewPanel.add(buildAdminsView(), "adm");
        viewPanel.add(buildRecordsView(), "rec");
        viewPanel.add(buildEventView(), "evt");
        Park.Card card = new Park.Card(new BorderLayout(), 22);
        card.setBorder(Park.pad(16, 16, 16, 16));
        card.add(viewPanel, BorderLayout.CENTER);
        content.add(card, BorderLayout.CENTER);

        root.add(content, BorderLayout.CENTER);
        setContentPane(root);
        showView("book");
    }

    private Park.RoundButton tabButton(String t) {
        Park.RoundButton b = new Park.RoundButton(t, Park.BTN_IDLE, Park.TEXT_MUTED);
        b.setFont(Park.bold(13)); b.setRadius(12); b.setPreferredSize(new Dimension(Math.max(96, t.length() * 9 + 46), 38));
        return b;
    }

    private void showView(String name) {
        Park.RoundButton[] all = {tabBook, tabFine, tabPri, tabAdm, tabRec, tabEvt};
        String[] names = {"book", "fine", "pri", "adm", "rec", "evt"};
        for (int i = 0; i < all.length; i++) {
            boolean on = names[i].equals(name);
            all[i].setFill(on ? Park.ACCENT : Park.BTN_IDLE);
            all[i].setForeground(on ? Color.WHITE : Park.TEXT_MUTED);
        }
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
        if (fill.equals(Park.BTN_IDLE)) b.setOutline(Park.BORDER);
        b.setToolTipText(tip);
        b.addActionListener(Fmt.act(r));
        return b;
    }

    private JPanel buildBookView() {
        JPanel p = new JPanel(new BorderLayout(0, 12)); p.setOpaque(false);
        bookTable = new JTable(bookModel);
        styleTable(bookTable, new int[]{120, 95, 75, 80, 80, 130, 110, 90, 115, 135, 80, 130});
        p.add(scroll(bookTable), BorderLayout.CENTER);

        bookCountLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        Color plain = Park.BTN_IDLE;
        Park.RoundButton approve = actionBtn("Approve Payment", Park.SUCCESS, Park.BG_DARK, 150,
            "Validate the selected payments: counter or QR bookings (check the reference number first) and overtime payments. "
            + "Park Now bookings start parking by themselves; overtime payments release the driver.", new Runnable() {
                public void run() { approvePayments(); } });
        Park.RoundButton reject = actionBtn("Reject Payment", Park.WARNING, Park.BG_DARK, 140,
            "The payment could not be verified (wrong or fake reference number, nothing received). The driver is notified.", new Runnable() {
                public void run() { rejectPayments(); } });
        Park.RoundButton walkin = actionBtn("Add Walk-in", Park.ACCENT, Color.WHITE, 120,
            "Park a driver who does not have the app. Shown as (walk-in).", new Runnable() {
                public void run() { addWalkIn(); } });
        Park.RoundButton move = actionBtn("Move Slot", plain, Park.TEXT_PRIMARY, 100,
            "Fix the system when a car parked in a different slot than the one booked.", new Runnable() {
                public void run() { moveSlot(); } });
        Park.RoundButton remove = actionBtn("Remove", plain, Park.TEXT_PRIMARY, 100,
            "Delete reservations that were never used. Select several with Ctrl/Shift.", new Runnable() {
                public void run() { removeReservations(); } });
        Park.RoundButton force = actionBtn("Force Check-out", Park.SLOT_OCCUPIED, Color.WHITE, 145,
            "Free parked slots (for example when drivers forgot to press Leave) and bill any overtime. Select several with Ctrl/Shift.", new Runnable() {
                public void run() { forceCheckout(); } });
        Park.RoundButton violation = actionBtn("Priority Violation", Park.DANGER, Color.WHITE, 150,
            "A driver (user or walk-in) parked in a PWD / senior citizen slot without a verified ID: charges " + Park.money(Park.PWD_VIOLATION) + ".", new Runnable() {
                public void run() { pwdViolation(); } });
        Park.RoundButton receipt = actionBtn("View Receipt", plain, Park.TEXT_PRIMARY, 100,
            "Open the payment receipt picture the driver attached (QR payments).", new Runnable() {
                public void run() { viewBookingReceipt(); } });
        Park.RoundButton edit = actionBtn("Edit Driver Info", Park.ACCENT_2, Color.WHITE, 100,
            "Correct a typo in the plate number, name, phone or email. Every change is saved in the audit log with the old and new value.", new Runnable() {
                public void run() { editDriverInfo(); } });
        JPanel btns = new JPanel(new GridLayout(2, 5, 10, 10)); btns.setOpaque(false);
        btns.add(approve); btns.add(reject); btns.add(receipt); btns.add(walkin); btns.add(move);
        btns.add(edit); btns.add(violation); btns.add(remove); btns.add(force);
        JPanel south = new JPanel(new BorderLayout(0, 10)); south.setOpaque(false);
        south.add(bookCountLbl, BorderLayout.NORTH); south.add(btns, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildFineView() {
        JPanel p = new JPanel(new BorderLayout(0, 12)); p.setOpaque(false);
        fineTable = new JTable(fineModel);
        styleTable(fineTable, new int[]{125, 100, 80, 90, 80, 110, 70, 75, 80, 120, 110});
        p.add(scroll(fineTable), BorderLayout.CENTER);

        fineCountLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        Park.RoundButton paid = actionBtn("Validate / Mark Paid", Park.SUCCESS, Park.BG_DARK, 190,
            "Confirm that the payment was received (check the reference number) or was collected at the counter.", new Runnable() {
            public void run() { markFinePaid(); } });
        Park.RoundButton rej = actionBtn("Reject Payment", Park.WARNING, Park.BG_DARK, 160,
            "The driver's payment could not be verified. The fee goes back to unpaid and the driver is notified.", new Runnable() {
            public void run() { rejectFinePayments(); } });
        Park.RoundButton frec = actionBtn("View Receipt", Park.BTN_IDLE, Park.TEXT_PRIMARY, 140,
            "Open the payment receipt picture the driver attached.", new Runnable() {
            public void run() { viewFineReceipt(); } });
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); btns.setOpaque(false);
        btns.add(frec); btns.add(rej); btns.add(paid);
        JPanel south = new JPanel(new BorderLayout()); south.setOpaque(false);
        south.add(fineCountLbl, BorderLayout.WEST); south.add(btns, BorderLayout.EAST);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildEventView() {
        JPanel p = new JPanel(new BorderLayout(0, 12)); p.setOpaque(false);
        evtTable = new JTable(evtModel);
        evtTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int r = evtTable.rowAtPoint(e.getPoint());
                if (r >= 0 && r < evtModel.rows.size()) openAlert(evtModel.rows.get(r));
            }
        });
        styleTable(evtTable, new int[]{125, 135, 125, 80, 80, 480, 115});
        p.add(scroll(evtTable), BorderLayout.CENTER);

        evtCountLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        Park.RoundButton read = actionBtn("Mark All Read", Park.BTN_IDLE, Park.TEXT_PRIMARY, 150, null, new Runnable() {
            public void run() { DataStore.markAllRead(); refreshEvents(); tick(); } });
        Park.RoundButton pen = actionBtn("Charge " + Park.money(Park.LEAVE_PENALTY) + " Penalty", Park.DANGER, Color.WHITE, 200,
            "Select a \"Pressed Leave\" alert, then charge this only if the driver pressed Leave while the car was still in the slot.", new Runnable() {
            public void run() { chargePenalty(); } });
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); btns.setOpaque(false);
        btns.add(read); btns.add(pen);
        JPanel south = new JPanel(new BorderLayout()); south.setOpaque(false);
        south.add(evtCountLbl, BorderLayout.WEST); south.add(btns, BorderLayout.EAST);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private JScrollPane scroll(JTable t) {
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createLineBorder(Park.BORDER, 1));
        sp.getViewport().setBackground(Park.LIST_BG);
        sp.getVerticalScrollBar().setUnitIncrement(14);
        return sp;
    }

    private void styleTable(JTable t, int[] widths) {
        t.setRowHeight(38);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setFillsViewportHeight(true);
        t.setBackground(Park.LIST_BG);
        t.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);   // Ctrl/Shift-click to pick several
        t.setDefaultRenderer(Object.class, new CellRenderer());
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setPreferredSize(new Dimension(100, 38));
        t.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;
            public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                JLabel l = (JLabel) super.getTableCellRendererComponent(tb, v, false, false, r, c);
                l.setOpaque(true); l.setBackground(Park.BTN_IDLE);
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
            if (sel)                bg = Park.SEL_BG;
            else if (m.hot(row))    bg = Park.HOT_BG;
            else if (row % 2 == 0)  bg = Park.LIST_BG;
            else                    bg = Park.ROW_ALT;
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

    private static boolean needsAction(DataStore.ReservationInfo r) {
        return r.status == DataStore.PENDING || r.hasLeaveRequest();
    }

    private class BookingsModel extends StyledModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Driver", "Contact", "Plate", "Date", "Slot", "Time", "Status", "Paid", "Ref. No.",
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
                if (i.hasLeaveRequest()) return "Verify overtime pay";
                if (i.overStayMillis(now) >= DataStore.FORGOT_HOURS * DataStore.HOUR_MS) return "Possibly forgotten";
                if (i.remainingMillis(now) < 0) return "Overtime";
                return "OK";
            }
            return i.endMillis < now ? "Stale reservation" : "OK";
        }

        private String refOf(DataStore.ReservationInfo i) {
            if (i.hasLeaveRequest()) return i.leaveRef.isEmpty() ? "Counter (overtime)" : i.leaveRef;
            if (!i.refNo.isEmpty()) return i.refNo;
            return i.status == DataStore.PENDING ? "Counter" : "-";
        }

        public Object getValueAt(int r, int c) {
            DataStore.ReservationInfo i = rows.get(r);
            long now = System.currentTimeMillis();
            boolean parked = i.status == DataStore.PARKED;
            switch (c) {
                case 0: return nameOf(i.user) + (DataStore.isPriorityUser(i.user)
                    ? ("SENIOR".equals(DataStore.priorityType(i.user)) ? "  [Senior]" : "  [PWD]") : "");
                case 1: return contactOf(i.user);
                case 2: return Fmt.formatPlate(i.plate);
                case 3: return Park.shortDate(i.date);
                case 4: return i.slot;
                case 5: return Fmt.timeRange(i.startMin, i.endMin());
                case 6: return parked ? (i.hasLeaveRequest() ? "Leaving (paid)" : "Parked")
                                      : i.status == DataStore.PENDING ? (i.immediate ? "Pending (Park Now)" : "Pending (Reserve)") : "Reserved";
                case 7: return i.hours + "h  (" + Park.money(i.price) + ")";
                case 8: return refOf(i);
                case 9:
                    if (i.status == DataStore.PENDING) return "Awaiting approval";
                    if (!parked) return Park.isPast(i.date) ? "Date has passed" : "Not arrived yet";
                    long rem = i.remainingMillis(now);
                    return rem >= 0 ? Park.hms(rem) + " left" : "+" + Park.hms(-rem) + " over";
                case 10:
                    double fee = parked ? i.shownOvertimeFee(now) : 0;
                    return fee > 0 ? Park.money(fee) + (i.hasLeaveRequest() ? " (sent)" : "") : "-";
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
            if (c == 8) return Park.ACCENT_2;
            if (c == 9 && i.status == DataStore.PARKED) {
                long rem = i.remainingMillis(now);
                if (rem < 0) return Park.DANGER;
                if (rem <= 5 * 60000L) return Park.WARNING;
                return Park.SUCCESS;
            }
            if (c == 10 && i.status == DataStore.PARKED && i.shownOvertimeFee(now) > 0) return Park.DANGER;
            if (c == 11) {
                String f = flag(i, now);
                if ("OK".equals(f)) return Park.SUCCESS;
                return (f.equals("Overtime") || f.equals("Needs approval") || f.equals("Verify overtime pay")) ? Park.WARNING : Park.DANGER;
            }
            return Park.TEXT_PRIMARY;
        }
    }

    private class FinesModel extends StyledModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Driver", "Contact", "Plate", "Date", "Slot", "Type", "Overtime", "Fee", "Ended By", "Ref. No.", "Status"};
        List<DataStore.Fine> rows = new ArrayList<DataStore.Fine>();

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }

        private String status(DataStore.Fine f) { return f.paid ? "Paid" : f.payPending ? "Verify payment" : "UNPAID"; }

        public Object getValueAt(int r, int c) {
            DataStore.Fine f = rows.get(r);
            switch (c) {
                case 0: return nameOf(f.user);
                case 1: return contactOf(f.user);
                case 2: return Fmt.formatPlate(f.plate);
                case 3: return Park.shortDate(f.date);
                case 4: return f.slot;
                case 5: return f.kind;
                case 6: return f.overtimeHours + "h";
                case 7: return Park.money(f.fee);
                case 8: return f.endedBy;
                case 9: return !f.payRef.isEmpty() ? f.payRef : (f.payPending || (f.paid && !f.payMode.isEmpty())) ? "Counter" : "-";
                default: return status(f);
            }
        }

        boolean hot(int r) { return r < rows.size() && !rows.get(r).paid; }

        Color fg(int r, int c) {
            if (r >= rows.size()) return Park.TEXT_PRIMARY;
            DataStore.Fine f = rows.get(r);
            if (c == 10) return f.paid ? Park.SUCCESS : f.payPending ? Park.WARNING : Park.DANGER;
            if (c == 7 && !f.paid) return Park.DANGER;
            if (c == 9) return Park.ACCENT_2;
            if (c == 0 && DataStore.isWalkIn(f.user)) return Park.ACCENT_2;
            return Park.TEXT_PRIMARY;
        }
    }

    private static String eventLabel(String type) {
        if ("RESERVE".equals(type))    return "Reservation";
        if ("PARK_NOW".equals(type))   return "Park Now (counter)";
        if ("ONLINE_PAY".equals(type)) return "Paid online";
        if ("LEAVE".equals(type))      return "Pressed Leave";
        if ("LEAVE_PAY".equals(type))  return "Overtime payment";
        if ("FINE_PAY".equals(type))   return "Fee payment";
        if ("PRIORITY".equals(type))   return "PWD / Senior ID";
        if ("PLATE_EDIT".equals(type)) return "Plate changed";
        if ("ADMIN_REQUEST".equals(type)) return "Admin request";
        if ("ACCOUNT_DELETED".equals(type)) return "Account deleted";
        return type;
    }

    private class EventsModel extends StyledModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Time", "Alert", "Driver", "Plate", "Slot", "Details", "Status"};
        List<DataStore.Event> rows = new ArrayList<DataStore.Event>();

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }

        public Object getValueAt(int r, int c) {
            DataStore.Event e = rows.get(r);
            switch (c) {
                case 0: return new java.text.SimpleDateFormat("MMM dd  h:mm:ss a", java.util.Locale.ENGLISH).format(new java.util.Date(e.millis));
                case 1: return eventLabel(e.type);
                case 2: return nameOf(e.user);
                case 3: return Fmt.formatPlate(e.plate);
                case 4: return e.slot;
                case 5: return e.message;
                default: return e.state == 0 ? "NEW" : e.state == 2 ? "Penalty charged" : "Read";
            }
        }

        boolean hot(int r) { return r < rows.size() && rows.get(r).state == 0; }

        Color fg(int r, int c) {
            if (r >= rows.size()) return Park.TEXT_PRIMARY;
            DataStore.Event e = rows.get(r);
            if (c == 1) {
                if ("LEAVE".equals(e.type)) return Park.WARNING;
                if ("ONLINE_PAY".equals(e.type) || "LEAVE_PAY".equals(e.type) || "FINE_PAY".equals(e.type)) return Park.ACCENT_2;
                return Park.TEXT_PRIMARY;
            }
            if (c == 6) return e.state == 0 ? Park.WARNING : e.state == 2 ? Park.DANGER : Park.TEXT_MUTED;
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
        clockLbl.setText(Park.clockText());
        int v = DataStore.version();                 // also picks up changes saved by another running copy
        if (v != seenVersion) { seenVersion = v; refreshFines(); refreshPriority(); refreshAdmins(); refreshEvents(); }

        long now = System.currentTimeMillis();
        final long fnow = now;
        List<DataStore.ReservationInfo> fresh = DataStore.getAllReservations();
        Collections.sort(fresh, new Comparator<DataStore.ReservationInfo>() {
            public int compare(DataStore.ReservationInfo a, DataStore.ReservationInfo b) {
                int pa = needsAction(a) ? 0 : 1, pb = needsAction(b) ? 0 : 1;
                if (pa != pb) return pa - pb;
                int c = Long.valueOf(b.overStayMillis(fnow)).compareTo(Long.valueOf(a.overStayMillis(fnow)));
                if (c != 0) return c;
                c = Park.parseDate(a.date).compareTo(Park.parseDate(b.date));
                if (c != 0) return c;
                c = a.startMin - b.startMin;
                return c != 0 ? c : a.slot.compareTo(b.slot);
            }
        });

        int parked = 0, resv = 0, pend = DataStore.pendingFinePayments(), over = 0;
        for (DataStore.ReservationInfo r : fresh) {
            if (r.status == DataStore.PARKED) {
                parked++;
                if (r.remainingMillis(now) < 0) over++;
                if (r.hasLeaveRequest()) pend++;
            }
            else if (r.status == DataStore.PENDING) pend++;
            else resv++;
        }

        parkedLbl.setText(String.valueOf(parked));
        resvLbl.setText(String.valueOf(resv));
        pendLbl.setText(String.valueOf(pend));
        overLbl.setText(String.valueOf(over));
        unpaidLbl.setText(Park.money(DataStore.getUnpaidTotal()));
        bookCountLbl.setForeground(Park.TEXT_MUTED);
        bookCountLbl.setText(fresh.size() + " active booking" + (fresh.size() == 1 ? "" : "s")
            + ".  Rows in red need attention.  Check the Ref. No. against your InstaPay records before approving.");

        int unread = DataStore.unreadEvents();
        if (flashMsg != null && now < flashUntil) {
            alertLbl.setForeground(Park.WARNING);
            alertLbl.setText("\u25CF " + flashMsg);
        } else if (unread > 0) {
            alertLbl.setForeground(Park.WARNING);
            alertLbl.setText("\u25CF " + unread + " unread alert" + (unread == 1 ? "" : "s") + " - see the Alerts tab");
        } else {
            alertLbl.setText(" ");
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

    /** Reloads the Activity Log and raises a banner (and a beep) for every alert that arrived since the last look. */
    private void refreshEvents() {
        List<DataStore.Event> all = DataStore.getEvents();
        Collections.reverse(all);   // newest first
        Set<String> sel = new HashSet<String>();
        for (int r : evtTable.getSelectedRows())
            if (r >= 0 && r < evtModel.rows.size()) sel.add(evtModel.rows.get(r).id);
        int unread = 0;
        for (DataStore.Event e : all) {
            if (e.state == 0) unread++;
            if (!knownEvents.contains(e.id)) {
                knownEvents.add(e.id);
                if (evtInit && e.state == 0)
                    flash(eventLabel(e.type) + "  \u00B7  " + nameOf(e.user) + "  \u00B7  " + Fmt.formatPlate(e.plate)
                        + "  \u00B7  slot " + e.slot);
                boolean leaveRelated = "LEAVE".equals(e.type) || "LEAVE_PAY".equals(e.type);
                if (e.state == 0 && leaveRelated && (evtInit || "LEAVE_PAY".equals(e.type))) {
                    final DataStore.Event fe = e;
                    SwingUtilities.invokeLater(new Runnable() { public void run() { showLeaveAlert(fe, false); } });
                }
            }
        }
        evtInit = true;
        evtModel.rows = all;
        evtModel.fireTableDataChanged();
        for (int i = 0; i < all.size(); i++)
            if (sel.contains(all.get(i).id)) evtTable.addRowSelectionInterval(i, i);
        evtCountLbl.setText(all.size() + " alert" + (all.size() == 1 ? "" : "s") + ",  " + unread
            + " new.  Alerts are created when a driver reserves, pays online, sends an overtime payment or presses Leave.");
        tabEvt.setText(unread > 0 ? "Alerts (" + unread + ")" : "Alerts");
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
        int verify = 0;
        for (DataStore.Fine f : all) { if (!f.paid) unpaid++; if (!f.paid && f.payPending) verify++; }
        fineCountLbl.setText(all.size() + " record" + (all.size() == 1 ? "" : "s") + ",  " + unpaid + " unpaid"
            + (verify > 0 ? ",  " + verify + " payment" + (verify == 1 ? "" : "s") + " to verify." : "."));
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
        ta.setBackground(Park.FIELD_BG); ta.setForeground(Park.TEXT_PRIMARY);
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
    private List<DataStore.ReservationInfo> selectedPayments() {
        List<DataStore.ReservationInfo> pend = new ArrayList<DataStore.ReservationInfo>();
        for (DataStore.ReservationInfo r : selectedBookings()) if (needsAction(r)) pend.add(r);
        return pend;
    }

    private String pwdNote(DataStore.ReservationInfo r) {
        boolean slot = Park.isPwdSlot(r.slot);
        boolean disc = !DataStore.isWalkIn(r.user) && DataStore.isPriorityUser(r.user) && !r.immediate;
        if (!slot && !disc) return "";
        String what = DataStore.isWalkIn(r.user) ? "walk-in, no account" :
            DataStore.priorityName(DataStore.priorityType(r.user)) + " ID " + DataStore.priorityId(r.user)
            + (DataStore.isPriorityUser(r.user) ? " (verified)" : " (NOT verified)");
        return "  |  " + (slot ? "PWD/SENIOR SLOT" : "PWD/senior rate") + ": " + (DataStore.priorityType(r.user).isEmpty() && !DataStore.isWalkIn(r.user) ? "no ID" : what);
    }

    private String paymentLine(DataStore.ReservationInfo r) {
        if (r.hasLeaveRequest())
            return describe(r) + "  |  OVERTIME " + Park.money(r.leaveFee) + "  |  "
                + (r.leaveRef.isEmpty() ? "counter" : "ref " + r.leaveRef) + pwdNote(r);
        return describe(r) + "  |  " + (r.immediate ? "Park Now " : "Reserve ") + r.hours + "h  |  " + Park.money(r.price)
            + "  |  " + (r.refNo.isEmpty() ? "counter" : "ref " + r.refNo) + pwdNote(r);
    }

    private void approvePayments() {
        List<DataStore.ReservationInfo> pend = selectedPayments();
        if (pend.isEmpty()) {
            info("Select one or more bookings that are \"Pending\" or \"Leaving (paid)\" first.");
            return;
        }
        double total = 0;
        List<String> lines = new ArrayList<String>();
        for (DataStore.ReservationInfo r : pend) {
            total += r.hasLeaveRequest() ? r.leaveFee : r.price;
            lines.add(paymentLine(r));
        }
        int ok = JOptionPane.showConfirmDialog(this,
            listMessage("Approve the payment of " + pend.size() + " booking" + (pend.size() == 1 ? "" : "s") + "?",
                lines, "Total: " + Park.money(total) + "\nCompare each reference number with your InstaPay / bank records. Approve only when the money is received.\n"
                    + "Park Now bookings start parking now. Reservations become Reserved. Overtime payments let the driver leave."),
            "Approve Payment", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        StringBuilder errs = new StringBuilder();
        for (DataStore.ReservationInfo r : pend) {
            if (r.hasLeaveRequest()) { DataStore.approveLeave(r.id); continue; }
            String err = DataStore.approve(r.id);
            if (err != null) errs.append("Slot ").append(r.slot).append(": ").append(err).append('\n');
        }
        tick(); refreshFines();
        if (errs.length() > 0) info(errs.toString());
    }

    private void rejectPayments() {
        List<DataStore.ReservationInfo> pend = selectedPayments();
        if (pend.isEmpty()) {
            info("Select one or more bookings that are \"Pending\" or \"Leaving (paid)\" first.");
            return;
        }
        List<String> lines = new ArrayList<String>();
        for (DataStore.ReservationInfo r : pend) lines.add(paymentLine(r));
        int ok = JOptionPane.showConfirmDialog(this,
            listMessage("Reject the payment of " + pend.size() + " booking" + (pend.size() == 1 ? "" : "s") + "?",
                lines, "Pending bookings are cancelled and the slot is released.\nFor overtime, the driver stays parked and must pay again.\nThe drivers are notified."),
            "Reject Payment", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        for (DataStore.ReservationInfo r : pend) {
            if (r.hasLeaveRequest()) DataStore.clearLeaveRequest(r.id);
            else DataStore.clearBooking(r.id);
        }
        tick();
    }

    private void addWalkIn() {
        String today = Park.today();
        int nowMin = Fmt.minuteOfDay(System.currentTimeMillis());
        List<String> free = new ArrayList<String>();
        for (String s : Park.SLOTS)
            if (DataStore.maxHours(today, s, nowMin, true, null) >= 1) free.add(s);
        if (free.isEmpty()) { info("No slot is free right now."); return; }

        final SlotPicker picker = new SlotPicker(free);
        JTextField plateF = Park.field(10);
        plateF.setPreferredSize(new Dimension(200, 38));
        JSpinner hrs = new JSpinner(new SpinnerNumberModel(1, 1, 24, 1));
        hrs.setFont(Park.plain(14));
        JTextField hrsText = ((JSpinner.DefaultEditor) hrs.getEditor()).getTextField();
        hrsText.setBackground(Park.FIELD_BG); hrsText.setForeground(Park.TEXT_PRIMARY);
        hrsText.setCaretColor(Park.ACCENT);

        JPanel grid = new JPanel(new GridLayout(0, 2, 12, 10)); grid.setOpaque(false);
        grid.add(Park.lbl("Floor", Park.plain(13), Park.TEXT_PRIMARY)); grid.add(picker.floorBox);
        grid.add(Park.lbl("Parking slot", Park.plain(13), Park.TEXT_PRIMARY)); grid.add(picker.slotBox);
        grid.add(Park.lbl("Plate number", Park.plain(13), Park.TEXT_PRIMARY)); grid.add(plateF);
        grid.add(Park.lbl("Paid hours", Park.plain(13), Park.TEXT_PRIMARY));   grid.add(hrs);
        JLabel errLbl = Park.lbl(" ", Park.plain(12), Park.DANGER);
        JPanel wrap = new JPanel(new BorderLayout(0, 10)); wrap.setOpaque(false);
        wrap.add(Park.lbl(Park.lines("<b>Walk-in driver (no app)</b>",
                "Paid at the counter (car " + Park.moneyShort(Park.CAR_RATE) + "/hr, motorcycle " + Park.moneyShort(Park.MOTO_RATE) + "/hr). The timer starts now.",
                "Shown as " + DataStore.WALK_IN + " in the tables."), Park.plain(12), Park.TEXT_PRIMARY), BorderLayout.NORTH);
        wrap.add(grid, BorderLayout.CENTER);
        wrap.add(errLbl, BorderLayout.SOUTH);

        while (true) {
            int ok = JOptionPane.showConfirmDialog(this, wrap, "Add Walk-in",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return;
            String pl = Fmt.normalizePlate(plateF.getText());
            if (!Fmt.validPlate(pl)) { errLbl.setText("Enter a valid 6 or 7-character plate number (e.g. ABC 1234)."); continue; }
            int h = ((Number) hrs.getValue()).intValue();
            String slot = picker.selected();
            if (slot == null) { errLbl.setText("Choose a slot."); continue; }
            boolean pwdShown = true;
            if (Park.isPwdSlot(slot)) {
                int a = askPwdId(slot, "this walk-in driver");
                if (a == 2 || a == JOptionPane.CLOSED_OPTION) continue;
                pwdShown = a == 0;
            }
            String err = DataStore.addWalkIn(slot, pl, h, pwdShown);
            if (err != null) { errLbl.setText("<html>" + err.replace("\n", "<br>") + "</html>"); continue; }
            tick();
            info("Walk-in parked in slot " + slot + ".\nPlate " + Fmt.formatPlate(pl) + ", " + h + "h, "
                + Park.money(h * Park.rateOf(slot)) + " (counter)."
                + (Park.isPwdSlot(slot) && !pwdShown ? "\n\n" + Park.money(Park.PWD_VIOLATION) + " PWD / senior slot violation added under Fees & Penalties." : ""));
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
        final SlotPicker picker = new SlotPicker(free);
        JPanel wrap = new JPanel(new BorderLayout(0, 12)); wrap.setOpaque(false);
        wrap.add(Park.lbl(Park.lines("<b>Move this booking to a different slot in the system.</b>",
                "Use it when the car is physically in another slot than the one booked.",
                "",
                "Driver:   " + nameOf(r.user) + "  (" + Fmt.formatPlate(r.plate) + ")",
                "Now in:   slot " + r.slot + "  \u00B7  " + Fmt.timeRange(r.startMin, r.endMin()),
                "The driver's screen updates automatically."), Park.plain(12), Park.TEXT_PRIMARY), BorderLayout.NORTH);
        wrap.add(picker.panel, BorderLayout.CENTER);
        wrap.add(Park.lbl("Only free slots are listed.", Park.plain(12), Park.TEXT_MUTED), BorderLayout.SOUTH);
        int ok = JOptionPane.showConfirmDialog(this, wrap, "Move to Another Slot",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        String newSlot = picker.selected();
        if (newSlot == null) return;
        String err = DataStore.moveBooking(r.id, newSlot);
        if (err != null) { info(err); return; }
        if (Park.isPwdSlot(newSlot) && !(!DataStore.isWalkIn(r.user) && DataStore.isPriorityUser(r.user))) {
            int a = askPwdId(newSlot, DataStore.isWalkIn(r.user) ? "this walk-in driver" : nameOf(r.user));
            if (a == 1) { DataStore.chargeViolation(r.user, r.plate, newSlot); refreshFines(); }
        }
        tick();
    }

    private void removeReservations() {
        List<DataStore.ReservationInfo> all = selectedBookings();
        if (all.isEmpty()) { info("Select one or more reservations in the table first."); return; }
        List<DataStore.ReservationInfo> rem = new ArrayList<DataStore.ReservationInfo>();
        for (DataStore.ReservationInfo r : all) if (r.status != DataStore.PARKED) rem.add(r);
        if (rem.isEmpty()) {
            info("Those cars are parked. Use Force Check-out so any overtime gets billed.\n(A driver with an overtime payment waiting: use Approve Payment.)");
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
            listMessage(head, lines, "The slots become available again. Pending requests are rejected\nand the drivers are notified."),
            "Remove Reservation", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        for (DataStore.ReservationInfo r : rem) DataStore.clearBooking(r.id);
        tick();
    }

    private void forceCheckout() {
        List<DataStore.ReservationInfo> all = selectedBookings();
        if (all.isEmpty()) { info("Select one or more parked bookings in the table first."); return; }
        List<DataStore.ReservationInfo> parked = new ArrayList<DataStore.ReservationInfo>();
        for (DataStore.ReservationInfo r : all) if (r.status == DataStore.PARKED && !r.hasLeaveRequest()) parked.add(r);
        if (parked.isEmpty()) {
            info("None of the selected bookings can be force checked out.\nUse Remove Reservation for reservations, and Approve / Reject Payment for pending payments.");
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
            lines.add(nameOf(f.user) + "  |  " + Fmt.formatPlate(f.plate) + "  |  Slot " + f.slot + "  |  " + f.kind + "  |  "
                + Park.money(f.fee) + "  |  " + (!f.payRef.isEmpty() ? "ref " + f.payRef : f.payPending ? "counter" : "no payment sent"));
        }
        int ok = JOptionPane.showConfirmDialog(this,
            listMessage("Mark " + pick.size() + " fee" + (pick.size() == 1 ? "" : "s") + " as paid?",
                lines, "Total: " + Park.money(total) + "\nCheck the reference numbers against your InstaPay / bank records first."),
            "Validate / Mark Paid", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        for (DataStore.Fine f : pick) DataStore.payFine(f);
        refreshFines();
    }

    private void rejectFinePayments() {
        List<DataStore.Fine> pick = new ArrayList<DataStore.Fine>();
        for (int r : fineTable.getSelectedRows())
            if (r >= 0 && r < fineModel.rows.size() && fineModel.rows.get(r).payPending && !fineModel.rows.get(r).paid)
                pick.add(fineModel.rows.get(r));
        if (pick.isEmpty()) { info("Select one or more fees with status \"Verify payment\" first."); return; }
        List<String> lines = new ArrayList<String>();
        for (DataStore.Fine f : pick)
            lines.add(nameOf(f.user) + "  |  Slot " + f.slot + "  |  " + Park.money(f.fee)
                + "  |  " + (f.payRef.isEmpty() ? "counter" : "ref " + f.payRef));
        int ok = JOptionPane.showConfirmDialog(this,
            listMessage("Reject " + pick.size() + " payment" + (pick.size() == 1 ? "" : "s") + "?", lines,
                "The fees go back to UNPAID and the drivers are notified."),
            "Reject Payment", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        for (DataStore.Fine f : pick) DataStore.rejectFinePayment(f);
        refreshFines();
    }

    private void chargePenalty() {
        List<DataStore.Event> sel = new ArrayList<DataStore.Event>();
        for (int r : evtTable.getSelectedRows())
            if (r >= 0 && r < evtModel.rows.size()) sel.add(evtModel.rows.get(r));
        if (sel.size() != 1 || !"LEAVE".equals(sel.get(0).type)) {
            info("Select exactly one \"Pressed Leave\" alert first.");
            return;
        }
        DataStore.Event e = sel.get(0);
        if (e.state == 2) { info("A penalty was already charged for this alert."); return; }
        int ok = JOptionPane.showConfirmDialog(this,
            listMessage("Charge the " + Park.money(Park.LEAVE_PENALTY) + " leave penalty?",
                java.util.Collections.singletonList(nameOf(e.user) + "  |  " + Fmt.formatPlate(e.plate) + "  |  Slot " + e.slot
                    + "  |  " + e.message),
                "Do this only if you confirmed that the car was still in the slot when the driver pressed Leave.\n"
                + "The fee is billed to the driver's account and the driver is notified."),
            "Leave Penalty", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        String err = DataStore.chargePenalty(e.id);
        if (err != null) info(err);
        refreshFines(); refreshEvents();
    }

    // ---------- pop-up alerts for Leave / overtime payment ----------
    private void openAlert(DataStore.Event e) {
        if ("LEAVE".equals(e.type) || "LEAVE_PAY".equals(e.type)) showLeaveAlert(e, true);
        else info(eventLabel(e.type) + "\n\n" + e.message);
    }

    /** A clear, always-on-top pop-up. For an overtime payment it has the Approve / Reject buttons right in it. */
    private void showLeaveAlert(final DataStore.Event e, boolean fromLog) {
        if (alertDialogs.containsKey(e.id)) { alertDialogs.get(e.id).toFront(); return; }
        final boolean pay = "LEAVE_PAY".equals(e.type);
        DataStore.ReservationInfo found = null;
        for (DataStore.ReservationInfo r : DataStore.getAllReservations())
            if (r.user.equals(e.user) && r.slot.equals(e.slot) && r.status == DataStore.PARKED && (!pay || r.hasLeaveRequest())) found = r;
        if (pay && found == null) {
            if (fromLog) info("This overtime payment was already approved or rejected.");
            return;
        }
        final DataStore.ReservationInfo booking = found;
        final JDialog d = new JDialog(this, pay ? "Overtime payment - approval needed" : "Driver pressed LEAVE", false);
        Park.applyIcon(d);
        d.setAlwaysOnTop(true);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        d.addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent ev) { alertDialogs.remove(e.id); }
        });

        Color accent = pay ? Park.ACCENT : Park.WARNING;
        Park.BgPanel root = new Park.BgPanel(new BorderLayout());
        root.setBorder(Park.pad(14, 16, 14, 16));
        Park.Card card = new Park.Card(new BorderLayout(0, 12), 20);
        card.setLine(accent);
        card.setBorder(Park.pad(18, 22, 16, 22));

        JPanel top = new JPanel(); top.setOpaque(false); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel banner = Park.lbl(pay ? "!  OVERTIME PAYMENT - VALIDATE TO LET THE DRIVER LEAVE" : "!  A DRIVER PRESSED LEAVE",
            Park.bold(17), accent);
        banner.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel when = Park.lbl(new java.text.SimpleDateFormat("EEE, MMM dd yyyy  h:mm:ss a", java.util.Locale.ENGLISH)
            .format(new java.util.Date(e.millis)), Park.plain(12), Park.TEXT_MUTED);
        when.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(banner); top.add(Box.createVerticalStrut(2)); top.add(when);
        card.add(top, BorderLayout.NORTH);

        java.util.List<String> rows = new ArrayList<String>();
        rows.add("<b>Driver:</b>  " + nameOf(e.user) + "  (" + contactOf(e.user) + ")");
        rows.add("<b>Plate:</b>  " + Fmt.formatPlate(e.plate));
        rows.add("<b>Slot:</b>  " + e.slot + "  -  " + Park.floorName(Park.floorOf(e.slot)) + ", " + Park.slotKind(e.slot));
        if (pay && booking != null) {
            rows.add("<b>Total extra charge:</b>  " + Park.money(booking.leaveFee) + "  (" + booking.leaveHours + "h overtime)");
            rows.add("<b>Paid by:</b>  " + booking.leaveMode + (booking.leaveRef.isEmpty() ? "  (at the counter)" : "   <b>Ref. no.:</b>  " + booking.leaveRef));
        } else {
            rows.add("<b>Details:</b>  " + e.message);
        }
        JLabel body = Park.lbl("<html><body style='width:430px'>" + joinHtml(rows) + "</body></html>", Park.plain(13), Park.TEXT_PRIMARY);
        JLabel hint = Park.lbl("<html><body style='width:430px'>" + (pay
            ? "Check the reference number in your InstaPay records (or collect the money at the counter), then approve. The driver stays parked until you approve."
            : "Check on site or on camera that the car has really left the slot. If the car is still there, charge the "
              + Park.money(Park.LEAVE_PENALTY) + " leave penalty.") + "</body></html>", Park.plain(12), Park.TEXT_MUTED);
        JPanel mid = new JPanel(new BorderLayout(0, 10)); mid.setOpaque(false);
        mid.add(body, BorderLayout.NORTH); mid.add(hint, BorderLayout.CENTER);
        card.add(mid, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); btns.setOpaque(false);
        Park.RoundButton later = Park.secondaryBtn("Later");
        later.setPreferredSize(new Dimension(90, 42));
        later.addActionListener(Fmt.act(new Runnable() { public void run() { d.dispose(); } }));
        if (pay) {
            Park.RoundButton rej = new Park.RoundButton("Reject", Park.WARNING, Park.BG_DARK);
            rej.setPreferredSize(new Dimension(100, 42)); rej.setFont(Park.bold(13));
            rej.addActionListener(Fmt.act(new Runnable() { public void run() {
                if (booking != null) DataStore.clearLeaveRequest(booking.id);
                DataStore.markEventRead(e.id); d.dispose(); refreshAfterAlert();
            } }));
            Park.RoundButton ok = new Park.RoundButton("Approve - Driver May Leave", Park.SUCCESS, Park.BG_DARK);
            ok.setPreferredSize(new Dimension(230, 42)); ok.setFont(Park.bold(13));
            ok.addActionListener(Fmt.act(new Runnable() { public void run() {
                if (booking != null) DataStore.approveLeave(booking.id);
                DataStore.markEventRead(e.id); d.dispose(); refreshAfterAlert();
            } }));
            btns.add(later); btns.add(rej); btns.add(ok);
        } else {
            Park.RoundButton still = new Park.RoundButton("Car still there: charge " + Park.money(Park.LEAVE_PENALTY), Park.DANGER, Color.WHITE);
            still.setPreferredSize(new Dimension(250, 42)); still.setFont(Park.bold(13));
            still.addActionListener(Fmt.act(new Runnable() { public void run() {
                String err = DataStore.chargePenalty(e.id);
                if (err != null) info(err);
                DataStore.markEventRead(e.id); d.dispose(); refreshAfterAlert();
            } }));
            Park.RoundButton noted = new Park.RoundButton("OK, car has left", Park.SUCCESS, Park.BG_DARK);
            noted.setPreferredSize(new Dimension(150, 42)); noted.setFont(Park.bold(13));
            noted.addActionListener(Fmt.act(new Runnable() { public void run() {
                DataStore.markEventRead(e.id); d.dispose(); refreshAfterAlert();
            } }));
            btns.add(later); btns.add(still); btns.add(noted);
        }
        card.add(btns, BorderLayout.SOUTH);
        root.add(card, BorderLayout.CENTER);
        d.setContentPane(root);
        d.pack();
        d.setSize(Math.max(d.getWidth(), 600), d.getHeight());
        int off = 26 * (alertDialogs.size() % 6);
        d.setLocationRelativeTo(this);
        d.setLocation(d.getX() + off, d.getY() + off);
        alertDialogs.put(e.id, d);
        d.setVisible(true);
        d.toFront();
        Toolkit.getDefaultToolkit().beep();
    }

    private String joinHtml(java.util.List<String> rows) {
        StringBuilder sb = new StringBuilder();
        for (String r : rows) sb.append(r).append("<br>");
        return sb.toString();
    }

    private void refreshAfterAlert() { refreshFines(); refreshEvents(); tick(); }

    // ---------- PWD / senior citizen ID verification ----------
    private JPanel buildPriorityView() {
        JPanel p = new JPanel(new BorderLayout(0, 12)); p.setOpaque(false);
        priTable = new JTable(priModel);
        styleTable(priTable, new int[]{150, 110, 100, 170, 100, 60, 190});
        p.add(scroll(priTable), BorderLayout.CENTER);

        priCountLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        Park.RoundButton verify = actionBtn("Verify ID", Park.SUCCESS, Park.BG_DARK, 130,
            "The ID is genuine (check the original ID). The driver can then use PWD / senior citizen slots and the discounted reservation rates.",
            new Runnable() { public void run() { reviewIds(true); } });
        Park.RoundButton reject = actionBtn("Reject ID", Park.WARNING, Park.BG_DARK, 130,
            "The ID is not valid. The driver is notified and loses the priority access.", new Runnable() { public void run() { reviewIds(false); } });
        Park.RoundButton photo = actionBtn("View ID Photo", Park.ACCENT_2, Color.WHITE, 150,
            "Open the photo of the ID the driver attached. Compare it with the details before you verify.", new Runnable() {
            public void run() { viewIdPhoto(); } });
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); btns.setOpaque(false);
        btns.add(photo); btns.add(reject); btns.add(verify);
        JPanel south = new JPanel(new BorderLayout()); south.setOpaque(false);
        south.add(priCountLbl, BorderLayout.WEST); south.add(btns, BorderLayout.EAST);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private class PriorityModel extends StyledModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Driver", "Contact", "Type", "ID number", "Birth date", "Age", "Status"};
        List<DataStore.User> rows = new ArrayList<DataStore.User>();

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }

        public Object getValueAt(int r, int c) {
            DataStore.User u = rows.get(r);
            switch (c) {
                case 0: return nameOf(u.id);
                case 1: return contactOf(u.id);
                case 2: return DataStore.priorityName(u.priorityType);
                case 3: return u.priorityId;
                case 4: return u.birth.isEmpty() ? "-" : u.birth;
                case 5: { int a = u.birth.isEmpty() ? -1 : Fmt.ageOf(u.birth); return a < 0 ? "-" : String.valueOf(a); }
                default: return u.priorityStatus == DataStore.P_VERIFIED ? "Verified"
                              : u.priorityStatus == DataStore.P_REJECTED ? "Rejected" : "Waiting for verification";
            }
        }

        boolean hot(int r) { return r < rows.size() && rows.get(r).priorityStatus == DataStore.P_PENDING; }

        Color fg(int r, int c) {
            if (r >= rows.size() || c != 6) return Park.TEXT_PRIMARY;
            int st = rows.get(r).priorityStatus;
            return st == DataStore.P_VERIFIED ? Park.SUCCESS : st == DataStore.P_REJECTED ? Park.DANGER : Park.WARNING;
        }
    }

    private void refreshPriority() {
        List<DataStore.User> all = DataStore.getPriorityUsers();
        Set<String> sel = new HashSet<String>();
        for (int r : priTable.getSelectedRows()) if (r >= 0 && r < priModel.rows.size()) sel.add(priModel.rows.get(r).id);
        int pending = 0;
        for (DataStore.User u : all) if (u.priorityStatus == DataStore.P_PENDING) pending++;
        priModel.rows = all;
        priModel.fireTableDataChanged();
        for (int i = 0; i < all.size(); i++) if (sel.contains(all.get(i).id)) priTable.addRowSelectionInterval(i, i);
        priCountLbl.setText(all.size() + " driver" + (all.size() == 1 ? "" : "s") + " with a PWD / senior citizen ID,  " + pending
            + " waiting for verification.  Seniors must be " + DataStore.SENIOR_AGE + " or older (checked from the birth date).");
        tabPri.setText(pending > 0 ? "PWD/Senior IDs (" + pending + ")" : "PWD/Senior IDs");
    }

    private void reviewIds(boolean verify) {
        List<DataStore.User> pick = new ArrayList<DataStore.User>();
        for (int r : priTable.getSelectedRows()) if (r >= 0 && r < priModel.rows.size()) pick.add(priModel.rows.get(r));
        if (pick.isEmpty()) { info("Select one or more drivers in the table first."); return; }
        List<String> lines = new ArrayList<String>();
        for (DataStore.User u : pick) {
            int age = u.birth.isEmpty() ? -1 : Fmt.ageOf(u.birth);
            lines.add(nameOf(u.id) + "  |  " + DataStore.priorityName(u.priorityType) + " ID " + u.priorityId
                + (age >= 0 ? "  |  age " + age : ""));
        }
        int ok = JOptionPane.showConfirmDialog(this,
            listMessage((verify ? "Verify " : "Reject ") + pick.size() + " ID" + (pick.size() == 1 ? "" : "s") + "?", lines,
                verify ? "Only verify after checking the original ID. Verified drivers can use PWD / senior citizen slots\nand pay the discounted reservation rates."
                       : "The drivers are notified and cannot use PWD / senior citizen slots or the discount."),
            verify ? "Verify ID" : "Reject ID", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        for (DataStore.User u : pick) DataStore.reviewPriority(u.id, verify);
        refreshPriority(); tick();
    }

    // ---------- viewing pictures ----------
    private void viewBookingReceipt() {
        List<DataStore.ReservationInfo> sel = selectedBookings();
        if (sel.size() != 1) { info("Select exactly one booking first."); return; }
        DataStore.ReservationInfo r = sel.get(0);
        String name = r.hasLeaveRequest() ? r.leaveReceipt : r.receipt;
        if (name == null || name.isEmpty()) { info("No receipt picture is attached to this booking.\n(Counter payments and older bookings have none.)"); return; }
        String ref = r.hasLeaveRequest() ? r.leaveRef : r.refNo;
        AttachPanel.view(this, "Payment receipt of " + nameOf(r.user) + (ref.isEmpty() ? "" : "  -  ref " + ref), name);
    }

    private void viewFineReceipt() {
        List<DataStore.Fine> pick = new ArrayList<DataStore.Fine>();
        for (int r : fineTable.getSelectedRows()) if (r >= 0 && r < fineModel.rows.size()) pick.add(fineModel.rows.get(r));
        if (pick.size() != 1) { info("Select exactly one fee record first."); return; }
        DataStore.Fine f = pick.get(0);
        if (f.payReceipt == null || f.payReceipt.isEmpty()) { info("No receipt picture is attached to this payment."); return; }
        AttachPanel.view(this, "Payment receipt of " + nameOf(f.user) + (f.payRef.isEmpty() ? "" : "  -  ref " + f.payRef), f.payReceipt);
    }

    private void viewIdPhoto() {
        List<DataStore.User> pick = new ArrayList<DataStore.User>();
        for (int r : priTable.getSelectedRows()) if (r >= 0 && r < priModel.rows.size()) pick.add(priModel.rows.get(r));
        if (pick.size() != 1) { info("Select exactly one driver first."); return; }
        DataStore.User u = pick.get(0);
        AttachPanel.view(this, DataStore.priorityName(u.priorityType) + " ID of " + nameOf(u.id) + "  -  " + u.priorityId, u.proofFile);
    }

    // ---------- admin edits a driver's information (typos and other mistakes) ----------
    private void editDriverInfo() {
        List<DataStore.ReservationInfo> sel = selectedBookings();
        if (sel.size() != 1) { info("Select exactly one booking: the driver whose information needs correcting."); return; }
        DataStore.ReservationInfo r = sel.get(0);
        boolean walkin = DataStore.isWalkIn(r.user);
        JTextField plateF = new JTextField(Fmt.formatPlate(r.plate), 18);
        JTextField nameF = new JTextField(walkin ? "" : DataStore.getDisplayName(r.user), 18);
        JTextField phoneF = new JTextField(walkin ? "" : DataStore.getPhone(r.user), 18);
        JTextField emailF = new JTextField(walkin ? "" : DataStore.getEmail(r.user), 18);
        JPanel pn = new JPanel(new GridLayout(0, 1, 0, 5));
        pn.add(new JLabel("<html><b>Correct the driver's information</b><br>Every change is saved in the audit log with the old and new value.</html>"));
        pn.add(new JLabel("Plate number (up to 3 letters + up to 4 digits)")); pn.add(plateF);
        if (!walkin) {
            pn.add(new JLabel("Full name")); pn.add(nameF);
            pn.add(new JLabel("Contact number (11 digits)")); pn.add(phoneF);
            pn.add(new JLabel("Email address")); pn.add(emailF);
        } else {
            pn.add(new JLabel("(Walk-in driver: only the plate number can be edited.)"));
        }
        while (true) {
            if (JOptionPane.showConfirmDialog(this, pn, "Edit Driver Info - " + (walkin ? "(walk-in)" : r.user),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String np = Fmt.normalizePlate(plateF.getText());
            String n = nameF.getText().trim().replaceAll("\\s+", " "), ph = phoneF.getText().trim(), em = emailF.getText().trim();
            String bad = null;
            if (!Fmt.validPlate(np)) bad = "Plate number: up to 3 letters followed by up to 4 digits (e.g. ABC 1234).";
            else if (!walkin && !Fmt.NAME_RX.matcher(n).matches()) bad = "Enter the full name (letters only).";
            else if (!walkin && !Fmt.PHONE_RX.matcher(ph).matches()) bad = "Contact number: 11 digits starting with 09.";
            else if (!walkin && !Fmt.EMAIL_RX.matcher(em).matches()) bad = "Enter a valid email address.";
            if (bad == null && !np.equals(r.plate)) bad = DataStore.adminEditBookingPlate(r.id, np);
            if (bad == null && !walkin) bad = DataStore.adminUpdateProfile(r.user, n, ph, em);
            if (bad == null) { refreshAfterAlert(); refreshPriority(); info("Saved. The change was recorded in the audit log."); return; }
            JOptionPane.showMessageDialog(this, bad, "Edit Driver Info", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ---------- administrators: requests and removal ----------
    private JPanel buildAdminsView() {
        JPanel p = new JPanel(new BorderLayout(0, 12)); p.setOpaque(false);
        admTable = new JTable(admModel);
        styleTable(admTable, new int[]{160, 220, 160, 200, 180});
        p.add(scroll(admTable), BorderLayout.CENTER);
        admCountLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        Park.RoundButton ok = actionBtn("Approve Request", Park.SUCCESS, Park.BG_DARK, 160,
            "Let this person log in as an administrator. Only an existing admin can do this.", new Runnable() { public void run() { adminDecision(true); } });
        Park.RoundButton no = actionBtn("Reject Request", Park.WARNING, Park.BG_DARK, 150,
            "Decline the request. The person cannot log in as an administrator.", new Runnable() { public void run() { adminDecision(false); } });
        Park.RoundButton rm = actionBtn("Remove Admin", Park.DANGER, Color.WHITE, 150,
            "Remove an administrator. You cannot remove yourself or the main account.", new Runnable() { public void run() { removeAdmin(); } });
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); btns.setOpaque(false);
        btns.add(rm); btns.add(no); btns.add(ok);
        JPanel south = new JPanel(new BorderLayout()); south.setOpaque(false);
        south.add(admCountLbl, BorderLayout.WEST); south.add(btns, BorderLayout.EAST);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private class AdminsModel extends StyledModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Username", "Name", "Status", "Added by / requested", "Date"};
        List<String[]> rows = new ArrayList<String[]>();     // {id, name, status, by, date, kind}
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
        boolean hot(int r) { return r < rows.size() && "req".equals(rows.get(r)[5]); }
        Color fg(int r, int c) {
            if (r >= rows.size() || c != 2) return Park.TEXT_PRIMARY;
            return "req".equals(rows.get(r)[5]) ? Park.WARNING : Park.SUCCESS;
        }
    }

    private void refreshAdmins() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MMM dd yyyy  h:mm a", java.util.Locale.ENGLISH);
        Set<String> sel = new HashSet<String>();
        for (int r : admTable.getSelectedRows()) if (r >= 0 && r < admModel.rows.size()) sel.add(admModel.rows.get(r)[0]);
        List<String[]> rows = new ArrayList<String[]>();
        List<String[]> reqs = DataStore.getAdminRequests();
        for (String[] q : reqs)
            rows.add(new String[]{q[0], q[1], "Waiting for approval", "requested", f.format(new java.util.Date(Long.parseLong(q[2]))), "req"});
        for (String[] a : DataStore.getAdmins()) {
            boolean main = DataStore.ADMIN_USER.equals(a[0]);
            rows.add(new String[]{a[0], a[1], main ? "Active (main account)" : "Active", a[2],
                main ? "-" : f.format(new java.util.Date(Long.parseLong(a[3]))), main ? "main" : "admin"});
        }
        admModel.rows = rows;
        admModel.fireTableDataChanged();
        for (int i = 0; i < rows.size(); i++) if (sel.contains(rows.get(i)[0])) admTable.addRowSelectionInterval(i, i);
        admCountLbl.setText((rows.size() - reqs.size()) + " administrator" + (rows.size() - reqs.size() == 1 ? "" : "s") + ", " + reqs.size()
            + " waiting for approval. A new administrator is only added after an existing admin approves.");
        tabAdm.setText(reqs.isEmpty() ? "Admins" : "Admins (" + reqs.size() + ")");
    }

    private void adminDecision(boolean approve) {
        List<String[]> pick = new ArrayList<String[]>();
        for (int r : admTable.getSelectedRows()) if (r >= 0 && r < admModel.rows.size() && "req".equals(admModel.rows.get(r)[5])) pick.add(admModel.rows.get(r));
        if (pick.isEmpty()) { info("Select one or more requests that are \"Waiting for approval\" first."); return; }
        List<String> lines = new ArrayList<String>();
        for (String[] q : pick) lines.add(q[1] + "  (" + q[0] + ")");
        int ok = JOptionPane.showConfirmDialog(this,
            listMessage((approve ? "Approve " : "Reject ") + pick.size() + " administrator request" + (pick.size() == 1 ? "" : "s") + "?", lines,
                approve ? "They will be able to log in as administrators and see everything in this window." : "They will not get administrator access."),
            approve ? "Approve Request" : "Reject Request", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        StringBuilder errs = new StringBuilder();
        for (String[] q : pick) {
            String err = approve ? DataStore.approveAdminRequest(q[0]) : DataStore.rejectAdminRequest(q[0]);
            if (err != null) errs.append(q[0]).append(": ").append(err).append('\n');
        }
        refreshAdmins();
        if (errs.length() > 0) info(errs.toString());
    }

    private void removeAdmin() {
        int r = admTable.getSelectedRow();
        if (r < 0 || r >= admModel.rows.size() || !"admin".equals(admModel.rows.get(r)[5])) {
            info("Select an administrator (status \"Active\") to remove. The main account cannot be removed.");
            return;
        }
        String[] a = admModel.rows.get(r);
        int ok = JOptionPane.showConfirmDialog(this, "Remove administrator " + a[1] + " (" + a[0] + ")?\nThey will no longer be able to log in as an admin.",
            "Remove Admin", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        String err = DataStore.removeAdmin(a[0]);
        if (err != null) info(err);
        refreshAdmins();
    }

    // ---------- records: permanent session history and audit log ----------
    private JPanel buildRecordsView() {
        JPanel p = new JPanel(new BorderLayout(0, 10)); p.setOpaque(false);

        recBtnHist = tabButton("Session History");
        recBtnAudit = tabButton("Audit Log");
        recBtnHist.addActionListener(Fmt.act(new Runnable() { public void run() { recHistory = true; refreshRecords(); } }));
        recBtnAudit.addActionListener(Fmt.act(new Runnable() { public void run() { recHistory = false; refreshRecords(); } }));
        recSearch = Park.field(20);
        recSearch.setPreferredSize(new Dimension(260, 38));
        recSearch.setToolTipText("Type part of a name, plate, slot, action or date. Several words must all match.");
        recSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshRecords(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshRecords(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshRecords(); }
        });
        Park.RoundButton verify = actionBtn("Verify Integrity", Park.SUCCESS, Park.BG_DARK, 150,
            "Checks that no record was edited or removed outside the program.", new Runnable() {
                public void run() { JOptionPane.showMessageDialog(AdminFrame.this, DataStore.verifyRecords(), "Records Integrity", JOptionPane.INFORMATION_MESSAGE); } });
        Park.RoundButton export = actionBtn("Export CSV", Park.ACCENT_2, Color.WHITE, 120,
            "Save the rows shown (after the search) as a CSV file that opens in Excel.", new Runnable() { public void run() { exportRecords(); } });
        JPanel top = new JPanel(new BorderLayout(10, 0)); top.setOpaque(false);
        JPanel leftTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); leftTop.setOpaque(false);
        leftTop.add(recBtnHist); leftTop.add(recBtnAudit);
        leftTop.add(Park.lbl("Search", Park.bold(12), Park.TEXT_MUTED)); leftTop.add(recSearch);
        JPanel rightTop = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); rightTop.setOpaque(false);
        rightTop.add(verify); rightTop.add(export);
        top.add(leftTop, BorderLayout.WEST); top.add(rightTop, BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);

        recTable = new JTable(recModel);
        styleTable(recTable, new int[]{150, 130, 110, 120, 110, 160, 120});
        p.add(scroll(recTable), BorderLayout.CENTER);
        recCountLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        p.add(recCountLbl, BorderLayout.SOUTH);
        return p;
    }

    private class RecordsModel extends StyledModel {
        private static final long serialVersionUID = 1L;
        String[] cols = {"Time"};
        List<String[]> rows = new ArrayList<String[]>();
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Object getValueAt(int r, int c) { return c < rows.get(r).length ? rows.get(r)[c] : ""; }
        boolean hot(int r) { return false; }
        Color fg(int r, int c) {
            if (r >= rows.size()) return Park.TEXT_PRIMARY;
            String v = (String) getValueAt(r, c);
            if (v.startsWith("REJECTED") || v.startsWith("CANCELLED") || v.startsWith("REMOVED") || v.contains("FAILED") || v.contains("DELETED")) return Park.DANGER;
            if (v.equals("COMPLETED")) return Park.SUCCESS;
            return Park.TEXT_PRIMARY;
        }
    }

    private static String stamp(String millis) {
        try {
            long m = Long.parseLong(millis);
            return m <= 0 ? "-" : new java.text.SimpleDateFormat("MMM dd yyyy  h:mm:ss a", java.util.Locale.ENGLISH).format(new java.util.Date(m));
        } catch (NumberFormatException e) { return millis; }
    }

    private static String fee(String v) {
        try { return Park.money(Double.parseDouble(v)); } catch (NumberFormatException e) { return v; }
    }

    private void refreshRecords() {
        boolean h = recHistory;
        Park.RoundButton[] bs = {recBtnHist, recBtnAudit};
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 0) == h;
            bs[i].setFill(on ? Park.ACCENT : Park.BTN_IDLE);
            bs[i].setForeground(on ? Color.WHITE : Park.TEXT_MUTED);
        }
        List<String[]> shown = new ArrayList<String[]>();
        String[] terms = recSearch.getText().trim().toLowerCase().split("\\s+");
        List<String[]> raw = h ? DataStore.getHistory() : DataStore.getAudit();
        for (int i = raw.size() - 1; i >= 0; i--) {          // newest first
            String[] x = raw.get(i), d;
            if (h) {
                d = new String[]{stamp(x[1]), x[2], x[3], x[4], x[5], Fmt.formatPlate(x[6]), x[7], x[8], stamp(x[9]), stamp(x[10]),
                    x[11] + "h", fee(x[12]), fee(x[13]), fee(x[14]), x[15], x[16], x[17]};
            } else {
                d = new String[]{stamp(x[1]), x[2], x[3], x[4], x[5]};
            }
            StringBuilder all = new StringBuilder();
            for (String c : d) all.append(c.toLowerCase()).append(' ');
            boolean ok = true;
            for (String t : terms) if (!t.isEmpty() && all.indexOf(t) < 0) { ok = false; break; }
            if (ok) shown.add(d);
        }
        recModel.cols = h
            ? new String[]{"Recorded", "Outcome", "Driver", "Name", "Contact", "Plate", "Slot", "Parking date", "Entry", "Exit", "Paid", "Fee", "Overtime", "Total", "Payment", "Ref. No.", "Ended by"}
            : new String[]{"Time", "Who", "Role", "Action", "Details"};
        recModel.rows = shown;
        recModel.fireTableStructureChanged();
        int[] w = h ? new int[]{150, 100, 90, 130, 100, 80, 70, 110, 150, 150, 50, 70, 70, 70, 120, 120, 170}
                    : new int[]{150, 110, 80, 200, 600};
        recTable.setAutoResizeMode(h ? JTable.AUTO_RESIZE_OFF : JTable.AUTO_RESIZE_LAST_COLUMN);
        for (int i = 0; i < w.length && i < recTable.getColumnCount(); i++) recTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        recCountLbl.setText(shown.size() + " of " + raw.size() + " record" + (raw.size() == 1 ? "" : "s") + " shown.  "
            + (h ? "Every session that ends (finished, cancelled, rejected or removed) is kept here permanently."
                 : "Every admin action and key driver action is kept here permanently, with who did it.")
            + "  Use Verify Integrity to detect edits made outside the program.");
    }

    private void exportRecords() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File((recHistory ? "parkwise_session_history" : "parkwise_audit_log") + ".csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.io.File f = fc.getSelectedFile();
        try {
            StringBuilder sb = new StringBuilder("\uFEFF");
            for (int c = 0; c < recModel.cols.length; c++) sb.append(c > 0 ? "," : "").append(csv(recModel.cols[c]));
            sb.append("\r\n");
            for (String[] row : recModel.rows) {
                for (int c = 0; c < recModel.cols.length; c++) sb.append(c > 0 ? "," : "").append(csv(row[c]));
                sb.append("\r\n");
            }
            java.nio.file.Files.write(f.toPath(), sb.toString().getBytes("UTF-8"));
            DataStore.audit("RECORDS_EXPORTED", (recHistory ? "Session history" : "Audit log") + ": " + recModel.rows.size() + " rows to " + f.getName());
            info("Saved " + recModel.rows.size() + " rows to\n" + f.getAbsolutePath());
        } catch (Exception ex) {
            info("Could not save the file: " + ex.getMessage());
        }
    }

    private static String csv(String v) {
        return "\"" + (v == null ? "" : v.replace("\"", "\"\"")) + "\"";
    }

    // ---------- choosing a slot (floor + slot) ----------
    private static class SlotPicker {
        final JPanel panel = new JPanel(new GridLayout(0, 2, 12, 10));
        final JComboBox<String> floorBox = new JComboBox<String>();
        final JComboBox<String> slotBox = new JComboBox<String>();
        final java.util.List<String> all;
        final java.util.List<Integer> floors = new ArrayList<Integer>();
        final java.util.List<String> shown = new ArrayList<String>();

        SlotPicker(java.util.List<String> slots) {
            all = slots;
            panel.setOpaque(false);
            Fmt.styleCombo(floorBox); Fmt.styleCombo(slotBox);
            for (int f = 1; f <= Park.FLOORS; f++) {
                for (String s : all) if (Park.floorOf(s) == f) { floors.add(f); floorBox.addItem(Park.floorName(f)); break; }
            }
            floorBox.addActionListener(new ActionListener() { public void actionPerformed(ActionEvent e) { fill(); } });
            fill();
            panel.add(Park.lbl("Floor", Park.plain(13), Park.TEXT_PRIMARY)); panel.add(floorBox);
            panel.add(Park.lbl("Slot", Park.plain(13), Park.TEXT_PRIMARY)); panel.add(slotBox);
        }

        private void fill() {
            slotBox.removeAllItems(); shown.clear();
            int i = floorBox.getSelectedIndex();
            if (i < 0) return;
            int f = floors.get(i);
            for (String s : all) if (Park.floorOf(s) == f) {
                shown.add(s);
                slotBox.addItem(Park.localOf(s) + "   (" + Park.slotKind(s).replace(" slot", "") + ")");
            }
        }

        String selected() { int i = slotBox.getSelectedIndex(); return i < 0 ? null : shown.get(i); }
    }

    /** Asks the admin whether this driver has a valid PWD ID; returns 0 = yes, 1 = no (charge), 2 = cancel. */
    private int askPwdId(String slot, String who) {
        Object[] opts = {"Valid ID shown", "No valid ID: charge " + Park.money(Park.PWD_VIOLATION), "Cancel"};
        return JOptionPane.showOptionDialog(this,
            "Slot " + slot + " is a PWD / senior citizen slot.\nDoes " + who + " show a valid PWD or senior citizen ID?\n\n"
            + "Without a PWD ID this is a violation: " + Park.money(Park.PWD_VIOLATION) + " is charged.",
            "PWD / Senior Citizen Slot", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
    }

    private void pwdViolation() {
        List<DataStore.ReservationInfo> sel = selectedBookings();
        if (sel.size() != 1) { info("Select exactly one booking: the driver who parked in a PWD slot."); return; }
        DataStore.ReservationInfo r = sel.get(0);
        java.util.List<String> pwdSlots = new ArrayList<String>();
        for (String s : Park.SLOTS) if (Park.isPwdSlot(s)) pwdSlots.add(s);
        SlotPicker pick = new SlotPicker(pwdSlots);
        if (!DataStore.isWalkIn(r.user) && DataStore.isPriorityUser(r.user))
            info(nameOf(r.user) + " has a verified " + DataStore.priorityName(DataStore.priorityType(r.user)) + " ID (" + DataStore.priorityId(r.user)
                + ").\nCharge the violation only if you are sure it does not apply.");
        JPanel wrap = new JPanel(new BorderLayout(0, 12)); wrap.setOpaque(false);
        wrap.add(Park.lbl(Park.lines("<b>PWD / senior citizen slot violation (" + Park.money(Park.PWD_VIOLATION) + ")</b>",
            "Driver:  " + nameOf(r.user) + "  (" + Fmt.formatPlate(r.plate) + ")",
            "The driver is billed. Walk-ins pay at the counter.",
            "In which PWD / senior citizen slot was the vehicle parked?"), Park.plain(12), Park.TEXT_PRIMARY), BorderLayout.NORTH);
        wrap.add(pick.panel, BorderLayout.CENTER);
        int ok = JOptionPane.showConfirmDialog(this, wrap, "PWD / Senior Citizen Slot Violation", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION || pick.selected() == null) return;
        DataStore.chargeViolation(r.user, r.plate, pick.selected());
        refreshFines();
        info(Park.money(Park.PWD_VIOLATION) + " PWD / senior slot violation charged to " + nameOf(r.user) + ". See Fees & Penalties.");
    }
}