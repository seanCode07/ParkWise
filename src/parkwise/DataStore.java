package parkwise;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * All data lives in the "parkwise_data" folder. Several copies of the program (for example one
 * user window and one admin window started from Eclipse) can run at the same time: every change is
 * written under a file lock, and every copy re-reads the files as soon as another copy changes them.
 * Screens poll version() once a second and redraw when it changes -> real-time updates.
 */
public class DataStore {

    // ---------------- Settings ----------------

    public static final String ADMIN_USER = "admin";
    private static final String ADMIN_DEFAULT_PASSWORD = "admin123";

    /** Shown instead of a username for drivers that do not have the app. Also used as their "user id". */
    public static final String WALK_IN = "(walk-in)";

    // booking statuses
    public static final int RESERVED = 1;   // paid, waiting for the driver to arrive
    public static final int PARKED   = 2;   // car is in the slot, timer running
    public static final int PENDING  = 3;   // "pay at counter" waiting for the admin to approve

    public static final long HOUR_MS = 3600000L;
    public static final int GRACE_MINUTES = 5;
    public static final long GRACE_MS = GRACE_MINUTES * 60000L;
    public static final int FORGOT_HOURS = 2;
    /** A reservation can be checked in (Park Now) this many minutes before its start time. */
    public static final int CHECKIN_EARLY_MIN = 15;
    /** Reservation start times are offered in steps of this many minutes. */
    public static final int STEP_MIN = 30;

    private static final File DIR        = new File("parkwise_data");
    private static final File USERS_FILE = new File(DIR, "users.txt");
    private static final File RES_FILE   = new File(DIR, "reservations.txt");
    private static final File FINES_FILE = new File(DIR, "fines.txt");
    private static final File LOCK_FILE  = new File(DIR, "parkwise.lock");

    private static final Map<String, User> users = new HashMap<String, User>();
    private static final Map<String, ReservationInfo> reservations = new LinkedHashMap<String, ReservationInfo>();
    private static final List<Fine> fines = new ArrayList<Fine>();
    private static final Random RND = new Random();

    private static long loadedStamp = Long.MIN_VALUE;
    private static long lastCheck = 0L;
    private static int version = 0;
    private static RandomAccessFile lockRaf;
    private static FileLock lockHandle;

    static {
        sync(true);
        if (!users.containsKey(ADMIN_USER)) {
            begin();
            try {
                if (!users.containsKey(ADMIN_USER)) {
                    users.put(ADMIN_USER, new User(ADMIN_USER, hash(ADMIN_USER, ADMIN_DEFAULT_PASSWORD),
                                                   "Administrator", "", "", 0L));
                    saveUsers();
                }
            } finally {
                end();
            }
        }
    }

    // ---------------- Cross-window sync ----------------

    /** Changes whenever the data changed (by this window or by another running copy). Poll it to refresh screens. */
    public static synchronized int version() {
        sync(false);
        return version;
    }

    private static long fileStamp(File f) { return f.exists() ? f.lastModified() * 31L + f.length() : 0L; }
    private static long stamp() { return fileStamp(USERS_FILE) * 961L + fileStamp(RES_FILE) * 31L + fileStamp(FINES_FILE); }

    private static void sync(boolean force) {
        long t = System.currentTimeMillis();
        if (!force && t - lastCheck < 200) return;
        lastCheck = t;
        long s = stamp();
        if (s != loadedStamp) {
            load();
            loadedStamp = s;
            version++;
        }
    }

    /** Takes the cross-process lock, then loads the newest data so changes are made on top of it. */
    private static void begin() {
        try {
            DIR.mkdirs();
            lockRaf = new RandomAccessFile(LOCK_FILE, "rw");
            lockHandle = lockRaf.getChannel().lock();
        } catch (Exception e) {
            lockHandle = null;      // carry on without the lock rather than failing
        }
        sync(true);
    }

    private static void end() {
        try { if (lockHandle != null) lockHandle.release(); } catch (Exception ignore) { }
        try { if (lockRaf != null) lockRaf.close(); } catch (Exception ignore) { }
        lockHandle = null;
        lockRaf = null;
    }

    // ---------------- Accounts ----------------

    public static boolean isAdmin(String username) { return ADMIN_USER.equals(username); }

