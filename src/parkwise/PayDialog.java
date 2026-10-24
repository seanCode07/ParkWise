package parkwise;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/* =====================================================================
   PayDialog : pay an overtime charge or a billed fee.
   The driver pays by InstaPay QR (and types the reference number) or at the counter.
   Nothing is final until the admin validates the payment.
   After the dialog closes: submitted, mode and ref hold the driver's choice.
   ===================================================================== */
class PayDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    boolean submitted = false;
    String mode = DataStore.MODE_QR;
    String ref = "";
    String receipt = "";          // stored name of the attached receipt picture (QR payments)
    private AttachPanel attach;

    private final double amount;
    private final boolean allowCounter;
    private CardLayout methods;
    private JPanel methodPanel;
    private Park.RoundButton qrBtn, counterBtn;
    private JTextField refField;
    private JLabel errLbl;

    PayDialog(Frame owner, String title, String detail, double amount, boolean allowCounter) {
        super(owner, "ParkWise - " + title, true);
        this.amount = amount;
        this.allowCounter = allowCounter;
        Park.applyIcon(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(500, Math.min(790, scr.height - 40)); setLocationRelativeTo(owner); setResizable(false);
        addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent e) { if (!submitted && attach != null) attach.clear(); }
        });
        buildUI(title, detail);
    }

    private JComponent left(JComponent c) { c.setAlignmentX(Component.LEFT_ALIGNMENT); return c; }

    private void buildUI(String title, String detail) {
        Park.BgPanel root = new Park.BgPanel(new BorderLayout());
        root.setBorder(Park.pad(16, 18, 16, 18));
        Park.Card card = new Park.Card(new BorderLayout(), 22);
        card.setBorder(Park.pad(20, 28, 18, 28));

        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.add(left(Park.lbl(title, Park.bold(22), Park.TEXT_PRIMARY)));
        col.add(Box.createVerticalStrut(2));
        col.add(left(Park.lbl(Park.lines(detail.split("\n")), Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(8));
        col.add(left(Park.lbl("Amount due:  " + Park.money(amount), Park.bold(20), Park.ACCENT)));
        col.add(Box.createVerticalStrut(12));

        qrBtn = new Park.RoundButton("InstaPay QR", Park.ACCENT, Color.WHITE);
        counterBtn = new Park.RoundButton("Pay at Counter", Park.BTN_IDLE, Park.TEXT_MUTED);
        qrBtn.setFont(Park.bold(13)); counterBtn.setFont(Park.bold(13));
        qrBtn.setRadius(12); counterBtn.setRadius(12);
        qrBtn.addActionListener(Fmt.act(new Runnable() { public void run() { select(DataStore.MODE_QR); } }));
        counterBtn.addActionListener(Fmt.act(new Runnable() { public void run() { select(DataStore.MODE_COUNTER); } }));
        JPanel tabs = new JPanel(new GridLayout(1, 2, 10, 0)); tabs.setOpaque(false);
        tabs.add(qrBtn);
        if (allowCounter) tabs.add(counterBtn);
        tabs.setPreferredSize(new Dimension(300, 42)); tabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        col.add(left(tabs));
        col.add(Box.createVerticalStrut(12));

        methods = new CardLayout();
        methodPanel = new JPanel(methods); methodPanel.setOpaque(false);
        methodPanel.add(buildQrPanel(), DataStore.MODE_QR);
        methodPanel.add(buildCounterPanel(), DataStore.MODE_COUNTER);
        col.add(left(methodPanel));
        col.add(Box.createVerticalGlue());

        Park.RoundButton submit = Park.primaryBtn("Submit for Verification");
        submit.setPreferredSize(new Dimension(300, 46)); submit.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        submit.addActionListener(Fmt.act(new Runnable() { public void run() { submit(); } }));
        Park.RoundButton cancel = Park.secondaryBtn("Cancel");
        cancel.setPreferredSize(new Dimension(300, 38)); cancel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        cancel.addActionListener(Fmt.act(new Runnable() { public void run() { dispose(); } }));
        col.add(left(submit)); col.add(Box.createVerticalStrut(8)); col.add(left(cancel));

        card.add(col, BorderLayout.CENTER);
        root.add(card, BorderLayout.CENTER);
        setContentPane(root);
        select(DataStore.MODE_QR);
    }

    private JPanel buildQrPanel() {
        JPanel p = new JPanel(); p.setOpaque(false); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        Park.ImgView qr = new Park.ImgView(Park.payQr(), 160, 160, true);
        qr.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel inst = Park.lbl(Park.lines("Scan with any InstaPay-enabled bank or e-wallet app,",
                "send exactly " + Park.money(amount) + ", then type the",
                "reference number from your payment receipt."), Park.plain(12), Park.TEXT_MUTED);
        inst.setHorizontalAlignment(SwingConstants.CENTER);
        inst.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel rl = Park.lbl("REFERENCE NUMBER", Park.bold(11), Park.TEXT_MUTED);
        rl.setAlignmentX(Component.CENTER_ALIGNMENT);
        refField = Park.field(20);
        refField.setPreferredSize(new Dimension(300, 40)); refField.setMaximumSize(new Dimension(300, 40));
        refField.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel al = Park.lbl("PAYMENT RECEIPT (screenshot or photo)", Park.bold(11), Park.TEXT_MUTED);
        al.setAlignmentX(Component.CENTER_ALIGNMENT);
        attach = new AttachPanel("receipt", "Attach receipt", "Required for QR payments");
        attach.setPreferredSize(new Dimension(300, 40)); attach.setMaximumSize(new Dimension(300, 40));
        attach.setAlignmentX(Component.CENTER_ALIGNMENT);
        errLbl = Park.lbl(" ", Park.plain(12), Park.DANGER);
        errLbl.setAlignmentX(Component.CENTER_ALIGNMENT);
        p.add(qr); p.add(Box.createVerticalStrut(8)); p.add(inst); p.add(Box.createVerticalStrut(10));
        p.add(rl); p.add(Box.createVerticalStrut(4)); p.add(refField); p.add(Box.createVerticalStrut(6));
        p.add(al); p.add(Box.createVerticalStrut(3)); p.add(attach); p.add(Box.createVerticalStrut(2)); p.add(errLbl);
        return p;
    }

    private JPanel buildCounterPanel() {
        JPanel p = new JPanel(new BorderLayout()); p.setOpaque(false);
        Park.Card c = new Park.Card(new BorderLayout(), 16);
        c.setFill(Park.BG_PANEL);
        c.setBorder(Park.pad(18, 18, 18, 18));
        c.add(Park.lbl(Park.lines("<b>Pay at the counter</b>",
                "Hand " + Park.money(amount) + " (cash or card) to the admin,",
                "then press Submit for Verification.",
                "The admin validates it before it is final."), Park.plain(13), Park.TEXT_PRIMARY), BorderLayout.CENTER);
        p.add(c, BorderLayout.NORTH);
        return p;
    }

    private void select(String m) {
        mode = m;
        boolean qr = DataStore.MODE_QR.equals(m);
        qrBtn.setFill(qr ? Park.ACCENT : Park.BTN_IDLE); qrBtn.setForeground(qr ? Color.WHITE : Park.TEXT_MUTED);
        counterBtn.setFill(qr ? Park.BTN_IDLE : Park.SUCCESS); counterBtn.setForeground(qr ? Park.TEXT_MUTED : Park.BG_DARK);
        methods.show(methodPanel, m);
    }

    private void submit() {
        if (DataStore.MODE_QR.equals(mode)) {
            if (!Fmt.validRef(refField.getText())) {
                errLbl.setText("Enter the reference number (8 to 25 letters/digits).");
                return;
            }
            if (!attach.hasPicture()) {
                errLbl.setText("Attach a screenshot or photo of your payment receipt.");
                return;
            }
            ref = Fmt.normalizeRef(refField.getText());
            receipt = attach.stored();
            attach.release();
        } else {
            ref = "";
            receipt = "";
            if (attach != null) attach.clear();
        }
        submitted = true;
        dispose();
    }
}