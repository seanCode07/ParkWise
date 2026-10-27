package parkwise;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        DataStore.version();      // start the data layer now (creates the data folder and the administrator recovery file)
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                new LoginFrame().setVisible(true);
            }
        });
    }
}