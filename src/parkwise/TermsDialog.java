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
   TermsDialog : short Terms and Conditions + Data Privacy Notice (RA 10173)
   ===================================================================== */
class TermsDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    boolean agreed = false;

    TermsDialog(Frame owner) {
        super(owner, "ParkWise - Terms and Privacy Notice", true);
        Park.applyIcon(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(700, 640); setLocationRelativeTo(owner); setResizable(false);

        Park.BgPanel root = new Park.BgPanel(new BorderLayout());
        root.setBorder(Park.pad(20, 22, 20, 22));
        Park.Card card = new Park.Card(new BorderLayout(0, 12), 22);
        card.setBorder(Park.pad(20, 22, 18, 22));

        JPanel head = new JPanel(); head.setOpaque(false); head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        JLabel t = Park.lbl("Terms and Conditions", Park.bold(22), Park.TEXT_PRIMARY);
        JLabel s = Park.lbl("Please read this before creating your account.", Park.plain(13), Park.TEXT_MUTED);
        t.setAlignmentX(Component.LEFT_ALIGNMENT); s.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(t); head.add(Box.createVerticalStrut(2)); head.add(s);
        card.add(head, BorderLayout.NORTH);

        JEditorPane ed = new JEditorPane("text/html", html());
        ed.setEditable(false);
        ed.setOpaque(true);
        ed.setBackground(Park.FIELD_BG);
        ed.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ed);
        sp.setBorder(BorderFactory.createLineBorder(Park.BORDER, 1));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        card.add(sp, BorderLayout.CENTER);

        Park.RoundButton close = Park.secondaryBtn("Close");
        close.setPreferredSize(new Dimension(120, 42));
        close.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { dispose(); }
        });
        Park.RoundButton agree = Park.primaryBtn("I Agree");
        agree.setPreferredSize(new Dimension(160, 42));
        agree.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { agreed = true; dispose(); }
        });
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); btns.setOpaque(false);
        btns.add(close); btns.add(agree);
        card.add(btns, BorderLayout.SOUTH);

        root.add(card, BorderLayout.CENTER);
        setContentPane(root);
    }

    private static String hex(Color c) { return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()); }

    private static String html() {
        String fg = hex(Park.TEXT_PRIMARY), muted = hex(Park.TEXT_MUTED), head = hex(Park.ACCENT);
        String car = Park.moneyShort(Park.CAR_RATE), moto = Park.moneyShort(Park.MOTO_RATE);
        String penalty = Park.money(Park.LEAVE_PENALTY);
        String pwdFee = Park.money(Park.PWD_VIOLATION);
        return "<html><body style=\"font-family:'Space Grotesk',sans-serif; font-size:13px; color:" + fg + "; margin:10px\">"
            + "<h3 style=\"color:" + head + "\">TERMS AND CONDITIONS</h3>"
            + "<p><b>1. Your account.</b> Choose a username, give accurate details and keep your password private. "
            + "You are responsible for everything done under your account. If you forget your password, you can reset it "
            + "with your username (or email) and the mobile number you registered with.</p>"
            + "<p><b>2. Rates.</b> <i>Park Now</i> costs " + car + " per hour for a car and " + moto + " per hour for a motorcycle. "
            + "A <i>reservation</i> costs " + Park.moneyShort(Park.CAR_RESERVE_FIRST) + " for the first hour of a car, then " + car
            + " for every extra hour, and " + Park.moneyShort(Park.MOTO_RESERVE_FIRST) + " for the first hour of a motorcycle, then " + moto
            + " for every extra hour. <b>Drivers with a verified PWD or senior citizen ID</b> pay less in <b>any slot, regular or PWD / senior citizen</b>: "
            + "car reservation " + Park.moneyShort(Park.CAR_PRIORITY_FIRST) + " for the first hour then " + Park.moneyShort(Park.CAR_PRIORITY_RATE)
            + " per hour, car Park Now " + Park.moneyShort(Park.CAR_PRIORITY_RATE) + " per hour; motorcycle reservation "
            + Park.moneyShort(Park.MOTO_PRIORITY_FIRST) + " for the first hour then " + Park.moneyShort(Park.MOTO_PRIORITY_RATE)
            + " per hour, motorcycle Park Now " + Park.moneyShort(Park.MOTO_PRIORITY_RATE) + " per hour. Overtime is billed at the same hourly rate as Park Now, so verified PWD / senior citizen drivers also pay the lower overtime rate. "
            + "You pay in advance for the hours you choose.</p>"
            + "<p><b>3. Payment and approval.</b> Reservations must be paid online through the InstaPay QR code. After paying, "
            + "type the payment reference number in the app. Park Now bookings may be paid by QR code or at the counter. For every QR payment you must also attach a screenshot or photo of your payment receipt. "
            + "<b>No payment is final until the admin validates it.</b> You will be notified when it is approved. "
            + "An approved reservation is marked Reserved. An approved Park Now booking starts by itself: your parking time "
            + "begins the moment the admin approves and your slot shows as occupied. A wrong or reused reference number "
            + "will be rejected.</p>"
            + "<p><b>4. One booking per slot per day.</b> Once a slot is reserved or occupied, other drivers cannot book it "
            + "for the rest of that date, even at a different time. This protects the booking in case a car stays longer "
            + "than paid. A reservation that is not used by its end time releases the slot. You may park more than one vehicle: "
            + "add each plate number and book a separate slot for each.</p>"
            + "<p><b>5. Floors and vehicles.</b> ParkWise covers the ground floor (1st) up to the 4th floor. Every floor has car slots "
            + "(rows A and B) and motorcycle slots (rows M1 and M2). Park a car in a car slot and a motorcycle in a motorcycle slot. "
            + "Enter the plate number of the vehicle you are parking: up to 3 letters followed by up to 4 digits (for example ABC 1234). "
            + "If you made a typo you can edit the plate number in the app; the administrator is told about every change.</p>"
            + "<p><b>6. PWD / senior citizen slots and ID validation.</b> Each floor has " + Park.PWD_COUNT + " PWD / senior citizen car slots and "
            + Park.PWD_COUNT + " PWD / senior citizen motorcycle slots. They are reserved for drivers with a PWD ID or a senior citizen ID "
            + "(age " + DataStore.SENIOR_AGE + " and above, checked from your birth date). You enter your ID number in the app and attach a clear photo of the ID, "
            + "and <b>the admin must verify it</b> before you can use these slots or the discounted reservation rates. "
            + "The admin may ask to see the original ID. <b>Any driver, user or walk-in, who parks in a PWD / senior citizen slot without a "
            + "verified ID is charged a violation of " + pwdFee + "</b>. For app users it is billed to the account; walk-ins pay it at the counter. "
            + "This is on top of any parking or overtime charges. Giving a false ID is a breach of these terms.</p>"
            + "<p><b>7. Overtime.</b> If you stay past your paid time, a " + DataStore.GRACE_MINUTES
            + "-minute grace period applies. After that, every extra hour you start is billed at the hourly rate of your slot type (car " + car + ", motorcycle " + moto + "; verified PWD / senior citizen drivers: car " + Park.moneyShort(Park.CAR_PRIORITY_RATE) + ", motorcycle " + Park.moneyShort(Park.MOTO_PRIORITY_RATE) + "). "
            + "If you have overtime, you must pay the <b>total extra charge</b> (QR code with reference number, or at the counter) "
            + "and the <b>admin must validate the payment before you are allowed to leave</b>. The amount is fixed at the moment "
            + "you send the payment. Overtime that is billed to your account must be paid from your dashboard and is also "
            + "validated by the admin.</p>"
            + "<p><b>8. Leaving your slot.</b> Press Leave in My Parking only when you are driving out. "
            + "<b>If you press Leave while your car is still parked in the slot, for example to avoid overtime charges, "
            + "you will be charged a penalty of " + penalty + "</b>, billed to your account on top of any overtime. "
            + "If a slot looks abandoned, an administrator may check you out and bill the overtime to your account.</p>"
            + "<p><b>9. Monitoring.</b> The administrator is always notified when you reserve a slot, pay online or press Leave, "
            + "so that bookings, payments and departures can be checked.</p>"
            + "<p><b>10. Fair use.</b> Do not book slots you do not plan to use, share your account, submit false payment details, "
            + "or tamper with the system.</p>"
            + "<p><b>11. Liability.</b> You park at your own risk. The management is not responsible for loss of or damage to "
            + "vehicles or belongings, except where the law says otherwise.</p>"
            + "<p><b>12. Records and audit.</b> ParkWise keeps a permanent record of every parking session (driver, plate number, slot, entry and exit times, "
            + "hours paid and fees) and of administrative actions (who did what, when, with the old and new values of any correction). "
            + "These records cannot be edited or deleted from the app. They are kept for the management's records even if an account is later deleted. "
            + "You can see your own past sessions under My Parking > History and remove entries from your own list; removing an entry only clears your view. "
            + "You also get an alert about 5 minutes before your paid parking time ends. "
            + "An administrator may correct typing mistakes in a plate number or in your personal information; each correction is recorded.</p>"
            + "<p><b>13. Your account.</b> In Settings you can edit your personal information, change your password "
            + "and delete your account. An account can only be deleted when it has no bookings and no unpaid fees. After deleting, the same username and email "
            + "can be used to sign up again. Administrators are added only with the approval of an existing administrator.</p>"
            + "<p><b>14. Changes.</b> These terms may be updated. Continuing to use ParkWise means you accept the update.</p>"
            + "<h3 style=\"color:" + head + "\">DATA PRIVACY NOTICE</h3>"
            + "<p style=\"color:" + muted + "\">Republic Act No. 10173, the Data Privacy Act of 2012</p>"
            + "<p><b>What we collect.</b> Your full name, username, 11-digit mobile number, email address and password "
            + "(stored only as a one-way hash), plus your plate numbers, your PWD or senior citizen ID number, a photo of that ID and, for seniors, your birth date (only if you register as such), and the payment receipt pictures you attach and your booking, payment (including reference numbers), "
            + "leave and overtime records.</p>"
            + "<p><b>Why we collect it.</b> To create and secure your account, let you recover it, process and verify your bookings "
            + "and payments, contact you about your parking, and let administrators handle overtime, departures and forgotten slots.</p>"
            + "<p><b>Who can see it.</b> Only you and authorized ParkWise administrators. Your data is not sold or shared "
            + "with anyone else without your consent, unless the law requires it.</p>"
            + "<p><b>How long we keep it.</b> Your account details until you delete your account. Parking session records and the audit log are kept permanently for accountability.</p>"
            + "<p><b>Your rights.</b> You may be informed about how your data is used, access and correct it, object to its "
            + "processing, ask for it to be erased or blocked, request a copy, claim damages for misuse, and file a complaint "
            + "with the National Privacy Commission (privacy.gov.ph).</p>"
            + "<p><b>Your consent.</b> By ticking the consent box and creating an account, you give your consent freely. "
            + "You may withdraw it by contacting the administrator; this may mean your account can no longer be used.</p>"
            + "</body></html>";
    }
}