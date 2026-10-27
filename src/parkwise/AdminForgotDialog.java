package parkwise;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/* =====================================================================
   AdminForgotDialog : an administrator forgot the password.
   Way 1: ask another administrator to reset it (they approve it under Admins and hand over a temporary password).
   Way 2: use the recovery code from parkwise_data/admin_recovery_code.txt (for a lone or locked-out administrator).
   ===================================================================== */
class AdminForgotDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    private static final int MAX_TRIES = 5;

    private int tries = 0;
    private CardLayout modes;
    private JPanel modePanel;
    private Park.RoundButton btnAsk, btnCode;
    private JTextField askUser, askName, codeUser, codeField;
    private JPasswordField newPass, confirmPass;
    private JLabel askMsg, codeMsg;

    AdminForgotDialog(Frame owner, String prefill) {
        super(owner, "ParkWise - Administrator Password", true);
        DataStore.version();
        Park.applyIcon(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(520, Math.min(640, scr.height - 40)); setLocationRelativeTo(owner); setResizable(false);

        Park.BgPanel root = new Park.BgPanel(new BorderLayout());
        root.setBorder(Park.pad(16, 18, 16, 18));
        Park.Card card = new Park.Card(new BorderLayout(0, 10), 22);
        card.setLine(Park.WARNING);
        card.setBorder(Park.pad(20, 26, 18, 26));

        JPanel head = new JPanel(); head.setOpaque(false); head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.add(left(Park.lbl("Forgot Administrator Password", Park.bold(22), Park.TEXT_PRIMARY)));
        head.add(Box.createVerticalStrut(4));
        head.add(left(Park.lbl("Choose how to get back in.", Park.plain(13), Park.TEXT_MUTED)));
        head.add(Box.createVerticalStrut(10));
        btnAsk = new Park.RoundButton("Ask another admin", Park.WARNING, Park.BG_DARK);
        btnCode = new Park.RoundButton("Use recovery code", Park.BTN_IDLE, Park.TEXT_MUTED);
        btnAsk.setFont(Park.bold(13)); btnCode.setFont(Park.bold(13)); btnAsk.setRadius(12); btnCode.setRadius(12);
        btnAsk.addActionListener(Fmt.act(new Runnable() { public void run() { setMode(true); } }));
        btnCode.addActionListener(Fmt.act(new Runnable() { public void run() { setMode(false); } }));
        JPanel tabs = new JPanel(new GridLayout(1, 2, 10, 0)); tabs.setOpaque(false);
        tabs.add(btnAsk); tabs.add(btnCode);
        tabs.setPreferredSize(new Dimension(300, 42)); tabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        head.add(left(tabs));
        card.add(head, BorderLayout.NORTH);

        modes = new CardLayout();
        modePanel = new JPanel(modes); modePanel.setOpaque(false);
        modePanel.add(buildAsk(prefill), "ask");
        modePanel.add(buildCode(prefill), "code");
        card.add(modePanel, BorderLayout.CENTER);

        Park.RoundButton close = Park.secondaryBtn("Close");
        close.setPreferredSize(new Dimension(110, 38));
        close.addActionListener(Fmt.act(new Runnable() { public void run() { dispose(); } }));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); south.setOpaque(false); south.add(close);
        card.add(south, BorderLayout.SOUTH);
        root.add(card, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JComponent left(JComponent c) { c.setAlignmentX(Component.LEFT_ALIGNMENT); return c; }

    private void size(JComponent c, int h) {
        c.setPreferredSize(new Dimension(300, h));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
    }

    private void setMode(boolean ask) {
        btnAsk.setFill(ask ? Park.WARNING : Park.BTN_IDLE);   btnAsk.setForeground(ask ? Park.BG_DARK : Park.TEXT_MUTED);
        btnCode.setFill(ask ? Park.BTN_IDLE : Park.WARNING);  btnCode.setForeground(ask ? Park.TEXT_MUTED : Park.BG_DARK);
        modes.show(modePanel, ask ? "ask" : "code");
    }

    private void field(JPanel col, String label, JComponent f) {
        col.add(left(Park.lbl(label, Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(3));
        size(f, 38);
        col.add(left(f));
        col.add(Box.createVerticalStrut(8));
    }

    // ---------- way 1: another admin resets it ----------
    private JPanel buildAsk(String prefill) {
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.add(Box.createVerticalStrut(10));
        col.add(left(Park.lbl(Park.lines("Send a reset request. Another administrator approves it under",
                "<b>Admins</b> and gives you a <b>temporary password</b>. You choose a new",
                "password the first time you log in with it."), Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(14));
        askUser = Park.field(20); if (prefill != null) askUser.setText(prefill);
        askName = Park.field(20);
        field(col, "Your admin username", askUser);
        field(col, "Your full name as registered (the main account: Administrator)", askName);
        askMsg = Park.lbl(" ", Park.plain(12), Park.DANGER);
        col.add(left(askMsg));
        col.add(Box.createVerticalStrut(8));
        Park.RoundButton send = new Park.RoundButton("Send reset request", Park.WARNING, Park.BG_DARK);
        send.setFont(Park.bold(14)); send.setRadius(12); size(send, 44);
        send.addActionListener(Fmt.act(new Runnable() { public void run() { doAsk(); } }));
        col.add(left(send));
        col.add(Box.createVerticalStrut(10));
        col.add(left(Park.lbl("Only one administrator? Use the recovery code instead.", Park.plain(11), Park.TEXT_MUTED)));
        return col;
    }

    private void doAsk() {
        String u = askUser.getText().trim(), n = askName.getText().trim();
        if (u.isEmpty() || n.isEmpty()) { fail(askMsg, "Please fill in both fields."); return; }
        if (tries >= MAX_TRIES) { fail(askMsg, "Too many tries. Close this window and try again later."); return; }
        String err = DataStore.requestAdminReset(u, n);
        if (err != null) { tries++; fail(askMsg, err + " (" + (MAX_TRIES - tries) + " tries left)"); return; }
        JOptionPane.showMessageDialog(this, "Request sent.\nAsk another administrator to approve it under Admins.\n"
            + "They will give you a temporary password.", "Request Sent", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    // ---------- way 2: recovery code ----------
    private JPanel buildCode(String prefill) {
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.add(Box.createVerticalStrut(10));
        col.add(left(Park.lbl(Park.lines("The recovery code is in the file",
                "<b>parkwise_data/admin_recovery_code.txt</b> on the computer running ParkWise.",
                "Open it, copy the code, and keep that file private. The code changes after each use."),
                Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(12));
        codeUser = Park.field(20); if (prefill != null) codeUser.setText(prefill);
        codeField = Park.field(20);
        newPass = Park.pass(20); confirmPass = Park.pass(20);
        field(col, "Your admin username", codeUser);
        field(col, "Recovery code (e.g. ABCD-EFGH-JKLM)", codeField);
        field(col, "New password (at least 8 characters)", newPass);
        field(col, "Confirm new password", confirmPass);
        codeMsg = Park.lbl(" ", Park.plain(12), Park.DANGER);
        col.add(left(codeMsg));
        col.add(Box.createVerticalStrut(6));
        Park.RoundButton go = new Park.RoundButton("Reset password", Park.WARNING, Park.BG_DARK);
        go.setFont(Park.bold(14)); go.setRadius(12); size(go, 44);
        go.addActionListener(Fmt.act(new Runnable() { public void run() { doCode(); } }));
        col.add(left(go));
        return col;
    }

    private void doCode() {
        String u = codeUser.getText().trim(), c = codeField.getText().trim();
        String a = new String(newPass.getPassword()), b = new String(confirmPass.getPassword());
        if (u.isEmpty() || c.isEmpty()) { fail(codeMsg, "Enter your username and the recovery code."); return; }
        if (a.length() < 8) { fail(codeMsg, "The new password must be at least 8 characters."); return; }
        if (!a.equals(b)) { fail(codeMsg, "The passwords do not match."); return; }
        if (tries >= MAX_TRIES) { fail(codeMsg, "Too many tries. Close this window and try again later."); return; }
        String err = DataStore.resetAdminWithCode(u, c, a);
        if (err != null) { tries++; fail(codeMsg, err + " (" + (MAX_TRIES - tries) + " tries left)"); return; }
        JOptionPane.showMessageDialog(this, "Password changed. You can log in now.\nA new recovery code was saved in the recovery file.",
            "Password Reset", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    private void fail(JLabel l, String m) { l.setText(m); }
}