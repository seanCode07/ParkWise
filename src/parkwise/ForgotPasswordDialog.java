package parkwise;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/* =====================================================================
   ForgotPasswordDialog : reset a forgotten password.
   ParkWise works offline, so identity is checked with the details given at sign-up:
   the username (or e-mail) AND the registered mobile number must match.
   ===================================================================== */
class ForgotPasswordDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private static final int MAX_TRIES = 5;

    /** Username of the account whose password was reset (null if the dialog was closed without finishing). */
    String resetUser = null;

    private int tries = 0;
    private String verifiedId = null;
    private CardLayout steps;
    private JPanel stepPanel;
    private JTextField idField, phoneField;
    private JPasswordField newPass, confirmPass;
    private JLabel verifyErr, resetErr;
    private Park.RoundButton verifyBtn, resetBtn;

    ForgotPasswordDialog(Frame owner, String prefill) {
        super(owner, "ParkWise - Forgot Password", true);
        Park.applyIcon(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(480, 520); setLocationRelativeTo(owner); setResizable(false);

        Park.BgPanel root = new Park.BgPanel(new BorderLayout());
        root.setBorder(Park.pad(18, 20, 18, 20));
        Park.Card card = new Park.Card(new BorderLayout(), 22);
        card.setBorder(Park.pad(22, 28, 20, 28));
        steps = new CardLayout();
        stepPanel = new JPanel(steps); stepPanel.setOpaque(false);
        stepPanel.add(buildVerify(prefill), "verify");
        stepPanel.add(buildReset(), "reset");
        card.add(stepPanel, BorderLayout.CENTER);
        root.add(card, BorderLayout.CENTER);
        setContentPane(root);
        getRootPane().setDefaultButton(verifyBtn);
    }

    private JComponent left(JComponent c) { c.setAlignmentX(Component.LEFT_ALIGNMENT); return c; }

    private void size(JComponent c, int h) {
        c.setPreferredSize(new Dimension(300, h));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
    }

    private JPanel buildVerify(String prefill) {
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.add(left(Park.lbl("Forgot Password", Park.bold(24), Park.TEXT_PRIMARY)));
        col.add(Box.createVerticalStrut(4));
        col.add(left(Park.lbl(Park.lines("Confirm it is you with the details you gave when",
                "you created your account."), Park.plain(13), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(20));

        col.add(left(Park.lbl("Username or email", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(4));
        idField = Park.field(20); size(idField, 42);
        if (prefill != null) idField.setText(prefill);
        col.add(left(idField));
        col.add(Box.createVerticalStrut(12));
        col.add(left(Park.lbl("Registered mobile number (11 digits)", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(4));
        phoneField = Park.field(20); size(phoneField, 42); Fmt.digitsOnly(phoneField, 11);
        col.add(left(phoneField));
        col.add(Box.createVerticalStrut(10));
        verifyErr = Park.lbl(" ", Park.plain(12), Park.DANGER);
        col.add(left(verifyErr));
        col.add(Box.createVerticalStrut(10));

        verifyBtn = Park.primaryBtn("Verify"); size(verifyBtn, 46);
        verifyBtn.addActionListener(Fmt.act(new Runnable() { public void run() { doVerify(); } }));
        Park.RoundButton cancel = Park.secondaryBtn("Cancel"); size(cancel, 40);
        cancel.addActionListener(Fmt.act(new Runnable() { public void run() { dispose(); } }));
        col.add(left(verifyBtn)); col.add(Box.createVerticalStrut(8)); col.add(left(cancel));
        col.add(Box.createVerticalGlue());
        return col;
    }

    private JPanel buildReset() {
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.add(left(Park.lbl("Create a New Password", Park.bold(24), Park.TEXT_PRIMARY)));
        col.add(Box.createVerticalStrut(4));
        col.add(left(Park.lbl("Identity confirmed. Choose a new password.", Park.plain(13), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(20));

        col.add(left(Park.lbl("New password (at least 8 characters)", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(4));
        newPass = Park.pass(20); size(newPass, 42); col.add(left(newPass));
        col.add(Box.createVerticalStrut(12));
        col.add(left(Park.lbl("Confirm new password", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(4));
        confirmPass = Park.pass(20); size(confirmPass, 42); col.add(left(confirmPass));
        col.add(Box.createVerticalStrut(10));
        resetErr = Park.lbl(" ", Park.plain(12), Park.DANGER);
        col.add(left(resetErr));
        col.add(Box.createVerticalStrut(10));

        resetBtn = Park.primaryBtn("Reset Password"); size(resetBtn, 46);
        resetBtn.addActionListener(Fmt.act(new Runnable() { public void run() { doReset(); } }));
        Park.RoundButton cancel = Park.secondaryBtn("Cancel"); size(cancel, 40);
        cancel.addActionListener(Fmt.act(new Runnable() { public void run() { dispose(); } }));
        col.add(left(resetBtn)); col.add(Box.createVerticalStrut(8)); col.add(left(cancel));
        col.add(Box.createVerticalGlue());
        return col;
    }

    private void doVerify() {
        String id = idField.getText().trim(), ph = phoneField.getText().trim();
        if (id.isEmpty() || ph.isEmpty()) { verifyErr.setText("Please fill in both fields."); return; }
        if (tries >= MAX_TRIES) { verifyErr.setText("Too many tries. Close this window and try again later."); return; }
        String found = DataStore.findForRecovery(id, ph);
        if (found == null) {
            tries++;
            verifyErr.setText("The details do not match our records. (" + (MAX_TRIES - tries) + " tries left)");
            return;
        }
        verifiedId = found;
        steps.show(stepPanel, "reset");
        getRootPane().setDefaultButton(resetBtn);
        newPass.requestFocusInWindow();
    }

    private void doReset() {
        String a = new String(newPass.getPassword()), b = new String(confirmPass.getPassword());
        if (a.length() < 8) { resetErr.setText("Password must be at least 8 characters."); return; }
        if (!a.equals(b))   { resetErr.setText("Passwords do not match."); return; }
        String err = DataStore.resetPassword(verifiedId, a);
        if (err != null) { resetErr.setText(err); return; }
        resetUser = verifiedId;
        dispose();
    }
}