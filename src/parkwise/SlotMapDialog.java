package parkwise;

import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.swing.*;

/* =====================================================================
   SlotMapDialog : the admin picks the slot to move a booking to by clicking it on the floor plan.
   Free slots (green outline) can be clicked; the booking's current slot is marked; taken slots are dimmed.
   After the dialog closes, "chosen" holds the picked slot id (null if cancelled).
   ===================================================================== */
class SlotMapDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    String chosen = null;

    private final DataStore.ReservationInfo booking;
    private final Set<String> free = new HashSet<String>();
    private FloorPlan plan;
    private JComboBox<String> floorBox;
    private JLabel status;
    private Park.RoundButton moveBtn;
    private javax.swing.Timer timer;
    private String picked = null;

    SlotMapDialog(Frame owner, DataStore.ReservationInfo booking, String driverText) {
        super(owner, "ParkWise - Move to Another Slot", true);
        this.booking = booking;
        free.addAll(DataStore.freeSlotsFor(booking));
        Park.applyIcon(this);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1060, scr.width - 40), Math.min(720, scr.height - 40));
        setLocationRelativeTo(owner);
        buildUI(driverText);
        timer = new javax.swing.Timer(1000, new ActionListener() {
            public void actionPerformed(ActionEvent e) { plan.repaint(); }
        });
        timer.start();
    }

    public void dispose() {
        if (timer != null) timer.stop();
        super.dispose();
    }

    private void buildUI(String driverText) {
        Park.BgPanel root = new Park.BgPanel(new BorderLayout());
        root.setBorder(Park.pad(14, 16, 14, 16));
        Park.Card card = new Park.Card(new BorderLayout(0, 8), 20);
        card.setBorder(Park.pad(14, 16, 12, 16));

        JPanel head = new JPanel(new BorderLayout(0, 4)); head.setOpaque(false);
        head.add(Park.lbl("Move this booking to another slot", Park.bold(18), Park.TEXT_PRIMARY), BorderLayout.NORTH);
        head.add(Park.lbl(driverText + "   \u00B7   now in slot " + booking.slot + "   \u00B7   " + booking.date + ", "
                + Fmt.timeRange(booking.startMin, booking.endMin()), Park.plain(12), Park.TEXT_MUTED), BorderLayout.CENTER);

        floorBox = new JComboBox<String>(Park.FLOOR_NAMES);
        Fmt.styleCombo(floorBox);
        floorBox.setPreferredSize(new Dimension(200, 36));
        floorBox.setSelectedIndex(Park.floorOf(booking.slot) - 1);
        floorBox.addActionListener(Fmt.act(new Runnable() { public void run() { showFloor(); } }));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)); row.setOpaque(false);
        row.add(Park.lbl("Floor", Park.bold(12), Park.TEXT_MUTED)); row.add(floorBox);
        row.add(Park.lbl("Click a slot with a green outline.  Blue = where the booking is now.  Dimmed = taken.", Park.plain(12), Park.TEXT_MUTED));
        JPanel north = new JPanel(new BorderLayout(0, 8)); north.setOpaque(false);
        north.add(head, BorderLayout.NORTH); north.add(row, BorderLayout.CENTER);
        card.add(north, BorderLayout.NORTH);

        plan = new FloorPlan();
        plan.setAdminMode(true);
        plan.setListener(new FloorPlan.Listener() {
            public void slotClicked(String id) { onClick(id); }
        });
        card.add(plan, BorderLayout.CENTER);

        status = Park.lbl("Pick the slot where the car really is.", Park.bold(13), Park.TEXT_PRIMARY);
        moveBtn = Park.primaryBtn("Move here");
        moveBtn.setPreferredSize(new Dimension(170, 42));
        moveBtn.setEnabled(false);
        moveBtn.addActionListener(Fmt.act(new Runnable() { public void run() { chosen = picked; dispose(); } }));
        Park.RoundButton cancel = Park.secondaryBtn("Cancel");
        cancel.setPreferredSize(new Dimension(120, 42));
        cancel.addActionListener(Fmt.act(new Runnable() { public void run() { dispose(); } }));
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); btns.setOpaque(false);
        btns.add(cancel); btns.add(moveBtn);
        JPanel south = new JPanel(new BorderLayout()); south.setOpaque(false);
        south.add(status, BorderLayout.CENTER); south.add(btns, BorderLayout.EAST);
        card.add(south, BorderLayout.SOUTH);

        root.add(card, BorderLayout.CENTER);
        setContentPane(root);
        showFloor();
    }

    private void showFloor() {
        int floor = floorBox.getSelectedIndex() + 1;
        Map<String, DataStore.ReservationInfo> infos = new HashMap<String, DataStore.ReservationInfo>();
        for (String s : Park.slotsOnFloor(floor)) {
            DataStore.ReservationInfo i = DataStore.activeBooking(booking.date, s);
            if (i != null) infos.put(s, i);
        }
        plan.setFloor(floor);
        plan.setData("", true, infos);
        plan.setSelectionMode(free, booking.slot);
        plan.setSelected(picked != null && Park.floorOf(picked) == floor ? picked : null);
    }

    private void onClick(String id) {
        if (id.equals(booking.slot)) {
            status.setText("That is the slot the booking is in now. Pick a different one.");
            return;
        }
        if (!free.contains(id)) {
            status.setText("Slot " + id + " is not free on " + booking.date + ". Pick a slot with a green outline.");
            return;
        }
        picked = id;
        plan.setSelected(id);
        moveBtn.setEnabled(true);
        status.setText("Move to slot " + id + "  (" + Park.floorName(Park.floorOf(id)) + ", " + Park.slotKind(id) + ")");
    }
}