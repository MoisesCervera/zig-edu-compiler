package mx.edu.automatas.zigedu.ui;

import mx.edu.automatas.zigedu.analysis.AnalysisResult;
import mx.edu.automatas.zigedu.analysis.Diagnostic;
import mx.edu.automatas.zigedu.analysis.ResultExporter;
import mx.edu.automatas.zigedu.analysis.SourceAnalyzer;
import mx.edu.automatas.zigedu.analysis.TokenInfo;
import mx.edu.automatas.zigedu.ast.AstFormatter;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** Ventana principal del analizador educativo. */
public final class CompilerFrame extends JFrame {
    private static final Color NAVY = new Color(28, 39, 67);
    private static final Color BLUE = new Color(50, 96, 214);
    private static final Color SUCCESS = new Color(36, 128, 76);
    private static final Color FAILURE = new Color(190, 55, 55);
    private static final Color SOFT_BACKGROUND = new Color(246, 248, 252);
    private static final Font CODE_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private static final String FALLBACK_SOURCE = """
            pub fn main() void {
                const values = [_]i32{ 3, 5, 8, 13 };
                var total: i32 = 0;

                for (values) |value| {
                    total += value;
                }

                if (total > 20) {
                    total -= 1;
                } else {
                    total += 1;
                }
                _ = total;
            }
            """;

    private final SourceAnalyzer analyzer = new SourceAnalyzer();
    private final Path outputDirectory = locateProjectRoot().resolve("out");
    private final ResultExporter exporter = new ResultExporter(outputDirectory);

