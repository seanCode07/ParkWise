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
   LoginFrame : landscape split screen (branding on the left, form on the right)
   ===================================================================== */
class LoginFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final Pattern NAME_RX  = Pattern.compile("[\\p{L}][\\p{L} .'\\-]{1,49}");
    private static final Pattern EMAIL_RX = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE_RX = Pattern.compile("09\\d{9}");

    private CardLayout forms;
    private JPanel formPanel, side;
    private Park.Card card;
    private JTextField loginUser, regName, regPhone, regEmail;
    private JPasswordField loginPass, regPass, regConfirm;
    private JLabel loginStatus, regStatus;
    private JCheckBox consent;
    private Park.RoundButton loginBtn, regBtn;

    LoginFrame() {
        Park.styleDialogs();
        setTitle("ParkWise - Login");
        Park.applyIcon(this);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1000, 700); setLocationRelativeTo(null); setResizable(false);
        buildUI();
    }

    private void buildUI() {
        Park.BgPanel root = new Park.BgPanel(new GridLayout(1, 2));
        root.add(buildBrand());
        root.add(buildFormSide());
        setContentPane(root);
        getRootPane().setDefaultButton(loginBtn);
    }

    // ---------- left side: logo and highlights ----------
    private JPanel buildBrand() {
        JPanel p = new JPanel(new GridBagLayout()); p.setOpaque(false);
        JPanel col = new JPanel(); col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        boolean logoOk = Park.hasLogo();
        Park.LogoView logo = new Park.LogoView(logoOk ? 240 : 92);
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);
        col.add(logo);
        col.add(Box.createVerticalStrut(14));
        if (!logoOk) {   // the logo already contains the name; only add text when it is missing
            JLabel title = Park.lbl("ParkWise", Park.bold(44), Park.TEXT_PRIMARY);
            title.setAlignmentX(Component.CENTER_ALIGNMENT);
            col.add(title); col.add(Box.createVerticalStrut(6));
        }
        JLabel sub = Park.lbl("Real-Time Parking Slot Management", Park.plain(15), Park.TEXT_MUTED);
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        col.add(sub);
        col.add(Box.createVerticalStrut(26));
        col.add(feature("Live slot availability", "See which slots are free, reserved or taken"));
        col.add(Box.createVerticalStrut(12));
        col.add(feature("Time-based billing", Park.money(Park.RATE) + " per hour, with a live timer"));
        col.add(Box.createVerticalStrut(12));
        col.add(feature("Everything is saved", "Accounts and bookings stay saved locally"));
        p.add(col);
        return p;
    }

    private JPanel feature(String title, String sub) {
        Park.Card c = new Park.Card(new BorderLayout(), 16);
        c.setBorder(Park.pad(12, 18, 12, 18));
        JPanel t = new JPanel(); t.setOpaque(false); t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
        t.add(Park.lbl(title, Park.bold(14), Park.TEXT_PRIMARY));
        t.add(Park.lbl(sub, Park.plain(12), Park.TEXT_MUTED));
        c.add(t, BorderLayout.CENTER);
        c.setPreferredSize(new Dimension(380, 62)); c.setMaximumSize(new Dimension(380, 62));
        c.setAlignmentX(Component.CENTER_ALIGNMENT);
        return c;
    }

    // ---------- right side: login / register forms ----------
    private JPanel buildFormSide() {
        side = new JPanel(new GridBagLayout()); side.setOpaque(false);
        card = new Park.Card(new BorderLayout(), 24);
        card.setBorder(Park.pad(26, 34, 22, 34));
        card.setPreferredSize(new Dimension(440, 470));
        forms = new CardLayout();
        formPanel = new JPanel(forms); formPanel.setOpaque(false);
        formPanel.add(buildLoginPanel(), "login");
        formPanel.add(buildRegisterPanel(), "register");
        card.add(formPanel, BorderLayout.CENTER);
        side.add(card);
        return side;
    }

    private JPanel column() {
        JPanel c = new JPanel(); c.setOpaque(false);
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        return c;
    }

    private JPanel centered(JPanel col) {
        JPanel wrap = new JPanel(new GridBagLayout()); wrap.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        wrap.add(col, gc);
        return wrap;
    }

    private <T extends JComponent> T left(T c) { c.setAlignmentX(Component.LEFT_ALIGNMENT); return c; }

    private void sizeIt(JComponent c, int h) {
        c.setPreferredSize(new Dimension(300, h));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
    }

    private JPanel toggleRow(String prompt, String link, final boolean toLogin) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0)); row.setOpaque(false);
        JLabel l1 = Park.lbl(prompt, Park.plain(12), Park.TEXT_MUTED);
        JLabel l2 = Park.lbl(link, Park.bold(12), Park.ACCENT);
        l2.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l2.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { setMode(toLogin); }
        });
        row.add(l1); row.add(l2);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private JPanel buildLoginPanel() {
        JPanel col = column();
        col.add(left(Park.lbl("Welcome Back", Park.bold(26), Park.TEXT_PRIMARY)));
        col.add(left(Park.lbl("Log in to manage your parking", Park.plain(13), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(22));
        col.add(left(Park.lbl("Email or username", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(4));
        loginUser = Park.field(20); sizeIt(loginUser, 42); col.add(left(loginUser));
        col.add(Box.createVerticalStrut(12));
        col.add(left(Park.lbl("Password", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(4));
        loginPass = Park.pass(20); sizeIt(loginPass, 42); col.add(left(loginPass));
        col.add(Box.createVerticalStrut(8));
        loginStatus = Park.lbl(" ", Park.plain(12), Park.DANGER);
        col.add(left(loginStatus));
        col.add(Box.createVerticalStrut(8));
        loginBtn = Park.primaryBtn("Login"); sizeIt(loginBtn, 46); col.add(left(loginBtn));
        col.add(Box.createVerticalStrut(14));
        col.add(toggleRow("Don't have an account?", "Register here", false));
        loginBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doLogin(); }
        });
        return centered(col);
    }

    private void addField(JPanel col, String label, JComponent field) {
        col.add(left(Park.lbl(label, Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(3));
        sizeIt(field, 34);
        col.add(left(field));
        col.add(Box.createVerticalStrut(6));
    }

    private JPanel buildRegisterPanel() {
        JPanel col = column();
        col.add(left(Park.lbl("Create Account", Park.bold(24), Park.TEXT_PRIMARY)));
        col.add(Box.createVerticalStrut(8));

        // data privacy reassurance
        Park.Card pc = new Park.Card(new BorderLayout(), 12);
        pc.setFill(Park.alpha(Park.SUCCESS, 26)); pc.setLine(Park.alpha(Park.SUCCESS, 110));
        pc.setBorder(Park.pad(8, 12, 8, 12));
        pc.add(Park.lbl(Park.lines("<b>Your data is protected.</b>",
                "The Data Privacy Act of 2012 (RA 10173) covers",
                "the details you enter below."), Park.plain(12), Park.TEXT_PRIMARY), BorderLayout.CENTER);
        pc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        col.add(left(pc));
        col.add(Box.createVerticalStrut(8));

        regName = Park.field(20);
        regPhone = Park.field(20); digitsOnly(regPhone, 11);
        regEmail = Park.field(20);
        regPass = Park.pass(20);
        regConfirm = Park.pass(20);
        addField(col, "Full Name", regName);
        addField(col, "Contact Number (11 digits, e.g. 09171234567)", regPhone);
        addField(col, "Email Address", regEmail);
        addField(col, "Password (at least 8 characters)", regPass);
        addField(col, "Confirm Password", regConfirm);

        // consent
        consent = new JCheckBox(Park.lines("I agree to the Terms and Conditions and consent",
                "to the processing of my personal data under",
                "RA 10173 (Data Privacy Act of 2012)."));
        consent.setOpaque(false); consent.setContentAreaFilled(false); consent.setFocusPainted(false);
        consent.setForeground(Park.TEXT_PRIMARY); consent.setFont(Park.plain(12));
        consent.setIcon(new Park.CheckIcon(false)); consent.setSelectedIcon(new Park.CheckIcon(true));
        consent.setIconTextGap(10);
        consent.setVerticalAlignment(SwingConstants.TOP); consent.setVerticalTextPosition(SwingConstants.TOP);
        consent.setBorder(Park.pad(0, 0, 0, 0));
        consent.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        col.add(left(consent));
        col.add(Box.createVerticalStrut(4));

        JLabel link = Park.lbl("Read the full Terms and Privacy Notice", Park.bold(12), Park.ACCENT);
        link.setBorder(Park.pad(0, 30, 0, 0));
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { showTerms(); }
        });
        col.add(left(link));
        col.add(Box.createVerticalStrut(6));

        regStatus = Park.lbl(" ", Park.plain(12), Park.DANGER);
        col.add(left(regStatus));
        col.add(Box.createVerticalStrut(4));
        regBtn = Park.primaryBtn("Create Account"); sizeIt(regBtn, 44); col.add(left(regBtn));
        regBtn.setEnabled(false);                 // switched on once the consent box is ticked
        col.add(Box.createVerticalStrut(10));
        col.add(toggleRow("Already have an account?", "Login here", true));

        consent.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) { regBtn.setEnabled(consent.isSelected()); }
        });
        regBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doRegister(); }
        });
        return centered(col);
    }

    /** Accepts digits only, up to max characters (for the phone number). */
    private void digitsOnly(JTextField tf, final int max) {
        ((AbstractDocument) tf.getDocument()).setDocumentFilter(new DocumentFilter() {
            public void insertString(FilterBypass fb, int off, String str, javax.swing.text.AttributeSet a)
                    throws BadLocationException {
                replace(fb, off, 0, str, a);
            }
            public void replace(FilterBypass fb, int off, int len, String str, javax.swing.text.AttributeSet a)
                    throws BadLocationException {
                String t = str == null ? "" : str.replaceAll("\\D", "");
                int room = max - (fb.getDocument().getLength() - len);
                if (t.length() > room) t = t.substring(0, Math.max(0, room));
                super.replace(fb, off, len, t, a);
            }
        });
    }

    private void showTerms() {
        TermsDialog d = new TermsDialog(this);
        d.setVisible(true);
        if (d.agreed) consent.setSelected(true);
    }

    // ---------- actions ----------
    private void doLogin() {
        String user = loginUser.getText().trim();
        String pw = new String(loginPass.getPassword());
        if (user.isEmpty() || pw.isEmpty()) { showLogin("Please fill in all fields.", Park.DANGER); return; }
        String id = DataStore.authenticate(user, pw);
        if (id == null) { showLogin("Invalid email/username or password.", Park.DANGER); return; }
        dispose();
        if (DataStore.isAdmin(id)) new AdminFrame(id).setVisible(true);
        else new MainFrame(id).setVisible(true);
    }

    private void doRegister() {
        String name = regName.getText().trim().replaceAll("\\s+", " ");
        String phone = regPhone.getText().trim();
        String email = regEmail.getText().trim();
        String pw = new String(regPass.getPassword()), pw2 = new String(regConfirm.getPassword());

        if (name.isEmpty() || phone.isEmpty() || email.isEmpty() || pw.isEmpty() || pw2.isEmpty())
            { showReg("Please fill in all fields."); return; }
        if (!NAME_RX.matcher(name).matches())  { showReg("Enter your full name (letters only)."); return; }
        if (!PHONE_RX.matcher(phone).matches()) { showReg("Enter an 11-digit number starting with 09."); return; }
        if (!EMAIL_RX.matcher(email).matches()) { showReg("Enter a valid email address."); return; }
        if (pw.length() < 8)                   { showReg("Password must be at least 8 characters."); return; }
        if (!pw.equals(pw2))                   { showReg("Passwords do not match."); return; }
        if (!consent.isSelected())             { showReg("Please accept the Terms and give your consent."); return; }

        String err = DataStore.registerUser(name, phone, email, pw);
        if (err != null) { showReg(err); return; }
        regName.setText(""); regPhone.setText(""); regEmail.setText("");
        regPass.setText(""); regConfirm.setText(""); consent.setSelected(false);
        setMode(true);
        loginUser.setText(email.toLowerCase());
        showLogin("Account created! Please log in.", Park.SUCCESS);
    }

    private void showLogin(String msg, Color c) { loginStatus.setForeground(c); loginStatus.setText(msg); }
    private void showReg(String msg) { regStatus.setForeground(Park.DANGER); regStatus.setText(msg); }

    private void setMode(boolean login) {
        loginStatus.setText(" "); regStatus.setText(" ");
        loginPass.setText("");
        forms.show(formPanel, login ? "login" : "register");
        card.setPreferredSize(new Dimension(440, login ? 470 : 640));
        getRootPane().setDefaultButton(login ? loginBtn : regBtn);
        side.revalidate(); side.repaint();
        setTitle(login ? "ParkWise - Login" : "ParkWise - Create Account");
    }
}