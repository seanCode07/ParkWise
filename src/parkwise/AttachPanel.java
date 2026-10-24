package parkwise;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;

/* =====================================================================
   AttachPanel : a button to attach a picture (ID photo or payment receipt) with a small preview.
   The picture is copied into the data folder as soon as it is chosen; stored() gives its stored name ("" if none).
   AttachPanel.view(...) shows a stored picture in a window (used by the admin).
   ===================================================================== */
class AttachPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private final String kind;
    private String stored = "";
    private final JLabel status;
    private final JLabel thumb = new JLabel();
    private final Park.RoundButton btn;

    AttachPanel(String kind, String buttonText, String emptyHint) {
        super(new BorderLayout(10, 0));
        this.kind = kind;
        setOpaque(false);
        btn = new Park.RoundButton(buttonText, Park.BTN_IDLE, Park.TEXT_PRIMARY);
        btn.setFont(Park.bold(12)); btn.setRadius(10); btn.setOutline(Park.BORDER);
        btn.setPreferredSize(new Dimension(150, 38));
        btn.addActionListener(Fmt.act(new Runnable() { public void run() { choose(); } }));
        status = Park.lbl(emptyHint, Park.plain(11), Park.TEXT_MUTED);
        thumb.setPreferredSize(new Dimension(38, 38));
        add(btn, BorderLayout.WEST);
        add(status, BorderLayout.CENTER);
        add(thumb, BorderLayout.EAST);
        setPreferredSize(new Dimension(300, 40));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
    }

    String stored() { return stored; }

    boolean hasPicture() { return !stored.isEmpty(); }

    void clear() {
        if (!stored.isEmpty()) DataStore.discardImage(stored);
        stored = "";
        thumb.setIcon(null);
        status.setText("No picture attached");
        status.setForeground(Park.TEXT_MUTED);
    }

    /** Forget the picture without deleting it (it was handed over to an account or a payment). */
    void release() {
        stored = "";
        thumb.setIcon(null);
        status.setText("No picture attached");
        status.setForeground(Park.TEXT_MUTED);
    }

    private void choose() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose a picture (JPG or PNG)");
        fc.setFileFilter(new FileNameExtensionFilter("Pictures (JPG, PNG, GIF, BMP)", "jpg", "jpeg", "png", "gif", "bmp"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        String name = DataStore.saveImage(f, kind);
        if (name == null) {
            JOptionPane.showMessageDialog(this, "That file could not be used as a picture.\nChoose a JPG or PNG image (at least 50 x 50 pixels, under 20 MB).",
                    "Attach Picture", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!stored.isEmpty()) DataStore.discardImage(stored);
        stored = name;
        BufferedImage im = DataStore.loadImage(name);
        if (im != null) {
            double sc = Math.min(38.0 / im.getWidth(), 38.0 / im.getHeight());
            thumb.setIcon(new ImageIcon(im.getScaledInstance(Math.max(1, (int) (im.getWidth() * sc)), Math.max(1, (int) (im.getHeight() * sc)), Image.SCALE_SMOOTH)));
        }
        String fn = f.getName();
        status.setText("Attached: " + (fn.length() > 24 ? fn.substring(0, 21) + "..." : fn));
        status.setForeground(Park.SUCCESS);
    }

    /** Opens a stored picture in a window, scaled to fit. */
    static void view(Component parent, String title, String storedName) {
        BufferedImage im = DataStore.loadImage(storedName);
        if (im == null) {
            JOptionPane.showMessageDialog(parent, "There is no picture attached.", title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Window w = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog d = new JDialog(w instanceof Frame ? (Frame) w : null, title, true);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        int maxW = Math.min(980, scr.width - 120), maxH = Math.min(700, scr.height - 160);
        double sc = Math.min(1.0, Math.min((double) maxW / im.getWidth(), (double) maxH / im.getHeight()));
        final Image shown = im.getScaledInstance(Math.max(1, (int) (im.getWidth() * sc)), Math.max(1, (int) (im.getHeight() * sc)), Image.SCALE_SMOOTH);
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Park.BG_CARD);
        root.setBorder(Park.pad(12, 12, 12, 12));
        JLabel pic = new JLabel(new ImageIcon(shown));
        pic.setBorder(BorderFactory.createLineBorder(Park.BORDER));
        root.add(pic, BorderLayout.CENTER);
        Park.RoundButton close = Park.primaryBtn("Close");
        close.setPreferredSize(new Dimension(120, 38));
        close.addActionListener(Fmt.act(new Runnable() { public void run() { d.dispose(); } }));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER)); south.setOpaque(false); south.add(close);
        root.add(south, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
    }
}