    private final JTextArea sourceEditor = new JTextArea();
    private final DefaultTableModel tokenModel = new DefaultTableModel(
            new String[]{"#", "Tipo", "Lexema", "Inicio", "Fin"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable tokenTable = new JTable(tokenModel);
    private final DefaultTableModel syntaxModel = new DefaultTableModel(
            new String[]{"#", "Construcción", "Detalle", "Ubicación"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable syntaxTable = new JTable(syntaxModel);
    private final JTextArea lexicalErrors = resultArea();
    private final JTextArea syntacticErrors = resultArea();
    private final JTabbedPane errorTabs = new JTabbedPane();
    private final JLabel fileLabel = new JLabel("Sin archivo");
    private final JLabel cursorLabel = new JLabel("Línea 1, columna 1");
    private final JLabel linesLabel = new JLabel("1 línea");
    private final JLabel analysisLabel = new JLabel("Listo");
    private final JLabel analysisBadge = new JLabel(" SIN EJECUTAR ");

    private Path currentFile;
    private boolean dirty;
    private boolean replacingDocument;

    public CompilerFrame() {
        super("Zig Edu Compiler — análisis léxico y sintáctico");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 700));
        setSize(1380, 880);
        setLocationRelativeTo(null);
        buildInterface();
        installListeners();
        replaceSource(loadBundledExample(), null);
        runAnalysis(false);
        updateTitle();
    }

    private void buildInterface() {
        setJMenuBar(createMenuBar());
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(SOFT_BACKGROUND);
        root.add(createToolbar(), BorderLayout.NORTH);
        root.add(createWorkArea(), BorderLayout.CENTER);
        root.add(createStatusBar(), BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JToolBar createToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 224, 233)),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        toolbar.setBackground(Color.WHITE);

        JButton open = toolbarButton("Abrir", this::openFile);
        JButton save = toolbarButton("Guardar", () -> save(false));
        JButton analyze = toolbarButton("▶  Analizar", this::runAnalysis);
        analyze.setBackground(BLUE);
        analyze.setForeground(Color.WHITE);
        analyze.setOpaque(true);

        toolbar.add(open);
        toolbar.add(save);
        toolbar.addSeparator(new Dimension(16, 1));
        toolbar.add(analyze);
        toolbar.addSeparator(new Dimension(18, 1));
        JLabel phase = new JLabel("Fase actual: léxico + sintáctico");
        phase.setForeground(new Color(92, 99, 117));
        toolbar.add(phase);
        toolbar.add(Box.createHorizontalGlue());
        analysisBadge.setOpaque(true);
        analysisBadge.setForeground(Color.WHITE);
        analysisBadge.setBackground(new Color(112, 119, 135));
        analysisBadge.setBorder(BorderFactory.createEmptyBorder(6, 9, 6, 9));
        toolbar.add(analysisBadge);
        return toolbar;
    }

    private JSplitPane createWorkArea() {
        sourceEditor.setFont(CODE_FONT);
        sourceEditor.setTabSize(4);
        sourceEditor.setLineWrap(false);
        sourceEditor.setMargin(new Insets(8, 10, 8, 10));
        sourceEditor.setSelectionColor(new Color(196, 215, 255));
        JScrollPane editorScroll = new JScrollPane(sourceEditor);
        editorScroll.setRowHeaderView(new LineNumberView(sourceEditor));
        editorScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel editorPanel = section("Código fuente", editorScroll);
        editorPanel.setMinimumSize(new Dimension(480, 350));

        tokenTable.setFont(CODE_FONT.deriveFont(12f));
        tokenTable.setRowHeight(24);
        tokenTable.setAutoCreateRowSorter(true);
        tokenTable.setFillsViewportHeight(true);
        tokenTable.getTableHeader().setReorderingAllowed(false);

        syntaxTable.setFont(CODE_FONT.deriveFont(12f));
        syntaxTable.setRowHeight(24);
        syntaxTable.setFillsViewportHeight(true);
        syntaxTable.setAutoCreateRowSorter(true);
        syntaxTable.getTableHeader().setReorderingAllowed(false);

        JTabbedPane results = new JTabbedPane();
        results.addTab("Análisis léxico", new JScrollPane(tokenTable));
        results.addTab("Análisis sintáctico", new JScrollPane(syntaxTable));

        errorTabs.addTab("Errores léxicos (0)", new JScrollPane(lexicalErrors));
        errorTabs.addTab("Errores sintácticos (0)", new JScrollPane(syntacticErrors));
        errorTabs.setPreferredSize(new Dimension(500, 235));

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, results, errorTabs);
        right.setResizeWeight(0.62);
        right.setBorder(BorderFactory.createEmptyBorder());

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorPanel, right);
        main.setResizeWeight(0.57);
        main.setDividerSize(7);
        main.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
        return main;
    }

    private JPanel createStatusBar() {
        JPanel status = new JPanel(new BorderLayout(14, 0));
        status.setBackground(NAVY);
        status.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        fileLabel.setForeground(Color.WHITE);
        analysisLabel.setForeground(new Color(180, 203, 255));
        cursorLabel.setForeground(Color.WHITE);
        linesLabel.setForeground(Color.WHITE);

        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 18, 0));
        right.setOpaque(false);
        right.add(cursorLabel);
        right.add(linesLabel);
        status.add(fileLabel, BorderLayout.WEST);
        status.add(analysisLabel, BorderLayout.CENTER);
        status.add(right, BorderLayout.EAST);
        return status;
    }

    private JMenuBar createMenuBar() {
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar menuBar = new JMenuBar();
        JMenu file = new JMenu("Archivo");
        file.add(menuItem("Nuevo", KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcut), this::newFile));
        file.add(menuItem("Abrir…", KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcut), this::openFile));
        file.addSeparator();
        file.add(menuItem("Guardar", KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut), () -> save(false)));
        file.add(menuItem("Guardar como…", KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut | KeyEvent.SHIFT_DOWN_MASK), () -> save(true)));
        file.addSeparator();
        file.add(menuItem("Salir", null, this::requestClose));

        JMenu analysis = new JMenu("Análisis");
        analysis.add(menuItem("Ejecutar análisis", KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), this::runAnalysis));
        analysis.add(menuItem("Abrir carpeta de resultados", null, this::showOutputPath));
        menuBar.add(file);
        menuBar.add(analysis);
        return menuBar;
    }

    private void installListeners() {
        sourceEditor.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if (!replacingDocument) {
                    dirty = true;
                }
                updateDocumentStatus();
                updateTitle();
            }

            @Override public void insertUpdate(DocumentEvent event) { changed(); }
            @Override public void removeUpdate(DocumentEvent event) { changed(); }
            @Override public void changedUpdate(DocumentEvent event) { changed(); }
        });
        sourceEditor.addCaretListener(event -> updateCursorStatus());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                requestClose();
            }
        });
    }

    private void runAnalysis() {
        runAnalysis(true);
    }

    private void runAnalysis(boolean showAlert) {
        AnalysisResult result = analyzer.analyze(sourceEditor.getText());
        updateTokens(result.tokens());
        syntaxModel.setRowCount(0);
        result.program().ifPresent(program -> {
            for (AstFormatter.AstEntry entry : AstFormatter.flatten(program)) {
                syntaxModel.addRow(new Object[]{
                        entry.number(),
                        "  ".repeat(entry.depth()) + entry.construction(),
                        entry.detail(),
                        entry.location()
                });
            }
        });
        lexicalErrors.setText(diagnostics(result.lexicalErrors(), "Sin errores léxicos."));
        syntacticErrors.setText(result.lexicalErrors().isEmpty()
                ? diagnostics(result.syntacticErrors(), "Sin errores sintácticos.")
                : "El análisis sintáctico no se ejecutó porque primero debe corregirse el error léxico.");
        errorTabs.setTitleAt(0, "Errores léxicos (" + result.lexicalErrors().size() + ")");
        errorTabs.setTitleAt(1, result.lexicalErrors().isEmpty()
                ? "Errores sintácticos (" + result.syntacticErrors().size() + ")"
                : "Errores sintácticos (no ejecutado)");
        if (!result.lexicalErrors().isEmpty()) {
            errorTabs.setSelectedIndex(0);
        } else if (!result.syntacticErrors().isEmpty()) {
            errorTabs.setSelectedIndex(1);
        }
        lexicalErrors.setCaretPosition(0);
        syntacticErrors.setCaretPosition(0);

        if (result.successful()) {
            analysisBadge.setText(" ✓ SIN ERRORES ");
            analysisBadge.setBackground(SUCCESS);
        } else {
            int totalErrors = result.lexicalErrors().size() + result.syntacticErrors().size();
            analysisBadge.setText(" ⚠ " + totalErrors + (totalErrors == 1 ? " ERROR " : " ERRORES "));
            analysisBadge.setBackground(FAILURE);
        }

        try {
            exporter.overwrite(result);
            analysisLabel.setText(result.successful()
                    ? "Análisis correcto · resultados reescritos en " + outputDirectory
                    : "Análisis con errores · detalles reescritos en " + outputDirectory);
        } catch (IOException exception) {
            analysisLabel.setText("Análisis terminado; no se pudieron exportar los resultados");
            showError("No fue posible escribir la carpeta de resultados.", exception);
        }

        List<Diagnostic> allErrors = result.lexicalErrors().isEmpty()
                ? result.syntacticErrors() : result.lexicalErrors();
        if (!allErrors.isEmpty()) {
            moveCaretTo(allErrors.getFirst());
        }
        if (showAlert) {
            showAnalysisAlert(result);
        }
    }

    private void showAnalysisAlert(AnalysisResult result) {
        if (result.successful()) {
            JOptionPane.showMessageDialog(this,
                    "El análisis terminó correctamente.\n\n"
                            + "No se encontraron errores léxicos ni sintácticos.",
                    "Análisis sin errores", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String syntacticCount = result.lexicalErrors().isEmpty()
                ? Integer.toString(result.syntacticErrors().size())
                : "no ejecutado por errores léxicos";
        JOptionPane.showMessageDialog(this,
                "El análisis terminó con errores.\n\n"
                        + "Errores léxicos: " + result.lexicalErrors().size() + "\n"
                        + "Errores sintácticos: " + syntacticCount + "\n\n"
                        + "Consulta los paneles inferiores para ver cada diagnóstico.",
                "Se encontraron errores", JOptionPane.ERROR_MESSAGE);
    }

    private void updateTokens(List<TokenInfo> tokens) {
        tokenModel.setRowCount(0);
        for (TokenInfo token : tokens) {
            tokenModel.addRow(new Object[]{
                    token.number(), token.type(), printable(token.lexeme()),
                    token.line() + ":" + token.column(), token.endLine() + ":" + token.endColumn()
            });
        }
    }

    private void moveCaretTo(Diagnostic diagnostic) {
        try {
            int line = Math.max(0, Math.min(diagnostic.line() - 1, sourceEditor.getLineCount() - 1));
            int start = sourceEditor.getLineStartOffset(line);
            int end = sourceEditor.getLineEndOffset(line);
            int position = Math.min(start + Math.max(diagnostic.column() - 1, 0), end);
            sourceEditor.setCaretPosition(position);
            sourceEditor.requestFocusInWindow();
        } catch (Exception ignored) {
            // El diagnóstico seguirá visible aun si el documento cambió durante el análisis.
        }
    }

    private String diagnostics(List<Diagnostic> values, String success) {
        return values.isEmpty() ? success : values.stream()
                .map(Diagnostic::format)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private void newFile() {
        if (!mayDiscardChanges()) {
            return;
        }
        replaceSource("", null);
        analysisLabel.setText("Nuevo documento");
    }

    private void openFile() {
        if (!mayDiscardChanges()) {
            return;
        }
        JFileChooser chooser = createFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path selected = chooser.getSelectedFile().toPath();
        try {
            replaceSource(Files.readString(selected, StandardCharsets.UTF_8), selected);
            analysisLabel.setText("Archivo abierto");
        } catch (IOException exception) {
            showError("No fue posible abrir el archivo seleccionado.", exception);
        }
    }

    private boolean save(boolean saveAs) {
        Path target = currentFile;
        if (saveAs || target == null) {
            JFileChooser chooser = createFileChooser();
            chooser.setSelectedFile((target == null ? Path.of("programa.zig") : target).toFile());
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return false;
            }
            target = chooser.getSelectedFile().toPath();
            if (Files.exists(target)) {
                int option = JOptionPane.showConfirmDialog(this,
                        "El archivo ya existe. ¿Deseas reemplazarlo?", "Confirmar reemplazo",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (option != JOptionPane.YES_OPTION) {
                    return false;
                }
            }
        }
        try {
            Files.writeString(target, sourceEditor.getText(), StandardCharsets.UTF_8);
            currentFile = target;
            dirty = false;
            updateDocumentStatus();
            updateTitle();
            analysisLabel.setText("Archivo guardado");
            return true;
        } catch (IOException exception) {
            showError("No fue posible guardar el archivo.", exception);
            return false;
        }
    }

    private boolean mayDiscardChanges() {
        if (!dirty) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "El documento tiene cambios sin guardar. ¿Deseas guardarlos?", "Cambios sin guardar",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
            return false;
        }
        return answer != JOptionPane.YES_OPTION || save(false);
    }

    private void requestClose() {
        if (mayDiscardChanges()) {
            dispose();
        }
    }

    private void replaceSource(String source, Path file) {
        replacingDocument = true;
        sourceEditor.setText(source);
        sourceEditor.setCaretPosition(0);
        replacingDocument = false;
        currentFile = file;
        dirty = false;
        clearResults();
        updateDocumentStatus();
        updateCursorStatus();
        updateTitle();
    }

    private void clearResults() {
        tokenModel.setRowCount(0);
        syntaxModel.setRowCount(0);
        lexicalErrors.setText("Aún no se ha ejecutado el análisis.");
        syntacticErrors.setText("Aún no se ha ejecutado el análisis.");
        errorTabs.setTitleAt(0, "Errores léxicos (sin ejecutar)");
        errorTabs.setTitleAt(1, "Errores sintácticos (sin ejecutar)");
        analysisBadge.setText(" SIN EJECUTAR ");
        analysisBadge.setBackground(new Color(112, 119, 135));
    }

    private void updateDocumentStatus() {
        fileLabel.setText((currentFile == null ? "Sin archivo" : currentFile.toAbsolutePath().toString())
                + (dirty ? "  • modificado" : ""));
        int count = Math.max(sourceEditor.getLineCount(), 1);
        linesLabel.setText(count + (count == 1 ? " línea" : " líneas"));
    }

    private void updateCursorStatus() {
        try {
            int offset = sourceEditor.getCaretPosition();
            int line = sourceEditor.getLineOfOffset(offset);
            int column = offset - sourceEditor.getLineStartOffset(line);
            cursorLabel.setText("Línea " + (line + 1) + ", columna " + (column + 1));
        } catch (Exception ignored) {
            cursorLabel.setText("Posición no disponible");
        }
    }

    private void updateTitle() {
        String name = currentFile == null ? "Sin título" : currentFile.getFileName().toString();
        setTitle((dirty ? "• " : "") + name + " — Zig Edu Compiler");
    }

    private void showOutputPath() {
        JOptionPane.showMessageDialog(this,
                "Los resultados se reescriben después de cada análisis en:\n" + outputDirectory,
                "Carpeta de resultados", JOptionPane.INFORMATION_MESSAGE);
    }

    private JFileChooser createFileChooser() {
        JFileChooser chooser = new JFileChooser(currentFile == null
                ? locateProjectRoot().toFile() : currentFile.toAbsolutePath().getParent().toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Código Zig o texto (*.zig, *.txt)", "zig", "txt"));
        return chooser;
    }

    private JButton toolbarButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setMargin(new Insets(7, 13, 7, 13));
        button.addActionListener(event -> action.run());
        return button;
    }

    private JMenuItem menuItem(String label, KeyStroke shortcut, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        if (shortcut != null) {
            item.setAccelerator(shortcut);
        }
        item.addActionListener(event -> action.run());
        return item;
    }

    private JPanel section(String title, java.awt.Component content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        JLabel heading = new JLabel(title, SwingConstants.LEFT);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        heading.setForeground(NAVY);
        heading.setBorder(BorderFactory.createEmptyBorder(10, 12, 9, 12));
        panel.add(heading, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createLineBorder(new Color(221, 225, 234)));
        return panel;
    }

    private void showError(String message, Exception exception) {
        JOptionPane.showMessageDialog(this, message + "\n\n" + exception.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
    }

    private static JTextArea resultArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(CODE_FONT.deriveFont(12f));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setMargin(new Insets(10, 10, 10, 10));
        area.setBackground(new Color(252, 252, 253));
        return area;
    }

    private static String printable(String value) {
        return value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static Path locateProjectRoot() {
        Path working = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (Files.exists(working.resolve("pom.xml"))) {
            return working;
        }
        try {
            Path code = Path.of(CompilerFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidate = Files.isDirectory(code) ? code : code.getParent();
            while (candidate != null) {
                if (Files.exists(candidate.resolve("pom.xml"))) {
                    return candidate;
                }
                candidate = candidate.getParent();
            }
        } catch (URISyntaxException | NullPointerException ignored) {
            // Se usa el directorio de trabajo como alternativa segura.
        }
        return working;
    }

    private static String loadBundledExample() {
        try (InputStream input = CompilerFrame.class.getResourceAsStream("/examples/todas_las_funciones.zig")) {
            if (input != null) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // El editor puede seguir funcionando con el ejemplo mínimo de respaldo.
        }
        return FALLBACK_SOURCE;
    }
}