    public static boolean isWalkIn(String id) { return WALK_IN.equals(id); }

    private static String clean(String t) {
        return t == null ? "" : t.replaceAll("[\\t\\r\\n]", " ").trim();
    }

    public static synchronized String registerUser(String name, String phone, String email, String password) {
        begin();
        try {
            String id = clean(email).toLowerCase();
            if (id.equals(ADMIN_USER) || users.containsKey(id)) return "An account with this email already exists.";
            users.put(id, new User(id, hash(id, password), clean(name), clean(phone), id, System.currentTimeMillis()));
            saveUsers();
            return null;
        } finally {
            end();
        }
    }

    public static synchronized String authenticate(String input, String password) {
        sync(true);
        String key = clean(input);
        User u = users.get(key);
        if (u == null) u = users.get(key.toLowerCase());
        if (u != null && u.hash.equals(hash(u.id, password))) return u.id;
        return null;
    }

    public static synchronized String getDisplayName(String id) {
        sync(false);
        User u = users.get(id);
        return (u == null || u.name.isEmpty()) ? id : u.name;
    }

    public static synchronized String getPhone(String id) {
        sync(false);
        User u = users.get(id);
        return u == null ? "" : u.phone;
    }

    public static synchronized String getEmail(String id) {
        sync(false);
        User u = users.get(id);
        return u == null ? "" : u.email;
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

    // ---------------- Booking queries ----------------

    public static synchronized ReservationInfo getById(String id) {
        sync(false);
        return reservations.get(id);
    }

    /** Every booking of one slot on one date, earliest first. */
    public static synchronized List<ReservationInfo> getBookings(String date, String slot) {
        sync(false);
        List<ReservationInfo> list = new ArrayList<ReservationInfo>();
        for (ReservationInfo r : reservations.values())
            if (r.date.equals(date) && r.slot.equals(slot)) list.add(r);
        Collections.sort(list, new Comparator<ReservationInfo>() {
            public int compare(ReservationInfo a, ReservationInfo b) { return a.startMin - b.startMin; }
        });
        return list;
    }

    /** Slot colour on the dashboard: 0 = no bookings, 1 = has bookings, 2 = a car is parked on that date. */
    public static synchronized int getSlotStatus(String date, String slot) {
        sync(false);
        int st = 0;
        for (ReservationInfo r : reservations.values()) {
            if (r.date.equals(date) && r.slot.equals(slot)) {
                if (r.status == PARKED) return 2;
                st = 1;
            }
        }
        return st;
    }

    public static synchronized List<ReservationInfo> getReservationsForUser(String username) {
        sync(false);
        List<ReservationInfo> list = new ArrayList<ReservationInfo>();
        for (ReservationInfo info : reservations.values())
            if (info.user.equals(username)) list.add(info);
        return list;
    }

    public static synchronized List<ReservationInfo> getAllReservations() {
        sync(false);
        return new ArrayList<ReservationInfo>(reservations.values());
    }

    /** The plate this user used last (empty if none). */
    public static synchronized String lastPlate(String username) {
        sync(false);
        String p = "";
        for (ReservationInfo r : reservations.values())
            if (r.user.equals(username) && r.plate != null && !r.plate.isEmpty()) p = r.plate;
        for (Fine f : fines)
            if (f.user.equals(username) && f.plate != null && !f.plate.isEmpty() && p.isEmpty()) p = f.plate;
        return p;
    }

    /** A car is physically parked in this slot right now (ignoring the booking with id except). */
    public static synchronized boolean hasParkedCar(String slot) {
        sync(false);
        return parkedIn(slot, null);
    }

    /** Start times (minutes after midnight, every STEP_MIN) at which at least one hour is free. */
    public static synchronized List<Integer> availableStarts(String date, String slot, int fromMin) {
        sync(false);
        List<Integer> out = new ArrayList<Integer>();
        int s = ((fromMin + STEP_MIN - 1) / STEP_MIN) * STEP_MIN;
        for (; s + 60 <= 1440; s += STEP_MIN)
            if (isFree(date, slot, s, 1, null)) out.add(s);
        return out;
    }

    /** Longest stay (hours, up to 24) that fits from startMin without touching another booking. 0 = nothing fits. */
    public static synchronized int maxHours(String date, String slot, int startMin, boolean immediate, String excludeId) {
        sync(false);
        int cap = immediate ? Math.max(1, (1440 - startMin) / 60) : (1440 - startMin) / 60;
        cap = Math.min(24, cap);
        int h = 0;
        while (h < cap && isFree(date, slot, startMin, h + 1, excludeId)) h++;
        return h;
    }

    /** Slots the given booking could be moved to (free at its time, and not holding a parked car if it is parked). */
    public static synchronized List<String> freeSlotsFor(ReservationInfo r) {
        sync(false);
        List<String> out = new ArrayList<String>();
        for (String s : Park.SLOTS) {
            if (s.equals(r.slot)) continue;
            if (!isFree(r.date, s, r.startMin, r.hours, r.id)) continue;
            if (r.status == PARKED && parkedIn(s, r.id)) continue;
            out.add(s);
        }
        return out;
    }

    private static boolean overlaps(int aS, int aE, int bS, int bE) { return aS < bE && bS < aE; }

    private static boolean isFree(String date, String slot, int startMin, int hours, String excludeId) {
        int end = startMin + hours * 60;
        for (ReservationInfo r : reservations.values()) {
            if (excludeId != null && r.id.equals(excludeId)) continue;
            if (r.date.equals(date) && r.slot.equals(slot) && overlaps(startMin, end, r.startMin, r.endMin())) return false;
        }
        return true;
    }

    private static boolean parkedIn(String slot, String excludeId) {
        for (ReservationInfo r : reservations.values()) {
            if (excludeId != null && r.id.equals(excludeId)) continue;
            if (r.status == PARKED && r.slot.equals(slot)) return true;
        }
        return false;
    }

    private static boolean plateBusy(ReservationInfo n, String excludeId) {
        if (n.plate == null || n.plate.isEmpty()) return false;
        for (ReservationInfo r : reservations.values()) {
            if (excludeId != null && r.id.equals(excludeId)) continue;
            if (n.plate.equals(r.plate) && n.date.equals(r.date)
                    && overlaps(n.startMin, n.endMin(), r.startMin, r.endMin())) return true;
        }
        return false;
    }

    private static String newId(long now) {
        return Long.toString(now, 36) + Integer.toString(RND.nextInt(46656), 36);
    }

    // ---------------- Booking changes ----------------

    /** Adds a booking. Returns null on success, or a message saying why it could not be made. */
    public static synchronized String addBooking(ReservationInfo info) {
        begin();
        try {
            return insertLocked(info);
        } finally {
            end();
        }
    }

    private static String insertLocked(ReservationInfo info) {
        long now = System.currentTimeMillis();
        if (info.immediate) {
            info.date = Park.today();
            info.startMin = Fmt.minuteOfDay(now);
        }
        if (!isFree(info.date, info.slot, info.startMin, info.hours, null))
            return "Slot " + info.slot + " was just booked for part of that time.\nPlease pick another time or slot.";
        if (info.status == PARKED && parkedIn(info.slot, null))
            return "Slot " + info.slot + " is occupied by another car right now.";
        if (plateBusy(info, null))
            return "Plate " + Fmt.formatPlate(info.plate) + " already has a booking that overlaps this time.";
        info.endMillis = info.immediate ? now + info.hours * HOUR_MS
                                        : Park.parseDate(info.date).getTime() + info.endMin() * 60000L;
        if (info.endMillis <= now) return "That time has already passed.";
        info.id = newId(now);
        info.startMillis = info.status == PARKED ? now : 0L;
        reservations.put(info.id, info);
        saveReservations();
        return null;
    }

    /** Admin parks a driver that has no account. Starts the timer right away. */
    public static synchronized String addWalkIn(String slot, String plate, int hours) {
        begin();
        try {
            ReservationInfo r = new ReservationInfo(WALK_IN, Park.today(), slot, 0, hours, hours * Park.RATE,
                    PARKED, "Walk-in (Counter)", plate, true);
            return insertLocked(r);
        } finally {
            end();
        }
    }

    /** Driver presses Park Now on a reservation (or an approved counter booking). */
    public static synchronized String startParking(String id) {
        begin();
        try {
            ReservationInfo r = reservations.get(id);
            long now = System.currentTimeMillis();
            if (r == null) return "That booking no longer exists.";
            if (r.status == PENDING) return "This booking is still waiting for the admin to approve your payment.";
            if (r.status != RESERVED) return "This booking is already parked.";
            if (now >= r.endMillis) return "The reserved time has already ended.";
            if (!r.immediate && now < r.schedStartMillis() - CHECKIN_EARLY_MIN * 60000L)
                return "Check-in opens " + CHECKIN_EARLY_MIN + " minutes before your start time ("
                        + Fmt.time(r.startMin) + ").";
            if (parkedIn(r.slot, id))
                return "Another car is still parked in slot " + r.slot + ".\nPlease ask the admin for help.";
            r.status = PARKED;
            r.startMillis = now;
            saveReservations();
            return null;
        } finally {
            end();
        }
    }

    /** Admin confirms the counter payment. The driver may then park. */
    public static synchronized String approve(String id) {
        begin();
        try {
            ReservationInfo r = reservations.get(id);
            if (r == null) return "That booking no longer exists.";
            if (r.status != PENDING) return "That booking is not waiting for approval.";
            long now = System.currentTimeMillis();
            int nowMin = Fmt.minuteOfDay(now);
            // the clock starts from the approval, not from when the request was sent
            if (r.immediate && r.date.equals(Park.today()) && isFree(r.date, r.slot, nowMin, r.hours, id)) {
                r.startMin = nowMin;
                r.endMillis = now + r.hours * HOUR_MS;
            } else if (r.endMillis <= now) {
                return "The requested time has already passed. Reject this request instead.";
            }
            r.status = RESERVED;
            r.paymentMode = "Counter (approved)";
            saveReservations();
            return null;
        } finally {
            end();
        }
    }

    /** Cancels, rejects or removes a booking without billing anything. */
    public static synchronized void clearBooking(String id) {
        begin();
        try {
            if (reservations.remove(id) != null) saveReservations();
        } finally {
            end();
        }
    }

    /** Admin moves a booking to another slot (same date and time). Returns null on success. */
    public static synchronized String moveBooking(String id, String newSlot) {
        begin();
        try {
            ReservationInfo r = reservations.get(id);
            if (r == null) return "That booking no longer exists.";
            if (r.slot.equals(newSlot)) return "Pick a different slot.";
            if (!isFree(r.date, newSlot, r.startMin, r.hours, id))
                return "Slot " + newSlot + " is booked during " + Fmt.timeRange(r.startMin, r.endMin()) + ".";
            if (r.status == PARKED && parkedIn(newSlot, id))
                return "Slot " + newSlot + " already has a parked car.";
            r.slot = newSlot;
            saveReservations();
            return null;
        } finally {
            end();
        }
    }

    /**
     * Ends a parked session. If the car stayed past the paid time (plus grace), an overtime fine is
     * recorded and returned; otherwise returns null. endedBy is "User" or "Admin".
     */
    public static synchronized Fine checkout(String id, String endedBy, boolean paidNow) {
        begin();
        try {
            ReservationInfo r = reservations.remove(id);
            if (r == null) return null;
            saveReservations();
            long now = System.currentTimeMillis();
            int ot = r.billableHours(now);
            if (r.status != PARKED || ot <= 0) return null;
            Fine f = new Fine(r.user, r.date, r.slot, r.plate, r.hours, ot, ot * Park.RATE, endedBy, now, paidNow);
            fines.add(f);
            saveFines();
            return f;
        } finally {
            end();
        }
    }

    // ---------------- Overtime fines ----------------

    public static synchronized List<Fine> getAllFines() {
        sync(false);
        return new ArrayList<Fine>(fines);
    }

    public static synchronized List<Fine> getUnpaidFinesForUser(String username) {
        sync(false);
        List<Fine> list = new ArrayList<Fine>();
        for (Fine f : fines)
            if (f.user.equals(username) && !f.paid) list.add(f);
        return list;
    }

    public static synchronized double getUnpaidTotal() {
        sync(false);
        double t = 0;
        for (Fine f : fines) if (!f.paid) t += f.fee;
        return t;
    }

    public static synchronized void payFine(Fine target) {
        begin();
        try {
            for (Fine f : fines) {
                if (f.key().equals(target.key())) { f.paid = true; break; }
            }
            saveFines();
        } finally {
            end();
        }
    }

    // ---------------- Saving and loading ----------------

    private static void writeAtomic(File target, List<String> lines) {
        File tmp = new File(DIR, target.getName() + ".tmp");
        BufferedWriter w = null;
        try {
            DIR.mkdirs();
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"));
            for (String l : lines) { w.write(l); w.newLine(); }
            w.close();
            w = null;
            for (int i = 0; i < 10; i++) {
                try {
                    Files.move(tmp.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    break;
                } catch (IOException e1) {
                    try {
                        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        break;
                    } catch (IOException e2) {
                        try { Thread.sleep(40); } catch (InterruptedException ie) { }
                    }
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            close(w);
        }
        loadedStamp = stamp();
        version++;
    }

    private static void saveUsers() {
        List<String> lines = new ArrayList<String>();
        for (User u : users.values())
            lines.add(u.id + "\t" + u.hash + "\t" + u.name + "\t" + u.phone + "\t" + u.email + "\t" + u.consentMillis);
        writeAtomic(USERS_FILE, lines);
    }

    private static void saveReservations() {
        List<String> lines = new ArrayList<String>();
        for (ReservationInfo r : reservations.values())
            lines.add(r.id + "\t" + r.user + "\t" + r.date + "\t" + r.slot + "\t" + r.startMin + "\t" + r.hours + "\t"
                    + r.price + "\t" + r.status + "\t" + r.paymentMode + "\t" + r.startMillis + "\t" + r.endMillis
                    + "\t" + (r.immediate ? "1" : "0") + "\t" + (r.plate == null ? "" : r.plate));
        writeAtomic(RES_FILE, lines);
    }

    private static void saveFines() {
        List<String> lines = new ArrayList<String>();
        for (Fine f : fines)
            lines.add(f.user + "\t" + f.date + "\t" + f.slot + "\t" + f.paidHours + "\t" + f.overtimeHours + "\t"
                    + f.fee + "\t" + f.endedBy + "\t" + f.createdMillis + "\t" + (f.paid ? "1" : "0")
                    + "\t" + (f.plate == null ? "" : f.plate));
        writeAtomic(FINES_FILE, lines);
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<String>();
        if (!f.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) out.add(line);
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            close(r);
        }
        return out;
    }

    /** Replaces everything in memory with what is currently saved on disk. */
    private static void load() {
        users.clear();
        reservations.clear();
        fines.clear();
        boolean needResave = false;

        for (String line : readLines(USERS_FILE)) {
            String[] p = line.split("\t", -1);
            if (p.length == 2) {
                users.put(p[0], new User(p[0], p[1], p[0], "", "", 0L));
            } else if (p.length == 6) {
                long consent = 0L;
                try { consent = Long.parseLong(p[5]); } catch (NumberFormatException ignore) { }
                users.put(p[0], new User(p[0], p[1], p[2], p[3], p[4], consent));
            }
        }

        long now = System.currentTimeMillis();
        for (String line : readLines(RES_FILE)) {
            String[] p = line.split("\t", -1);
            try {
                if (p.length == 13) {
                    ReservationInfo r = new ReservationInfo(p[0], p[1], p[2], p[3],
                            Integer.parseInt(p[4]), Integer.parseInt(p[5]), Double.parseDouble(p[6]),
                            Integer.parseInt(p[7]), p[8], Long.parseLong(p[9]), Long.parseLong(p[10]),
                            "1".equals(p[11]), p[12]);
                    reservations.put(r.id, r);
                } else if (p.length == 7 || p.length == 8) {
                    // older save files: no time window and no plate. Treat as a whole-hours booking from midnight.
                    int hours = Integer.parseInt(p[3]);
                    int status = Integer.parseInt(p[5]);
                    long start = (p.length == 8) ? Long.parseLong(p[7]) : 0L;
                    if (status == PARKED && start <= 0) start = now;
                    long end = status == PARKED ? start + hours * HOUR_MS
                                                : Park.parseDate(p[1]).getTime() + hours * HOUR_MS;
                    ReservationInfo r = new ReservationInfo(newId(now), p[0], p[1], p[2], 0, hours,
                            Double.parseDouble(p[4]), status, p[6], start, end, status == PARKED, "");
                    reservations.put(r.id, r);
                    needResave = true;
                }
            } catch (NumberFormatException ignore) {
                // skip a damaged line
            }
        }

        for (String line : readLines(FINES_FILE)) {
            String[] p = line.split("\t", -1);
            if (p.length != 9 && p.length != 10) continue;
            try {
                fines.add(new Fine(p[0], p[1], p[2], p.length == 10 ? p[9] : "", Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Double.parseDouble(p[5]), p[6], Long.parseLong(p[7]), "1".equals(p[8])));
            } catch (NumberFormatException ignore) {
                // skip a damaged line
            }
        }
        if (needResave) saveReservations();
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignore) { }
        }
    }

    // ---------------- Data classes ----------------

    public static class User {
        public final String id;
        public final String hash;
        public final String name;
        public final String phone;
        public final String email;
        public final long   consentMillis;

        public User(String id, String hash, String name, String phone, String email, long consentMillis) {
            this.id = id; this.hash = hash; this.name = name;
            this.phone = phone; this.email = email; this.consentMillis = consentMillis;
        }
    }

    public static class ReservationInfo {
        public String  id;
        public String  user;          // account id, or WALK_IN
        public String  date;          // "MMMM dd, yyyy"
        public String  slot;
        public int     startMin;      // booked start, minutes after midnight
        public int     hours;         // hours paid for
        public double  price;
        public int     status;        // RESERVED, PARKED or PENDING
        public String  paymentMode;
        public long    startMillis;   // when the car was parked (0 until then)
        public long    endMillis;     // when the paid time ends
        public boolean immediate;     // a "Park Now" booking (its clock does not follow a booked start time)
        public String  plate;

        /** New booking (id and the clock times are filled in by DataStore.addBooking). */
        public ReservationInfo(String user, String date, String slot, int startMin, int hours, double price,
                               int status, String paymentMode, String plate, boolean immediate) {
            this(null, user, date, slot, startMin, hours, price, status, paymentMode, 0L, 0L, immediate, plate);
        }

        public ReservationInfo(String id, String user, String date, String slot, int startMin, int hours,
                               double price, int status, String paymentMode, long startMillis, long endMillis,
                               boolean immediate, String plate) {
            this.id = id; this.user = user; this.date = date; this.slot = slot;
            this.startMin = startMin; this.hours = hours; this.price = price; this.status = status;
            this.paymentMode = paymentMode; this.startMillis = startMillis; this.endMillis = endMillis;
            this.immediate = immediate; this.plate = plate;
        }

        public int endMin() { return startMin + hours * 60; }

        public long schedStartMillis() { return endMillis - hours * HOUR_MS; }

        /** Time left of the paid time (negative once it has run out). */
        public long remainingMillis(long now) { return endMillis - now; }

        /** How long the car has stayed past the paid time (0 if still within it). */
        public long overStayMillis(long now) {
            if (status != PARKED || startMillis <= 0) return 0L;
            return Math.max(0L, now - endMillis);
        }

        /** Overtime hours to bill: free grace period first, then every started hour counts. */
        public int billableHours(long now) {
            long billable = overStayMillis(now) - GRACE_MS;
            if (billable <= 0) return 0;
            return (int) ((billable + HOUR_MS - 1) / HOUR_MS);
        }

        public double overtimeFee(long now) { return billableHours(now) * Park.RATE; }

        /** True when a reserved (and paid) booking may be checked in right now. */
        public boolean canCheckIn(long now) {
            if (status != RESERVED || now >= endMillis) return false;
            return immediate || now >= schedStartMillis() - CHECKIN_EARLY_MIN * 60000L;
        }
    }

    public static class Fine {
        public String  user;
        public String  date;
        public String  slot;
        public String  plate;
        public int     paidHours;
        public int     overtimeHours;
        public double  fee;
        public String  endedBy;
        public long    createdMillis;
        public boolean paid;

        public Fine(String user, String date, String slot, String plate, int paidHours, int overtimeHours,
                    double fee, String endedBy, long createdMillis, boolean paid) {
            this.user = user; this.date = date; this.slot = slot; this.plate = plate;
            this.paidHours = paidHours; this.overtimeHours = overtimeHours; this.fee = fee;
            this.endedBy = endedBy; this.createdMillis = createdMillis; this.paid = paid;
        }

        public String key() { return createdMillis + "|" + user + "|" + slot; }
    }
}