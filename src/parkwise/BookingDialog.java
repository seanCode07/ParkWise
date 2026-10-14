package parkwise;

import java.awt.*;
import java.awt.event.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.swing.*;

/* =====================================================================
   BookingDialog : booking type + start time + duration -> payment -> (QR) -> receipt
   Several drivers can book the same slot as long as their times do not overlap.
   ===================================================================== */
class BookingDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final String username, date, slot, plate;
    private final boolean isToday, canParkNow;
    private int hours = 1;
    private int maxHours = 1;
    private int startMin = 0;
    private int bookingStatus = 1;          // 1 = reserve for a time, 2 = park now
    private final List<Integer> starts = new ArrayList<Integer>();

    private JLabel sumType, sumTime, sumHours, sumTotal, hoursLbl, noteLbl, timerLbl, bookedLbl, limitLbl, startNowLbl, startTitle;
    private JComboBox<String> startBox;
    private Park.RoundButton reserveBtn, parkBtn, paidBtn;
    private Park.Bar bar;
    private CardLayout steps;
    private JPanel stepPanel, qrHolder, receiptHolder;
    private javax.swing.Timer timer;
    private int countdown = 60;
    private boolean loading = false;

    BookingDialog(Frame owner, String username, String date, String slot, String plate) {
        super(owner, "ParkWise - Book Slot " + slot, true);
        this.username = username; this.date = date; this.slot = slot; this.plate = plate;
        this.isToday = date.equals(Park.today());
        this.canParkNow = isToday && !DataStore.hasParkedCar(slot)
                && DataStore.maxHours(date, slot, Fmt.minuteOfDay(System.currentTimeMillis()), true, null) >= 1;
        Park.applyIcon(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(880, 700); setLocationRelativeTo(owner); setResizable(false);
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
        reloadOptions();
        steps.show(stepPanel, "form");
    }

    private JComponent left(JComponent c) { c.setAlignmentX(Component.LEFT_ALIGNMENT); return c; }

    // ---------- summary panel ----------
    private JPanel buildSummary() {
        Park.Card c = new Park.Card(new BorderLayout(), 22);
        c.setBorder(Park.pad(24, 24, 24, 24));
        c.setPreferredSize(new Dimension(250, 100));
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        col.add(left(Park.lbl("PARKING SLOT", Park.bold(11), Park.TEXT_MUTED)));
        col.add(left(Park.lbl(slot, Park.bold(56), Park.ACCENT)));
        col.add(Box.createVerticalStrut(2));
        col.add(left(Park.lbl(date, Park.plain(13), Park.TEXT_PRIMARY)));
        col.add(Box.createVerticalStrut(18));

        sumType = Park.lbl(" ", Park.bold(16), Park.TEXT_PRIMARY);
        sumTime = Park.lbl(" ", Park.bold(14), Park.TEXT_PRIMARY);
        sumHours = Park.lbl(" ", Park.bold(16), Park.TEXT_PRIMARY);
        sumTotal = Park.lbl(" ", Park.bold(30), Park.ACCENT);
        col.add(left(Park.lbl("BOOKING TYPE", Park.bold(11), Park.TEXT_MUTED))); col.add(left(sumType));
        col.add(Box.createVerticalStrut(12));
        col.add(left(Park.lbl("TIME", Park.bold(11), Park.TEXT_MUTED))); col.add(left(sumTime));
        col.add(Box.createVerticalStrut(12));
        col.add(left(Park.lbl("DURATION", Park.bold(11), Park.TEXT_MUTED))); col.add(left(sumHours));
        col.add(Box.createVerticalStrut(12));
        col.add(left(Park.lbl("PLATE NUMBER", Park.bold(11), Park.TEXT_MUTED)));
        col.add(left(Park.lbl(Fmt.formatPlate(plate), Park.bold(16), Park.TEXT_PRIMARY)));
        col.add(Box.createVerticalStrut(12));
        col.add(left(Park.lbl("RATE", Park.bold(11), Park.TEXT_MUTED)));
        col.add(left(Park.lbl(Park.money(Park.RATE) + " / hour", Park.bold(16), Park.TEXT_PRIMARY)));
        col.add(Box.createVerticalStrut(18));
        col.add(left(Park.lbl("TOTAL", Park.bold(11), Park.TEXT_MUTED))); col.add(left(sumTotal));
        c.add(col, BorderLayout.NORTH);
        return c;
    }

    private void updateSummary() {
        sumType.setText(bookingStatus == 1 ? "Reserve" : "Park Now");
        sumType.setForeground(bookingStatus == 1 ? Park.SLOT_RESERVED : Park.SLOT_OCCUPIED);
        sumTime.setText(Fmt.timeRange(startMin, startMin + hours * 60));
        sumHours.setText(hours + (hours == 1 ? " hour" : " hours"));
        sumTotal.setText(Park.money(total()));
    }

    // ---------- step 1: details ----------
    private JPanel buildFormStep() {
        Park.Card c = new Park.Card(new BorderLayout(), 22);
        c.setBorder(Park.pad(26, 30, 26, 30));
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        col.add(left(Park.lbl("Booking Details", Park.bold(24), Park.TEXT_PRIMARY)));
        col.add(left(Park.lbl("Choose how and when you want to use this slot.", Park.plain(13), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(18));
        col.add(left(Park.lbl("BOOKING TYPE", Park.bold(11), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(8));

        reserveBtn = new Park.RoundButton("Reserve a Time", Park.SLOT_RESERVED, Park.BG_DARK);
        parkBtn = new Park.RoundButton("Park Now", new Color(30, 30, 62), Park.TEXT_MUTED);
        reserveBtn.setFont(Park.bold(14)); parkBtn.setFont(Park.bold(14));
        reserveBtn.setRadius(14); parkBtn.setRadius(14);
        reserveBtn.addActionListener(Fmt.act(new Runnable() { public void run() { selectType(1); } }));
        parkBtn.addActionListener(Fmt.act(new Runnable() { public void run() { selectType(2); } }));
        JPanel typeRow = new JPanel(new GridLayout(1, 2, 12, 0)); typeRow.setOpaque(false);
        typeRow.add(reserveBtn); typeRow.add(parkBtn);
        typeRow.setPreferredSize(new Dimension(300, 50)); typeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        col.add(left(typeRow));
        col.add(Box.createVerticalStrut(8));
        noteLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        col.add(left(noteLbl));
        col.add(Box.createVerticalStrut(16));

        // start time
        startTitle = Park.lbl("START TIME", Park.bold(11), Park.TEXT_MUTED);
        col.add(left(startTitle));
        col.add(Box.createVerticalStrut(6));
        startBox = new JComboBox<String>();
        Fmt.styleCombo(startBox);
        startBox.setPreferredSize(new Dimension(220, 40)); startBox.setMaximumSize(new Dimension(220, 40));
        startBox.addActionListener(Fmt.act(new Runnable() {
            public void run() {
                if (loading || bookingStatus != 1) return;
                int i = startBox.getSelectedIndex();
                if (i >= 0 && i < starts.size()) { startMin = starts.get(i); recalcLimit(); }
            }
        }));
        col.add(left(startBox));
        startNowLbl = Park.lbl(" ", Park.bold(16), Park.ACCENT);
        col.add(left(startNowLbl));
        col.add(Box.createVerticalStrut(6));
        bookedLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        col.add(left(bookedLbl));
        col.add(Box.createVerticalStrut(16));

        col.add(left(Park.lbl("DURATION", Park.bold(11), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(8));
        Park.RoundButton minus = new Park.RoundButton("-", new Color(30, 30, 62), Park.TEXT_PRIMARY);
        Park.RoundButton plus  = new Park.RoundButton("+", new Color(30, 30, 62), Park.TEXT_PRIMARY);
        minus.setFont(Park.bold(20)); plus.setFont(Park.bold(20));
        minus.setOutline(Park.BORDER); plus.setOutline(Park.BORDER);
        minus.setPreferredSize(new Dimension(44, 44)); plus.setPreferredSize(new Dimension(44, 44));
        hoursLbl = Park.lbl("1 hour", Park.bold(22), Park.ACCENT);
        hoursLbl.setPreferredSize(new Dimension(110, 44)); hoursLbl.setHorizontalAlignment(SwingConstants.CENTER);
        minus.addActionListener(Fmt.act(new Runnable() {
            public void run() { if (hours > 1) { hours--; updateHours(); } }
        }));
        plus.addActionListener(Fmt.act(new Runnable() {
            public void run() { if (hours < maxHours) { hours++; updateHours(); } }
        }));
        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0)); ctrl.setOpaque(false);
        ctrl.add(minus); ctrl.add(hoursLbl); ctrl.add(plus);
        ctrl.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        col.add(left(ctrl));
        col.add(Box.createVerticalStrut(12));
        bar = new Park.Bar();
        bar.setPreferredSize(new Dimension(300, 8)); bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
        col.add(left(bar));
        col.add(Box.createVerticalStrut(6));
        limitLbl = Park.lbl(" ", Park.plain(12), Park.TEXT_MUTED);
        col.add(left(limitLbl));
        col.add(Box.createVerticalGlue());

        Park.RoundButton go = Park.primaryBtn("Continue to Payment");
        go.setPreferredSize(new Dimension(300, 50)); go.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        go.addActionListener(Fmt.act(new Runnable() {
            public void run() {
                if (bookingStatus == 1 && starts.isEmpty()) return;
                updatePayOptions();
                steps.show(stepPanel, "pay");
            }
        }));
        col.add(left(go));
        c.add(col, BorderLayout.CENTER);
        return c;
    }

    /** Re-reads what is free (also used after someone else grabbed the time) and refreshes the form. */
    private void reloadOptions() {
        starts.clear();
        starts.addAll(DataStore.availableStarts(date, slot, Fmt.startFloor(date)));
        loading = true;
        startBox.removeAllItems();
        for (int s : starts) startBox.addItem(Fmt.time(s));
        loading = false;

        // list who is already booked (times only, no names)
        List<DataStore.ReservationInfo> list = DataStore.getBookings(date, slot);
        if (list.isEmpty()) {
            bookedLbl.setText("Nobody has booked this slot on this date yet.");
        } else {
            String[] ls = new String[Math.min(list.size(), 4) + 1];
            ls[0] = "Already booked on this slot:";
            for (int i = 0; i < ls.length - 1; i++) {
                DataStore.ReservationInfo r = list.get(i);
                ls[i + 1] = "   " + Fmt.timeRange(r.startMin, r.endMin());
            }
            if (list.size() > 4) ls[ls.length - 1] += "   ... and " + (list.size() - 4) + " more";
            bookedLbl.setText(Park.lines(ls));
        }

        reserveBtn.setEnabled(!starts.isEmpty());
        parkBtn.setEnabled(canParkNow);
        parkBtn.setToolTipText(canParkNow ? null
            : "Park Now is only available today, while the slot is free right now.");
        if (bookingStatus == 2 && !canParkNow) bookingStatus = 1;
        if (bookingStatus == 1 && starts.isEmpty() && canParkNow) bookingStatus = 2;
        selectType(bookingStatus);
    }

    private void recalcLimit() {
        boolean immediate = bookingStatus == 2;
        maxHours = Math.max(1, DataStore.maxHours(date, slot, startMin, immediate, null));
        if (hours > maxHours) hours = maxHours;
        limitLbl.setText("Up to " + maxHours + (maxHours == 1 ? " hour" : " hours")
                + " fit" + (maxHours == 1 ? "s" : "") + " from this start time.");
        updateHours();
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
                "Only your chosen time is held. Other drivers can book the",
                "same slot at different times. Reservations are paid by QR code."));
            startTitle.setText("START TIME");
            startBox.setVisible(true); startNowLbl.setText(" "); startNowLbl.setVisible(false);
            if (!starts.isEmpty()) {
                int i = Math.max(0, startBox.getSelectedIndex());
                if (i >= starts.size()) i = 0;
                loading = true; startBox.setSelectedIndex(i); loading = false;
                startMin = starts.get(i);
            }
        } else {
            parkBtn.setFill(Park.SLOT_OCCUPIED);     parkBtn.setForeground(Color.WHITE);
            reserveBtn.setFill(new Color(30, 30, 62)); reserveBtn.setForeground(Park.TEXT_MUTED);
            noteLbl.setText(Park.lines(
                "Your time starts right away. Pay by QR code, or at the counter",
                "(the admin approves it first). Overtime is " + Park.money(Park.RATE) + "/hr after "
                    + DataStore.GRACE_MINUTES + " min."));
            startTitle.setText("START TIME");
            startMin = Fmt.minuteOfDay(System.currentTimeMillis());
            startBox.setVisible(false);
            startNowLbl.setText("Now  (" + Fmt.time(startMin) + ")"); startNowLbl.setVisible(true);
        }
        if (!starts.isEmpty() || bookingStatus == 2) recalcLimit(); else updateSummary();
    }

    // ---------- step 2: payment method ----------
    private Park.Card counterCard;
    private JLabel payNote;

    private JPanel buildPayStep() {
        Park.Card c = new Park.Card(new BorderLayout(), 22);
        c.setBorder(Park.pad(28, 30, 28, 30));
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        col.add(left(Park.lbl("Payment Method", Park.bold(24), Park.TEXT_PRIMARY)));
        col.add(left(Park.lbl("Select how you would like to pay.", Park.plain(13), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(8));
        payNote = Park.lbl(" ", Park.plain(12), Park.WARNING);
        col.add(left(payNote));
        col.add(Box.createVerticalStrut(18));
        col.add(optionCard("QR Code Payment", "InstaPay: any participating bank or e-wallet app", Park.ACCENT, new Runnable() {
            public void run() { showQr(); }
        }));
        col.add(Box.createVerticalStrut(14));
        counterCard = optionCard("Pay at Counter", "Pay cash or card, then wait for the admin to approve", Park.SUCCESS, new Runnable() {
            public void run() { confirmBooking("Counter"); }
        });
        col.add(counterCard);
        col.add(Box.createVerticalGlue());

        Park.RoundButton back = Park.secondaryBtn("Back");
        back.setPreferredSize(new Dimension(300, 46)); back.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        back.addActionListener(Fmt.act(new Runnable() { public void run() { steps.show(stepPanel, "form"); } }));
        col.add(left(back));
        c.add(col, BorderLayout.CENTER);
        return c;
    }

    /** Reservations are made ahead of time, so they can only be paid online. Counter payment is for Park Now. */
    private void updatePayOptions() {
        boolean counterOk = bookingStatus == 2;
        counterCard.putClientProperty("disabled", !counterOk);
        JLabel tl = (JLabel) counterCard.getClientProperty("title");
        JLabel sl = (JLabel) counterCard.getClientProperty("sub");
        tl.setForeground(counterOk ? Park.TEXT_PRIMARY : Park.TEXT_MUTED);
        sl.setText(counterOk ? "Pay cash or card, then wait for the admin to approve" : "Not available for reservations");
        counterCard.setFill(counterOk ? Park.BG_PANEL : new Color(14, 14, 32, 120));
        counterCard.setLine(Park.BORDER);
        counterCard.setCursor(counterOk ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        payNote.setText(counterOk ? " " : "Reservations must be paid online with the QR code.");
    }

    private Park.Card optionCard(String title, String sub, final Color accent, final Runnable action) {
        final Park.Card oc = new Park.Card(new BorderLayout(), 16);
        oc.setFill(Park.BG_PANEL);
        oc.setBorder(Park.pad(14, 18, 14, 18));
        JPanel t = new JPanel(); t.setOpaque(false); t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        JLabel tl = Park.lbl(title, Park.bold(16), Park.TEXT_PRIMARY);
        JLabel sl = Park.lbl(sub, Park.plain(12), Park.TEXT_MUTED);
        t.add(tl); t.add(sl);
        oc.putClientProperty("title", tl);
        oc.putClientProperty("sub", sl);
        oc.add(t, BorderLayout.CENTER);
        oc.setPreferredSize(new Dimension(300, 76)); oc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));
        oc.setAlignmentX(Component.LEFT_ALIGNMENT);
        oc.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        oc.addMouseListener(new MouseAdapter() {
            private boolean off() { return Boolean.TRUE.equals(oc.getClientProperty("disabled")); }
            public void mouseEntered(MouseEvent e) { if (!off()) oc.setLine(accent); }
            public void mouseExited(MouseEvent e)  { oc.setLine(Park.BORDER); }
            public void mouseClicked(MouseEvent e) { if (!off()) action.run(); }
        });
        return oc;
    }

    // ---------- step 3: QR ----------
    private void showQr() {
        qrHolder.removeAll();
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        JLabel t = Park.lbl("Scan QR Code to Pay", Park.bold(22), Park.TEXT_PRIMARY); t.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel am = Park.lbl("Amount due:  " + Park.money(total()), Park.bold(15), Park.ACCENT); am.setAlignmentX(Component.CENTER_ALIGNMENT);
        Park.ImgView qr = new Park.ImgView(Park.payQr(), 232, 232, true);
        qr.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel inst = Park.lbl(Park.lines("Scan with any InstaPay-enabled bank or e-wallet app,",
                "then send exactly " + Park.money(total()) + "."), Park.plain(12), Park.TEXT_MUTED);
        inst.setHorizontalAlignment(SwingConstants.CENTER);
        inst.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel timerRow = new JPanel(); timerRow.setOpaque(false);
        timerRow.add(Park.lbl("QR expires in  ", Park.plain(12), Park.TEXT_MUTED));
        timerLbl = Park.lbl("60s", Park.bold(12), Park.WARNING); timerRow.add(timerLbl);
        timerRow.setAlignmentX(Component.CENTER_ALIGNMENT);

        paidBtn = Park.primaryBtn("I've Paid - Show Receipt");
        paidBtn.setPreferredSize(new Dimension(340, 46)); paidBtn.setMaximumSize(new Dimension(340, 46));
        paidBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        paidBtn.addActionListener(Fmt.act(new Runnable() {
            public void run() { stopTimer(); confirmBooking("InstaPay QR"); }
        }));
        Park.RoundButton cancel = Park.secondaryBtn("Cancel");
        cancel.setPreferredSize(new Dimension(340, 40)); cancel.setMaximumSize(new Dimension(340, 40));
        cancel.setAlignmentX(Component.CENTER_ALIGNMENT);
        cancel.addActionListener(Fmt.act(new Runnable() {
            public void run() { stopTimer(); steps.show(stepPanel, "pay"); }
        }));

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
        if (bookingStatus == 1 && "Counter".equals(mode)) return;   // reservations are online-only
        int status;
        if (bookingStatus == 1) status = DataStore.RESERVED;
        else if ("Counter".equals(mode)) status = DataStore.PENDING;   // admin must approve first
        else status = DataStore.PARKED;                                // paid online: timer starts now

        DataStore.ReservationInfo info = new DataStore.ReservationInfo(username, date, slot, startMin, hours,
                total(), status, mode, plate, bookingStatus == 2);
        String err = DataStore.addBooking(info);
        if (err != null) {
            JOptionPane.showMessageDialog(this, err, "Could Not Book", JOptionPane.WARNING_MESSAGE);
            reloadOptions();
            steps.show(stepPanel, "form");
            return;
        }
        startMin = info.startMin;       // Park Now uses the exact minute the booking was made
        buildReceipt(mode, status);
        steps.show(stepPanel, "receipt");
    }

    private void buildReceipt(String mode, int status) {
        receiptHolder.removeAll();
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        String head; Color headColor; String subText;
        if (status == DataStore.RESERVED) {
            head = "Slot Reserved!"; headColor = Park.SUCCESS;
            subText = "Slot " + slot + " is yours on " + date + ", " + Fmt.timeRange(startMin, startMin + hours * 60) + ".";
        } else if (status == DataStore.PENDING) {
            head = "Request Sent!"; headColor = Park.WARNING;
            subText = "Pay " + Park.money(total()) + " at the counter. You can park once the admin approves.";
        } else {
            head = "You're Parked!"; headColor = Park.SUCCESS;
            subText = "Slot " + slot + " is yours. Use Leave in My Parking when you are done.";
        }
        JLabel conf = Park.lbl(head, Park.bold(24), headColor);
        conf.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sub = Park.lbl(subText, Park.plain(12), Park.TEXT_MUTED);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel receipt = buildReceiptCard(mode, status); receipt.setAlignmentX(Component.CENTER_ALIGNMENT);

        Park.RoundButton done = Park.primaryBtn("Done");
        done.setPreferredSize(new Dimension(380, 46)); done.setMaximumSize(new Dimension(380, 46));
        done.setAlignmentX(Component.CENTER_ALIGNMENT);
        done.addActionListener(Fmt.act(new Runnable() { public void run() { dispose(); } }));

        col.add(conf); col.add(Box.createVerticalStrut(4)); col.add(sub); col.add(Box.createVerticalStrut(14));
        col.add(receipt); col.add(Box.createVerticalGlue()); col.add(done);
        receiptHolder.add(col, BorderLayout.CENTER);
        receiptHolder.revalidate(); receiptHolder.repaint();
    }

    private JPanel buildReceiptCard(String mode, final int status) {
        JPanel card = new JPanel() {
            private static final long serialVersionUID = 1L;
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = Park.aa(g);
                g2.setColor(new Color(255, 250, 235)); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.setColor(status == DataStore.RESERVED ? Park.SLOT_RESERVED
                          : status == DataStore.PENDING ? Park.WARNING : Park.SLOT_OCCUPIED);
                g2.fillRect(0, 0, getWidth(), 5);
                g2.setColor(new Color(200, 185, 130));
                g2.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 6}, 0));
                g2.drawLine(18, getHeight() - 44, getWidth() - 18, getHeight() - 44);
                g2.dispose();
            }
        };
        card.setOpaque(false); card.setLayout(new BorderLayout());
        card.setPreferredSize(new Dimension(380, 372)); card.setMaximumSize(new Dimension(380, 372));
        card.setBorder(Park.pad(14, 20, 12, 20));

        JPanel rows = new JPanel(new GridLayout(12, 2, 6, 2)); rows.setOpaque(false);
        String txn = "TXN" + (System.currentTimeMillis() % 100000);
        String now = new SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.ENGLISH).format(new Date());
        boolean pending = status == DataStore.PENDING;
        row(rows, "PARKWISE RECEIPT", "", true);
        row(rows, "Transaction ID:", txn, false);  row(rows, "Issued:", now, false);
        row(rows, "Parking Date:", date, false);
        row(rows, "Time:", Fmt.timeRange(startMin, startMin + hours * 60), false);
        row(rows, "Slot:", slot, false);
        row(rows, "Plate Number:", Fmt.formatPlate(plate), false);
        row(rows, "Booking Type:", status == DataStore.RESERVED ? "RESERVED" : pending ? "PENDING APPROVAL" : "OCCUPIED", false);
        row(rows, "Duration:", hours + (hours == 1 ? " hour" : " hours"), false);
        row(rows, "Rate:", Park.money(Park.RATE) + "/hr", false);
        row(rows, "Payment:", pending ? "Counter (to be approved)" : mode, false);
        row(rows, pending ? "TOTAL DUE:" : "TOTAL PAID:", Park.money(total()), true);
        card.add(rows, BorderLayout.CENTER);

        JLabel footer = new JLabel("Thank you for using ParkWise!", SwingConstants.CENTER);
        footer.setFont(Park.plain(11).deriveFont(Font.ITALIC)); footer.setForeground(new Color(140, 120, 70));
        footer.setPreferredSize(new Dimension(100, 30));
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    private void row(JPanel grid, String k, String v, boolean bold) {
        JLabel kl = new JLabel(k);
        kl.setFont(bold ? Park.bold(12) : Park.plain(11));
        kl.setForeground(bold ? Park.BG_DARK : new Color(110, 95, 55));
        JLabel vl = new JLabel(v, SwingConstants.RIGHT);
        vl.setFont(bold ? Park.bold(13) : Park.bold(11));
        vl.setForeground(bold ? new Color(0, 130, 80) : Park.BG_DARK);
        grid.add(kl); grid.add(vl);
    }
}