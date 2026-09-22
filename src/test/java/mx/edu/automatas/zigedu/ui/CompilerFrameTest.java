package mx.edu.automatas.zigedu.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/** Prueba visual opcional: mvn -Dzigedu.uiTests=true test en una sesión gráfica. */
@EnabledIfSystemProperty(named="zigedu.uiTests", matches="true")
class CompilerFrameTest {
    private CompilerFrame frame;

    private Object field(String name) throws Exception {
        Field f = CompilerFrame.class.getDeclaredField(name); f.setAccessible(true); return f.get(frame);
    }
    private void invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method m = CompilerFrame.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(frame, arguments);
    }
    private List<Component> descendants(Container root) {
        List<Component> result = new ArrayList<>();
        for (Component c : root.getComponents()) {
            result.add(c);
            if (c instanceof Container child) result.addAll(descendants(child));
        }
        return result;
    }
    private void awaitAnalysis() throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> running = new AtomicReference<>(true);
            SwingUtilities.invokeAndWait(() -> {
                try { running.set((Boolean) field("analysisRunning")); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            if (!running.get()) return;
            Thread.sleep(25);
        }
        fail("El análisis no terminó dentro de 10 segundos");
    }
    private void analyze(String source) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                invoke("replaceSource", new Class[]{String.class, Path.class}, source, null);
                invoke("runAnalysis", new Class[]{boolean.class}, false);
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        awaitAnalysis();
    }
    private void capture(String name) throws Exception {
        String directory = System.getProperty("zigedu.screenshots");
        if (directory == null) return;
        Path folder = Path.of(directory); Files.createDirectories(folder);
        ImageIO.write(new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())),
                "png", folder.resolve(name + ".png").toFile());
    }

    @Test void preservesExistingTabsShowsSemanticDiagnosticsAndClearsStaleResults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try { UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel"); }
            catch (Exception e) { throw new RuntimeException(e); }
            frame = new CompilerFrame(); frame.setVisible(true);
        });
        try {
            awaitAnalysis();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    assertTrue(((JLabel) field("analysisBadge")).getText().contains("SIN ERRORES"));
                    List<JTabbedPane> tabs = descendants(frame).stream().filter(JTabbedPane.class::isInstance)
                            .map(JTabbedPane.class::cast).toList();
                    JTabbedPane results = tabs.stream().filter(t -> t.getTitleAt(0).equals("Análisis léxico")).findFirst().orElseThrow();
                    assertEquals(3, results.getTabCount());
                    assertEquals("Análisis sintáctico", results.getTitleAt(1));
                    assertEquals("Análisis semántico", results.getTitleAt(2));
                    results.setSelectedIndex(2);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            analyze("fn main() void {\n    var edad: i32 = true;\n    total = 10;\n    var edad: bool = false;\n}");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    assertEquals(2, ((JTabbedPane) field("errorTabs")).getSelectedIndex());
                    assertTrue(((JLabel) field("analysisBadge")).getText().contains("3 ERRORES"));
                    String details = ((JTextArea) field("semanticErrors")).getText();
                    assertTrue(details.contains("línea 2, columna 21"));
                    assertTrue(details.contains("SEM_NO_DECLARADO"));
                    assertTrue(details.contains("SEM_DUPLICADO"));
                    assertTrue(details.contains("Se esperaba:"));
                    assertTrue(((DefaultTableModel) field("tokenModel")).getRowCount() > 0);
                    assertTrue(((DefaultTableModel) field("syntaxModel")).getRowCount() > 0);
                    assertTrue(((DefaultTableModel) field("semanticModel")).getRowCount() > 0);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            capture("interfaz_semantica");
            AtomicReference<String> dialogText = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                Timer timer = new Timer(350, event -> {
                    for (Window window : Window.getWindows()) {
                        if (window instanceof JDialog dialog && dialog.isShowing()) {
                            try {
                                dialogText.set(descendants(dialog).stream().filter(JTextArea.class::isInstance)
                                        .map(JTextArea.class::cast).map(JTextArea::getText).findFirst().orElseThrow());
                                capture("alerta_semantica");
                            } catch (Throwable e) { failure.set(e); }
                            finally { dialog.dispose(); }
                        }
                    }
                });
                timer.setRepeats(false); timer.start();
                try {
                    String source = ((JTextArea) field("sourceEditor")).getText();
                    invoke("showAnalysisAlert", new Class[]{mx.edu.automatas.zigedu.analysis.AnalysisResult.class},
                            new mx.edu.automatas.zigedu.analysis.SourceAnalyzer().analyze(source));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertNull(failure.get()); assertNotNull(dialogText.get());
            assertTrue(dialogText.get().contains("Errores semánticos: 3"));
            assertTrue(dialogText.get().contains("SEM_TIPO"));
            assertTrue(dialogText.get().contains("SEM_DUPLICADO"));
            assertTrue(dialogText.get().contains("Se encontró:"));
            analyze("fn main() void { var x = ; }");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    assertTrue(((JTextArea) field("semanticErrors")).getText().contains("no se ejecutó"));
                    assertEquals(1, ((DefaultTableModel) field("semanticModel")).getRowCount());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            analyze("fn main() void { const x = 1; _ = x; }");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    assertEquals("Sin errores semánticos.", ((JTextArea) field("semanticErrors")).getText());
                    assertTrue(((JLabel) field("analysisBadge")).getText().contains("SIN ERRORES"));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> frame.dispose());
        }
    }
}
