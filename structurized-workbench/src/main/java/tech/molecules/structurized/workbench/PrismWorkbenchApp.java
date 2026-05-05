package tech.molecules.structurized.workbench;

import tech.molecules.structurized.workbench.prism.PrismRepositoryPanel;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.nio.file.Path;

/**
 * Small desktop app for exploring PRISM TSV repositories.
 */
public final class PrismWorkbenchApp {
    private PrismWorkbenchApp() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Structurized Workbench");
            PrismRepositoryPanel repositoryPanel = new PrismRepositoryPanel();
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.add(repositoryPanel, BorderLayout.CENTER);
            frame.setSize(1280, 820);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            if (args.length > 0) {
                try {
                    repositoryPanel.loadRepository(Path.of(args[0]));
                } catch (Exception e) {
                    throw new IllegalStateException("failed to load PRISM TSV repository " + args[0], e);
                }
            }
        });
    }
}
