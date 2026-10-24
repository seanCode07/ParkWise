package parkwise;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/* =====================================================================
   SettingsDialog : personal information, password and account deletion.
   Used by drivers and by administrators (admins only get the parts that apply to them).
   ===================================================================== */
class SettingsDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final boolean admin;
    private final Runnable onRebuild;     // redraw the main window (the name changed)
    private final Runnable onDeleted;     // the account was deleted: go back to the login screen

    SettingsDialog(Frame owner, String id, boolean admin, Runnable onRebuild, Runnable onDeleted) {
        super(owner, "ParkWise - Settings", true);
        this.id = id; this.admin = admin; this.onRebuild = onRebuild; this.onDeleted = onDeleted;
        Park.applyIcon(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(580, Math.min(760, scr.height - 50));
        setLocationRelativeTo(owner);
        buildUI();
    }

    private JComponent left(JComponent c) { c.setAlignmentX(Component.LEFT_ALIGNMENT); return c; }

    private JTextField field(String text) {
        JTextField f = Park.field(20);
        f.setText(text);
        f.setPreferredSize(new Dimension(300, 38)); f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return f;
    }

    private JPasswordField pass() {
        JPasswordField f = Park.pass(20);
        f.setPreferredSize(new Dimension(300, 38)); f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return f;
    }

    private JPanel section(String title, String hint) {
        Park.Card c = new Park.Card(new BorderLayout(), 16);
        c.setFill(Park.BG_PANEL);
        c.setBorder(Park.pad(14, 16, 14, 16));
        JPanel col = new JPanel(); col.setOpaque(false); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.add(left(Park.lbl(title, Park.bold(15), Park.TEXT_PRIMARY)));
        if (hint != null) col.add(left(Park.lbl(hint, Park.plain(11), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(8));
        c.add(col, BorderLayout.CENTER);
        c.putClientProperty("col", col);
        return c;
    }

    private JPanel colOf(JPanel section) { return (JPanel) ((JComponent) section).getClientProperty("col"); }

    private void labelled(JPanel col, String label, JComponent f) {
        col.add(left(Park.lbl(label, Park.plain(11), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(2));
        col.add(left(f));
        col.add(Box.createVerticalStrut(6));
    }

    private void buildUI() {
        Park.BgPanel root = new Park.BgPanel(new BorderLayout());
        root.setBorder(Park.pad(14, 16, 14, 16));
        Park.Card card = new Park.Card(new BorderLayout(0, 8), 22);
        card.setBorder(Park.pad(16, 18, 14, 18));
        card.add(Park.lbl("Settings", Park.bold(22), Park.TEXT_PRIMARY), BorderLayout.NORTH);

        Park.ScrollView view = new Park.ScrollView(new BorderLayout());
        JPanel stack = new JPanel(); stack.setOpaque(false); stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        view.add(stack, BorderLayout.NORTH);

        stack.add(profileSection());          stack.add(Box.createVerticalStrut(10));
        stack.add(passwordSection());         stack.add(Box.createVerticalStrut(10));
        if (!admin) { stack.add(idSection()); stack.add(Box.createVerticalStrut(10)); stack.add(deleteSection()); }

        JScrollPane sp = new JScrollPane(view);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false); sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        card.add(sp, BorderLayout.CENTER);

        Park.RoundButton close = Park.secondaryBtn("Close");
        close.setPreferredSize(new Dimension(120, 38));
        close.addActionListener(Fmt.act(new Runnable() { public void run() { dispose(); } }));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); south.setOpaque(false); south.add(close);
        card.add(south, BorderLayout.SOUTH);
        root.add(card, BorderLayout.CENTER);
        setContentPane(root);
    }

    // ---------- personal information ----------
    private JPanel profileSection() {
        final boolean main = DataStore.ADMIN_USER.equals(id);
        JPanel sec = section(admin ? "Administrator profile" : "Personal information",
                admin ? "Your username cannot be changed." : "Fix a typo or update your details. Your username cannot be changed.");
        JPanel col = colOf(sec);
        labelled(col, "Username", Park.lbl(id, Park.bold(13), Park.TEXT_PRIMARY));
        final JTextField name = field(DataStore.getDisplayName(id).equals(id) && admin ? "" : DataStore.getDisplayName(id));
        final JTextField phone = field(DataStore.getPhone(id));
        final JTextField email = field(DataStore.getEmail(id));
        final JLabel status = Park.lbl(" ", Park.plain(12), Park.DANGER);
        labelled(col, "Full name", name);
        if (!admin) {
            labelled(col, "Contact number (11 digits, e.g. 09171234567)", phone);
            Fmt.digitsOnly(phone, 11);
            labelled(col, "Email address", email);
        }
        if (admin && main) {
            col.add(left(Park.lbl("This is the main administrator account.", Park.plain(12), Park.TEXT_MUTED)));
            name.setEnabled(false);
        }
        col.add(left(status));
        if (!(admin && main)) {
            Park.RoundButton save = Park.primaryBtn("Save changes");
            save.setPreferredSize(new Dimension(300, 40)); save.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            save.addActionListener(Fmt.act(new Runnable() { public void run() {
                String n = name.getText().trim().replaceAll("\\s+", " ");
                if (!Fmt.NAME_RX.matcher(n).matches()) { fail(status, "Enter your full name (letters only)."); return; }
                String err;
                if (admin) {
                    err = DataStore.updateAdminName(id, n);
                } else {
                    String ph = phone.getText().trim(), em = email.getText().trim();
                    if (!Fmt.PHONE_RX.matcher(ph).matches()) { fail(status, "Enter an 11-digit number starting with 09."); return; }
                    if (!Fmt.EMAIL_RX.matcher(em).matches()) { fail(status, "Enter a valid email address."); return; }
                    err = DataStore.updateProfile(id, n, ph, em);
                }
                if (err != null) { fail(status, err); return; }
                ok(status, "Saved.");
                if (onRebuild != null) { dispose(); onRebuild.run(); }
            } }));
            col.add(left(save));
        }
        return sec;
    }

    private void fail(JLabel l, String m) { l.setForeground(Park.DANGER); l.setText(m); }
    private void ok(JLabel l, String m) { l.setForeground(Park.SUCCESS); l.setText(m); }

    // ---------- password ----------
    private JPanel passwordSection() {
        JPanel sec = section("Change password", "Use at least 8 characters.");
        JPanel col = colOf(sec);
        final JPasswordField cur = pass(), nw = pass(), cf = pass();
        final JLabel status = Park.lbl(" ", Park.plain(12), Park.DANGER);
        labelled(col, "Current password", cur);
        labelled(col, "New password", nw);
        labelled(col, "Confirm new password", cf);
        col.add(left(status));
        Park.RoundButton go = Park.primaryBtn("Change password");
        go.setPreferredSize(new Dimension(300, 40)); go.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        go.addActionListener(Fmt.act(new Runnable() { public void run() {
            String a = new String(cur.getPassword()), b = new String(nw.getPassword()), c = new String(cf.getPassword());
            if (a.isEmpty()) { fail(status, "Enter your current password."); return; }
            if (b.length() < 8) { fail(status, "The new password must be at least 8 characters."); return; }
            if (!b.equals(c)) { fail(status, "The new passwords do not match."); return; }
            String err = DataStore.changePassword(id, a, b);
            if (err != null) { fail(status, err); return; }
            ok(status, "Password changed.");
            cur.setText(""); nw.setText(""); cf.setText("");
        } }));
        col.add(left(go));
        return sec;
    }

    // ---------- PWD / senior ID status ----------
    private JPanel idSection() {
        JPanel sec = section("PWD / senior citizen ID", null);
        JPanel col = colOf(sec);
        int st = DataStore.priorityStatus(id);
        String name = DataStore.priorityName(DataStore.priorityType(id));
        String text = st == DataStore.P_VERIFIED ? name + " ID " + DataStore.priorityId(id) + " is verified (priority slots and discounted reservations)."
                    : st == DataStore.P_PENDING ? name + " ID " + DataStore.priorityId(id) + " is waiting for the admin to verify it."
                    : st == DataStore.P_REJECTED ? name + " ID was not verified. Register it again from the Parking Map."
                    : "No PWD or senior citizen ID registered. You can register one from the Parking Map.";
        col.add(left(Park.lbl("<html><body style='width:420px'>" + text + "</body></html>", Park.plain(12), Park.TEXT_PRIMARY)));
        return sec;
    }

    // ---------- delete account ----------
    private JPanel deleteSection() {
        JPanel sec = section("Delete account", "This removes your account. You can sign up again later with the same username and email.");
        ((Park.Card) sec).setLine(Park.alpha(Park.DANGER, 150));
        JPanel col = colOf(sec);
        col.add(left(Park.lbl("<html><body style='width:420px'>You cannot delete the account while you have bookings or unpaid fees. "
                + "Your past parking records are kept by the parking management as required for its records.</body></html>", Park.plain(11), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(8));
        Park.RoundButton del = new Park.RoundButton("Delete my account", Park.DANGER, Color.WHITE);
        del.setFont(Park.bold(13)); del.setRadius(12);
        del.setPreferredSize(new Dimension(300, 40)); del.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        del.addActionListener(Fmt.act(new Runnable() { public void run() { deleteAccount(); } }));
        col.add(left(del));
        return sec;
    }

    private void deleteAccount() {
        int ok = JOptionPane.showConfirmDialog(this, "Delete your ParkWise account?\nThis cannot be undone.", "Delete Account",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        JPasswordField pw = new JPasswordField(18);
        JPanel pn = new JPanel(new GridLayout(0, 1, 0, 6));
        pn.add(new JLabel("Type your password to confirm:"));
        pn.add(pw);
        if (JOptionPane.showConfirmDialog(this, pn, "Delete Account", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        String err = DataStore.deleteAccount(id, new String(pw.getPassword()));
        if (err != null) {
            JOptionPane.showMessageDialog(this, err, "Delete Account", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(this, "Your account was deleted.\nYou can sign up again with the same username and email.", "Account Deleted", JOptionPane.INFORMATION_MESSAGE);
        dispose();
        if (onDeleted != null) onDeleted.run();
    }
}