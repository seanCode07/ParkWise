package parkwise;

import java.awt.Component;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;

/** Small helpers shared by the dashboard, the booking dialog and the admin screen. */
final class Fmt {
    private Fmt() { }

    /**
     * Plate number = 1 to 3 letters followed by 1 to 4 digits, e.g. ABC 1234, AB 123. Nothing else is accepted.
     */
    static final Pattern PLATE_RX = Pattern.compile("[A-Z]{1,3}[0-9]{1,4}");

    static String normalizePlate(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ENGLISH);
    }

    static boolean validPlate(String s) {
        return PLATE_RX.matcher(normalizePlate(s)).matches();
    }

    /** ABC1234 -> "ABC 1234" (anything else is shown as stored). */
    static String formatPlate(String p) {
        if (p == null || p.isEmpty()) return "-";
        java.util.regex.Matcher m = Pattern.compile("([A-Z]{1,3})([0-9]{1,4})").matcher(p);
        if (m.matches()) return m.group(1) + " " + m.group(2);
        return p;
    }

    /** Asks for a plate number until a valid one is typed. Returns the normalized plate, or null if cancelled. */
    static String askPlate(Component parent, String initial) {
        String msg = "Enter your vehicle's plate number: up to 3 letters followed by up to 4 digits\n"
                   + "(for example ABC 1234 or AB 123). It is required for every booking, for safety.";
        String shown = (initial == null || initial.isEmpty()) ? "" : formatPlate(initial);
        while (true) {
            Object in = JOptionPane.showInputDialog(parent, msg, "Plate Number",
                    JOptionPane.QUESTION_MESSAGE, null, null, shown);
            if (in == null) return null;
            String p = normalizePlate(in.toString());
            if (validPlate(p)) return p;
            msg = "That is not a valid plate number.\nUse up to 3 letters followed by up to 4 digits (e.g. ABC 1234).";
            shown = in.toString();
        }
    }

    /**
     * Fixes a typo in a saved plate number. The driver picks the plate (if there are several) and types the correct one.
     * Returns {oldPlate, newPlate} when it was changed, otherwise null.
     */
    static String[] editPlate(Component parent, String user, String current) {
        List<String> known = DataStore.getPlates(user);
        if (known.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "You have no saved plate numbers yet.", "Edit Plate Number", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        String old = current != null && known.contains(current) ? current : known.get(0);
        if (known.size() > 1) {
            Object[] items = new Object[known.size()];
            Object initial = null;
            for (int i = 0; i < items.length; i++) { items[i] = formatPlate(known.get(i)); if (known.get(i).equals(old)) initial = items[i]; }
            Object sel = JOptionPane.showInputDialog(parent, "Which plate number do you want to correct?",
                    "Edit Plate Number", JOptionPane.QUESTION_MESSAGE, null, items, initial);
            if (sel == null) return null;
            for (int i = 0; i < items.length; i++) if (items[i].equals(sel)) old = known.get(i);
        }
        String msg = "Type the correct plate number for " + formatPlate(old) + ".\n"
                   + "Up to 3 letters followed by up to 4 digits. The admin is told about every change.";
        String shown = formatPlate(old);
        while (true) {
            Object in = JOptionPane.showInputDialog(parent, msg, "Edit Plate Number", JOptionPane.QUESTION_MESSAGE, null, null, shown);
            if (in == null) return null;
            String p = normalizePlate(in.toString());
            if (!validPlate(p)) {
                msg = "That is not a valid plate number.\nUse up to 3 letters followed by up to 4 digits (e.g. ABC 1234).";
                shown = in.toString();
                continue;
            }
            if (p.equals(old)) return null;
            String err = DataStore.renamePlate(user, old, p);
            if (err == null) return new String[]{old, p};
            msg = err; shown = in.toString();
        }
    }

    // ---------- PWD / senior citizen ID ----------
    static boolean validPriorityId(String s) {
        return s != null && s.trim().matches("[A-Za-z0-9\\-/ ]{4,30}");
    }

    /** Birth date typed as MM/DD/YYYY. Returns the date as yyyy-MM-dd, or null if it is not a real date in the past. */
    static String parseBirth(String text) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("MM/dd/yyyy", Locale.ENGLISH);
            in.setLenient(false);
            Date d = in.parse(text.trim());
            if (d.after(new Date())) return null;
            Calendar c = Calendar.getInstance(); c.setTime(d);
            if (c.get(Calendar.YEAR) < 1900) return null;
            return new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(d);
        } catch (Exception e) {
            return null;
        }
    }

    /** Age in whole years of a yyyy-MM-dd birth date (-1 if it cannot be read). */
    static int ageOf(String isoBirth) {
        try {
            Calendar b = Calendar.getInstance();
            b.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(isoBirth));
            Calendar n = Calendar.getInstance();
            int age = n.get(Calendar.YEAR) - b.get(Calendar.YEAR);
            if (n.get(Calendar.DAY_OF_YEAR) < b.get(Calendar.DAY_OF_YEAR)) age--;
            return age;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Lets the driver pick which of their vehicles (plate numbers) to use, or add another one.
     * Returns the chosen normalized plate, or null if cancelled.
     */
    static String choosePlate(Component parent, String user, String current) {
        List<String> known = DataStore.getPlates(user);
        if (known.isEmpty()) {
            String p = askPlate(parent, current);
            if (p != null) DataStore.addPlate(user, p);
            return p;
        }
        final String addNew = "+ Add another vehicle (new plate number)";
        List<String> plates = new ArrayList<String>(known);
        Object[] items = new Object[plates.size() + 1];
        Object initial = null;
        for (int i = 0; i < plates.size(); i++) {
            items[i] = formatPlate(plates.get(i));
            if (plates.get(i).equals(current)) initial = items[i];
        }
        items[items.length - 1] = addNew;
        if (initial == null) initial = items[0];
        Object sel = JOptionPane.showInputDialog(parent,
                "Which vehicle are you parking?\nYou can park more than one vehicle - add each plate number once.",
                "Vehicle", JOptionPane.QUESTION_MESSAGE, null, items, initial);
        if (sel == null) return null;
        if (addNew.equals(sel)) {
            String p = askPlate(parent, null);
            if (p != null) DataStore.addPlate(user, p);
            return p;
        }
        for (int i = 0; i < plates.size(); i++) if (items[i].equals(sel)) return plates.get(i);
        return null;
    }

    /** Accepts digits only, up to max characters (for phone numbers). */
    static void digitsOnly(JTextField tf, final int max) {
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

    // ---------- personal information rules (used at sign-up and in Settings) ----------
    static final Pattern NAME_RX  = Pattern.compile("[\\p{L}][\\p{L} .'\\-]{1,49}");
    static final Pattern USER_RX  = Pattern.compile("[A-Za-z0-9_.]{3,20}");
    static final Pattern EMAIL_RX = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    static final Pattern PHONE_RX = Pattern.compile("09\\d{9}");

    // ---------- payment reference ----------
    /** InstaPay reference numbers: 8 to 25 letters/digits. */
    static final Pattern REF_RX = Pattern.compile("[A-Z0-9]{8,25}");

    static String normalizeRef(String s) {
        return s == null ? "" : s.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ENGLISH);
    }

    static boolean validRef(String s) { return REF_RX.matcher(normalizeRef(s)).matches(); }

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
        cb.setBackground(Park.FIELD_BG);
        cb.setForeground(Park.TEXT_PRIMARY);
        cb.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foc);
                c.setBackground(sel ? Park.SEL_BG : Park.LIST_BG);
                c.setForeground(Park.TEXT_PRIMARY);
                c.setFont(Park.plain(14));
                c.setBorder(Park.pad(5, 10, 5, 10));
                return c;
            }
        });
    }
}