package parkwise;

import java.awt.Component;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;

/** Small helpers shared by the dashboard, the booking dialog and the admin screen. */
final class Fmt {
    private Fmt() { }

    /**
     * Philippine plate number = 7 characters (letters and digits), e.g. ABC 1234.
     * To accept ONLY the standard "3 letters + 4 digits" format, change this to "[A-Z]{3}\\d{4}".
     */
    static final Pattern PLATE_RX = Pattern.compile("[A-Z0-9]{7}");

    static String normalizePlate(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ENGLISH);
    }

    static boolean validPlate(String s) {
        return PLATE_RX.matcher(normalizePlate(s)).matches();
    }

    /** ABC1234 -> "ABC 1234" (anything else is shown as stored). */
    static String formatPlate(String p) {
        if (p == null || p.isEmpty()) return "-";
        if (p.matches("[A-Z]{3}\\d{4}")) return p.substring(0, 3) + " " + p.substring(3);
        return p;
    }

    /** Asks for a plate number until a valid one is typed. Returns the normalized plate, or null if cancelled. */
    static String askPlate(Component parent, String initial) {
        String msg = "Enter your vehicle's plate number (7 characters, e.g. ABC 1234).\n"
                   + "It is required for every booking, for safety.";
        String shown = (initial == null || initial.isEmpty()) ? "" : formatPlate(initial);
        while (true) {
            Object in = JOptionPane.showInputDialog(parent, msg, "Plate Number",
                    JOptionPane.QUESTION_MESSAGE, null, null, shown);
            if (in == null) return null;
            String p = normalizePlate(in.toString());
            if (validPlate(p)) return p;
            msg = "That is not a valid plate number.\nPlease enter exactly 7 letters/digits (e.g. ABC 1234).";
            shown = in.toString();
        }
    }

    // ---------- time ----------
    /** Minutes after midnight -> "2:30 PM". */
    static String time(int min) {
        int h = (min / 60) % 24, m = min % 60;
        int h12 = (h % 12 == 0) ? 12 : h % 12;
        return h12 + ":" + (m < 10 ? "0" : "") + m + " " + (h < 12 ? "AM" : "PM");
    }

    static String timeRange(int s, int e) { return time(s) + " - " + time(e); }

    /** Shorter range for narrow places: "2:00 - 4:00 PM" (or the full form if AM/PM differ). */
    static String timeShort(int s, int e) {
        boolean sameAp = ((s / 60) % 24 < 12) == ((e / 60) % 24 < 12);
        if (sameAp) {
            String a = time(s);
            return a.substring(0, a.length() - 3) + " - " + time(e);
        }
        return timeRange(s, e);
    }

    static int minuteOfDay(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** Earliest start (in minutes) that may be picked for this date: the current half hour today, 0 otherwise. */
    static int startFloor(String date) {
        if (!date.equals(Park.today())) return 0;
        return (minuteOfDay(System.currentTimeMillis()) / DataStore.STEP_MIN) * DataStore.STEP_MIN;
    }

    /** "Oct 14" */
    static String dayMonth(String date) {
        return new SimpleDateFormat("MMM dd", Locale.ENGLISH).format(Park.parseDate(date));
    }

    // ---------- swing ----------
    static ActionListener act(final Runnable r) {
        return new ActionListener() {
            public void actionPerformed(ActionEvent e) { r.run(); }
        };
    }

    /** Dark theme for a combo box and its drop-down list. */
    static void styleCombo(JComboBox<String> cb) {
        cb.setFont(Park.plain(14));
        cb.setBackground(new Color(12, 12, 28));
        cb.setForeground(Park.TEXT_PRIMARY);
        cb.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foc);
                c.setBackground(sel ? new Color(64, 52, 140) : new Color(18, 18, 40));
                c.setForeground(Park.TEXT_PRIMARY);
                c.setFont(Park.plain(14));
                c.setBorder(Park.pad(5, 10, 5, 10));
                return c;
            }
        });
    }
}