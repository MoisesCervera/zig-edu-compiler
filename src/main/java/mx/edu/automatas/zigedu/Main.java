package mx.edu.automatas.zigedu;

import mx.edu.automatas.zigedu.ui.CompilerFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            configureLookAndFeel();
            CompilerFrame frame = new CompilerFrame();
            frame.setVisible(true);
        });
    }

    private static void configureLookAndFeel() {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    return;
                }
            }
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing conservará su apariencia multiplataforma predeterminada.
        }
    }
}
