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
    private static final Pattern USER_RX  = Pattern.compile("[A-Za-z0-9_.]{3,20}");
    private static final Pattern EMAIL_RX = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE_RX = Pattern.compile("09\\d{9}");

    private CardLayout forms;
    private JPanel formPanel, side;
    private Park.Card card;
    private JTextField loginUser, regName, regUser, regPhone, regEmail;
    private JPasswordField loginPass, regPass, regConfirm;
    private JLabel loginStatus, regStatus;
    private JCheckBox consent;
    private JComboBox<String> priorityBox;
    private JTextField regBirth;
    private JPanel birthRow;
    private Park.RoundButton loginBtn, regBtn, roleUser, roleAdmin, adminBtn;
    private JTextField adminUser, regPwd;
    private AttachPanel proofAttach;
    private JPanel proofRow;
    private JPasswordField adminPass;
    private JLabel adminStatus;
    private JPanel pwdIdRow;
    private boolean adminMode = false;

    LoginFrame() {
        Park.styleDialogs();
        setTitle("ParkWise - Login");
        Park.applyIcon(this);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(1000, Math.min(780, scr.height - 40)); setLocationRelativeTo(null); setResizable(false);
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
        col.add(feature("4 floors, cars and motorcycles", "Live map with PWD priority slots on every floor"));
        col.add(Box.createVerticalStrut(12));
        col.add(feature("Time-based billing", "Car " + Park.moneyShort(Park.CAR_RATE) + "/hr  \u00B7  Motorcycle " + Park.moneyShort(Park.MOTO_RATE) + "/hr"));
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
        card.setPreferredSize(new Dimension(440, 560));
        forms = new CardLayout();
        formPanel = new JPanel(forms); formPanel.setOpaque(false);
        formPanel.add(buildLoginPanel(), "login");
        formPanel.add(buildRegisterPanel(), "register");
        formPanel.add(buildAdminPanel(), "admin");
        card.add(formPanel, BorderLayout.CENTER);

        JPanel stack = new JPanel(); stack.setOpaque(false); stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        JPanel roles = buildRoleBar(); roles.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.setAlignmentX(Component.CENTER_ALIGNMENT);
        stack.add(roles); stack.add(Box.createVerticalStrut(14)); stack.add(card);
        side.add(stack);
        return side;
    }

    /** Two big switch buttons so nobody mixes up the parking-user login and the administrator login. */
    private JPanel buildRoleBar() {
        roleUser = new Park.RoundButton("Parking User", Park.ACCENT, Color.WHITE);
        roleAdmin = new Park.RoundButton("Administrator", Park.BTN_IDLE, Park.TEXT_MUTED);
        roleUser.setFont(Park.bold(15)); roleAdmin.setFont(Park.bold(15));
        roleUser.setRadius(14); roleAdmin.setRadius(14);
        roleUser.addActionListener(Fmt.act(new Runnable() { public void run() { setRole(false); } }));
        roleAdmin.addActionListener(Fmt.act(new Runnable() { public void run() { setRole(true); } }));
        JPanel bar = new JPanel(new GridLayout(1, 2, 10, 0)); bar.setOpaque(false);
        bar.add(roleUser); bar.add(roleAdmin);
        bar.setPreferredSize(new Dimension(440, 50)); bar.setMaximumSize(new Dimension(440, 50));
        return bar;
    }

    private JPanel buildAdminPanel() {
        JPanel col = column();
        JLabel badge = Park.lbl("ADMINISTRATOR AREA", Park.bold(11), Park.BG_DARK);
        badge.setOpaque(true); badge.setBackground(Park.WARNING); badge.setBorder(Park.pad(3, 10, 3, 10));
        col.add(left(badge));
        col.add(Box.createVerticalStrut(10));
        col.add(left(Park.lbl("Administrator Sign In", Park.bold(26), Park.TEXT_PRIMARY)));
        col.add(left(Park.lbl("For authorized ParkWise staff only", Park.plain(13), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(22));
        col.add(left(Park.lbl("Admin username", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(4));
        adminUser = Park.field(20); sizeIt(adminUser, 42); col.add(left(adminUser));
        col.add(Box.createVerticalStrut(12));
        col.add(left(Park.lbl("Admin password", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(4));
        adminPass = Park.pass(20); sizeIt(adminPass, 42); col.add(left(adminPass));
        col.add(Box.createVerticalStrut(6));
        JLabel aforgot = Park.lbl("Forgot password?", Park.bold(12), Park.WARNING);
        aforgot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        aforgot.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { new AdminForgotDialog(LoginFrame.this, adminUser.getText().trim()).setVisible(true); }
        });
        JPanel aforgotRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); aforgotRow.setOpaque(false);
        aforgotRow.add(aforgot);
        aforgotRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        col.add(left(aforgotRow));
        col.add(Box.createVerticalStrut(4));
        adminStatus = Park.lbl(" ", Park.plain(12), Park.DANGER);
        col.add(left(adminStatus));
        col.add(Box.createVerticalStrut(8));
        adminBtn = new Park.RoundButton("Admin Login", Park.WARNING, Park.BG_DARK);
        adminBtn.setFont(Park.bold(15)); adminBtn.setRadius(14); sizeIt(adminBtn, 46);
        col.add(left(adminBtn));
        col.add(Box.createVerticalStrut(10));
        col.add(left(Park.lbl("New administrator?", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(6));
        Park.RoundButton req = new Park.RoundButton("Sign Up as Administrator", Park.BTN_IDLE, Park.TEXT_PRIMARY);
        req.setFont(Park.bold(14)); req.setRadius(14); req.setOutline(Park.WARNING); sizeIt(req, 44);
        req.setToolTipText("An existing administrator has to approve your request");
        req.addActionListener(Fmt.act(new Runnable() { public void run() { showAdminRequest(); } }));
        col.add(left(req));
        col.add(Box.createVerticalStrut(4));
        col.add(left(Park.lbl("An existing administrator must approve your sign-up.", Park.plain(11), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(8));
        col.add(left(Park.lbl(Park.lines("Parking users: use the <b>Parking User</b> tab above",
                "to log in or create an account."), Park.plain(12), Park.TEXT_MUTED)));
        adminBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doAdminLogin(); }
        });
        return centered(col);
    }

    private void setRole(boolean admin) {
        adminMode = admin;
        loginStatus.setText(" "); regStatus.setText(" "); adminStatus.setText(" ");
        roleUser.setFill(admin ? Park.BTN_IDLE : Park.ACCENT);
        roleUser.setForeground(admin ? Park.TEXT_MUTED : Color.WHITE);
        roleAdmin.setFill(admin ? Park.WARNING : Park.BTN_IDLE);
        roleAdmin.setForeground(admin ? Park.BG_DARK : Park.TEXT_MUTED);
        card.setLine(admin ? Park.WARNING : Park.BORDER);
        if (admin) {
            forms.show(formPanel, "admin");
            card.setPreferredSize(new Dimension(440, 600));
            getRootPane().setDefaultButton(adminBtn);
            setTitle("ParkWise - Administrator Login");
            adminPass.setText("");
        } else {
            setMode(true);
        }
        side.revalidate(); side.repaint();
    }

    private JPanel column() {
        JPanel c = new JPanel(); c.setOpaque(false);
        c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
        return c;
    }

    private JPanel centered(JPanel col) {
        JPanel wrap = new Park.ScrollView(new GridBagLayout());
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
        JLabel badge = Park.lbl("PARKING USER", Park.bold(11), Color.WHITE);
        badge.setOpaque(true); badge.setBackground(Park.ACCENT); badge.setBorder(Park.pad(3, 10, 3, 10));
        col.add(left(badge));
        col.add(Box.createVerticalStrut(10));
        col.add(left(Park.lbl("Welcome Back", Park.bold(26), Park.TEXT_PRIMARY)));
        col.add(left(Park.lbl("Log in to manage your parking", Park.plain(13), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(18));
        col.add(left(Park.lbl("Email or username", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(4));
        loginUser = Park.field(20); sizeIt(loginUser, 42); col.add(left(loginUser));
        col.add(Box.createVerticalStrut(12));
        col.add(left(Park.lbl("Password", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(4));
        loginPass = Park.pass(20); sizeIt(loginPass, 42); col.add(left(loginPass));
        col.add(Box.createVerticalStrut(6));
        JLabel forgot = Park.lbl("Forgot password?", Park.bold(12), Park.ACCENT);
        forgot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        forgot.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { showForgot(); }
        });
        JPanel forgotRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); forgotRow.setOpaque(false);
        forgotRow.add(forgot);
        forgotRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        col.add(left(forgotRow));
        col.add(Box.createVerticalStrut(4));
        loginStatus = Park.lbl(" ", Park.plain(12), Park.DANGER);
        col.add(left(loginStatus));
        col.add(Box.createVerticalStrut(8));
        loginBtn = Park.primaryBtn("Login"); sizeIt(loginBtn, 46); col.add(left(loginBtn));
        col.add(Box.createVerticalStrut(14));
        col.add(left(Park.lbl("New to ParkWise?", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(6));
        Park.RoundButton signUp = new Park.RoundButton("Sign Up - Create an Account", Park.BTN_IDLE, Park.TEXT_PRIMARY);
        signUp.setFont(Park.bold(14)); signUp.setRadius(14); signUp.setOutline(Park.ACCENT); sizeIt(signUp, 44);
        signUp.addActionListener(Fmt.act(new Runnable() { public void run() { setMode(false); } }));
        col.add(left(signUp));
        loginBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { doLogin(); }
        });
        return centered(col);
    }

    private void addField(JPanel col, String label, JComponent field) {
        col.add(left(Park.lbl(label, Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(2));
        sizeIt(field, 32);
        col.add(left(field));
        col.add(Box.createVerticalStrut(4));
    }

    private JPanel buildRegisterPanel() {
        JPanel col = column();
        col.add(left(Park.lbl("Create Account", Park.bold(24), Park.TEXT_PRIMARY)));
        col.add(Box.createVerticalStrut(6));

        // data privacy reassurance
        Park.Card pc = new Park.Card(new BorderLayout(), 12);
        pc.setFill(Park.alpha(Park.SUCCESS, 26)); pc.setLine(Park.alpha(Park.SUCCESS, 110));
        pc.setBorder(Park.pad(6, 12, 6, 12));
        pc.add(Park.lbl(Park.lines("<b>Your data is protected</b> by the Data Privacy",
                "Act of 2012 (RA 10173)."), Park.plain(12), Park.TEXT_PRIMARY), BorderLayout.CENTER);
        pc.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        col.add(left(pc));
        col.add(Box.createVerticalStrut(8));

        regName = Park.field(20);
        regUser = Park.field(20);
        regPhone = Park.field(20); Fmt.digitsOnly(regPhone, 11);
        regEmail = Park.field(20);
        regPass = Park.pass(20);
        regConfirm = Park.pass(20);
        addField(col, "Full Name", regName);
        addField(col, "Username (3-20 letters, numbers, . or _)", regUser);
        addField(col, "Contact Number (11 digits, e.g. 09171234567)", regPhone);
        addField(col, "Email Address", regEmail);
        addField(col, "Password (at least 8 characters)", regPass);
        addField(col, "Confirm Password", regConfirm);

        // PWD / senior citizen: needed for the PWD / senior citizen slots and the discounted reservation rates (admin verifies the ID)
        col.add(left(Park.lbl("PWD / senior citizen status", Park.plain(12), Park.TEXT_MUTED)));
        col.add(Box.createVerticalStrut(2));
        priorityBox = new JComboBox<String>(new String[]{"Regular driver", "PWD (Person with Disability)", "Senior citizen (age 60 and above)"});
        Fmt.styleCombo(priorityBox);
        sizeIt(priorityBox, 34);
        col.add(left(priorityBox));
        col.add(Box.createVerticalStrut(4));
        regPwd = Park.field(20);
        pwdIdRow = column();
        addField(pwdIdRow, "ID number (the admin verifies it)", regPwd);
        pwdIdRow.setVisible(false);
        col.add(left(pwdIdRow));
        regBirth = Park.field(20);
        birthRow = column();
        addField(birthRow, "Birth date (MM/DD/YYYY)", regBirth);
        birthRow.setVisible(false);
        col.add(left(birthRow));
        proofAttach = new AttachPanel("id", "Attach ID photo", "Required: a clear photo of the ID");
        proofRow = column();
        proofRow.add(left(Park.lbl("Photo of your ID (the admin checks it)", Park.plain(12), Park.TEXT_MUTED)));
        proofRow.add(Box.createVerticalStrut(2));
        proofRow.add(left(proofAttach));
        proofRow.add(Box.createVerticalStrut(4));
        proofRow.setVisible(false);
        col.add(left(proofRow));
        priorityBox.addActionListener(Fmt.act(new Runnable() {
            public void run() {
                pwdIdRow.setVisible(priorityBox.getSelectedIndex() > 0);
                birthRow.setVisible(priorityBox.getSelectedIndex() == 2);
                proofRow.setVisible(priorityBox.getSelectedIndex() > 0);
                formPanel.revalidate(); formPanel.repaint();
            }
        }));
        col.add(Box.createVerticalStrut(4));

        // consent
        consent = new JCheckBox(Park.lines("I agree to the Terms and Conditions and consent to",
                "the processing of my personal data (RA 10173)."));
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
        JScrollPane sp = new JScrollPane(centered(col));
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false); sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        JPanel holder = new JPanel(new BorderLayout()); holder.setOpaque(false);
        holder.add(sp, BorderLayout.CENTER);
        return holder;
    }

    /** The administrator logged in with a temporary password: they must pick a new one before going on. */
    private boolean forceNewPassword(String id, String tempPassword) {
        JPasswordField p1 = new JPasswordField(18), p2 = new JPasswordField(18);
        JPanel pn = new JPanel(new GridLayout(0, 1, 0, 5));
        pn.add(new JLabel("<html>You logged in with a temporary password.<br>Choose a new password to continue.</html>"));
        pn.add(new JLabel("New password (at least 8 characters)")); pn.add(p1);
        pn.add(new JLabel("Confirm new password")); pn.add(p2);
        while (true) {
            if (JOptionPane.showConfirmDialog(this, pn, "Choose a New Password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION)
                return false;
            String a = new String(p1.getPassword()), b = new String(p2.getPassword());
            String msg = a.length() < 8 ? "The password must be at least 8 characters."
                       : !a.equals(b) ? "The passwords do not match."
                       : a.equals(tempPassword) ? "Choose a different password from the temporary one."
                       : DataStore.changePassword(id, tempPassword, a);
            if (msg == null) return true;
            JOptionPane.showMessageDialog(this, msg, "Choose a New Password", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Someone asks for administrator access. It only becomes active after an existing admin approves it. */
    private void showAdminRequest() {
        JTextField u = new JTextField(18), n = new JTextField(18);
        JPasswordField p1 = new JPasswordField(18), p2 = new JPasswordField(18);
        JPanel pn = new JPanel(new GridLayout(0, 1, 0, 5));
        pn.add(new JLabel("<html>Ask for administrator access.<br>An existing administrator must approve your request<br>before you can log in.</html>"));
        pn.add(new JLabel("Username (3-20 letters, numbers, . or _)")); pn.add(u);
        pn.add(new JLabel("Full name")); pn.add(n);
        pn.add(new JLabel("Password (at least 8 characters)")); pn.add(p1);
        pn.add(new JLabel("Confirm password")); pn.add(p2);
        while (true) {
            if (JOptionPane.showConfirmDialog(this, pn, "Request Admin Access", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
            String a = new String(p1.getPassword()), b = new String(p2.getPassword());
            String msg = null;
            if (!USER_RX.matcher(u.getText().trim()).matches()) msg = "Username: 3-20 letters, numbers, dot or underscore.";
            else if (!NAME_RX.matcher(n.getText().trim().replaceAll("\\s+", " ")).matches()) msg = "Enter your full name (letters only).";
            else if (a.length() < 8) msg = "Password must be at least 8 characters.";
            else if (!a.equals(b)) msg = "Passwords do not match.";
            else msg = DataStore.requestAdmin(u.getText().trim(), n.getText().trim().replaceAll("\\s+", " "), a);
            if (msg == null) {
                JOptionPane.showMessageDialog(this, "Request sent. You can log in as soon as an existing administrator approves it.", "Request Sent", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JOptionPane.showMessageDialog(this, msg, "Request Admin Access", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void showForgot() {
        ForgotPasswordDialog d = new ForgotPasswordDialog(this, loginUser.getText().trim());
        d.setVisible(true);
        if (d.resetUser != null) {
            loginUser.setText(d.resetUser);
            loginPass.setText("");
            showLogin("Password changed! Please log in with your new password.", Park.SUCCESS);
        }
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
        if (id == null) { DataStore.recordLogin(user, false, false); showLogin("Invalid email/username or password.", Park.DANGER); return; }
        if (DataStore.isAdmin(id)) {
            showLogin("That is an administrator account. Use the Administrator tab above.", Park.WARNING);
            return;
        }
        DataStore.setActor(id);
        DataStore.recordLogin(id, true, false);
        dispose();
        new MainFrame(id).setVisible(true);
    }

    private void doAdminLogin() {
        String user = adminUser.getText().trim();
        String pw = new String(adminPass.getPassword());
        if (user.isEmpty() || pw.isEmpty()) { adminStatus.setText("Please fill in all fields."); return; }
        String id = DataStore.adminLogin(user, pw);
        if (id == null) {
            DataStore.recordLogin(user, false, true);
            adminStatus.setText(DataStore.adminRequestPending(user)
                ? "Your request is waiting for approval by an existing administrator."
                : "Invalid administrator username or password.");
            return;
        }
        if (DataStore.adminMustChange(id) && !forceNewPassword(id, pw)) {
            adminStatus.setText("You must choose a new password to continue.");
            adminPass.setText("");
            return;
        }
        DataStore.setActor(id);
        DataStore.recordLogin(id, true, true);
        dispose();
        new AdminFrame(id).setVisible(true);
    }

    private void doRegister() {
        String name = regName.getText().trim().replaceAll("\\s+", " ");
        String username = regUser.getText().trim();
        String phone = regPhone.getText().trim();
        String email = regEmail.getText().trim();
        String pw = new String(regPass.getPassword()), pw2 = new String(regConfirm.getPassword());

        if (name.isEmpty() || username.isEmpty() || phone.isEmpty() || email.isEmpty() || pw.isEmpty() || pw2.isEmpty())
            { showReg("Please fill in all fields."); return; }
        if (!NAME_RX.matcher(name).matches())  { showReg("Enter your full name (letters only)."); return; }
        if (!USER_RX.matcher(username).matches()) { showReg("Username: 3-20 letters, numbers, dot or underscore."); return; }
        if (username.equalsIgnoreCase(DataStore.ADMIN_USER)) { showReg("That username is not available."); return; }
        if (!PHONE_RX.matcher(phone).matches()) { showReg("Enter an 11-digit number starting with 09."); return; }
        if (!EMAIL_RX.matcher(email).matches()) { showReg("Enter a valid email address."); return; }
        if (pw.length() < 8)                   { showReg("Password must be at least 8 characters."); return; }
        if (!pw.equals(pw2))                   { showReg("Passwords do not match."); return; }
        if (!consent.isSelected())             { showReg("Please accept the Terms and give your consent."); return; }
        String ptype = "", pid = "", birth = "";
        if (priorityBox.getSelectedIndex() > 0 && !proofAttach.hasPicture()) { showReg("Attach a clear photo of your ID."); return; }
        if (priorityBox.getSelectedIndex() == 0 && proofAttach.hasPicture()) proofAttach.clear();
        if (priorityBox.getSelectedIndex() > 0) {
            ptype = priorityBox.getSelectedIndex() == 2 ? DataStore.PRIORITY_SENIOR : DataStore.PRIORITY_PWD;
            pid = regPwd.getText().trim();
            if (!Fmt.validPriorityId(pid)) { showReg("Enter your ID number (4-30 letters/digits)."); return; }
            if (priorityBox.getSelectedIndex() == 2) {
                birth = Fmt.parseBirth(regBirth.getText());
                if (birth == null) { showReg("Enter your birth date as MM/DD/YYYY."); return; }
                if (Fmt.ageOf(birth) < DataStore.SENIOR_AGE) { showReg("Senior citizen is for ages " + DataStore.SENIOR_AGE + " and above."); return; }
            }
        }

        String err = DataStore.registerUser(username, name, phone, email, pw, ptype, pid, birth, proofAttach.stored());
        if (err != null) { showReg(err); return; }
        regName.setText(""); regUser.setText(""); regPhone.setText(""); regEmail.setText("");
        regPass.setText(""); regConfirm.setText(""); consent.setSelected(false);
        proofAttach.release();
        priorityBox.setSelectedIndex(0); regPwd.setText(""); regBirth.setText("");
        setMode(true);
        loginUser.setText(username.toLowerCase());
        showLogin("Account created! Please log in.", Park.SUCCESS);
    }

    private void showLogin(String msg, Color c) { loginStatus.setForeground(c); loginStatus.setText(msg); }
    private void showReg(String msg) { regStatus.setForeground(Park.DANGER); regStatus.setText(msg); }

    private void setMode(boolean login) {
        loginStatus.setText(" "); regStatus.setText(" ");
        loginPass.setText("");
        forms.show(formPanel, login ? "login" : "register");
        card.setPreferredSize(new Dimension(440, login ? 560 : 640));
        getRootPane().setDefaultButton(login ? loginBtn : regBtn);
        side.revalidate(); side.repaint();
        setTitle(login ? "ParkWise - Login" : "ParkWise - Create Account");
    }
}