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
   BookingDialog : booking type + start time + duration -> payment -> (QR + reference number) -> receipt
   Every booking is sent to the admin as a pending request. The admin validates the payment first;
   the driver is then notified and marked as Reserved (reservation) or Parked (Park Now).
   A slot that is already reserved or occupied cannot be booked by anyone else on that date.
   ===================================================================== */
class BookingDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private final String username, date, slot;
    private String plate;
    private final boolean isToday, canParkNow;
    private final boolean priority;          // verified PWD / senior citizen: discounted reservations
    private int hours = 1;
    private int maxHours = 1;
    private int startMin = 0;
    private int bookingStatus = 1;          // 1 = reserve for a time, 2 = park now
    private final List<Integer> starts = new ArrayList<Integer>();

    private JLabel sumType, sumTime, sumHours, sumTotal, sumRate, sumPlate, hoursLbl, noteLbl, bookedLbl, limitLbl, startNowLbl, startTitle;
    private JLabel qrError;
    private JTextField refField;
    private AttachPanel qrAttach;
    private JComboBox<String> startBox;
    private Park.RoundButton reserveBtn, parkBtn, paidBtn;
    private Park.Bar bar;
    private CardLayout steps;
    private JPanel stepPanel, qrHolder, receiptHolder;
    private boolean loading = false;

    BookingDialog(Frame owner, String username, String date, String slot, String plate) {
        super(owner, "ParkWise - Book Slot " + slot, true);
        this.username = username; this.date = date; this.slot = slot; this.plate = plate;
        this.isToday = date.equals(Park.today());
        this.priority = DataStore.isPriorityUser(username);
        this.canParkNow = isToday
                && DataStore.maxHours(date, slot, Fmt.minuteOfDay(System.currentTimeMillis()), true, null) >= 1;
        Park.applyIcon(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent e) { if (qrAttach != null) qrAttach.clear(); }   // nothing attached stays behind
        });
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(880, Math.min(720, scr.height - 40)); setLocationRelativeTo(owner); setResizable(false);
        buildUI();
    }

    /** The plate that was used (the driver may have switched vehicles inside this dialog). */
    String chosenPlate() { return plate; }

    private double total() { return Park.priceFor(hours, bookingStatus == 1, slot, priority); }

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
        qrHolder.setBorder(Park.pad(20, 30, 20, 30));
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
        col.add(left(Park.lbl(Park.localOf(slot), Park.bold(46), Park.ACCENT)));
        String kind = Park.slotKind(slot);
        col.add(left(Park.lbl(Park.floorName(Park.floorOf(slot)), Park.bold(13), Park.TEXT_PRIMARY)));
        col.add(left(Park.lbl(Character.toUpperCase(kind.charAt(0)) + kind.substring(1), Park.plain(12),
                Park.isPwdSlot(slot) ? new Color(90, 160, 255) : Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(2));
        col.add(left(Park.lbl(date, Park.plain(13), Park.TEXT_PRIMARY)));
        col.add(Box.createVerticalStrut(14));

        sumType = Park.lbl(" ", Park.bold(16), Park.TEXT_PRIMARY);
        sumTime = Park.lbl(" ", Park.bold(14), Park.TEXT_PRIMARY);
        sumHours = Park.lbl(" ", Park.bold(16), Park.TEXT_PRIMARY);
        sumRate = Park.lbl(" ", Park.bold(14), Park.TEXT_PRIMARY);
        sumTotal = Park.lbl(" ", Park.bold(30), Park.ACCENT);
        col.add(left(Park.lbl("BOOKING TYPE", Park.bold(11), Park.TEXT_MUTED))); col.add(left(sumType));
        col.add(Box.createVerticalStrut(10));
        col.add(left(Park.lbl("TIME", Park.bold(11), Park.TEXT_MUTED))); col.add(left(sumTime));
        col.add(Box.createVerticalStrut(10));
        col.add(left(Park.lbl("DURATION", Park.bold(11), Park.TEXT_MUTED))); col.add(left(sumHours));
        col.add(Box.createVerticalStrut(10));
        col.add(left(Park.lbl("PLATE NUMBER", Park.bold(11), Park.TEXT_MUTED)));
        sumPlate = Park.lbl(Fmt.formatPlate(plate), Park.bold(16), Park.TEXT_PRIMARY);
        JLabel change = Park.lbl("Change", Park.bold(12), Park.ACCENT);
        change.setBorder(Park.pad(0, 10, 0, 0));
        change.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        change.setToolTipText("Choose another vehicle or add a new plate number");
        change.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                String p = Fmt.choosePlate(BookingDialog.this, username, plate);
                if (p != null) { plate = p; sumPlate.setText(Fmt.formatPlate(plate)); }
            }
        });
        JPanel plateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); plateRow.setOpaque(false);
        plateRow.add(sumPlate); plateRow.add(change);
        plateRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        col.add(left(plateRow));
        col.add(Box.createVerticalStrut(10));
        col.add(left(Park.lbl("RATE", Park.bold(11), Park.TEXT_MUTED)));
        col.add(left(sumRate));
        col.add(Box.createVerticalStrut(16));
        col.add(left(Park.lbl("TOTAL", Park.bold(11), Park.TEXT_MUTED))); col.add(left(sumTotal));
        c.add(col, BorderLayout.NORTH);
        return c;
    }

    private String rateText() {
        return Park.rateText(slot, bookingStatus == 1, priority);
    }

    private void updateSummary() {
        sumType.setText(bookingStatus == 1 ? "Reserve" : "Park Now");
        sumType.setForeground(bookingStatus == 1 ? Park.SLOT_RESERVED : Park.SLOT_OCCUPIED);
        sumTime.setText(Fmt.timeRange(startMin, startMin + hours * 60));
        sumHours.setText(hours + (hours == 1 ? " hour" : " hours"));
        sumRate.setText(priority && bookingStatus == 1
            ? Park.lines(rateText(), "PWD / senior citizen rate") : rateText());
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
        parkBtn = new Park.RoundButton("Park Now", Park.BTN_IDLE, Park.TEXT_MUTED);
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
        bookedLbl = Park.lbl(Park.lines("Once booked, this slot is closed to other drivers for the",
                "whole day, so an overstaying car can never clash with someone else."),
                Park.plain(12), Park.TEXT_MUTED);
        col.add(left(bookedLbl));
        col.add(Box.createVerticalStrut(16));

        col.add(left(Park.lbl("DURATION", Park.bold(11), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(8));
        Park.RoundButton minus = new Park.RoundButton("-", Park.BTN_IDLE, Park.TEXT_PRIMARY);
        Park.RoundButton plus  = new Park.RoundButton("+", Park.BTN_IDLE, Park.TEXT_PRIMARY);
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

    /** Re-reads what is free (also used after someone else grabbed the slot) and refreshes the form. */
    private void reloadOptions() {
        starts.clear();
        starts.addAll(DataStore.availableStarts(date, slot, Fmt.startFloor(date)));
        loading = true;
        startBox.removeAllItems();
        for (int s : starts) startBox.addItem(Fmt.time(s));
        loading = false;

        reserveBtn.setEnabled(!starts.isEmpty());
        parkBtn.setEnabled(canParkNow);
        parkBtn.setToolTipText(canParkNow ? null
            : "Park Now is only available today, while the slot is free.");
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
            parkBtn.setFill(Park.BTN_IDLE);  parkBtn.setForeground(Park.TEXT_MUTED);
            noteLbl.setText(Park.lines(
                rateText() + (priority ? "  (PWD / senior citizen rate)" : "") + ".",
                "Paid by QR code. The admin validates your reference number first."));
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
            reserveBtn.setFill(Park.BTN_IDLE); reserveBtn.setForeground(Park.TEXT_MUTED);
            noteLbl.setText(Park.lines(
                Park.money(Park.rateOf(slot)) + " per hour. Pay by QR code or at the counter. Your time starts by itself",
                "when the admin approves. Overtime is " + Park.money(Park.rateOf(slot)) + "/hr after "
                    + DataStore.GRACE_MINUTES + " min."));
            startTitle.setText("START TIME");
            startMin = Fmt.minuteOfDay(System.currentTimeMillis());
            startBox.setVisible(false);
            startNowLbl.setText("When approved  (about " + Fmt.time(startMin) + ")"); startNowLbl.setVisible(true);
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
        col.add(optionCard("QR Code Payment", "InstaPay: pay, then type the reference number", Park.ACCENT, new Runnable() {
            public void run() { showQr(); }
        }));
        col.add(Box.createVerticalStrut(14));
        counterCard = optionCard("Pay at Counter", "Pay cash or card, then wait for the admin to approve", Park.SUCCESS, new Runnable() {
            public void run() { confirmBooking(DataStore.MODE_COUNTER, "", ""); }
        });
        col.add(counterCard);
        col.add(Box.createVerticalStrut(14));
        col.add(left(Park.lbl(Park.lines("Whichever you choose, the admin must validate your payment first.",
                "You will be notified as soon as it is approved."), Park.plain(12), Park.TEXT_MUTED)));
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
        counterCard.setFill(counterOk ? Park.BG_PANEL : Park.alpha(Park.BG_PANEL, 120));
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

    // ---------- step 3: QR + reference number ----------
    private void showQr() {
        qrHolder.removeAll();
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        JLabel t = Park.lbl("Scan QR Code to Pay", Park.bold(22), Park.TEXT_PRIMARY); t.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel am = Park.lbl("Amount due:  " + Park.money(total()), Park.bold(15), Park.ACCENT); am.setAlignmentX(Component.CENTER_ALIGNMENT);
        Park.ImgView qr = new Park.ImgView(Park.payQr(), 170, 170, true);
        qr.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel inst = Park.lbl(Park.lines("Scan with any InstaPay-enabled bank or e-wallet app,",
                "send exactly " + Park.money(total()) + ", then type the reference",
                "number from your payment receipt below."), Park.plain(12), Park.TEXT_MUTED);
        inst.setHorizontalAlignment(SwingConstants.CENTER);
        inst.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel rl = Park.lbl("REFERENCE NUMBER", Park.bold(11), Park.TEXT_MUTED);
        rl.setAlignmentX(Component.CENTER_ALIGNMENT);
        refField = Park.field(20);
        refField.setPreferredSize(new Dimension(340, 40)); refField.setMaximumSize(new Dimension(340, 40));
        refField.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel recLbl = Park.lbl("PAYMENT RECEIPT (screenshot or photo)", Park.bold(11), Park.TEXT_MUTED);
        recLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        if (qrAttach != null) qrAttach.clear();
        qrAttach = new AttachPanel("receipt", "Attach receipt", "Required for QR payments");
        qrAttach.setPreferredSize(new Dimension(340, 40)); qrAttach.setMaximumSize(new Dimension(340, 40));
        qrAttach.setAlignmentX(Component.CENTER_ALIGNMENT);
        qrError = Park.lbl(" ", Park.plain(12), Park.DANGER);
        qrError.setAlignmentX(Component.CENTER_ALIGNMENT);

        paidBtn = Park.primaryBtn("Submit for Verification");
        paidBtn.setPreferredSize(new Dimension(340, 46)); paidBtn.setMaximumSize(new Dimension(340, 46));
        paidBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        paidBtn.addActionListener(Fmt.act(new Runnable() {
            public void run() {
                if (!Fmt.validRef(refField.getText())) {
                    qrError.setText("Enter the reference number (8 to 25 letters/digits).");
                    return;
                }
                if (!qrAttach.hasPicture()) {
                    qrError.setText("Attach a screenshot or photo of your payment receipt.");
                    return;
                }
                qrError.setText(" ");
                confirmBooking(DataStore.MODE_QR, Fmt.normalizeRef(refField.getText()), qrAttach.stored());
            }
        }));
        Park.RoundButton cancel = Park.secondaryBtn("Cancel");
        cancel.setPreferredSize(new Dimension(340, 40)); cancel.setMaximumSize(new Dimension(340, 40));
        cancel.setAlignmentX(Component.CENTER_ALIGNMENT);
        cancel.addActionListener(Fmt.act(new Runnable() {
            public void run() { if (qrAttach != null) qrAttach.clear(); steps.show(stepPanel, "pay"); }
        }));

        col.add(t); col.add(Box.createVerticalStrut(4)); col.add(am); col.add(Box.createVerticalStrut(10));
        col.add(qr); col.add(Box.createVerticalStrut(8)); col.add(inst); col.add(Box.createVerticalStrut(10));
        col.add(rl); col.add(Box.createVerticalStrut(4)); col.add(refField); col.add(Box.createVerticalStrut(6));
        col.add(recLbl); col.add(Box.createVerticalStrut(3)); col.add(qrAttach); col.add(Box.createVerticalStrut(2));
        col.add(qrError); col.add(Box.createVerticalGlue());
        col.add(paidBtn); col.add(Box.createVerticalStrut(8)); col.add(cancel);
        qrHolder.add(col, BorderLayout.CENTER);
        qrHolder.revalidate(); qrHolder.repaint();
        steps.show(stepPanel, "qr");
    }

    // ---------- step 4: receipt ----------
    private void confirmBooking(String mode, String ref, String receipt) {
        if (bookingStatus == 1 && DataStore.MODE_COUNTER.equals(mode)) return;   // reservations are online-only

        DataStore.ReservationInfo info = new DataStore.ReservationInfo(username, date, slot, startMin, hours,
                total(), DataStore.PENDING, mode, plate, bookingStatus == 2);
        info.refNo = ref;
        info.receipt = receipt;
        String err = DataStore.addBooking(info);
        if (err != null) {
            JOptionPane.showMessageDialog(this, err, "Could Not Book", JOptionPane.WARNING_MESSAGE);
            if (err.startsWith("That reference")) return;            // stay on the QR step so the number can be fixed
            reloadOptions();
            steps.show(stepPanel, "form");
            return;
        }
        if (qrAttach != null) qrAttach.release();           // the picture now belongs to the booking
        startMin = info.startMin;       // Park Now uses the exact minute the request was made
        buildReceipt(mode, ref);
        steps.show(stepPanel, "receipt");
    }

    private void buildReceipt(String mode, String ref) {
        receiptHolder.removeAll();
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        boolean reserve = bookingStatus == 1;
        JLabel conf = Park.lbl("Request Sent!", Park.bold(24), Park.WARNING);
        conf.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sub = Park.lbl(Park.lines(
                "The admin will validate your payment" + (DataStore.MODE_QR.equals(mode) ? " (ref. no. " + ref + ")" : "") + ".",
                reserve ? "You will be notified, and slot " + slot + " will be marked Reserved for you."
                        : "You will be notified, and your parking time will start by itself."),
                Park.plain(12), Park.TEXT_MUTED);
        sub.setHorizontalAlignment(SwingConstants.CENTER);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel receipt = buildReceiptCard(mode, ref); receipt.setAlignmentX(Component.CENTER_ALIGNMENT);

        Park.RoundButton done = Park.primaryBtn("Done");
        done.setPreferredSize(new Dimension(380, 46)); done.setMaximumSize(new Dimension(380, 46));
        done.setAlignmentX(Component.CENTER_ALIGNMENT);
        done.addActionListener(Fmt.act(new Runnable() { public void run() { dispose(); } }));

        col.add(conf); col.add(Box.createVerticalStrut(4)); col.add(sub); col.add(Box.createVerticalStrut(12));
        col.add(receipt); col.add(Box.createVerticalGlue()); col.add(done);
        receiptHolder.add(col, BorderLayout.CENTER);
        receiptHolder.revalidate(); receiptHolder.repaint();
    }

    private JPanel buildReceiptCard(String mode, String ref) {
        JPanel card = new JPanel() {
            private static final long serialVersionUID = 1L;
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = Park.aa(g);
                g2.setColor(new Color(255, 250, 235)); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g2.setColor(Park.WARNING);
                g2.fillRect(0, 0, getWidth(), 5);
                g2.setColor(new Color(200, 185, 130));
                g2.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 6}, 0));
                g2.drawLine(18, getHeight() - 44, getWidth() - 18, getHeight() - 44);
                g2.dispose();
            }
        };
        card.setOpaque(false); card.setLayout(new BorderLayout());
        card.setPreferredSize(new Dimension(380, 392)); card.setMaximumSize(new Dimension(380, 392));
        card.setBorder(Park.pad(14, 20, 12, 20));

        boolean reserve = bookingStatus == 1;
        JPanel rows = new JPanel(new GridLayout(13, 2, 6, 2)); rows.setOpaque(false);
        String txn = "TXN" + (System.currentTimeMillis() % 100000);
        String now = new SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.ENGLISH).format(new Date());
        row(rows, "PARKWISE RECEIPT", "", true);
        row(rows, "Transaction ID:", txn, false);  row(rows, "Issued:", now, false);
        row(rows, "Parking Date:", date, false);
        row(rows, "Time:", reserve ? Fmt.timeRange(startMin, startMin + hours * 60) : "Starts when approved", false);
        row(rows, "Slot:", slot + "  (" + Park.slotKind(slot).replace(" slot", "") + ")", false);
        row(rows, "Plate Number:", Fmt.formatPlate(plate), false);
        row(rows, "Booking Type:", reserve ? "RESERVATION" : "PARK NOW", false);
        row(rows, "Duration:", hours + (hours == 1 ? " hour" : " hours"), false);
        row(rows, "Rate:", rateText().replace(", then ", ", "), false);
        row(rows, "Payment:", DataStore.MODE_QR.equals(mode) ? "InstaPay QR" : "Counter", false);
        row(rows, "Ref. No.:", DataStore.MODE_QR.equals(mode) ? ref : "-", false);
        row(rows, "TOTAL DUE:", Park.money(total()), true);
        card.add(rows, BorderLayout.CENTER);

        JLabel footer = new JLabel("Status: PENDING ADMIN APPROVAL", SwingConstants.CENTER);
        footer.setFont(Park.bold(11)); footer.setForeground(new Color(150, 110, 30));
        footer.setPreferredSize(new Dimension(100, 30));
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    private void row(JPanel grid, String k, String v, boolean bold) {
        JLabel kl = new JLabel(k);
        kl.setFont(bold ? Park.bold(12) : Park.plain(11));
        kl.setForeground(bold ? new Color(30, 30, 50) : new Color(110, 95, 55));
        JLabel vl = new JLabel(v, SwingConstants.RIGHT);
        vl.setFont(bold ? Park.bold(13) : Park.bold(11));
        vl.setForeground(bold ? new Color(0, 130, 80) : new Color(30, 30, 50));
        grid.add(kl); grid.add(vl);
    }
}