package parkwise;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
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
import java.util.LinkedHashSet;
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
    public static final int RESERVED = 1;   // paid and approved, waiting for the driver to arrive
    public static final int PARKED   = 2;   // car is in the slot, timer running
    public static final int PENDING  = 3;   // payment sent (QR reference or counter), waiting for the admin to validate

    // PWD / senior citizen status of an account
    public static final String PRIORITY_PWD    = "PWD";
    public static final String PRIORITY_SENIOR = "SENIOR";
    public static final int P_NONE = 0, P_PENDING = 1, P_VERIFIED = 2, P_REJECTED = 3;
    public static final int SENIOR_AGE = 60;

    public static final String MODE_QR      = "InstaPay QR";
    public static final String MODE_COUNTER = "Counter";

    public static final long HOUR_MS = 3600000L;
    public static final int GRACE_MINUTES = 5;
    public static final long GRACE_MS = GRACE_MINUTES * 60000L;
    public static final int FORGOT_HOURS = 2;
    /** A reservation can be checked in (Park Now) this many minutes before its start time. */
    public static final int CHECKIN_EARLY_MIN = 15;
    /** Reservation start times are offered in steps of this many minutes. */
    public static final int STEP_MIN = 30;

    private static final File DIR         = new File("parkwise_data");
    private static final File USERS_FILE  = new File(DIR, "users.txt");
    private static final File RES_FILE    = new File(DIR, "reservations.txt");
    private static final File FINES_FILE  = new File(DIR, "fines.txt");
    private static final File EVENTS_FILE = new File(DIR, "events.txt");
    private static final File PLATES_FILE = new File(DIR, "plates.txt");
    private static final File ADMINS_FILE = new File(DIR, "admins.txt");
    private static final File REQ_FILE    = new File(DIR, "admin_requests.txt");
    private static final File AUDIT_FILE  = new File(DIR, "audit.txt");        // permanent, append-only, hash-chained
    private static final File HISTORY_FILE = new File(DIR, "history.txt");     // permanent, append-only, hash-chained
    private static final File UPLOAD_DIR  = new File(DIR, "uploads");          // ID photos and payment receipts
    private static final File LOCK_FILE   = new File(DIR, "parkwise.lock");

    private static final Map<String, User> users = new HashMap<String, User>();
    private static final Map<String, ReservationInfo> reservations = new LinkedHashMap<String, ReservationInfo>();
    private static final List<Fine> fines = new ArrayList<Fine>();
    private static final List<Event> events = new ArrayList<Event>();
    private static final List<String[]> plates = new ArrayList<String[]>();     // {user, plate}
    private static final Map<String, String[]> admins = new LinkedHashMap<String, String[]>();   // id -> {id, hash, name, createdBy, millis}
    private static final List<String[]> adminReqs = new ArrayList<String[]>();                    // {id, hash, name, millis}
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
                                                   "Administrator", "", "", 0L, "", "", "", P_NONE));
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

    private static long stamp() {
        long s = 0L;
        File[] all = {USERS_FILE, RES_FILE, FINES_FILE, EVENTS_FILE, PLATES_FILE, ADMINS_FILE, REQ_FILE};
        for (File f : all) s = s * 961L + fileStamp(f);
        return s;
    }

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

    // ---------------- Permanent records: session history + audit log ----------------

    private static final String CHAIN_SALT = "ParkWise-records-v1";
    private static String actor = "system";
    private static String actorRole = "SYSTEM";

    /** Who is using this copy of the program (set at login). Every audit entry is stamped with it. */
    public static synchronized void setActor(String id) {
        boolean none = id == null || id.isEmpty();
        actor = none ? "system" : id;
        actorRole = none ? "SYSTEM" : isAdmin(id) ? "ADMIN" : "USER";
    }

    private static String actorLabel() {
        return ("ADMIN".equals(actorRole) ? "Admin " : "USER".equals(actorRole) ? "User " : "") + actor;
    }

    private static String cap(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 3) + "..."; }

    private static String sha(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(text.getBytes("UTF-8"))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The chain value of the last line of a record file ("GENESIS" for an empty file). */
    private static String lastChain(File f) {
        if (!f.exists() || f.length() == 0) return "GENESIS";
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            int n = (int) Math.min(len, 16384L);
            raf.seek(len - n);
            byte[] b = new byte[n];
            raf.readFully(b);
            String[] ls = new String(b, "UTF-8").split("\n");
            for (int i = ls.length - 1; i >= 0; i--) {
                String l = ls[i].trim();
                if (l.isEmpty()) continue;
                String[] c = l.split("\t", -1);
                return c[c.length - 1];
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            close(raf);
        }
        return "GENESIS";
    }

    /** Appends one line. The last column is a SHA-256 chain of the line and the line before it, so edits or deletions show up. */
    private static void appendChained(File f, String payload) {
        try {
            DIR.mkdirs();
            String chain = sha(CHAIN_SALT + lastChain(f) + payload);
            Files.write(f.toPath(), (payload + "\t" + chain + "\n").getBytes("UTF-8"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void auditLocked(String action, String details) { auditAsLocked(actor, actorRole, action, details); }

    private static void auditAsLocked(String who, String role, String action, String details) {
        long now = System.currentTimeMillis();
        appendChained(AUDIT_FILE, newId(now) + "\t" + now + "\t" + clean(who) + "\t" + role + "\t" + clean(action)
                + "\t" + cap(clean(details), 1200));
    }

    /** Adds one line to the permanent audit log (who did what, with details such as old and new values). */
    public static synchronized void audit(String action, String details) {
        begin();
        try {
            auditLocked(action, details);
        } finally {
            end();
        }
    }

    public static synchronized void recordLogin(String attempted, boolean ok, boolean adminSide) {
        begin();
        try {
            String who = clean(attempted);
            auditAsLocked(who.isEmpty() ? "?" : who, ok ? (isAdmin(who) ? "ADMIN" : "USER") : "UNKNOWN",
                    ok ? "LOGIN" : "LOGIN_FAILED", adminSide ? "administrator login" : "user login");
        } finally {
            end();
        }
    }

    /** One permanent line for a booking that ended (finished, cancelled, rejected or removed). */
    private static void historyLocked(ReservationInfo r, String outcome, String endedBy, double overtimeFee) {
        User u = users.get(r.user);
        long now = System.currentTimeMillis();
        boolean done = "COMPLETED".equals(outcome);
        String name = isWalkIn(r.user) ? "(walk-in)" : (u == null || u.name.isEmpty()) ? r.user : u.name;
        appendChained(HISTORY_FILE, newId(now) + "\t" + now + "\t" + outcome + "\t" + clean(r.user) + "\t" + clean(name)
                + "\t" + (u == null ? "" : clean(u.phone)) + "\t" + nz(r.plate) + "\t" + r.slot + "\t" + r.date
                + "\t" + r.startMillis + "\t" + (done && r.startMillis > 0 ? now : 0L) + "\t" + r.hours
                + "\t" + r.price + "\t" + overtimeFee + "\t" + (done ? r.price + overtimeFee : 0.0)
                + "\t" + nz(r.paymentMode) + "\t" + nz(r.refNo) + "\t" + clean(endedBy));
    }

    private static List<String[]> readChained(File f) {
        List<String[]> out = new ArrayList<String[]>();
        for (String line : readLines(f)) {
            if (line.trim().isEmpty()) continue;
            String[] p = line.split("\t", -1);
            out.add(java.util.Arrays.copyOf(p, p.length - 1));
        }
        return out;
    }

    /** Permanent audit log: {id, millis, who, role, action, details}. Oldest first. */
    public static synchronized List<String[]> getAudit() { return readChained(AUDIT_FILE); }

    /** Permanent session history: {id, recordedMillis, outcome, user, name, contact, plate, slot, date, entryMillis, exitMillis,
     *  hoursPaid, baseFee, overtimeFee, totalFee, paymentMode, refNo, endedBy}. Oldest first. */
    public static synchronized List<String[]> getHistory() { return readChained(HISTORY_FILE); }

    private static String verifyFile(File f, String label) {
        if (!f.exists()) return label + ": no records yet.";
        String prev = "GENESIS";
        int n = 0;
        for (String line : readLines(f)) {
            if (line.trim().isEmpty()) continue;
            n++;
            int i = line.lastIndexOf('\t');
            if (i < 0) return label + ": LINE " + n + " IS DAMAGED.";
            String payload = line.substring(0, i), chain = line.substring(i + 1);
            if (!sha(CHAIN_SALT + prev + payload).equals(chain))
                return label + ": TAMPERING DETECTED at record " + n + " (it was edited, or an earlier record was removed).";
            prev = chain;
        }
        return label + ": " + n + " record" + (n == 1 ? "" : "s") + " checked, all intact.";
    }

    /** Re-computes the hash chain of both record files. */
    public static synchronized String verifyRecords() {
        sync(true);
        return verifyFile(AUDIT_FILE, "Audit log") + "\n" + verifyFile(HISTORY_FILE, "Session history");
    }

    // ---------------- Uploaded pictures (ID photos, payment receipts) ----------------

    /** Copies a picture into the data folder (scaled down, as a JPEG). Returns its stored name, or null if it is not a usable image. */
    public static String saveImage(File src, String kind) {
        try {
            if (src == null || !src.isFile() || src.length() > 20L * 1024 * 1024) return null;
            BufferedImage im = ImageIO.read(src);
            if (im == null || im.getWidth() < 50 || im.getHeight() < 50) return null;
            int max = 1600;
            double sc = Math.min(1.0, (double) max / Math.max(im.getWidth(), im.getHeight()));
            int w = Math.max(1, (int) Math.round(im.getWidth() * sc)), h = Math.max(1, (int) Math.round(im.getHeight() * sc));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(im, 0, 0, w, h, null);
            g.dispose();
            UPLOAD_DIR.mkdirs();
            String name = kind + "_" + newId(System.currentTimeMillis()) + ".jpg";
            if (!ImageIO.write(out, "jpg", new File(UPLOAD_DIR, name))) return null;
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    public static BufferedImage loadImage(String name) {
        try {
            if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
            File f = new File(UPLOAD_DIR, name);
            return f.isFile() ? ImageIO.read(f) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Removes a picture that was attached but not used (for example when the driver picks another one). */
    public static void discardImage(String name) { deleteImage(name); }

    private static void deleteImage(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return;
        new File(UPLOAD_DIR, name).delete();
    }

    // ---------------- Accounts ----------------

    public static synchronized boolean isAdmin(String username) { return ADMIN_USER.equals(username) || admins.containsKey(username); }

    public static boolean isWalkIn(String id) { return WALK_IN.equals(id); }

    private static String clean(String t) {
        return t == null ? "" : t.replaceAll("[\\t\\r\\n]", " ").trim();
    }

    private static String digits(String t) { return t == null ? "" : t.replaceAll("\\D", ""); }

    /** Finds an account by username (the account id) or by e-mail address. */
    private static User findUser(String key) {
        String k = clean(key);
        if (k.isEmpty()) return null;
        User u = users.get(k);
        if (u == null) u = users.get(k.toLowerCase());
        if (u == null) {
            for (User x : users.values()) {
                if (!x.email.isEmpty() && x.email.equalsIgnoreCase(k)) { u = x; break; }
            }
        }
        return u;
    }

    /** Creates an account. The username becomes the account id. Returns null on success, else the reason. */
    public static synchronized String registerUser(String username, String name, String phone, String email, String password,
                                                   String priorityType, String priorityId, String birthIso, String proofFile) {
        begin();
        try {
            String id = clean(username).toLowerCase();
            String mail = clean(email).toLowerCase();
            if (id.equals(ADMIN_USER) || users.containsKey(id) || admins.containsKey(id) || reqPending(id))
                return "That username is already taken.";
            for (User u : users.values()) {
                if (u.id.equals(mail) || (!u.email.isEmpty() && u.email.equalsIgnoreCase(mail)))
                    return "An account with this email already exists.";
            }
            String type = clean(priorityType);
            String bad = checkPriority(type, priorityId, birthIso);
            if (bad != null) return bad;
            boolean hasP = !type.isEmpty();
            if (hasP && (proofFile == null || proofFile.isEmpty())) return "Attach a clear photo of your " + priorityName(type) + " ID.";
            users.put(id, new User(id, hash(id, password), clean(name), clean(phone), mail, System.currentTimeMillis(),
                    type, hasP ? clean(priorityId) : "", hasP ? clean(birthIso) : "", hasP ? P_PENDING : P_NONE,
                    hasP ? proofFile : ""));
            auditAsLocked(id, "USER", "ACCOUNT_CREATED", "New account " + id + " (" + clean(name) + ")"
                    + (hasP ? ", " + priorityName(type) + " ID submitted with a photo" : ""));
            if (hasP) addEventLocked("PRIORITY", id, "", "", priorityName(type) + " ID " + clean(priorityId)
                    + " submitted at sign-up. Verify it under Priority IDs.");
            saveUsers();
            return null;
        } finally {
            end();
        }
    }

    /** Log in with the username OR the e-mail address. Returns the account id, or null if wrong. */
    public static synchronized String authenticate(String input, String password) {
        sync(true);
        User u = findUser(input);
        if (u != null && u.hash.equals(hash(u.id, password))) return u.id;
        return null;
    }

    /**
     * Forgot password, step 1: the username (or e-mail) and the mobile number given at sign-up must both match.
     * Returns the account id, or null when they do not match. (The admin account cannot be reset here.)
     */
    public static synchronized String findForRecovery(String identifier, String phone) {
        sync(true);
        User u = findUser(identifier);
        if (u == null || isAdmin(u.id) || u.phone.isEmpty()) return null;
        return digits(u.phone).equals(digits(phone)) ? u.id : null;
    }

    /** Forgot password, step 2: sets a new password. Returns null on success. */
    public static synchronized String resetPassword(String id, String newPassword) {
        begin();
        try {
            User u = users.get(id);
            if (u == null || isAdmin(id)) return "That account cannot be reset here.";
            users.put(id, new User(u.id, hash(u.id, newPassword), u.name, u.phone, u.email, u.consentMillis,
                    u.priorityType, u.priorityId, u.birth, u.priorityStatus, u.proofFile));
            saveUsers();
            auditAsLocked(id, "USER", "PASSWORD_RESET", "Password reset with the forgot-password check");
            return null;
        } finally {
            end();
        }
    }

    public static synchronized String getDisplayName(String id) {
        sync(false);
        String[] a = admins.get(id);
        if (a != null) return a[2].isEmpty() ? id : a[2];
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

    public static String priorityName(String type) {
        return PRIORITY_SENIOR.equals(type) ? "Senior citizen" : PRIORITY_PWD.equals(type) ? "PWD" : "";
    }

    /** Checks the ID (and for seniors the birth date / age). Returns null when fine. */
    private static String checkPriority(String type, String idNo, String birthIso) {
        if (type == null || type.isEmpty()) return null;
        if (!PRIORITY_PWD.equals(type) && !PRIORITY_SENIOR.equals(type)) return "Unknown PWD / senior citizen type.";
        if (!Fmt.validPriorityId(idNo)) return "Enter a valid " + priorityName(type) + " ID number (4 to 30 letters or digits).";
        if (PRIORITY_SENIOR.equals(type)) {
            int age = Fmt.ageOf(birthIso == null ? "" : birthIso);
            if (age < 0) return "Enter your birth date as MM/DD/YYYY.";
            if (age < SENIOR_AGE) return "Senior citizen privileges are for ages " + SENIOR_AGE + " and above.";
        }
        return null;
    }

    /** True only when the PWD / senior citizen ID was verified by the admin: priority slots and discounted reservations. */
    public static synchronized boolean isPriorityUser(String id) {
        sync(false);
        User u = users.get(id);
        return u != null && u.priorityStatus == P_VERIFIED;
    }

    public static synchronized String priorityType(String id) {
        sync(false);
        User u = users.get(id);
        return u == null ? "" : u.priorityType;
    }

    public static synchronized String priorityId(String id) {
        sync(false);
        User u = users.get(id);
        return u == null ? "" : u.priorityId;
    }

    public static synchronized String priorityBirth(String id) {
        sync(false);
        User u = users.get(id);
        return u == null ? "" : u.birth;
    }

    /** P_NONE, P_PENDING, P_VERIFIED or P_REJECTED. */
    public static synchronized int priorityStatus(String id) {
        sync(false);
        User u = users.get(id);
        return u == null ? P_NONE : u.priorityStatus;
    }

    /** A driver who did not register as PWD / senior at sign-up (or was rejected) sends the ID later. It then waits for the admin. */
    public static synchronized String setPriority(String id, String type, String idNo, String birthIso, String proofFile) {
        begin();
        try {
            User u = users.get(id);
            if (u == null || isAdmin(id)) return "Account not found.";
            if (u.priorityStatus == P_PENDING || u.priorityStatus == P_VERIFIED) return "Your ID is already " + (u.priorityStatus == P_VERIFIED ? "verified." : "waiting for verification.");
            String t = clean(type);
            if (t.isEmpty()) return "Choose PWD or senior citizen.";
            String bad = checkPriority(t, idNo, birthIso);
            if (bad != null) return bad;
            if (proofFile == null || proofFile.isEmpty()) return "Attach a clear photo of your " + priorityName(t) + " ID.";
            deleteImage(u.proofFile);
            users.put(id, new User(u.id, u.hash, u.name, u.phone, u.email, u.consentMillis, t, clean(idNo),
                    PRIORITY_SENIOR.equals(t) ? clean(birthIso) : "", P_PENDING, proofFile));
            saveUsers();
            auditLocked("PRIORITY_ID_SUBMITTED", priorityName(t) + " ID " + clean(idNo) + " with photo");
            addEventLocked("PRIORITY", id, "", "", priorityName(t) + " ID " + clean(idNo) + " submitted. Verify it under Priority IDs.");
            return null;
        } finally {
            end();
        }
    }

    /** Admin: verify (true) or reject (false) a submitted PWD / senior citizen ID. */
    public static synchronized void reviewPriority(String id, boolean verify) {
        begin();
        try {
            User u = users.get(id);
            if (u == null || u.priorityType.isEmpty()) return;
            users.put(id, new User(u.id, u.hash, u.name, u.phone, u.email, u.consentMillis, u.priorityType, u.priorityId,
                    u.birth, verify ? P_VERIFIED : P_REJECTED, u.proofFile));
            saveUsers();
            auditLocked(verify ? "PRIORITY_ID_VERIFIED" : "PRIORITY_ID_REJECTED", id + ": " + priorityName(u.priorityType) + " ID " + u.priorityId);
        } finally {
            end();
        }
    }

    /** Stored name of the ID photo this driver attached (or ""). */
    public static synchronized String priorityProof(String id) {
        sync(false);
        User u = users.get(id);
        return u == null ? "" : u.proofFile;
    }

    /** Every account that submitted a PWD / senior citizen ID. */
    public static synchronized List<User> getPriorityUsers() {
        sync(false);
        List<User> list = new ArrayList<User>();
        for (User u : users.values()) if (!u.priorityType.isEmpty()) list.add(u);
        Collections.sort(list, new Comparator<User>() {
            public int compare(User a, User b) {
                int pa = a.priorityStatus == P_PENDING ? 0 : 1, pb = b.priorityStatus == P_PENDING ? 0 : 1;
                return pa != pb ? pa - pb : a.id.compareTo(b.id);
            }
        });
        return list;
    }

    // ---------- fixing a typo in a plate number ----------

    /** Renames one of the driver's plates everywhere (saved plates, bookings, fees). The admin gets an alert. Returns null on success. */
    private static String doRenamePlate(String user, String oldPlate, String newPlate, boolean byAdmin) {
        begin();
        try {
            if (isWalkIn(user) || oldPlate == null || newPlate == null || oldPlate.equals(newPlate)) return "Nothing to change.";
            String dup = "You already have " + Fmt.formatPlate(newPlate) + " saved. Choose it from your list instead.";
            for (String[] p : plates) if (p[0].equals(user) && p[1].equals(newPlate)) return dup;
            for (ReservationInfo r : reservations.values()) if (r.user.equals(user) && newPlate.equals(r.plate)) return dup;
            for (Fine f : fines) if (f.user.equals(user) && newPlate.equals(f.plate)) return dup;
            String slotNote = "";
            for (ReservationInfo r : reservations.values()) {
                if (!r.user.equals(user) || !oldPlate.equals(r.plate)) continue;
                slotNote = r.slot;
                for (ReservationInfo x : reservations.values()) {
                    if (x == r || !newPlate.equals(x.plate)) continue;
                    if (x.date.equals(r.date) && overlaps(r.startMin, r.endMin(), x.startMin, x.endMin()))
                        return "Plate " + Fmt.formatPlate(newPlate) + " already has a booking that overlaps this time.";
                }
            }
            boolean saved = false;
            for (String[] p : plates) if (p[0].equals(user) && p[1].equals(oldPlate)) { p[1] = newPlate; saved = true; }
            if (!saved) plates.add(new String[]{user, newPlate});
            for (ReservationInfo r : reservations.values()) if (r.user.equals(user) && oldPlate.equals(r.plate)) r.plate = newPlate;
            for (Fine f : fines) if (f.user.equals(user) && oldPlate.equals(f.plate)) f.plate = newPlate;
            savePlates(); saveReservations(); saveFines();
            auditLocked(byAdmin ? "ADMIN_EDIT_PLATE" : "PLATE_EDIT", "Driver " + user + ": plate " + Fmt.formatPlate(oldPlate)
                    + " -> " + Fmt.formatPlate(newPlate) + (slotNote.isEmpty() ? "" : " (booking in slot " + slotNote + ")"));
            if (!byAdmin)
                addEventLocked("PLATE_EDIT", user, slotNote, newPlate, "Plate number changed from " + Fmt.formatPlate(oldPlate)
                    + " to " + Fmt.formatPlate(newPlate) + (slotNote.isEmpty() ? "." : " (booking in slot " + slotNote + ")."));
            return null;
        } finally {
            end();
        }
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

    /** A driver fixes a typo in one of their plates. */
    public static synchronized String renamePlate(String user, String oldPlate, String newPlate) {
        return doRenamePlate(user, oldPlate, newPlate, false);
    }

    /**
     * An admin corrects the plate of a booking. For a driver with an account the plate is renamed everywhere for that driver
     * (saved plates, bookings, fees); for a walk-in only this booking changes. Logged with the old and new value.
     */
    public static synchronized String adminEditBookingPlate(String bookingId, String newPlate) {
        begin();
        try {
            if (!"ADMIN".equals(actorRole)) return "Only an administrator can do this.";
            ReservationInfo r = reservations.get(bookingId);
            if (r == null) return "That booking no longer exists.";
            String old = r.plate == null ? "" : r.plate;
            if (old.equals(newPlate)) return "Nothing to change.";
            if (!isWalkIn(r.user)) return doRenamePlate(r.user, old, newPlate, true);
            String keep = r.plate;
            r.plate = newPlate;
            if (plateBusy(r, bookingId)) { r.plate = keep; return "Plate " + Fmt.formatPlate(newPlate) + " already has a booking that overlaps this time."; }
            saveReservations();
            auditLocked("ADMIN_EDIT_PLATE", "Walk-in booking in slot " + r.slot + ": plate " + Fmt.formatPlate(old) + " -> " + Fmt.formatPlate(newPlate));
            return null;
        } finally {
            end();
        }
    }

    // ---------------- Profile, password, account deletion ----------------

    private static String checkProfile(String id, String name, String phone, String email) {
        String mail = clean(email).toLowerCase();
        for (User x : users.values())
            if (!x.id.equals(id) && !mail.isEmpty() && (x.id.equals(mail) || x.email.equalsIgnoreCase(mail)))
                return "Another account already uses that email address.";
        return null;
    }

    private static String profileLocked(String id, String name, String phone, String email, String action) {
        User u = users.get(id);
        if (u == null || isAdmin(id)) return "Account not found.";
        String bad = checkProfile(id, name, phone, email);
        if (bad != null) return bad;
        String n = clean(name), ph = clean(phone), em = clean(email).toLowerCase();
        StringBuilder ch = new StringBuilder();
        if (!n.equals(u.name)) ch.append("name '").append(u.name).append("' -> '").append(n).append("'; ");
        if (!ph.equals(u.phone)) ch.append("phone '").append(u.phone).append("' -> '").append(ph).append("'; ");
        if (!em.equals(u.email)) ch.append("email '").append(u.email).append("' -> '").append(em).append("'; ");
        if (ch.length() == 0) return null;
        users.put(id, new User(u.id, u.hash, n, ph, em, u.consentMillis, u.priorityType, u.priorityId, u.birth, u.priorityStatus, u.proofFile));
        saveUsers();
        auditLocked(action, "Account " + id + ": " + ch);
        return null;
    }

    /** A driver edits their own personal information. */
    public static synchronized String updateProfile(String id, String name, String phone, String email) {
        begin();
        try {
            return profileLocked(id, name, phone, email, "PROFILE_UPDATED");
        } finally {
            end();
        }
    }

    /** An admin corrects a driver's personal information (typos and other mistakes). Logged with old and new values. */
    public static synchronized String adminUpdateProfile(String userId, String name, String phone, String email) {
        begin();
        try {
            if (!"ADMIN".equals(actorRole)) return "Only an administrator can do this.";
            return profileLocked(userId, name, phone, email, "ADMIN_EDIT_PROFILE");
        } finally {
            end();
        }
    }

    /** Changes the password of a driver or an administrator. */
    public static synchronized String changePassword(String id, String oldPassword, String newPassword) {
        begin();
        try {
            String[] a = admins.get(id);
            if (a != null) {
                if (!a[1].equals(hash(id, oldPassword))) return "Your current password is not correct.";
                a[1] = hash(id, newPassword);
                saveAdmins();
            } else {
                User u = users.get(id);
                if (u == null) return "Account not found.";
                if (!u.hash.equals(hash(id, oldPassword))) return "Your current password is not correct.";
                users.put(id, new User(u.id, hash(id, newPassword), u.name, u.phone, u.email, u.consentMillis,
                        u.priorityType, u.priorityId, u.birth, u.priorityStatus, u.proofFile));
                saveUsers();
            }
            auditAsLocked(id, isAdmin(id) ? "ADMIN" : "USER", "PASSWORD_CHANGED", "Password changed in Settings");
            return null;
        } finally {
            end();
        }
    }

    /** An administrator changes their own display name. */
    public static synchronized String updateAdminName(String id, String name) {
        begin();
        try {
            String[] a = admins.get(id);
            if (a == null) return "Only added administrators can change their name here.";
            String old = a[2];
            a[2] = clean(name);
            saveAdmins();
            auditAsLocked(id, "ADMIN", "ADMIN_NAME_CHANGED", "'" + old + "' -> '" + a[2] + "'");
            return null;
        } finally {
            end();
        }
    }

    /**
     * A driver deletes their own account (password required). It is refused while they still have bookings or unpaid fees.
     * The username and e-mail become free, so the same details can be used to sign up again. Paid fee records are kept without the username,
     * and the permanent session history and audit log keep their entries.
     */
    public static synchronized String deleteAccount(String id, String password) {
        begin();
        try {
            User u = users.get(id);
            if (u == null || isAdmin(id)) return "Account not found.";
            if (!u.hash.equals(hash(id, password))) return "The password is not correct.";
            int bookings = 0;
            for (ReservationInfo r : reservations.values()) if (r.user.equals(id)) bookings++;
            if (bookings > 0) return "You still have " + bookings + " booking" + (bookings == 1 ? "" : "s")
                    + " (reserved, pending or parked).\nCancel or finish " + (bookings == 1 ? "it" : "them") + " first.";
            for (Fine f : fines) if (f.user.equals(id) && !f.paid) return "You still have unpaid fees or penalties.\nPay them first.";
            for (Fine f : fines) if (f.user.equals(id)) f.user = "deleted:" + id;
            for (java.util.Iterator<String[]> it = plates.iterator(); it.hasNext();) if (it.next()[0].equals(id)) it.remove();
            deleteImage(u.proofFile);
            users.remove(id);
            saveUsers(); saveFines(); savePlates();
            auditAsLocked(id, "USER", "ACCOUNT_DELETED", "Account " + id + " (" + u.name + ", " + u.email + ") deleted by the driver");
            addEventLocked("ACCOUNT_DELETED", id, "", "", "Driver " + u.name + " (" + id + ") deleted their account.");
            return null;
        } finally {
            end();
        }
    }

    // ---------------- Administrators (added only with the approval of an existing admin) ----------------

    private static boolean reqPending(String id) {
        for (String[] r : adminReqs) if (r[0].equals(id)) return true;
        return false;
    }

    /** Log in as an administrator. Returns the admin id, or null. */
    public static synchronized String adminLogin(String input, String password) {
        sync(true);
        String id = clean(input).toLowerCase();
        if (ADMIN_USER.equals(id)) {
            User u = users.get(ADMIN_USER);
            return u != null && u.hash.equals(hash(id, password)) ? id : null;
        }
        String[] a = admins.get(id);
        return a != null && a[1].equals(hash(id, password)) ? id : null;
    }

    public static synchronized boolean adminRequestPending(String input) {
        sync(true);
        return reqPending(clean(input).toLowerCase());
    }

    /** Someone asks to become an administrator. An existing admin has to approve it. Returns null on success. */
    public static synchronized String requestAdmin(String username, String name, String password) {
        begin();
        try {
            String id = clean(username).toLowerCase();
            if (!id.matches("[a-z0-9_.]{3,20}")) return "Username: 3-20 letters, numbers, dot or underscore.";
            if (clean(name).length() < 2) return "Enter your full name.";
            if (id.equals(ADMIN_USER) || users.containsKey(id) || admins.containsKey(id) || reqPending(id))
                return "That username is not available.";
            adminReqs.add(new String[]{id, hash(id, password), clean(name), Long.toString(System.currentTimeMillis())});
            saveAdminReqs();
            auditAsLocked(id, "PENDING_ADMIN", "ADMIN_ACCESS_REQUESTED", "Requested administrator access as '" + clean(name) + "'");
            addEventLocked("ADMIN_REQUEST", id, "", "", clean(name) + " (" + id + ") asks to become an administrator. Approve or reject it under Admins.");
            return null;
        } finally {
            end();
        }
    }

    /** Administrators: {id, name, addedBy, addedMillis}. The main account comes first. */
    public static synchronized List<String[]> getAdmins() {
        sync(false);
        List<String[]> out = new ArrayList<String[]>();
        out.add(new String[]{ADMIN_USER, "Administrator", "(main account)", "0"});
        for (String[] a : admins.values()) out.add(new String[]{a[0], a[2], a[3], a[4]});
        return out;
    }

    /** Waiting requests: {id, name, requestedMillis}. */
    public static synchronized List<String[]> getAdminRequests() {
        sync(false);
        List<String[]> out = new ArrayList<String[]>();
        for (String[] r : adminReqs) out.add(new String[]{r[0], r[2], r[3]});
        return out;
    }

    public static synchronized String approveAdminRequest(String id) {
        begin();
        try {
            if (!"ADMIN".equals(actorRole)) return "Only an administrator can approve a request.";
            String[] req = null;
            for (String[] r : adminReqs) if (r[0].equals(id)) req = r;
            if (req == null) return "That request no longer exists.";
            if (users.containsKey(id) || admins.containsKey(id)) { adminReqs.remove(req); saveAdminReqs(); return "That username is already in use."; }
            admins.put(id, new String[]{id, req[1], req[2], actor, Long.toString(System.currentTimeMillis())});
            adminReqs.remove(req);
            saveAdmins(); saveAdminReqs();
            auditLocked("ADMIN_APPROVED", "New administrator " + id + " ('" + req[2] + "') approved");
            return null;
        } finally {
            end();
        }
    }

    public static synchronized String rejectAdminRequest(String id) {
        begin();
        try {
            if (!"ADMIN".equals(actorRole)) return "Only an administrator can reject a request.";
            String[] req = null;
            for (String[] r : adminReqs) if (r[0].equals(id)) req = r;
            if (req == null) return "That request no longer exists.";
            adminReqs.remove(req);
            saveAdminReqs();
            auditLocked("ADMIN_REQUEST_REJECTED", "Administrator request of " + id + " ('" + req[2] + "') rejected");
            return null;
        } finally {
            end();
        }
    }

    public static synchronized String removeAdmin(String id) {
        begin();
        try {
            if (!"ADMIN".equals(actorRole)) return "Only an administrator can remove an administrator.";
            if (ADMIN_USER.equals(id)) return "The main administrator account cannot be removed.";
            if (id.equals(actor)) return "You cannot remove your own account.";
            String[] a = admins.remove(id);
            if (a == null) return "That administrator no longer exists.";
            saveAdmins();
            auditLocked("ADMIN_REMOVED", "Administrator " + id + " ('" + a[2] + "') removed");
            return null;
        } finally {
            end();
        }
    }

    // ---------------- Plates (a driver may own several vehicles) ----------------

    /** Every plate this driver has used or saved, oldest first. */
    public static synchronized List<String> getPlates(String username) {
        sync(false);
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        for (String[] p : plates) if (p[0].equals(username)) set.add(p[1]);
        for (ReservationInfo r : reservations.values())
            if (r.user.equals(username) && r.plate != null && !r.plate.isEmpty()) set.add(r.plate);
        for (Fine f : fines)
            if (f.user.equals(username) && f.plate != null && !f.plate.isEmpty()) set.add(f.plate);
        return new ArrayList<String>(set);
    }

    public static synchronized void addPlate(String username, String plate) {
        begin();
        try {
            addPlateLocked(username, plate);
        } finally {
            end();
        }
    }

    private static void addPlateLocked(String username, String plate) {
        if (plate == null || plate.isEmpty() || isWalkIn(username)) return;
        for (String[] p : plates) if (p[0].equals(username) && p[1].equals(plate)) return;
        plates.add(new String[]{username, plate});
        savePlates();
    }

    /** The plate this user used last (empty if none). */
    public static synchronized String lastPlate(String username) {
        sync(false);
        String p = "";
        for (ReservationInfo r : reservations.values())
            if (r.user.equals(username) && r.plate != null && !r.plate.isEmpty()) p = r.plate;
        for (Fine f : fines)
            if (f.user.equals(username) && f.plate != null && !f.plate.isEmpty() && p.isEmpty()) p = f.plate;
        if (p.isEmpty()) {
            for (String[] x : plates) if (x[0].equals(username)) p = x[1];
        }
        return p;
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

    /**
     * The booking that keeps this slot closed for the given date, or null if the slot is open.
     * One booking holds a slot for the WHOLE day (so a car that overstays can never run into another driver).
     * A reservation that was never used stops holding the slot once its time has ended.
     */
    public static synchronized ReservationInfo activeBooking(String date, String slot) {
        sync(false);
        return blocking(date, slot, null, System.currentTimeMillis());
    }

    private static boolean dateAfter(String a, String b) { return Park.parseDate(a).after(Park.parseDate(b)); }

    private static boolean active(ReservationInfo r, long now) {
        return r.status == PARKED || r.status == PENDING || r.endMillis > now;
    }

    private static ReservationInfo blocking(String date, String slot, String excludeId, long now) {
        ReservationInfo best = null;
        String today = Park.today();
        for (ReservationInfo r : reservations.values()) {
            if (excludeId != null && r.id.equals(excludeId)) continue;
            if (!r.slot.equals(slot)) continue;
            boolean hit;
            if (r.date.equals(date)) hit = active(r, now);
            else hit = r.status == PARKED && dateAfter(date, r.date) && !dateAfter(date, today);   // car still there after midnight
            if (hit && (best == null || (r.status == PARKED && best.status != PARKED))) best = r;
        }
        return best;
    }

    /** Slot colour on the dashboard: 0 = free, 1 = reserved / waiting for approval, 2 = a car is parked. */
    public static synchronized int getSlotStatus(String date, String slot) {
        sync(false);
        ReservationInfo b = blocking(date, slot, null, System.currentTimeMillis());
        return b == null ? 0 : b.status == PARKED ? 2 : 1;
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

    /** A car is physically parked in this slot right now. */
    public static synchronized boolean hasParkedCar(String slot) {
        sync(false);
        return parkedIn(slot, null);
    }

    /** Start times (minutes after midnight, every STEP_MIN) at which at least one hour is free. Empty if the slot is taken that day. */
    public static synchronized List<Integer> availableStarts(String date, String slot, int fromMin) {
        sync(false);
        List<Integer> out = new ArrayList<Integer>();
        if (blocking(date, slot, null, System.currentTimeMillis()) != null) return out;
        int s = ((fromMin + STEP_MIN - 1) / STEP_MIN) * STEP_MIN;
        for (; s + 60 <= 1440; s += STEP_MIN) out.add(s);
        return out;
    }

    /** Longest stay (hours, up to 24) from startMin. 0 = the slot is taken that day. */
    public static synchronized int maxHours(String date, String slot, int startMin, boolean immediate, String excludeId) {
        sync(false);
        if (blocking(date, slot, excludeId, System.currentTimeMillis()) != null) return 0;
        int cap = immediate ? Math.max(1, (1440 - startMin) / 60) : (1440 - startMin) / 60;
        return Math.min(24, cap);
    }

    /** Slots the given booking could be moved to (open on its date). */
    public static synchronized List<String> freeSlotsFor(ReservationInfo r) {
        sync(false);
        List<String> out = new ArrayList<String>();
        long now = System.currentTimeMillis();
        for (String s : Park.SLOTS) {
            if (s.equals(r.slot)) continue;
            if (blocking(r.date, s, r.id, now) != null) continue;
            out.add(s);
        }
        return out;
    }

    private static boolean overlaps(int aS, int aE, int bS, int bE) { return aS < bE && bS < aE; }

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

    /** True if this payment reference number was already used for any booking, overtime payment or fee. */
    private static boolean refUsed(String ref, String excludeId) {
        if (ref == null || ref.isEmpty()) return false;
        for (ReservationInfo r : reservations.values()) {
            if (excludeId != null && r.id.equals(excludeId)) continue;
            if (ref.equalsIgnoreCase(r.refNo) || ref.equalsIgnoreCase(r.leaveRef)) return true;
        }
        for (Fine f : fines) if (ref.equalsIgnoreCase(f.payRef)) return true;
        return false;
    }

    private static String newId(long now) {
        return Long.toString(now, 36) + Integer.toString(RND.nextInt(46656), 36);
    }

    // ---------------- Alerts for the admin ----------------

    private static void addEventLocked(String type, String user, String slot, String plate, String message) {
        events.add(new Event(newId(System.currentTimeMillis()), System.currentTimeMillis(), type, user, slot,
                plate == null ? "" : plate, clean(message), 0));
        while (events.size() > 500) events.remove(0);
        saveEvents();
    }

    /** All alerts, oldest first. */
    public static synchronized List<Event> getEvents() {
        sync(false);
        return new ArrayList<Event>(events);
    }

    public static synchronized void markEventRead(String id) {
        begin();
        try {
            for (Event e : events) if (e.id.equals(id) && e.state == 0) { e.state = 1; saveEvents(); break; }
        } finally {
            end();
        }
    }

    public static synchronized int unreadEvents() {
        sync(false);
        int n = 0;
        for (Event e : events) if (e.state == 0) n++;
        return n;
    }

    public static synchronized void markAllRead() {
        begin();
        try {
            boolean changed = false;
            for (Event e : events) if (e.state == 0) { e.state = 1; changed = true; }
            if (changed) saveEvents();
        } finally {
            end();
        }
    }

    // ---------------- Booking changes ----------------

    /**
     * Adds a booking. Returns null on success, or a message saying why it could not be made.
     * Every online (QR) or counter booking from a driver starts as PENDING and must be approved by the admin.
     */
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
        if (info.refNo == null) info.refNo = "";
        info.refNo = clean(info.refNo).replace(" ", "").toUpperCase();
        if (!info.refNo.isEmpty() && refUsed(info.refNo, null))
            return "That reference number has already been used for another payment.\nPlease check the number and try again.";
        if (info.receipt == null) info.receipt = "";
        boolean qrPay = info.paymentMode != null && info.paymentMode.startsWith(MODE_QR);
        if (qrPay && !isWalkIn(info.user) && info.receipt.isEmpty())
            return "Please attach a screenshot or photo of your payment receipt.";
        boolean priority = false;
        if (!isWalkIn(info.user)) {
            User u = users.get(info.user);
            priority = u != null && u.priorityStatus == P_VERIFIED;
            if (Park.isPwdSlot(info.slot) && !priority)
                return "Slot " + info.slot + " is a PWD / senior citizen slot, reserved for drivers whose PWD or senior citizen ID\n"
                     + "was verified by the admin. Please pick a regular slot.";
        }
        if (blocking(info.date, info.slot, null, now) != null)
            return "Slot " + info.slot + " is already reserved or occupied on that date.\nPlease pick another slot.";
        if (plateBusy(info, null))
            return "Plate " + Fmt.formatPlate(info.plate) + " already has a booking that overlaps this time.";
        info.price = Park.priceFor(info.hours, !info.immediate, info.slot, priority);
        info.endMillis = info.immediate ? now + info.hours * HOUR_MS
                                        : Park.parseDate(info.date).getTime() + info.endMin() * 60000L;
        if (info.endMillis <= now) return "That time has already passed.";
        info.id = newId(now);
        info.startMillis = info.status == PARKED ? now : 0L;
        reservations.put(info.id, info);
        saveReservations();
        addPlateLocked(info.user, info.plate);
        auditLocked(isWalkIn(info.user) ? "WALKIN_PARKED" : info.immediate ? "PARK_NOW_REQUEST" : "RESERVATION_REQUEST",
                (isWalkIn(info.user) ? "Walk-in" : "Driver " + info.user) + ", plate " + Fmt.formatPlate(info.plate) + ", slot " + info.slot
                + ", " + info.date + " " + Fmt.timeRange(info.startMin, info.endMin()) + ", " + info.hours + "h, " + Park.money(info.price)
                + ", " + nz(info.paymentMode) + (info.refNo.isEmpty() ? "" : ", ref " + info.refNo) + (info.receipt.isEmpty() ? "" : ", receipt attached"));

        if (!isWalkIn(info.user) && info.status == PENDING) {
            boolean qr = info.paymentMode != null && info.paymentMode.startsWith(MODE_QR);
            String what = info.immediate ? "Park Now" : "Reservation";
            String msg = what + ": slot " + info.slot + ", " + Fmt.timeRange(info.startMin, info.endMin()) + " ("
                    + info.hours + "h, " + Park.money(info.price) + ")"
                    + (qr ? ", paid online via InstaPay, ref no. " + info.refNo + ". Verify it, then approve."
                          : ", to be paid at the counter.");
            addEventLocked(qr ? "ONLINE_PAY" : info.immediate ? "PARK_NOW" : "RESERVE",
                    info.user, info.slot, info.plate, msg);
        }
        return null;
    }

    /** Admin parks a driver that has no account. Starts the timer right away. */
    public static synchronized String addWalkIn(String slot, String plate, int hours, boolean pwdIdShown) {
        begin();
        try {
            ReservationInfo r = new ReservationInfo(WALK_IN, Park.today(), slot, 0, hours, hours * Park.rateOf(slot),
                    PARKED, "Walk-in (Counter)", plate, true);
            String err = insertLocked(r);
            if (err == null && Park.isPwdSlot(slot) && !pwdIdShown)
                addViolationLocked(WALK_IN, plate, slot);
            return err;
        } finally {
            end();
        }
    }

    /** Driver presses Park Now on a reservation that the admin already approved. */
    public static synchronized String startParking(String id) {
        begin();
        try {
            ReservationInfo r = reservations.get(id);
            long now = System.currentTimeMillis();
            if (r == null) return "That booking no longer exists.";
            if (r.status == PENDING) return "This booking is still waiting for the admin to validate your payment.";
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
            auditLocked("CHECK_IN", "Driver " + r.user + " parked in " + r.slot + " (plate " + Fmt.formatPlate(r.plate) + ")");
            return null;
        } finally {
            end();
        }
    }

    /**
     * Admin validates the payment of a booking.
     * Park Now bookings start parking by themselves (the timer starts now and the slot shows as occupied);
     * reservations become RESERVED.
     */
    public static synchronized String approve(String id) {
        begin();
        try {
            ReservationInfo r = reservations.get(id);
            if (r == null) return "That booking no longer exists.";
            if (r.status != PENDING) return "That booking is not waiting for approval.";
            long now = System.currentTimeMillis();
            if (r.immediate) {
                if (!r.date.equals(Park.today()))
                    return "The requested time has already passed. Reject this request instead.";
                if (blocking(r.date, r.slot, id, now) != null)
                    return "Slot " + r.slot + " is no longer free. Reject this request instead.";
                // the clock starts from the approval, not from when the request was sent
                r.startMin = Fmt.minuteOfDay(now);
                r.startMillis = now;
                r.endMillis = now + r.hours * HOUR_MS;
                r.status = PARKED;
            } else {
                if (r.endMillis <= now) return "The requested time has already passed. Reject this request instead.";
                r.status = RESERVED;
            }
            boolean qr = r.paymentMode != null && r.paymentMode.startsWith(MODE_QR);
            r.paymentMode = qr ? MODE_QR + " (verified)" : "Counter (approved)";
            saveReservations();
            auditLocked("PAYMENT_APPROVED", "Driver " + r.user + ", slot " + r.slot + ", " + r.hours + "h, " + Park.money(r.price)
                    + (r.refNo.isEmpty() ? ", counter" : ", ref " + r.refNo) + (r.status == PARKED ? " - parking started automatically" : " - reservation confirmed"));
            return null;
        } finally {
            end();
        }
    }

    /** Cancels, rejects or removes a booking without billing anything. */
    public static synchronized void clearBooking(String id) {
        begin();
        try {
            ReservationInfo r = reservations.remove(id);
            if (r != null) {
                saveReservations();
                String outcome = "ADMIN".equals(actorRole) ? (r.status == PENDING ? "REJECTED" : "REMOVED") : "CANCELLED";
                historyLocked(r, outcome, actorLabel(), 0);
                auditLocked("BOOKING_" + outcome, "Driver " + r.user + ", plate " + Fmt.formatPlate(r.plate) + ", slot " + r.slot + ", "
                        + r.date + " " + Fmt.timeRange(r.startMin, r.endMin()) + (r.refNo.isEmpty() ? "" : ", ref " + r.refNo));
            }
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
            if (blocking(r.date, newSlot, id, System.currentTimeMillis()) != null)
                return "Slot " + newSlot + " is already reserved or occupied on " + r.date + ".";
            String oldSlot = r.slot;
            r.slot = newSlot;
            saveReservations();
            auditLocked("MOVE_BOOKING", "Driver " + r.user + ", plate " + Fmt.formatPlate(r.plate) + ": slot " + oldSlot + " -> " + newSlot);
            return null;
        } finally {
            end();
        }
    }

    // ---------------- Leaving ----------------

    /**
     * Driver presses Leave and has NO overtime to pay. The slot is freed right away and the admin gets an alert
     * (so that a driver who leaves while the car is still in the slot can be charged a penalty).
     */
    public static synchronized String leave(String id) {
        begin();
        try {
            ReservationInfo r = reservations.get(id);
            if (r == null) return "That booking no longer exists.";
            if (r.status != PARKED) return "This booking is not parked.";
            long now = System.currentTimeMillis();
            if (r.billableHours(now) > 0)
                return "You have an overtime charge. Pay it first and wait for the admin to validate it.";
            reservations.remove(id);
            saveReservations();
            historyLocked(r, "COMPLETED", "User " + r.user, 0);
            auditLocked("LEAVE", "Driver " + r.user + " left slot " + r.slot + " (plate " + Fmt.formatPlate(r.plate) + ")");
            long rem = r.remainingMillis(now);
            String how = rem > 0 ? "left with " + Park.hms(rem) + " of paid time remaining"
                       : rem == 0 ? "left exactly on time"
                       : "left " + Park.hms(-rem) + " after the paid time ended (inside the grace period)";
            addEventLocked("LEAVE", r.user, r.slot, r.plate, "Driver pressed Leave: " + how + ". Parked for "
                    + Park.hms(now - r.startMillis) + ". Please check that the car really left the slot.");
            return null;
        } finally {
            end();
        }
    }

    /**
     * Driver has overtime: pays it (QR reference or counter) and asks to leave. The fee is frozen at this moment.
     * The car stays PARKED until the admin validates the payment (approveLeave).
     */
    public static synchronized String requestLeave(String id, String mode, String ref, double expectedFee, String receipt) {
        begin();
        try {
            ReservationInfo r = reservations.get(id);
            if (r == null) return "That booking no longer exists.";
            if (r.status != PARKED) return "This booking is not parked.";
            if (r.leaveReqMillis > 0) return "You already sent an overtime payment. Please wait for the admin to validate it.";
            long now = System.currentTimeMillis();
            int ot = r.billableHours(now);
            if (ot <= 0) return "There is no overtime charge. You can leave without paying.";
            double rate = Park.rateOf(r.slot);
            if (ot * rate > expectedFee + 0.001)
                return "The overtime charge went up to " + Park.money(ot * rate) + " while you were paying.\n"
                     + "Please press Leave again and pay the new total.";
            String rf = clean(ref).replace(" ", "").toUpperCase();
            boolean qr = MODE_QR.equals(mode);
            if (qr) {
                if (rf.isEmpty()) return "Please enter the payment reference number.";
                if (receipt == null || receipt.isEmpty()) return "Please attach a screenshot or photo of your payment receipt.";
                if (refUsed(rf, id)) return "That reference number has already been used for another payment.";
            } else {
                rf = "";
            }
            r.leaveReceipt = qr ? receipt : "";
            r.leaveReqMillis = now;
            r.leaveHours = ot;
            r.leaveFee = ot * rate;
            r.leaveMode = qr ? MODE_QR : MODE_COUNTER;
            r.leaveRef = rf;
            saveReservations();
            auditLocked("OVERTIME_PAYMENT_SENT", "Driver " + r.user + ", slot " + r.slot + ", " + Park.money(r.leaveFee) + " for " + ot + "h overtime, "
                    + (qr ? "ref " + rf + ", receipt attached" : "counter"));
            addEventLocked("LEAVE_PAY", r.user, r.slot, r.plate, "Overtime payment " + Park.money(r.leaveFee) + " (" + ot
                    + "h over) for slot " + r.slot + (qr ? " via InstaPay, ref no. " + rf + "." : " to be paid at the counter.")
                    + " The driver cannot leave until you validate it.");
            return null;
        } finally {
            end();
        }
    }

    /** Clears an overtime payment request (the driver cancels it, or the admin could not validate it). */
    public static synchronized void clearLeaveRequest(String id) {
        begin();
        try {
            ReservationInfo r = reservations.get(id);
            if (r == null || r.leaveReqMillis <= 0) return;
            double was = r.leaveFee;
            r.leaveReqMillis = 0L; r.leaveHours = 0; r.leaveFee = 0; r.leaveMode = ""; r.leaveRef = ""; r.leaveReceipt = "";
            saveReservations();
            auditLocked("ADMIN".equals(actorRole) ? "OVERTIME_PAYMENT_REJECTED" : "OVERTIME_PAYMENT_CANCELLED",
                    "Driver " + r.user + ", slot " + r.slot + ", " + Park.money(was));
        } finally {
            end();
        }
    }

    /** Admin validated the overtime payment: the car is checked out and the fee is recorded as paid. */
    public static synchronized Fine approveLeave(String id) {
        begin();
        try {
            ReservationInfo r = reservations.get(id);
            if (r == null || r.status != PARKED || r.leaveReqMillis <= 0) return null;
            reservations.remove(id);
            saveReservations();
            Fine f = new Fine(r.user, r.date, r.slot, r.plate, r.hours, r.leaveHours, r.leaveFee,
                    "User (admin approved)", System.currentTimeMillis(), true);
            f.payMode = r.leaveMode;
            f.payRef = r.leaveRef;
            f.payReceipt = r.leaveReceipt;
            fines.add(f);
            saveFines();
            historyLocked(r, "COMPLETED", "User " + r.user + " (approved by " + actorLabel() + ")", r.leaveFee);
            auditLocked("LEAVE_APPROVED", "Driver " + r.user + ", slot " + r.slot + ", overtime " + Park.money(r.leaveFee)
                    + (r.leaveRef.isEmpty() ? ", counter" : ", ref " + r.leaveRef));
            return f;
        } finally {
            end();
        }
    }

    /**
     * Ends a parked session on the admin's side (or a legacy path). If the car stayed past the paid time (plus grace),
     * an overtime fine is recorded and returned; otherwise returns null. endedBy is "User" or "Admin".
     */
    public static synchronized Fine checkout(String id, String endedBy, boolean paidNow) {
        begin();
        try {
            ReservationInfo r = reservations.remove(id);
            if (r == null) return null;
            saveReservations();
            long now = System.currentTimeMillis();
            int ot = r.status == PARKED ? r.billableHours(now) : 0;
            double otFee = ot * Park.rateOf(r.slot);
            if (r.status == PARKED) {
                historyLocked(r, "COMPLETED", actorLabel() + " (force check-out)", otFee);
                auditLocked("FORCE_CHECKOUT", "Driver " + r.user + ", plate " + Fmt.formatPlate(r.plate) + ", slot " + r.slot
                        + (ot > 0 ? ", overtime " + Park.money(otFee) + (paidNow ? " collected" : " billed to the account") : ", no overtime"));
            } else {
                historyLocked(r, "REMOVED", actorLabel(), 0);
            }
            if (r.status != PARKED || ot <= 0) return null;
            Fine f = new Fine(r.user, r.date, r.slot, r.plate, r.hours, ot, otFee, endedBy, now, paidNow);
            fines.add(f);
            saveFines();
            return f;
        } finally {
            end();
        }
    }

    // ---------------- Fees (overtime and penalties) ----------------

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

    public static synchronized List<Fine> getFinesForUser(String username) {
        sync(false);
        List<Fine> list = new ArrayList<Fine>();
        for (Fine f : fines) if (f.user.equals(username)) list.add(f);
        return list;
    }

    public static synchronized double getUnpaidTotal() {
        sync(false);
        double t = 0;
        for (Fine f : fines) if (!f.paid) t += f.fee;
        return t;
    }

    /** Number of fee payments that the admin still has to validate. */
    public static synchronized int pendingFinePayments() {
        sync(false);
        int n = 0;
        for (Fine f : fines) if (!f.paid && f.payPending) n++;
        return n;
    }

    /** Admin: the fee is paid (this validates a driver's payment, or records a payment collected at the counter). */
    public static synchronized void payFine(Fine target) {
        begin();
        try {
            for (Fine f : fines) {
                if (f.key().equals(target.key())) {
                    f.paid = true; f.payPending = false;
                    auditLocked("FEE_PAYMENT_APPROVED", f.kind + " " + Park.money(f.fee) + " of " + f.user + ", slot " + f.slot
                            + (f.payRef.isEmpty() ? "" : ", ref " + f.payRef));
                    break;
                }
            }
            saveFines();
        } finally {
            end();
        }
    }

    /** Driver: sends the payment of a billed fee (QR reference or counter) to the admin to be validated. */
    public static synchronized String submitFinePayment(Fine target, String mode, String ref, String receipt) {
        begin();
        try {
            Fine f = null;
            for (Fine x : fines) if (x.key().equals(target.key())) { f = x; break; }
            if (f == null) return "That fee no longer exists.";
            if (f.paid) return "That fee is already paid.";
            if (f.payPending) return "You already sent this payment. Please wait for the admin to validate it.";
            String rf = clean(ref).replace(" ", "").toUpperCase();
            boolean qr = MODE_QR.equals(mode);
            if (qr) {
                if (rf.isEmpty()) return "Please enter the payment reference number.";
                if (receipt == null || receipt.isEmpty()) return "Please attach a screenshot or photo of your payment receipt.";
                if (refUsed(rf, null)) return "That reference number has already been used for another payment.";
            } else {
                rf = "";
            }
            f.payPending = true;
            f.payMode = qr ? MODE_QR : MODE_COUNTER;
            f.payRef = rf;
            f.payReceipt = qr ? receipt : "";
            saveFines();
            auditLocked("FEE_PAYMENT_SENT", f.kind + " " + Park.money(f.fee) + ", slot " + f.slot + (qr ? ", ref " + rf + ", receipt attached" : ", counter"));
            addEventLocked("FINE_PAY", f.user, f.slot, f.plate, f.kind + " of " + Park.money(f.fee) + " (slot " + f.slot
                    + ") " + (qr ? "paid via InstaPay, ref no. " + rf + ". Verify it, then mark it as paid."
                                 : "to be paid at the counter."));
            return null;
        } finally {
            end();
        }
    }

    /** Admin could not validate a fee payment: it goes back to unpaid. */
    public static synchronized void rejectFinePayment(Fine target) {
        begin();
        try {
            for (Fine f : fines) {
                if (f.key().equals(target.key())) {
                    f.payPending = false; f.payMode = ""; f.payRef = ""; f.payReceipt = "";
                    auditLocked("FEE_PAYMENT_REJECTED", f.kind + " " + Park.money(f.fee) + " of " + f.user + ", slot " + f.slot);
                    break;
                }
            }
            saveFines();
        } finally {
            end();
        }
    }

    private static void addViolationLocked(String user, String plate, String slot) {
        Fine f = new Fine(user, Park.today(), slot, plate == null ? "" : plate, 0, 0, Park.PWD_VIOLATION, "Admin",
                System.currentTimeMillis(), false);
        f.kind = "PWD violation";
        fines.add(f);
        saveFines();
        auditLocked("PWD_VIOLATION_CHARGED", Park.money(Park.PWD_VIOLATION) + " to " + user + ", plate " + Fmt.formatPlate(plate == null ? "" : plate) + ", slot " + slot);
    }

    /** Admin charges the PWD violation (a driver parked in a PWD / senior citizen slot without being a PWD driver). Works for walk-ins too. */
    public static synchronized void chargeViolation(String user, String plate, String slot) {
        begin();
        try {
            addViolationLocked(user, plate, slot);
        } finally {
            end();
        }
    }

    /** Admin charges the Leave-button penalty for a "Leave" alert (the driver left the app while the car was still there). */
    public static synchronized String chargePenalty(String eventId) {
        begin();
        try {
            Event e = null;
            for (Event x : events) if (x.id.equals(eventId)) { e = x; break; }
            if (e == null) return "That alert no longer exists.";
            if (!"LEAVE".equals(e.type)) return "A penalty can only be charged on a \"Leave\" alert.";
            if (e.state == 2) return "A penalty was already charged for this alert.";
            Fine f = new Fine(e.user, Park.today(), e.slot, e.plate, 0, 0, Park.LEAVE_PENALTY, "Admin",
                    System.currentTimeMillis(), false);
            f.kind = "Leave penalty";
            fines.add(f);
            saveFines();
            e.state = 2;
            saveEvents();
            auditLocked("LEAVE_PENALTY_CHARGED", Park.money(Park.LEAVE_PENALTY) + " to " + e.user + ", plate " + Fmt.formatPlate(e.plate) + ", slot " + e.slot);
            return null;
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

    private static String nz(String s) { return s == null ? "" : clean(s); }

    private static void saveUsers() {
        List<String> lines = new ArrayList<String>();
        for (User u : users.values())
            lines.add(u.id + "\t" + u.hash + "\t" + u.name + "\t" + u.phone + "\t" + u.email + "\t" + u.consentMillis + "\t" + nz(u.priorityId)
                    + "\t" + nz(u.priorityType) + "\t" + nz(u.birth) + "\t" + u.priorityStatus + "\t" + nz(u.proofFile));
        writeAtomic(USERS_FILE, lines);
    }

    private static void saveReservations() {
        List<String> lines = new ArrayList<String>();
        for (ReservationInfo r : reservations.values())
            lines.add(r.id + "\t" + r.user + "\t" + r.date + "\t" + r.slot + "\t" + r.startMin + "\t" + r.hours + "\t"
                    + r.price + "\t" + r.status + "\t" + nz(r.paymentMode) + "\t" + r.startMillis + "\t" + r.endMillis
                    + "\t" + (r.immediate ? "1" : "0") + "\t" + nz(r.plate)
                    + "\t" + nz(r.refNo) + "\t" + r.leaveReqMillis + "\t" + r.leaveFee + "\t" + r.leaveHours
                    + "\t" + nz(r.leaveMode) + "\t" + nz(r.leaveRef)
                    + "\t" + nz(r.receipt) + "\t" + nz(r.leaveReceipt));
        writeAtomic(RES_FILE, lines);
    }

    private static void saveFines() {
        List<String> lines = new ArrayList<String>();
        for (Fine f : fines)
            lines.add(f.user + "\t" + f.date + "\t" + f.slot + "\t" + f.paidHours + "\t" + f.overtimeHours + "\t"
                    + f.fee + "\t" + f.endedBy + "\t" + f.createdMillis + "\t" + (f.paid ? "1" : "0")
                    + "\t" + nz(f.plate) + "\t" + nz(f.kind) + "\t" + (f.payPending ? "1" : "0")
                    + "\t" + nz(f.payMode) + "\t" + nz(f.payRef) + "\t" + nz(f.payReceipt));
        writeAtomic(FINES_FILE, lines);
    }

    private static void saveEvents() {
        List<String> lines = new ArrayList<String>();
        for (Event e : events)
            lines.add(e.id + "\t" + e.millis + "\t" + e.type + "\t" + e.user + "\t" + e.slot + "\t" + nz(e.plate)
                    + "\t" + nz(e.message) + "\t" + e.state);
        writeAtomic(EVENTS_FILE, lines);
    }

    private static void saveAdmins() {
        List<String> lines = new ArrayList<String>();
        for (String[] a : admins.values()) lines.add(a[0] + "\t" + a[1] + "\t" + clean(a[2]) + "\t" + a[3] + "\t" + a[4]);
        writeAtomic(ADMINS_FILE, lines);
    }

    private static void saveAdminReqs() {
        List<String> lines = new ArrayList<String>();
        for (String[] a : adminReqs) lines.add(a[0] + "\t" + a[1] + "\t" + clean(a[2]) + "\t" + a[3]);
        writeAtomic(REQ_FILE, lines);
    }

    private static void savePlates() {
        List<String> lines = new ArrayList<String>();
        for (String[] p : plates) lines.add(p[0] + "\t" + p[1]);
        writeAtomic(PLATES_FILE, lines);
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
        admins.clear();
        adminReqs.clear();
        reservations.clear();
        fines.clear();
        events.clear();
        plates.clear();
        boolean needResave = false;

        for (String line : readLines(USERS_FILE)) {
            String[] p = line.split("\t", -1);
            if (p.length == 2) {
                users.put(p[0], new User(p[0], p[1], p[0], "", "", 0L, "", "", "", P_NONE));
            } else if (p.length == 6 || p.length == 7 || p.length == 10 || p.length == 11) {
                long consent = 0L;
                try { consent = Long.parseLong(p[5]); } catch (NumberFormatException ignore) { }
                if (p.length >= 10) {
                    int st = P_NONE;
                    try { st = Integer.parseInt(p[9]); } catch (NumberFormatException ignore) { }
                    users.put(p[0], new User(p[0], p[1], p[2], p[3], p[4], consent, p[7], p[6], p[8], st, p.length == 11 ? p[10] : ""));
                } else if (p.length == 7 && !p[6].isEmpty()) {
                    // older save file: a PWD ID without verification. It now has to be verified by the admin.
                    users.put(p[0], new User(p[0], p[1], p[2], p[3], p[4], consent, PRIORITY_PWD, p[6], "", P_PENDING));
                } else {
                    users.put(p[0], new User(p[0], p[1], p[2], p[3], p[4], consent, "", "", "", P_NONE));
                }
            }
        }

        long now = System.currentTimeMillis();
        for (String line : readLines(RES_FILE)) {
            String[] p = line.split("\t", -1);
            try {
                if (p.length == 13 || p.length == 19 || p.length == 21) {
                    ReservationInfo r = new ReservationInfo(p[0], p[1], p[2], p[3],
                            Integer.parseInt(p[4]), Integer.parseInt(p[5]), Double.parseDouble(p[6]),
                            Integer.parseInt(p[7]), p[8], Long.parseLong(p[9]), Long.parseLong(p[10]),
                            "1".equals(p[11]), p[12]);
                    if (p.length >= 19) {
                        if (p.length == 21) { r.receipt = p[19]; r.leaveReceipt = p[20]; }
                        r.refNo = p[13];
                        r.leaveReqMillis = Long.parseLong(p[14]);
                        r.leaveFee = Double.parseDouble(p[15]);
                        r.leaveHours = Integer.parseInt(p[16]);
                        r.leaveMode = p[17];
                        r.leaveRef = p[18];
                    }
                    r.slot = Park.normalizeSlot(r.slot);
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
                    r.slot = Park.normalizeSlot(r.slot);
                    reservations.put(r.id, r);
                    needResave = true;
                }
            } catch (NumberFormatException ignore) {
                // skip a damaged line
            }
        }

        for (String line : readLines(FINES_FILE)) {
            String[] p = line.split("\t", -1);
            if (p.length != 9 && p.length != 10 && p.length != 14 && p.length != 15) continue;
            try {
                Fine f = new Fine(p[0], p[1], p[2], p.length >= 10 ? p[9] : "", Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Double.parseDouble(p[5]), p[6], Long.parseLong(p[7]), "1".equals(p[8]));
                if (p.length >= 14) {
                    if (p.length == 15) f.payReceipt = p[14];
                    f.kind = p[10].isEmpty() ? "Overtime" : p[10];
                    f.payPending = "1".equals(p[11]);
                    f.payMode = p[12];
                    f.payRef = p[13];
                }
                f.slot = Park.normalizeSlot(f.slot);
                fines.add(f);
            } catch (NumberFormatException ignore) {
                // skip a damaged line
            }
        }

        for (String line : readLines(EVENTS_FILE)) {
            String[] p = line.split("\t", -1);
            if (p.length != 8) continue;
            try {
                events.add(new Event(p[0], Long.parseLong(p[1]), p[2], p[3], Park.normalizeSlot(p[4]), p[5], p[6], Integer.parseInt(p[7])));
            } catch (NumberFormatException ignore) {
                // skip a damaged line
            }
        }

        for (String line : readLines(ADMINS_FILE)) {
            String[] p = line.split("\t", -1);
            if (p.length == 5) admins.put(p[0], p);
        }
        for (String line : readLines(REQ_FILE)) {
            String[] p = line.split("\t", -1);
            if (p.length == 4) adminReqs.add(p);
        }

        for (String line : readLines(PLATES_FILE)) {
            String[] p = line.split("\t", -1);
            if (p.length == 2) plates.add(new String[]{p[0], p[1]});
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
        public final String id;          // the username
        public final String hash;
        public final String name;
        public final String phone;
        public final String email;
        public final long   consentMillis;
        public final String priorityType;    // "", PRIORITY_PWD or PRIORITY_SENIOR
        public final String priorityId;      // PWD ID / senior citizen ID number
        public final String birth;           // yyyy-MM-dd (seniors only)
        public final int    priorityStatus;  // P_NONE, P_PENDING, P_VERIFIED, P_REJECTED
        public final String proofFile;       // stored name of the attached ID photo

        public User(String id, String hash, String name, String phone, String email, long consentMillis,
                    String priorityType, String priorityId, String birth, int priorityStatus) {
            this(id, hash, name, phone, email, consentMillis, priorityType, priorityId, birth, priorityStatus, "");
        }

        public User(String id, String hash, String name, String phone, String email, long consentMillis,
                    String priorityType, String priorityId, String birth, int priorityStatus, String proofFile) {
            this.proofFile = proofFile == null ? "" : proofFile;
            this.id = id; this.hash = hash; this.name = name;
            this.phone = phone; this.email = email; this.consentMillis = consentMillis;
            this.priorityType = priorityType == null ? "" : priorityType;
            this.priorityId = priorityId == null ? "" : priorityId;
            this.birth = birth == null ? "" : birth;
            this.priorityStatus = priorityStatus;
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
        public String  refNo = "";    // InstaPay reference number typed by the driver (QR payments)
        public String  receipt = "";  // stored name of the attached payment receipt picture (QR payments)
        public String  leaveReceipt = "";

        // overtime payment waiting for the admin ("leave request"); leaveReqMillis = 0 when there is none
        public long    leaveReqMillis = 0L;
        public double  leaveFee = 0;
        public int     leaveHours = 0;
        public String  leaveMode = "";
        public String  leaveRef = "";

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

        /** True when the driver already sent an overtime payment that the admin has not validated yet. */
        public boolean hasLeaveRequest() { return status == PARKED && leaveReqMillis > 0; }

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

        public double overtimeFee(long now) { return billableHours(now) * Park.rateOf(slot); }

        /** The overtime amount to show: frozen at the moment of the leave request if there is one. */
        public double shownOvertimeFee(long now) { return hasLeaveRequest() ? leaveFee : overtimeFee(now); }

        /** True when a reserved (and approved) booking may be checked in right now. */
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
        public String  kind = "Overtime";     // "Overtime" or "Leave penalty"
        public boolean payPending = false;    // the driver sent the payment, the admin has not validated it yet
        public String  payMode = "";
        public String  payRef = "";
        public String  payReceipt = "";

        public Fine(String user, String date, String slot, String plate, int paidHours, int overtimeHours,
                    double fee, String endedBy, long createdMillis, boolean paid) {
            this.user = user; this.date = date; this.slot = slot; this.plate = plate;
            this.paidHours = paidHours; this.overtimeHours = overtimeHours; this.fee = fee;
            this.endedBy = endedBy; this.createdMillis = createdMillis; this.paid = paid;
        }

        public String key() { return createdMillis + "|" + user + "|" + slot; }
    }

    /** An alert for the admin: someone reserved, paid online, pressed Leave, and so on. */
    public static class Event {
        public final String id;
        public final long   millis;
        public final String type;      // RESERVE, PARK_NOW, ONLINE_PAY, LEAVE, LEAVE_PAY, FINE_PAY
        public final String user;
        public final String slot;
        public final String plate;
        public final String message;
        public int state;              // 0 = new, 1 = read, 2 = penalty charged

        public Event(String id, long millis, String type, String user, String slot, String plate, String message, int state) {
            this.id = id; this.millis = millis; this.type = type; this.user = user; this.slot = slot;
            this.plate = plate; this.message = message; this.state = state;
        }
    }
}