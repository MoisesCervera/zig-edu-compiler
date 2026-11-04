package mx.edu.automatas.zigedu.ui;

import mx.edu.automatas.zigedu.opt.OptimizationResult;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Rectangle2D;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Pestaña "Optimización": código optimizado con las líneas modificadas resaltadas. */
public final class OptimizationPanel extends JPanel {
    private static final Color CHANGED_LINE = new Color(226, 244, 232);

    private final JTextArea code = new JTextArea();
    private final JButton copy = new JButton("Copiar");
    private final JButton save = new JButton("Guardar…");
    private final Highlighter.HighlightPainter changedPainter = new LinePainter();
    private OptimizationResult current;

    public OptimizationPanel(Consumer<String> saveAction) {
        super(new BorderLayout());
        setBackground(Color.WHITE);
        setMinimumSize(new Dimension(350, 230));

        JLabel title = new JLabel("Código optimizado");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setForeground(new Color(28, 39, 67));
        for (JButton b : new JButton[]{copy, save}) {
            b.setFont(b.getFont().deriveFont(11f));
            b.setMargin(new Insets(3, 8, 3, 8));
            b.setFocusable(false);
        }
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(code.getText()), null));
        save.addActionListener(e -> saveAction.accept(code.getText()));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(copy);
        buttons.add(save);
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(Color.WHITE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(221, 225, 234)),
                BorderFactory.createEmptyBorder(5, 8, 5, 6)));
        header.add(title, BorderLayout.WEST);
        header.add(buttons, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        code.setEditable(false);
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        code.setTabSize(4);
        code.setMargin(new Insets(6, 8, 6, 8));
        JScrollPane scroll = new JScrollPane(code);
        scroll.setRowHeaderView(new LineNumberView(code));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        clear("");
    }

    public void clear(String message) {
        current = null;
        code.getHighlighter().removeAllHighlights();
        code.setText(message.isEmpty() ? "" : "// " + message);
        copy.setEnabled(false);
        save.setEnabled(false);
    }

    public void showResult(OptimizationResult result) {
        if (!result.available()) {
            clear(result.message());
            current = result;
            return;
        }
        current = result;
        code.setText(result.optimizedSource());
        code.setCaretPosition(0);
        highlightChangedLines(result);
        copy.setEnabled(true);
        save.setEnabled(true);
    }

    public OptimizationResult current() { return current; }

    public String optimizedCode() { return code.getText(); }

    private void highlightChangedLines(OptimizationResult result) {
        Set<String> original = new HashSet<>();
        result.normalizedSource().lines().map(String::strip).forEach(original::add);
        int offset = 0;
        for (String line : result.optimizedSource().split("\n", -1)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.equals("}") && !original.contains(trimmed)) {
                try {
                    code.getHighlighter().addHighlight(offset, offset + line.length(), changedPainter);
                } catch (BadLocationException ignored) {
                }
            }
            offset += line.length() + 1;
        }
    }

    private static final class LinePainter implements Highlighter.HighlightPainter {
        @Override public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                Rectangle2D start = c.modelToView2D(p0);
                if (start == null) return;
                g.setColor(CHANGED_LINE);
                g.fillRect(0, (int) start.getY(), c.getWidth(), (int) start.getHeight());
            } catch (BadLocationException ignored) {
            }
        }
    }
}
