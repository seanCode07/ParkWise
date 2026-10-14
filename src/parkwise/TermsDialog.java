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
        ed.setBackground(new Color(12, 12, 28));
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

    private static String html() {
        String peso = Park.PESO;
        String rate = peso + String.format(Locale.US, "%.2f", Park.RATE);
        return "<html><body style=\"font-family:'Space Grotesk',sans-serif; font-size:13px; color:#F0F0FF; margin:10px\">"
            + "<h3 style=\"color:#9B86FF\">TERMS AND CONDITIONS</h3>"
            + "<p><b>1. Your account.</b> Give accurate details and keep your password private. "
            + "You are responsible for everything done under your account.</p>"
            + "<p><b>2. Rates and payment.</b> Parking costs " + rate + " per hour, paid in advance for the hours you choose. "
            + "Reservations must be paid online through the InstaPay QR code. "
            + "Park Now bookings may be paid by QR code or at the counter.</p>"
            + "<p><b>3. Overtime.</b> If you stay past your paid time, a " + DataStore.GRACE_MINUTES
            + "-minute grace period applies. After that, every extra hour you start is billed at " + rate
            + ". Overtime must be paid when you leave, or from your dashboard if it was billed to your account.</p>"
            + "<p><b>4. Leaving your slot.</b> Press Leave in My Parking when you drive out. If a slot looks abandoned, "
            + "an administrator may check you out and bill the overtime to your account.</p>"
            + "<p><b>5. Fair use.</b> Do not book slots you do not plan to use, share your account, or tamper with the system.</p>"
            + "<p><b>6. Liability.</b> You park at your own risk. The management is not responsible for loss of or damage to "
            + "vehicles or belongings, except where the law says otherwise.</p>"
            + "<p><b>7. Changes.</b> These terms may be updated. Continuing to use ParkWise means you accept the update.</p>"
            + "<h3 style=\"color:#9B86FF\">DATA PRIVACY NOTICE</h3>"
            + "<p style=\"color:#8A8AB2\">Republic Act No. 10173, the Data Privacy Act of 2012</p>"
            + "<p><b>What we collect.</b> Your full name, 11-digit mobile number, email address and password "
            + "(stored only as a one-way hash), plus your booking, payment and overtime records.</p>"
            + "<p><b>Why we collect it.</b> To create and secure your account, process your bookings and payments, "
            + "contact you about your parking, and let administrators handle overtime and forgotten slots.</p>"
            + "<p><b>Who can see it.</b> Only you and authorized ParkWise administrators. Your data is not sold or shared "
            + "with anyone else without your consent, unless the law requires it.</p>"
            + "<p><b>How long we keep it.</b> Only as long as needed for the purposes above.</p>"
            + "<p><b>Your rights.</b> You may be informed about how your data is used, access and correct it, object to its "
            + "processing, ask for it to be erased or blocked, request a copy, claim damages for misuse, and file a complaint "
            + "with the National Privacy Commission (privacy.gov.ph).</p>"
            + "<p><b>Your consent.</b> By ticking the consent box and creating an account, you give your consent freely. "
            + "You may withdraw it by contacting the administrator; this may mean your account can no longer be used.</p>"
            + "</body></html>";
    }
}