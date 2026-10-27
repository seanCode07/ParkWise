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
    private JLabel monthYearLbl, selectedDateLbl, slotsTitleLbl, availLbl, resvLbl, occLbl, usageCaption, plateLbl, clockLbl;
    private JPanel calGrid, myList, pages;
    private CardLayout pageLayout;
    private FloorPlan plan;
    private JComboBox<String> floorBox;
    private JLabel pwdLbl;
    private Park.RoundButton tabMap, tabMine;
    private Park.RingPanel ring;
    private String plate;                       // the driver's plate number for this session

    // live parking timer + real-time sync
    private javax.swing.Timer liveTimer;
    private final List<LiveRow> live = new ArrayList<LiveRow>();
    private final Set<String> notified = new HashSet<String>();
    private int seenVersion = -1;
    private final Map<String, Snap> seen = new HashMap<String, Snap>();        // booking id -> what it looked like last time
    private final Map<String, String> seenFines = new HashMap<String, String>();  // fee key -> "paid|pending|kind|fee"
    private boolean seenInit = false;
    private int seenPriority = -1;
    private static final long ALMOST_OVER_MS = 5 * 60000L;
    private final Set<String> warned5 = new HashSet<String>();      // bookings that already got the "5 minutes left" alert

    /** What a booking looked like at the last refresh (used to tell the driver what the admin changed). */
    private static class Snap {
        final String slot; final int status; final boolean leave;
        Snap(String slot, int status, boolean leave) { this.slot = slot; this.status = status; this.leave = leave; }
    }

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
                clockLbl.setText(Park.clockText());
                plan.repaint();                                    // slot countdowns on the map
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

        // page 1: the parking map (calendar + floor plan). Page 2: my parking (usage + my bookings and fees)
        JPanel mapPage = new JPanel(new GridBagLayout()); mapPage.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.BOTH; gc.weighty = 1; gc.gridy = 0;
        gc.gridx = 0; gc.weightx = 0; gc.insets = new Insets(0, 0, 0, 18); mapPage.add(buildLeft(), gc);
        gc.gridx = 1; gc.weightx = 1; gc.insets = new Insets(0, 0, 0, 0);  mapPage.add(buildCenter(), gc);

        JPanel minePage = new JPanel(new GridBagLayout()); minePage.setOpaque(false);
        gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.BOTH; gc.weighty = 1; gc.gridy = 0;
        gc.gridx = 0; gc.weightx = 0; gc.insets = new Insets(0, 0, 0, 18); minePage.add(buildUsageCard(), gc);
        gc.gridx = 1; gc.weightx = 1; gc.insets = new Insets(0, 0, 0, 0);  minePage.add(buildMineCard(), gc);

        pageLayout = new CardLayout();
        pages = new JPanel(pageLayout); pages.setOpaque(false);
        pages.add(mapPage, "map"); pages.add(minePage, "mine");
        content.add(pages, BorderLayout.CENTER);

        root.add(content, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel buildSidebar() {
        JPanel sb = new JPanel(new BorderLayout()) {
            private static final long serialVersionUID = 1L;
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = Park.aa(g);
                g2.setColor(Park.alpha(Park.FIELD_BG, 205)); g2.fillRect(0, 0, getWidth(), getHeight());
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
        Park.RoundButton logout = new Park.RoundButton("Logout", Park.BTN_IDLE, Park.TEXT_PRIMARY);
        logout.setFont(Park.plain(11)); logout.setRadius(10); logout.setOutline(Park.BORDER);
        logout.setPreferredSize(new Dimension(56, 28)); logout.setMaximumSize(new Dimension(56, 28));
        logout.setAlignmentX(Component.CENTER_ALIGNMENT);
        logout.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                DataStore.audit("LOGOUT", "User signed out");
                DataStore.setActor(null);
                dispose(); new LoginFrame().setVisible(true);
            }
        });
        Park.RoundButton settings = new Park.RoundButton("Settings", Park.BTN_IDLE, Park.TEXT_PRIMARY);
        settings.setFont(Park.plain(11)); settings.setRadius(10); settings.setOutline(Park.BORDER);
        settings.setPreferredSize(new Dimension(60, 28)); settings.setMaximumSize(new Dimension(60, 28));
        settings.setAlignmentX(Component.CENTER_ALIGNMENT);
        settings.setToolTipText("Edit your information, change your password, delete your account");
        settings.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                new SettingsDialog(MainFrame.this, username, false,
                    new Runnable() { public void run() { dispose(); new MainFrame(username).setVisible(true); } },
                    new Runnable() { public void run() { DataStore.setActor(null); dispose(); new LoginFrame().setVisible(true); } }
                ).setVisible(true);
            }
        });
        logout.setPreferredSize(new Dimension(60, 28)); logout.setMaximumSize(new Dimension(60, 28));
        bottom.add(avatar); bottom.add(Box.createVerticalStrut(10)); bottom.add(settings);
        bottom.add(Box.createVerticalStrut(6)); bottom.add(logout);

        sb.add(top, BorderLayout.NORTH); sb.add(mid, BorderLayout.CENTER); sb.add(bottom, BorderLayout.SOUTH);
        return sb;
    }

    private JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout()); h.setOpaque(false);
        JPanel left = new JPanel(); left.setOpaque(false); left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(Park.lbl("ParkWise - Parking Overview", Park.bold(22), Park.TEXT_PRIMARY));
        left.add(Box.createVerticalStrut(2));
        left.add(Park.lbl("Hello, " + DataStore.getDisplayName(username).split(" ")[0]
                + ". Pick a date, then choose a slot and a time.", Park.plain(13), Park.TEXT_MUTED));
        h.add(left, BorderLayout.WEST);
        tabMap = new Park.RoundButton("Parking Map", Park.ACCENT, Color.WHITE);
        tabMine = new Park.RoundButton("My Parking", Park.BTN_IDLE, Park.TEXT_MUTED);
        tabMap.setFont(Park.bold(14)); tabMine.setFont(Park.bold(14));
        tabMap.setRadius(14); tabMine.setRadius(14);
        tabMap.setPreferredSize(new Dimension(150, 42)); tabMine.setPreferredSize(new Dimension(190, 42));
        tabMap.addActionListener(Fmt.act(new Runnable() { public void run() { showPage("map"); } }));
        tabMine.addActionListener(Fmt.act(new Runnable() { public void run() { showPage("mine"); } }));
        JPanel tabRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0)); tabRow.setOpaque(false);
        tabRow.add(tabMap); tabRow.add(tabMine);
        JPanel tabWrap = new JPanel(new GridBagLayout()); tabWrap.setOpaque(false); tabWrap.add(tabRow);
        h.add(tabWrap, BorderLayout.CENTER);
        clockLbl = Park.clockLabel();
        clockLbl.setToolTipText("Current date and time");
        Park.Card clockCard = new Park.Card(new BorderLayout(), 16);
        clockCard.setBorder(Park.pad(6, 12, 6, 12));
        clockCard.add(clockLbl, BorderLayout.CENTER);
        JPanel clockWrap = new JPanel(new GridBagLayout()); clockWrap.setOpaque(false); clockWrap.add(clockCard);
        h.add(clockWrap, BorderLayout.EAST);
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

        Park.RoundButton prev = new Park.RoundButton("", Park.BTN_IDLE, Park.ACCENT);
        Park.RoundButton next = new Park.RoundButton("", Park.BTN_IDLE, Park.ACCENT);
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

        JLabel l2 = Park.lbl("VEHICLE PLATE NUMBER", Park.bold(11), Park.TEXT_MUTED); l2.setAlignmentX(Component.LEFT_ALIGNMENT);
        plateLbl = Park.lbl(" ", Park.bold(17), Park.TEXT_PRIMARY);
        JLabel change = Park.lbl("Switch / Add", Park.bold(12), Park.ACCENT);
        change.setToolTipText("Park a different vehicle: pick a saved plate or add a new one");
        change.setBorder(Park.pad(0, 12, 0, 0));
        change.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        change.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { promptPlate(); }
        });
        JPanel plateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); plateRow.setOpaque(false);
        JLabel editLink = Park.lbl("Edit", Park.bold(12), Park.ACCENT);
        editLink.setToolTipText("Fix a typo in a saved plate number");
        editLink.setBorder(Park.pad(0, 10, 0, 0));
        editLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        editLink.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { editPlate(); }
        });
        plateRow.add(plateLbl); plateRow.add(change); plateRow.add(editLink);
        plateRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        plateRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        updatePlateLabel();

        JLabel l3 = Park.lbl("RATES", Park.bold(11), Park.TEXT_MUTED); l3.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel rate = Park.lbl(Park.lines(
                "Car:  " + Park.moneyShort(Park.CAR_RATE) + "/hr  \u00B7  Reserve " + Park.moneyShort(Park.CAR_RESERVE_FIRST)
                    + ", then " + Park.moneyShort(Park.CAR_RATE),
                "Motorcycle:  " + Park.moneyShort(Park.MOTO_RATE) + "/hr  \u00B7  Reserve " + Park.moneyShort(Park.MOTO_RESERVE_FIRST)
                    + ", then " + Park.moneyShort(Park.MOTO_RATE)),
                Park.bold(11), Park.ACCENT); rate.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel discount = Park.lbl("PWD / senior citizen rates", Park.bold(11), Park.TEXT_PRIMARY);
        discount.setAlignmentX(Component.LEFT_ALIGNMENT);
        discount.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        discount.setToolTipText("Click to see the discounted rates");
        discount.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { showDiscountRates(); }
        });
        JLabel tip = Park.lbl("A booked slot is closed to others all day.", Park.plain(11), Park.TEXT_MUTED);
        tip.setAlignmentX(Component.LEFT_ALIGNMENT);

        col.add(l1); col.add(Box.createVerticalStrut(2)); col.add(selectedDateLbl);
        col.add(Box.createVerticalStrut(8));
        col.add(l2); col.add(Box.createVerticalStrut(2)); col.add(plateRow);
        col.add(Box.createVerticalStrut(8));
        col.add(l3); col.add(Box.createVerticalStrut(2)); col.add(rate);
        col.add(Box.createVerticalStrut(2)); col.add(discount);
        col.add(Box.createVerticalStrut(6)); col.add(tip);
        card.add(col, BorderLayout.NORTH);
        return card;
    }

    private void updatePlateLabel() {
        if (plate == null) { plateLbl.setText("Not set"); plateLbl.setForeground(Park.TEXT_MUTED); }
        else { plateLbl.setText(Fmt.formatPlate(plate)); plateLbl.setForeground(Park.TEXT_PRIMARY); }
    }

    private boolean promptPlate() {
        String p = Fmt.choosePlate(this, username, plate);
        if (p == null) return false;
        plate = p;
        updatePlateLabel();
        return true;
    }

    private boolean ensurePlate() { return plate != null || promptPlate(); }

    private void showPage(String name) {
        boolean map = name.equals("map");
        tabMap.setFill(map ? Park.ACCENT : Park.BTN_IDLE);   tabMap.setForeground(map ? Color.WHITE : Park.TEXT_MUTED);
        tabMine.setFill(map ? Park.BTN_IDLE : Park.ACCENT);  tabMine.setForeground(map ? Park.TEXT_MUTED : Color.WHITE);
        pageLayout.show(pages, name);
    }

    // ----- center column: stats + floor plan -----
    private JPanel buildCenter() {
        JPanel p = new JPanel(new BorderLayout(0, 16)); p.setOpaque(false);

        JPanel stats = new JPanel(new GridLayout(1, 3, 14, 0)); stats.setOpaque(false);
        stats.setPreferredSize(new Dimension(100, 76));
        availLbl = new JLabel(); resvLbl = new JLabel(); occLbl = new JLabel();
        stats.add(statCard("Available", Park.SLOT_AVAILABLE, availLbl));
        stats.add(statCard("Reserved",  Park.SLOT_RESERVED,  resvLbl));
        stats.add(statCard("Occupied",  Park.SLOT_OCCUPIED,  occLbl));
        p.add(stats, BorderLayout.NORTH);

        Park.Card slotsCard = new Park.Card(new BorderLayout(0, 8), 22);
        slotsCard.setBorder(Park.pad(14, 16, 12, 16));

        floorBox = new JComboBox<String>(Park.FLOOR_NAMES);
        Fmt.styleCombo(floorBox);
        floorBox.setPreferredSize(new Dimension(200, 38));
        floorBox.addActionListener(Fmt.act(new Runnable() {
            public void run() { plan.setFloor(floorBox.getSelectedIndex() + 1); refreshSlots(); refreshStats(); }
        }));
        slotsTitleLbl = Park.lbl("Parking Map", Park.bold(16), Park.TEXT_PRIMARY);
        pwdLbl = Park.lbl(" ", Park.bold(12), Park.ACCENT);
        pwdLbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        pwdLbl.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { registerPriority(); }
        });
        JPanel leftHead = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0)); leftHead.setOpaque(false);
        JLabel fl = Park.lbl("Floor", Park.bold(12), Park.TEXT_MUTED);
        leftHead.add(fl); leftHead.add(floorBox); leftHead.add(slotsTitleLbl);
        JPanel head = new JPanel(new BorderLayout()); head.setOpaque(false);
        head.add(leftHead, BorderLayout.WEST);
        JPanel pwdWrap = new JPanel(new GridBagLayout()); pwdWrap.setOpaque(false); pwdWrap.add(pwdLbl);
        head.add(pwdWrap, BorderLayout.EAST);

        plan = new FloorPlan();
        plan.setListener(new FloorPlan.Listener() {
            public void slotClicked(String slotId) { onSlotClick(Park.fmt(selectedCal), slotId); }
        });

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.CENTER, 18, 0)); legend.setOpaque(false);
        legend.add(Park.lbl("\u2022 Available", Park.plain(12), Park.SLOT_AVAILABLE));
        legend.add(Park.lbl("\u2022 Reserved / pending",  Park.plain(12), Park.SLOT_RESERVED));
        legend.add(Park.lbl("\u2022 Vehicle parked",  Park.plain(12), Park.SLOT_OCCUPIED));
        legend.add(Park.lbl("\u25A0 PWD / Senior slot (verified drivers only)",  Park.plain(12), new Color(70, 140, 255)));
        legend.add(Park.lbl("Hover a slot for its remaining time", Park.plain(12), Park.TEXT_MUTED));

        slotsCard.add(head, BorderLayout.NORTH); slotsCard.add(plan, BorderLayout.CENTER); slotsCard.add(legend, BorderLayout.SOUTH);
        p.add(slotsCard, BorderLayout.CENTER);
        return p;
    }

    private void showDiscountRates() {
        JOptionPane.showMessageDialog(this,
            "PWD / senior citizen rates (for drivers with a verified ID).\n"
            + "The discount applies in any slot, regular or PWD / senior citizen.\n\n"
            + "Car\n"
            + "   Reserve:  " + Park.moneyShort(Park.CAR_PRIORITY_FIRST) + " for the first hour, then " + Park.moneyShort(Park.CAR_PRIORITY_RATE) + " per hour\n"
            + "   Park Now:  " + Park.moneyShort(Park.CAR_PRIORITY_RATE) + " per hour\n"
            + "Motorcycle\n"
            + "   Reserve:  " + Park.moneyShort(Park.MOTO_PRIORITY_FIRST) + " for the first hour, then " + Park.moneyShort(Park.MOTO_PRIORITY_RATE) + " per hour\n"
            + "   Park Now:  " + Park.moneyShort(Park.MOTO_PRIORITY_RATE) + " per hour\n\n"
            + "Regular rates: car " + Park.moneyShort(Park.CAR_RATE) + "/hr (reserve " + Park.moneyShort(Park.CAR_RESERVE_FIRST) + " then "
            + Park.moneyShort(Park.CAR_RATE) + "), motorcycle " + Park.moneyShort(Park.MOTO_RATE) + "/hr (reserve "
            + Park.moneyShort(Park.MOTO_RESERVE_FIRST) + " then " + Park.moneyShort(Park.MOTO_RATE) + ").\n"
            + "Overtime is billed at the same hourly rate as Park Now (PWD / senior citizen: car " + Park.moneyShort(Park.CAR_PRIORITY_RATE)
            + ", motorcycle " + Park.moneyShort(Park.MOTO_PRIORITY_RATE) + ").",
            "Rates", JOptionPane.INFORMATION_MESSAGE);
    }

    private void editPlate() {
        String[] r = Fmt.editPlate(this, username, plate);
        if (r == null) return;
        if (r[0].equals(plate)) plate = r[1];
        updatePlateLabel();
        refreshAll();
        JOptionPane.showMessageDialog(this, "Plate number changed from " + Fmt.formatPlate(r[0]) + " to " + Fmt.formatPlate(r[1])
            + ".\nYour bookings were updated and the admin was notified.", "Plate Number Updated", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Send a PWD or senior citizen ID to the admin for verification. */
    private void registerPriority() {
        int st = DataStore.priorityStatus(username);
        if (st == DataStore.P_VERIFIED) return;
        if (st == DataStore.P_PENDING) {
            JOptionPane.showMessageDialog(this, "Your " + DataStore.priorityName(DataStore.priorityType(username))
                + " ID was sent and is waiting for the admin to verify it.", "Verification Pending", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final JComboBox<String> type = new JComboBox<String>(new String[]{"PWD (Person with Disability)", "Senior citizen (age 60 and above)"});
        final JTextField idF = new JTextField(18);
        final JTextField birthF = new JTextField(18);
        birthF.setEnabled(false);
        type.addActionListener(Fmt.act(new Runnable() {
            public void run() { birthF.setEnabled(type.getSelectedIndex() == 1); }
        }));
        final AttachPanel proof = new AttachPanel("id", "Attach ID photo", "Required: a clear photo of the ID");
        JPanel pn = new JPanel(new GridLayout(0, 1, 0, 6));
        pn.add(new JLabel("<html>Register your PWD or senior citizen ID.<br>The admin verifies it before you can use the PWD / senior<br>citizen slots and the discounted reservation rates.<br>Parking in those slots without a verified ID is charged "
            + Park.money(Park.PWD_VIOLATION) + ".</html>"));
        pn.add(type);
        pn.add(new JLabel("ID number"));  pn.add(idF);
        pn.add(new JLabel("Birth date (MM/DD/YYYY) - seniors only"));  pn.add(birthF);
        pn.add(new JLabel("Photo of the ID (the admin checks it)"));  pn.add(proof);
        while (true) {
            int ok = JOptionPane.showConfirmDialog(this, pn, "PWD / Senior Citizen ID", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) { proof.clear(); return; }
            if (!proof.hasPicture()) { JOptionPane.showMessageDialog(this, "Attach a clear photo of your ID.", "PWD / Senior Citizen ID", JOptionPane.WARNING_MESSAGE); continue; }
            boolean senior = type.getSelectedIndex() == 1;
            String birth = "";
            if (senior) {
                birth = Fmt.parseBirth(birthF.getText());
                if (birth == null) { JOptionPane.showMessageDialog(this, "Enter your birth date as MM/DD/YYYY.", "Senior Citizen", JOptionPane.WARNING_MESSAGE); continue; }
            }
            String err = DataStore.setPriority(username, senior ? DataStore.PRIORITY_SENIOR : DataStore.PRIORITY_PWD, idF.getText(), birth, proof.stored());
            if (err != null) { JOptionPane.showMessageDialog(this, err, "PWD / Senior Citizen ID", JOptionPane.WARNING_MESSAGE); continue; }
            proof.release();
            JOptionPane.showMessageDialog(this, "ID sent. The admin will verify it and you will be notified.", "ID Sent", JOptionPane.INFORMATION_MESSAGE);
            refreshSlots();
            return;
        }
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

    // ----- page 2: usage ring + my parking -----
    private JPanel buildUsageCard() {
        Park.Card usage = new Park.Card(new BorderLayout(0, 4), 20);
        usage.setBorder(Park.pad(16, 16, 16, 16));
        usage.setPreferredSize(new Dimension(300, 100));
        ring = new Park.RingPanel();
        usageCaption = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        usageCaption.setHorizontalAlignment(SwingConstants.CENTER);
        usage.add(Park.lbl("Slot Usage (selected floor and date)", Park.bold(14), Park.TEXT_PRIMARY), BorderLayout.NORTH);
        usage.add(ring, BorderLayout.CENTER); usage.add(usageCaption, BorderLayout.SOUTH);
        JPanel wrap = new JPanel(new BorderLayout()); wrap.setOpaque(false);
        wrap.setPreferredSize(new Dimension(300, 100));
        wrap.add(usage, BorderLayout.NORTH);
        usage.setPreferredSize(new Dimension(300, 300));
        return wrap;
    }

    private CardLayout mineCards;
    private JPanel mineCardPanel;
    private Park.RoundButton mineBtnNow, mineBtnHist;
    private JTable histTable;
    private javax.swing.table.DefaultTableModel histModel;
    private final List<String> histIds = new ArrayList<String>();
    private JLabel histInfo;

    private JPanel buildMineCard() {
        Park.Card mine = new Park.Card(new BorderLayout(0, 10), 20);
        mine.setBorder(Park.pad(16, 18, 16, 18));

        // current bookings and fees
        myList = new JPanel(); myList.setOpaque(false); myList.setLayout(new BoxLayout(myList, BoxLayout.Y_AXIS));
        JPanel wrapper = new Park.ScrollView(new BorderLayout());
        wrapper.add(myList, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrapper);
        sp.setOpaque(false); sp.getViewport().setOpaque(false);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(14);

        mineCards = new CardLayout();
        mineCardPanel = new JPanel(mineCards); mineCardPanel.setOpaque(false);
        mineCardPanel.add(sp, "now");
        mineCardPanel.add(buildHistoryPanel(), "hist");

        mineBtnNow = new Park.RoundButton("Current", Park.ACCENT, Color.WHITE);
        mineBtnHist = new Park.RoundButton("History", Park.BTN_IDLE, Park.TEXT_MUTED);
        for (Park.RoundButton b : new Park.RoundButton[]{mineBtnNow, mineBtnHist}) {
            b.setFont(Park.bold(12)); b.setRadius(10); b.setPreferredSize(new Dimension(120, 32));
        }
        mineBtnNow.addActionListener(Fmt.act(new Runnable() { public void run() { showMine(true); } }));
        mineBtnHist.addActionListener(Fmt.act(new Runnable() { public void run() { showMine(false); } }));
        JPanel head = new JPanel(new BorderLayout()); head.setOpaque(false);
        head.add(Park.lbl("My Parking", Park.bold(17), Park.TEXT_PRIMARY), BorderLayout.WEST);
        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); toggles.setOpaque(false);
        toggles.add(mineBtnNow); toggles.add(mineBtnHist);
        head.add(toggles, BorderLayout.EAST);

        mine.add(head, BorderLayout.NORTH);
        mine.add(mineCardPanel, BorderLayout.CENTER);
        return mine;
    }

    private void showMine(boolean current) {
        mineBtnNow.setFill(current ? Park.ACCENT : Park.BTN_IDLE);   mineBtnNow.setForeground(current ? Color.WHITE : Park.TEXT_MUTED);
        mineBtnHist.setFill(current ? Park.BTN_IDLE : Park.ACCENT);  mineBtnHist.setForeground(current ? Park.TEXT_MUTED : Color.WHITE);
        mineCards.show(mineCardPanel, current ? "now" : "hist");
        if (!current) refreshHistory();
    }

    /** My past parking sessions: when I parked, when I left, how long, what I paid. I can remove entries from my own view. */
    private JPanel buildHistoryPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 8)); p.setOpaque(false);
        histModel = new javax.swing.table.DefaultTableModel(
                new String[]{"Date", "Slot", "Plate", "Parked at", "Left at", "Paid", "Total fee", "Result"}, 0) {
            private static final long serialVersionUID = 1L;
            public boolean isCellEditable(int r, int c) { return false; }
        };
        histTable = new JTable(histModel);
        histTable.setRowHeight(30);
        histTable.setBackground(Park.BG_PANEL); histTable.setForeground(Park.TEXT_PRIMARY);
        histTable.setFont(Park.plain(12));
        histTable.setGridColor(Park.BORDER);
        histTable.setSelectionBackground(Park.SEL_BG); histTable.setSelectionForeground(Park.TEXT_PRIMARY);
        histTable.setFillsViewportHeight(true);
        histTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        histTable.getTableHeader().setBackground(Park.BG_CARD_HI);
        histTable.getTableHeader().setForeground(Park.TEXT_PRIMARY);
        histTable.getTableHeader().setFont(Park.bold(12));
        int[] w = {90, 70, 90, 140, 140, 50, 80, 120};
        for (int i = 0; i < w.length; i++) histTable.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        JScrollPane hs = new JScrollPane(histTable);
        hs.setBorder(BorderFactory.createLineBorder(Park.BORDER));
        hs.getViewport().setBackground(Park.BG_PANEL);
        p.add(hs, BorderLayout.CENTER);

        histInfo = Park.lbl(" ", Park.plain(11), Park.TEXT_MUTED);
        Park.RoundButton remove = new Park.RoundButton("Remove selected", Park.DANGER, Color.WHITE);
        remove.setFont(Park.bold(12)); remove.setRadius(10); remove.setPreferredSize(new Dimension(170, 34));
        remove.addActionListener(Fmt.act(new Runnable() { public void run() { removeSelectedHistory(); } }));
        JPanel south = new JPanel(new BorderLayout()); south.setOpaque(false);
        south.add(histInfo, BorderLayout.CENTER); south.add(remove, BorderLayout.EAST);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    private static String shortStamp(String millis) {
        try {
            long m = Long.parseLong(millis);
            return m <= 0 ? "-" : new java.text.SimpleDateFormat("MMM dd  h:mm a", java.util.Locale.ENGLISH).format(new java.util.Date(m));
        } catch (NumberFormatException e) { return "-"; }
    }

    private static String outcomeText(String o) {
        if ("COMPLETED".equals(o)) return "Completed";
        if ("CANCELLED".equals(o)) return "Cancelled";
        if ("REJECTED".equals(o)) return "Payment rejected";
        if ("REMOVED".equals(o)) return "Removed by admin";
        return o;
    }

    private void refreshHistory() {
        if (histModel == null) return;
        List<String[]> rows = DataStore.getUserHistory(username);
        histModel.setRowCount(0);
        histIds.clear();
        for (int i = rows.size() - 1; i >= 0; i--) {         // newest first
            String[] h = rows.get(i);
            histIds.add(h[0]);
            String total = "COMPLETED".equals(h[2]) ? fmtMoney(h[14]) : "-";
            histModel.addRow(new Object[]{Park.shortDate(h[8]), h[7], Fmt.formatPlate(h[6]), shortStamp(h[9]), shortStamp(h[10]),
                    h[11] + "h", total, outcomeText(h[2])});
        }
        mineBtnHist.setText(rows.isEmpty() ? "History" : "History (" + rows.size() + ")");
        histInfo.setText(rows.isEmpty() ? "Your finished parking sessions will appear here."
            : rows.size() + " session" + (rows.size() == 1 ? "" : "s") + ". Select rows (Ctrl or Shift for several), then Remove selected. "
              + "This only clears your own list.");
    }

    private static String fmtMoney(String v) {
        try { return Park.money(Double.parseDouble(v)); } catch (NumberFormatException e) { return v; }
    }

    private void removeSelectedHistory() {
        int[] sel = histTable.getSelectedRows();
        if (sel.length == 0) { JOptionPane.showMessageDialog(this, "Select one or more sessions in the list first.", "Remove History", JOptionPane.INFORMATION_MESSAGE); return; }
        int ok = JOptionPane.showConfirmDialog(this, "Remove " + sel.length + " session" + (sel.length == 1 ? "" : "s") + " from your history?\n"
            + "They disappear from your list only. The parking management keeps its own records.",
            "Remove History", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        List<String> ids = new ArrayList<String>();
        for (int r : sel) if (r >= 0 && r < histIds.size()) ids.add(histIds.get(r));
        DataStore.hideHistory(username, ids);
        refreshHistory();
    }

    // ---------- refreshing ----------
    private void refreshAll() {
        detectChanges();
        refreshCal(); refreshSlots(); refreshStats(); refreshMine();
    }

    /** Compares my bookings and fees with the last look and tells the driver what the admin changed. */
    private void detectChanges() {
        Map<String, Snap> now = new HashMap<String, Snap>();
        for (DataStore.ReservationInfo r : DataStore.getReservationsForUser(username))
            now.put(r.id, new Snap(r.slot, r.status, r.hasLeaveRequest()));
        Map<String, String> nowF = new HashMap<String, String>();
        Map<String, DataStore.Fine> fineByKey = new HashMap<String, DataStore.Fine>();
        for (DataStore.Fine f : DataStore.getFinesForUser(username)) {
            nowF.put(f.key(), (f.paid ? "1" : "0") + "|" + (f.payPending ? "1" : "0"));
            fineByKey.put(f.key(), f);
        }
        if (seenInit) {
            for (Map.Entry<String, Snap> e : seen.entrySet()) {
                Snap old = e.getValue();
                Snap cur = now.get(e.getKey());
                if (cur == null) {
                    if (old.status == DataStore.PENDING)
                        notice("Payment Not Approved", "The admin could not validate your payment for slot " + old.slot
                                + " (for example, the reference number could not be verified).\n"
                                + "The request was declined and the slot has been released.\n"
                                + "Please book again, or ask the admin for help.", JOptionPane.WARNING_MESSAGE);
                    else if (old.status == DataStore.PARKED && old.leave)
                        notice("Leave Approved", "The admin validated your overtime payment.\n"
                                + "You may now drive out. Slot " + old.slot + " is free again. Thank you for using ParkWise!",
                                JOptionPane.INFORMATION_MESSAGE);
                    else if (old.status == DataStore.PARKED)
                        notice("Parking Ended", "An administrator ended your parking session in slot " + old.slot
                                + ".\nAny overtime is shown under My Parking.", JOptionPane.WARNING_MESSAGE);
                    else
                        notice("Reservation Removed", "Your reservation for slot " + old.slot
                                + " was removed by an administrator.", JOptionPane.WARNING_MESSAGE);
                } else {
                    DataStore.ReservationInfo ri = DataStore.getById(e.getKey());
                    if (old.status == DataStore.PENDING && cur.status == DataStore.RESERVED && ri != null)
                        notice("Payment Approved", "The admin validated your payment.\nSlot " + cur.slot
                                + " is now RESERVED for you:\n" + ri.date + ", " + Fmt.timeRange(ri.startMin, ri.endMin())
                                + "\nPress \"Park Now\" under My Parking when you arrive (check-in opens "
                                + DataStore.CHECKIN_EARLY_MIN + " min before).", JOptionPane.INFORMATION_MESSAGE);
                    if (old.status == DataStore.PENDING && cur.status == DataStore.PARKED && ri != null)
                        notice("Payment Approved - You Are Parked", "The admin validated your payment.\nYour parking time has "
                                + "started automatically and slot " + cur.slot + " is now OCCUPIED.\n"
                                + "Paid time: " + ri.hours + "h (ends " + Fmt.time(ri.endMin() % 1440)
                                + "). Watch the remaining time under My Parking.\nPress Leave when you drive out.",
                                JOptionPane.INFORMATION_MESSAGE);
                    if (old.status == DataStore.PARKED && cur.status == DataStore.PARKED && old.leave && !cur.leave)
                        notice("Overtime Payment Not Validated", "The admin could not validate your overtime payment for slot "
                                + cur.slot + ".\nCheck the reference number and press Leave again to pay.",
                                JOptionPane.WARNING_MESSAGE);
                    if (!old.slot.equals(cur.slot))
                        notice("Slot Changed", "An administrator moved your booking from slot " + old.slot
                                + " to slot " + cur.slot + " to match where your car is.", JOptionPane.INFORMATION_MESSAGE);
                }
            }
            for (Map.Entry<String, String> e : nowF.entrySet()) {
                String o = seenFines.get(e.getKey());
                String c = e.getValue();
                DataStore.Fine f = fineByKey.get(e.getKey());
                if (o == null) {
                    if (c.startsWith("0"))
                        notice("New Charge On Your Account", f.kind + " of " + Park.money(f.fee) + " (slot " + f.slot + ", "
                                + Park.shortDate(f.date) + ") was billed to your account.\n"
                                + "Pay it under My Parking. The admin validates the payment.", JOptionPane.WARNING_MESSAGE);
                } else {
                    if (o.equals("0|1") && c.equals("1|0"))
                        notice("Payment Validated", "The admin validated your payment of " + Park.money(f.fee)
                                + " (" + f.kind + ", slot " + f.slot + "). Thank you!", JOptionPane.INFORMATION_MESSAGE);
                    if (o.equals("0|1") && c.equals("0|0"))
                        notice("Payment Not Validated", "The admin could not validate your payment of " + Park.money(f.fee)
                                + " (" + f.kind + ", slot " + f.slot + ").\nCheck the reference number and pay again from My Parking.",
                                JOptionPane.WARNING_MESSAGE);
                }
            }
        }
        int pstNow = DataStore.priorityStatus(username);
        if (seenInit && seenPriority != pstNow) {
            String pn = DataStore.priorityName(DataStore.priorityType(username));
            if (pstNow == DataStore.P_VERIFIED)
                notice("ID Verified", "The admin verified your " + pn + " ID.\nYou can now use the PWD / senior citizen slots and the discounted reservation rates.",
                        JOptionPane.INFORMATION_MESSAGE);
            else if (pstNow == DataStore.P_REJECTED)
                notice("ID Not Verified", "The admin could not verify your " + pn + " ID.\nPlease check the details and register your ID again, or ask the admin.",
                        JOptionPane.WARNING_MESSAGE);
        }
        seenPriority = pstNow;
        seen.clear(); seen.putAll(now);
        seenFines.clear(); seenFines.putAll(nowF);
        seenInit = true;
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
            Park.RoundButton btn = new Park.RoundButton(String.valueOf(d), Park.BTN_IDLE, Park.TEXT_PRIMARY);
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
        int floor = floorBox.getSelectedIndex() + 1;
        slotsTitleLbl.setText("\u00B7  " + Park.shortDate(date));
        Map<String, DataStore.ReservationInfo> infos = new HashMap<String, DataStore.ReservationInfo>();
        for (String slot : Park.slotsOnFloor(floor)) {
            DataStore.ReservationInfo info = DataStore.activeBooking(date, slot);
            if (info != null) infos.put(slot, info);
        }
        int pst = DataStore.priorityStatus(username);
        String pname = DataStore.priorityName(DataStore.priorityType(username));
        plan.setData(username, pst == DataStore.P_VERIFIED, infos);
        if (pst == DataStore.P_VERIFIED) { pwdLbl.setText(pname + " \u2713 verified  (priority slots + discount)"); pwdLbl.setForeground(Park.SLOT_AVAILABLE); }
        else if (pst == DataStore.P_PENDING) { pwdLbl.setText(pname + " ID waiting for admin verification"); pwdLbl.setForeground(Park.WARNING); }
        else if (pst == DataStore.P_REJECTED) { pwdLbl.setText(pname + " ID was not verified - register again"); pwdLbl.setForeground(Park.DANGER); }
        else { pwdLbl.setText("PWD / senior citizen? Register your ID"); pwdLbl.setForeground(Park.ACCENT); }
        pwdLbl.setCursor(pst == DataStore.P_VERIFIED ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void refreshStats() {
        String date = Park.fmt(selectedCal);
        int floor = floorBox.getSelectedIndex() + 1;
        int resv = 0, occ = 0;
        for (String s : Park.slotsOnFloor(floor)) {
            int st = DataStore.getSlotStatus(date, s);
            if (st == 1) resv++; else if (st == 2) occ++;
        }
        int total = Park.SLOTS_PER_FLOOR;
        availLbl.setText(String.valueOf(total - resv - occ));
        resvLbl.setText(String.valueOf(resv));
        occLbl.setText(String.valueOf(occ));
        ring.setPercent((resv + occ) * 100 / total);
        usageCaption.setText((resv + occ) + " of " + total + " slots on " + Park.floorName(floor) + " have bookings");
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
            myList.add(sectionLabel("UNPAID FEES AND PENALTIES"));
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
        refreshHistory();
        int n = list.size() + unpaid.size();
        tabMine.setText("My Parking" + (n > 0 ? " (" + n + ")" : ""));
        tickLive();
    }

    private JPanel buildRow(final DataStore.ReservationInfo info) {
        boolean parked = info.status == DataStore.PARKED;
        boolean pending = info.status == DataStore.PENDING;
        int height = parked ? 96 : 80;
        Park.Card row = new Park.Card(new BorderLayout(8, 0), 14);
        row.setFill(Park.BG_PANEL); row.setBorder(Park.pad(8, 12, 8, 10));
        row.setPreferredSize(new Dimension(10, height)); row.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        Color c = parked ? Park.SLOT_OCCUPIED : pending ? Park.WARNING : Park.SLOT_RESERVED;
        String word = parked ? (info.hasLeaveRequest() ? "Leaving (payment sent)" : "Parked") : pending ? "Awaiting admin" : "Reserved";

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
        if (parked && info.hasLeaveRequest())
                          { label = "Waiting";  fill = Park.BTN_IDLE; fg = Park.TEXT_PRIMARY; }
        else if (parked)  { label = "Leave";    fill = Park.SLOT_OCCUPIED; fg = Color.WHITE; }
        else if (pending) { label = "Cancel";   fill = Park.BTN_IDLE; fg = Park.TEXT_PRIMARY; }
        else if (info.canCheckIn(System.currentTimeMillis()))
                          { label = "Park Now"; fill = Park.ACCENT; fg = Color.WHITE; }
        else              { label = "Manage";   fill = Park.BTN_IDLE; fg = Park.TEXT_PRIMARY; }
        Park.RoundButton btn = new Park.RoundButton(label, fill, fg);
        if (fill.equals(Park.BTN_IDLE)) btn.setOutline(Park.BORDER);
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
        row.setFill(Park.FINE_BG); row.setLine(Park.DANGER);
        row.setBorder(Park.pad(8, 12, 8, 10));
        row.setPreferredSize(new Dimension(10, 62)); row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        String detail = "Leave penalty".equals(f.kind) ? "Leave button misuse"
                      : "PWD violation".equals(f.kind) ? "Parked in a PWD slot" : f.overtimeHours + "h over";
        JPanel text = new JPanel(); text.setOpaque(false); text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(Park.lbl(("Overtime".equals(f.kind) ? "Overtime fee" : f.kind) + "  " + Park.money(f.fee), Park.bold(13), Park.DANGER));
        text.add(Park.lbl("Slot " + f.slot + "  \u00B7  " + Park.shortDate(f.date) + "  \u00B7  "
                + detail, Park.plain(11), Park.TEXT_MUTED));
        if (f.payPending) text.add(Park.lbl("Payment sent - admin is checking", Park.plain(11), Park.WARNING));

        Park.RoundButton pay = new Park.RoundButton(f.payPending ? "Waiting" : "Pay", Park.ACCENT, Color.WHITE);
        pay.setFont(Park.bold(11)); pay.setRadius(10); pay.setPreferredSize(new Dimension(70, 30));
        pay.setEnabled(!f.payPending);
        pay.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { payFee(f); }
        });
        JPanel east = new JPanel(new GridBagLayout()); east.setOpaque(false); east.add(pay);

        row.add(text, BorderLayout.CENTER); row.add(east, BorderLayout.EAST);
        return row;
    }

    private void payFee(DataStore.Fine f) {
        PayDialog d = new PayDialog(this, "Pay " + f.kind,
                "Slot " + f.slot + "  \u00B7  " + f.date + "\nThe admin validates your payment before it is final.",
                f.fee, true);
        d.setVisible(true);
        if (!d.submitted) return;
        String err = DataStore.submitFinePayment(f, d.mode, d.ref, d.receipt);
        if (err != null) {
            JOptionPane.showMessageDialog(this, err, "Could Not Send Payment", JOptionPane.WARNING_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this, "Payment sent. The admin will validate it and you will be notified.",
                    "Payment Sent", JOptionPane.INFORMATION_MESSAGE);
        }
        refreshMine();
    }

    // ---------- live parking timer ----------
    private void tickLive() {
        long now = System.currentTimeMillis();
        for (LiveRow lr : live) {
            long rem = lr.info.remainingMillis(now);
            if (lr.info.hasLeaveRequest()) {
                lr.lbl.setText("Overtime payment sent - waiting for admin");
                lr.lbl.setForeground(Park.WARNING);
            } else if (rem >= 0) {
                lr.lbl.setText(Park.hms(rem) + " left");
                lr.lbl.setForeground(rem <= 5 * 60000L ? Park.WARNING : Park.SUCCESS);
            } else {
                double fee = lr.info.overtimeFee(now);
                lr.lbl.setText("+" + Park.hms(-rem) + " over  \u00B7  " + (fee > 0 ? Park.money(fee) : "grace"));
                lr.lbl.setForeground(Park.DANGER);
            }
        }
        // heads-up when the paid time is almost over
        for (DataStore.ReservationInfo r : DataStore.getReservationsForUser(username)) {
            long left = r.remainingMillis(now);
            if (r.status != DataStore.PARKED || r.hasLeaveRequest() || left <= 0 || left > ALMOST_OVER_MS) continue;
            if (warned5.add(r.id)) {
                int mins = (int) ((left + 59999L) / 60000L);
                Toolkit.getDefaultToolkit().beep();
                notice("Your Parking Time Is Almost Over",
                    "Only " + mins + " minute" + (mins == 1 ? "" : "s") + " left in slot " + r.slot + " (plate " + Fmt.formatPlate(r.plate) + ").\n"
                    + "Paid time ends at " + Fmt.time(Fmt.minuteOfDay(r.endMillis)) + ".\n\n"
                    + "Please drive out and press Leave in My Parking before then.\n"
                    + "After a " + DataStore.GRACE_MINUTES + "-minute grace period, every extra hour is billed at " + Park.money(r.overtimeRate()) + ".",
                    JOptionPane.WARNING_MESSAGE);
            }
        }
        for (DataStore.ReservationInfo r : DataStore.getReservationsForUser(username)) {
            if (r.status != DataStore.PARKED || r.remainingMillis(now) > 0) continue;
            if (notified.add(r.id)) {
                notice("Parking Time Is Up",
                    "Your paid parking time for slot " + r.slot + " has ended.\n"
                    + "After a " + DataStore.GRACE_MINUTES + "-minute grace period, every extra hour is billed at "
                    + Park.money(r.overtimeRate()) + ".\nTo leave, pay the extra charge under My Parking and wait for the admin to validate it.",
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
    private void onSlotClick(String date, String slot) {
        long now = System.currentTimeMillis();
        DataStore.ReservationInfo act = DataStore.activeBooking(date, slot);
        if (act != null) {
            if (act.user.equals(username)) { manageBooking(act.id); return; }
            boolean parked = act.status == DataStore.PARKED;
            String line = parked
                ? "A car is parked here" + (act.remainingMillis(now) >= 0
                    ? ". Paid time ends in " + Park.hms(act.remainingMillis(now)) + "."
                    : " and has stayed past its paid time.")
                : "It is " + (act.status == DataStore.PENDING ? "waiting for payment approval" : "reserved")
                    + " (" + Fmt.timeRange(act.startMin, act.endMin()) + ").";
            JOptionPane.showMessageDialog(this,
                "Slot " + slot + " is not available on " + date + ".\n" + line + "\n\n"
                + "A booked slot is closed to other drivers for the whole day, so a car that stays longer\n"
                + "than paid can never overlap another booking. Please choose another slot.",
                "Slot " + slot + " Unavailable", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (Park.isPwdSlot(slot) && !DataStore.isPriorityUser(username)) {
            int st = DataStore.priorityStatus(username);
            JOptionPane.showMessageDialog(this,
                "Slot " + slot + " is a PWD / senior citizen slot, reserved for drivers with a verified PWD or senior citizen ID.\n\n"
                + (st == DataStore.P_PENDING ? "Your ID is waiting for the admin to verify it.\n"
                   : "Register your ID (top right of the map) and wait for the admin to verify it.\n")
                + "Parking in these slots without a verified ID is charged " + Park.money(Park.PWD_VIOLATION) + ".",
                "PWD / Senior Citizen Slot", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!ensurePlate()) return;
        openBooking(date, slot);
    }

    private void openBooking(String date, String slot) {
        boolean today = date.equals(Park.today());
        int nowMin = Fmt.minuteOfDay(System.currentTimeMillis());
        boolean canStart = !DataStore.availableStarts(date, slot, Fmt.startFloor(date)).isEmpty();
        boolean canParkNow = today && DataStore.maxHours(date, slot, nowMin, true, null) >= 1;
        if (!canStart && !canParkNow) {
            JOptionPane.showMessageDialog(this, "Slot " + slot + " has no free time left on " + date + ".",
                "Slot Unavailable", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int before = DataStore.getReservationsForUser(username).size();
        BookingDialog dlg = new BookingDialog(this, username, date, slot, plate);
        dlg.setVisible(true);
        if (DataStore.getReservationsForUser(username).size() > before) showPage("mine");
        if (dlg.chosenPlate() != null) { plate = dlg.chosenPlate(); updatePlateLabel(); }
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
            if (info.hasLeaveRequest()) {
                Object[] opts = {"Cancel Payment", "Close"};
                int ch = JOptionPane.showOptionDialog(this,
                    "You sent an overtime payment of " + Park.money(info.leaveFee) + " (" + info.leaveMode
                    + (info.leaveRef.isEmpty() ? "" : ", ref. no. " + info.leaveRef) + ").\n"
                    + "The admin has to validate it before you can leave. This screen updates by itself.\n\n"
                    + "Cancel the payment request instead?", "Waiting for the Admin",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[1]);
                if (ch == 0) DataStore.clearLeaveRequest(id);
                refreshAll();
                return;
            }
            double fee = info.overtimeFee(now);
            if (fee > 0) {
                String msg = "Slot " + slot + " on " + date + "\n\n"
                    + "Paid time:       " + info.hours + "h\n"
                    + "Time parked:    " + Park.hms(now - info.startMillis) + "\n"
                    + "Overtime:         " + Park.hms(info.overStayMillis(now)) + "  ("
                    + info.billableHours(now) + "h billed at " + Park.money(info.overtimeRate()) + ")\n\n"
                    + "Total extra charge: " + Park.money(fee) + "\n\n"
                    + "Pay the total extra charge first. The admin must validate your payment\n"
                    + "before you are approved to leave.";
                Object[] opts = {"Pay " + Park.money(fee), "Keep Parking"};
                int ch = JOptionPane.showOptionDialog(this, msg, "Overtime Charge",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
                if (ch == 0) {
                    PayDialog d = new PayDialog(this, "Overtime Payment",
                        "Slot " + slot + "  \u00B7  " + info.billableHours(now) + "h overtime\nThe admin validates it before you can leave.",
                        fee, true);
                    d.setVisible(true);
                    if (d.submitted) {
                        String err = DataStore.requestLeave(id, d.mode, d.ref, fee, d.receipt);
                        if (err != null) JOptionPane.showMessageDialog(this, err, "Could Not Send Payment", JOptionPane.WARNING_MESSAGE);
                        else JOptionPane.showMessageDialog(this,
                            "Payment sent to the admin.\nYou will be notified when it is validated and you may leave.",
                            "Payment Sent", JOptionPane.INFORMATION_MESSAGE);
                    }
                }
            } else {
                int ch = JOptionPane.showConfirmDialog(this,
                    "Leave slot " + slot + "?\nThe slot will become available for other drivers.\n\n"
                    + "Only press Leave when you are driving out. If your car is still in the slot, you will be\n"
                    + "charged a " + Park.money(Park.LEAVE_PENALTY) + " penalty. The admin is notified when you leave.",
                    "Leave Parking", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ch == JOptionPane.YES_OPTION) {
                    seen.remove(id);
                    String err = DataStore.leave(id);
                    if (err != null) JOptionPane.showMessageDialog(this, err, "Cannot Leave", JOptionPane.WARNING_MESSAGE);
                }
            }
        } else if (info.status == DataStore.PENDING) {
            Object[] opts = {"Cancel Request", "Keep Waiting"};
            int ch = JOptionPane.showOptionDialog(this,
                "Your payment for slot " + slot + " (" + info.hours + "h, " + Park.money(info.price) + ", "
                + (info.refNo.isEmpty() ? "counter" : "ref. no. " + info.refNo) + ") is waiting for the admin to validate it.\n"
                + (info.refNo.isEmpty() ? "Pay " + Park.money(info.price) + " at the counter. " : "")
                + "This screen updates by itself and you will be notified once it is approved.\n\nCancel the request instead?",
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
}