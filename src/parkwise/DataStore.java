package parkwise;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DataStore {

    // ---------------- Settings ----------------

    /** The built-in moderator account. Change the default password after the first login (or edit here). */
    public static final String ADMIN_USER = "admin";
    private static final String ADMIN_DEFAULT_PASSWORD = "admin123";

    public static final long HOUR_MS = 3600000L;
    /** Minutes after the paid time ends before overtime starts being billed. */
    public static final int GRACE_MINUTES = 5;
    public static final long GRACE_MS = GRACE_MINUTES * 60000L;
    /** A parked booking this far past its paid time is flagged as "possibly forgotten" for the admin. */
    public static final int FORGOT_HOURS = 2;

    // Data is saved in a "parkwise_data" folder next to where the program runs
    private static final File DIR        = new File("parkwise_data");
    private static final File USERS_FILE = new File(DIR, "users.txt");
    private static final File RES_FILE   = new File(DIR, "reservations.txt");
    private static final File FINES_FILE = new File(DIR, "fines.txt");

    private static Map<String, String> users = new HashMap<String, String>();
    private static Map<String, ReservationInfo> reservations = new LinkedHashMap<String, ReservationInfo>();
    private static List<Fine> fines = new ArrayList<Fine>();

    static {
        load();
        if (!users.containsKey(ADMIN_USER)) {
            users.put(ADMIN_USER, hash(ADMIN_USER, ADMIN_DEFAULT_PASSWORD));
            saveUsers();
        }
    }

    // ---------------- Accounts ----------------

    public static boolean isAdmin(String username) {
        return ADMIN_USER.equals(username);
    }

    public static synchronized boolean registerUser(String username, String password) {
        if (username.equalsIgnoreCase(ADMIN_USER)) return false;
        if (users.containsKey(username)) return false;
        users.put(username, hash(username, password));
        saveUsers();
        return true;
    }

    public static synchronized boolean loginUser(String username, String password) {
        String stored = users.get(username);
        return stored != null && stored.equals(hash(username, password));
    }

    private static String hash(String username, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((username + ":" + password).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------- Reservations ----------------

    private static String makeKey(String date, String slot) {
        return date + "||" + slot;
    }

    // 0 = available, 1 = reserved, 2 = occupied
    public static synchronized int getSlotStatus(String date, String slot) {
        ReservationInfo r = reservations.get(makeKey(date, slot));
        if (r == null) return 0;
        return r.status;
    }

    public static synchronized ReservationInfo getReservation(String date, String slot) {
        return reservations.get(makeKey(date, slot));
    }

    public static synchronized void reserveSlot(String date, String slot, ReservationInfo info) {
        reservations.put(makeKey(date, slot), info);
        saveReservations();
    }

    // Changes a booking from reserved (1) to occupied (2). Parking starts the timer.
    public static synchronized void setStatus(String date, String slot, int status) {
        ReservationInfo r = reservations.get(makeKey(date, slot));
        if (r != null) {
            r.status = status;
            r.startMillis = (status == 2) ? System.currentTimeMillis() : 0L;
            saveReservations();
        }
    }

    // Used for cancelling a reservation (no billing involved)
    public static synchronized void clearSlot(String date, String slot) {
        reservations.remove(makeKey(date, slot));
        saveReservations();
    }

    /**
     * Ends a parked session. If the car stayed past the paid time (plus grace), an overtime
     * fine is recorded and returned. Returns null when there is nothing to bill.
     * endedBy is "User" or "Admin"; paidNow tells whether the fee was collected right away.
     */
    public static synchronized Fine checkout(String date, String slot, String endedBy, boolean paidNow) {
        ReservationInfo r = reservations.remove(makeKey(date, slot));
        if (r == null) return null;
        saveReservations();
        long now = System.currentTimeMillis();
        int ot = r.billableHours(now);
        if (r.status != 2 || ot <= 0) return null;
        Fine f = new Fine(r.user, r.date, r.slot, r.hours, ot, ot * Park.RATE, endedBy, now, paidNow);
        fines.add(f);
        saveFines();
        return f;
    }

    public static synchronized List<ReservationInfo> getReservationsForUser(String username) {
        List<ReservationInfo> list = new ArrayList<ReservationInfo>();
        for (ReservationInfo info : reservations.values()) {
            if (info.user.equals(username)) {
                list.add(info);
            }
        }
        return list;
    }

    public static synchronized List<ReservationInfo> getAllReservations() {
        return new ArrayList<ReservationInfo>(reservations.values());
    }

    // ---------------- Overtime fines ----------------

    public static synchronized List<Fine> getAllFines() {
        return new ArrayList<Fine>(fines);
    }

    public static synchronized List<Fine> getUnpaidFinesForUser(String username) {
        List<Fine> list = new ArrayList<Fine>();
        for (Fine f : fines) {
            if (f.user.equals(username) && !f.paid) list.add(f);
        }
        return list;
    }

    public static synchronized double getUnpaidTotal() {
        double t = 0;
        for (Fine f : fines) if (!f.paid) t += f.fee;
        return t;
    }

    public static synchronized void payFine(Fine f) {
        f.paid = true;
        saveFines();
    }

    // ---------------- Saving and loading ----------------

    private static void saveUsers() {
        BufferedWriter w = null;
        try {
            DIR.mkdirs();
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(USERS_FILE), "UTF-8"));
            for (Map.Entry<String, String> e : users.entrySet()) {
                w.write(e.getKey() + "\t" + e.getValue());
                w.newLine();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            close(w);
        }
    }

    private static void saveReservations() {
        BufferedWriter w = null;
        try {
            DIR.mkdirs();
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(RES_FILE), "UTF-8"));
            for (ReservationInfo r : reservations.values()) {
                w.write(r.user + "\t" + r.date + "\t" + r.slot + "\t" + r.hours + "\t"
                        + r.price + "\t" + r.status + "\t" + r.paymentMode + "\t" + r.startMillis);
                w.newLine();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            close(w);
        }
    }

    private static void saveFines() {
        BufferedWriter w = null;
        try {
            DIR.mkdirs();
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(FINES_FILE), "UTF-8"));
            for (Fine f : fines) {
                w.write(f.user + "\t" + f.date + "\t" + f.slot + "\t" + f.paidHours + "\t"
                        + f.overtimeHours + "\t" + f.fee + "\t" + f.endedBy + "\t"
                        + f.createdMillis + "\t" + (f.paid ? "1" : "0"));
                w.newLine();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            close(w);
        }
    }

    private static void load() {
        BufferedReader r = null;
        boolean needResave = false;
        try {
            if (USERS_FILE.exists()) {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(USERS_FILE), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    if (p.length == 2) users.put(p[0], p[1]);
                }
                close(r);
            }
            if (RES_FILE.exists()) {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(RES_FILE), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    // 7 columns = older save files (no parking timer yet), 8 = current
                    if (p.length != 7 && p.length != 8) continue;
                    try {
                        int status = Integer.parseInt(p[5]);
                        long start = (p.length == 8) ? Long.parseLong(p[7]) : 0L;
                        if (status == 2 && start <= 0) {
                            start = System.currentTimeMillis();   // old booking: start counting from now
                            needResave = true;
                        }
                        ReservationInfo info = new ReservationInfo(p[0], p[1], p[2],
                                Integer.parseInt(p[3]), Double.parseDouble(p[4]),
                                status, p[6], start);
                        reservations.put(makeKey(info.date, info.slot), info);
                    } catch (NumberFormatException ignore) {
                        // skip a damaged line
                    }
                }
                close(r);
            }
            if (FINES_FILE.exists()) {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(FINES_FILE), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    if (p.length != 9) continue;
                    try {
                        fines.add(new Fine(p[0], p[1], p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                                Double.parseDouble(p[5]), p[6], Long.parseLong(p[7]), "1".equals(p[8])));
                    } catch (NumberFormatException ignore) {
                        // skip a damaged line
                    }
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            close(r);
        }
        if (needResave) saveReservations();
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignore) { }
        }
    }

    // ---------------- Data classes ----------------

    public static class ReservationInfo {
        public String user;
        public String date;
        public String slot;
        public int    hours;          // hours paid for
        public double price;
        public int    status;         // 1 = reserved, 2 = parked
        public String paymentMode;
        public long   startMillis;    // when the car was parked (0 while only reserved)

        public ReservationInfo(String user, String date, String slot,
                               int hours, double price, int status, String paymentMode) {
            this(user, date, slot, hours, price, status, paymentMode,
                 status == 2 ? System.currentTimeMillis() : 0L);
        }

        public ReservationInfo(String user, String date, String slot,
                               int hours, double price, int status, String paymentMode, long startMillis) {
            this.user        = user;
            this.date        = date;
            this.slot        = slot;
            this.hours       = hours;
            this.price       = price;
            this.status      = status;
            this.paymentMode = paymentMode;
            this.startMillis = startMillis;
        }

        /** Time left of the paid hours (negative once the paid time has run out). Parked bookings only. */
        public long remainingMillis(long now) {
            return hours * HOUR_MS - (now - startMillis);
        }

        /** How long the car has stayed past the paid time (0 if still within it). */
        public long overStayMillis(long now) {
            if (status != 2 || startMillis <= 0) return 0L;
            return Math.max(0L, -remainingMillis(now));
        }

        /** Overtime hours to bill: free grace period first, then every started hour counts. */
        public int billableHours(long now) {
            long billable = overStayMillis(now) - GRACE_MS;
            if (billable <= 0) return 0;
            return (int) ((billable + HOUR_MS - 1) / HOUR_MS);
        }

        public double overtimeFee(long now) {
            return billableHours(now) * Park.RATE;
        }
    }

    public static class Fine {
        public String  user;
        public String  date;
        public String  slot;
        public int     paidHours;
        public int     overtimeHours;
        public double  fee;
        public String  endedBy;
        public long    createdMillis;
        public boolean paid;

        public Fine(String user, String date, String slot, int paidHours, int overtimeHours,
                    double fee, String endedBy, long createdMillis, boolean paid) {
            this.user = user;
            this.date = date;
            this.slot = slot;
            this.paidHours = paidHours;
            this.overtimeHours = overtimeHours;
            this.fee = fee;
            this.endedBy = endedBy;
            this.createdMillis = createdMillis;
            this.paid = paid;
        }
    }
}