package mx.edu.automatas.zigedu.ui;

import mx.edu.automatas.zigedu.analysis.AnalysisResult;
import mx.edu.automatas.zigedu.ast.Ast.SourceSpan;
import mx.edu.automatas.zigedu.ir.IntermediateResult;
import mx.edu.automatas.zigedu.ir.IntermediateResult.Notation;
import mx.edu.automatas.zigedu.ir.IntermediateResult.Quadruple;
import mx.edu.automatas.zigedu.ir.NumericPreview;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Cuatro campos por cuádruplo y las tres notaciones de la expresión elegida. */
public final class IntermediatePanel extends JPanel {
    private final DefaultTableModel quadModel = new DefaultTableModel(
            new String[]{"Operador", "Arg. 1", "Arg. 2", "Resultado"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable quadTable = new JTable(quadModel);
    private final JComboBox<ExpressionView> expressions = new JComboBox<>();
    private final JTextArea notations = new JTextArea();
    private final JLabel status = new JLabel();
    private final JButton previous = button("Anterior"), next = button("Siguiente"),
            reset = button("Reiniciar"), all = button("Todo");
    private final Consumer<SourceSpan> navigate;
    private List<Quadruple> activeQuads = List.of();
    private Map<Integer, NumericPreview.Values> numericValues = Map.of();
    private int visible;
    private boolean filling;

    private record ExpressionView(String title, List<Quadruple> quads, List<Notation> notations,
                                  SourceSpan span) {
        @Override public String toString() { return title; }
    }

    public IntermediatePanel(Consumer<SourceSpan> navigate) {
        super(new BorderLayout(4, 4));
        this.navigate = navigate;
        setMinimumSize(new Dimension(350, 230));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));

        JPanel selector = new JPanel(new BorderLayout(6, 0));
        selector.add(new JLabel("Expresión:"), BorderLayout.WEST);
        expressions.setPrototypeDisplayValue(new ExpressionView("main · L2 · x = a + b * c", List.of(), List.of(), null));
        expressions.setMaximumRowCount(12);
        expressions.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                setToolTipText(value == null ? null : value.toString());
                return this;
            }
        });
        selector.add(expressions, BorderLayout.CENTER);
        add(selector, BorderLayout.NORTH);

        quadTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        quadTable.setRowHeight(24);
        quadTable.setFillsViewportHeight(true);
        quadTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        quadTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        quadTable.getTableHeader().setReorderingAllowed(false);
        quadTable.setDefaultRenderer(Object.class, new WrappedCell());
        int[] widths = {90, 110, 110, 120};
        for (int i = 0; i < widths.length; i++) {
            quadTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        quadTable.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { fitRows(); }
        });

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        controls.add(reset); controls.add(previous); controls.add(next); controls.add(all);
        JPanel steps = new JPanel(new BorderLayout(4, 0));
        steps.add(controls, BorderLayout.WEST);
        status.setFont(status.getFont().deriveFont(11f));
        JPanel heading = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Cuádruplos");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        heading.add(title, BorderLayout.WEST);
        heading.add(status, BorderLayout.EAST);
        JPanel quadruples = new JPanel(new BorderLayout());
        quadruples.setBorder(BorderFactory.createEmptyBorder(2, 3, 0, 3));
        quadruples.add(heading, BorderLayout.NORTH);
        quadruples.add(new JScrollPane(quadTable), BorderLayout.CENTER);
        quadruples.add(steps, BorderLayout.SOUTH);
        quadruples.setMinimumSize(new Dimension(0, 100));

        notations.setEditable(false);
        notations.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        notations.setLineWrap(true);
        notations.setWrapStyleWord(true);
        notations.setMargin(new Insets(6, 7, 6, 7));
        JScrollPane notationView = new JScrollPane(notations);
        notationView.setBorder(BorderFactory.createTitledBorder("Notaciones"));
        notationView.setMinimumSize(new Dimension(0, 85));
        notationView.setPreferredSize(new Dimension(0, 135));
        JSplitPane content = new JSplitPane(JSplitPane.VERTICAL_SPLIT, quadruples, notationView);
        content.setResizeWeight(.68);
        content.setDividerSize(5);
        content.setBorder(BorderFactory.createEmptyBorder());
        add(content, BorderLayout.CENTER);

        expressions.addActionListener(event -> { if (!filling) selectExpression(true); });
        reset.addActionListener(event -> reveal(0));
        previous.addActionListener(event -> reveal(visible - 1));
        next.addActionListener(event -> {
            reveal(visible + 1);
            if (visible > 0) navigate.accept(activeQuads.get(visible - 1).span());
        });
        all.addActionListener(event -> reveal(activeQuads.size()));
        quadTable.getSelectionModel().addListSelectionListener(event -> {
            int row = quadTable.getSelectedRow();
            if (!event.getValueIsAdjusting() && !filling && row >= 0) {
                navigate.accept(activeQuads.get(row).span());
            }
        });
        clear("Sin analizar");
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFont(button.getFont().deriveFont(11f));
        button.setMargin(new Insets(3, 5, 3, 5));
        return button;
    }

    public void showResult(AnalysisResult analysis) {
        if (analysis.intermediate().isEmpty()) { clear("Sin código intermedio"); return; }
        var result = analysis.intermediate().get();
        // La evaluación usa la traducción completa; aquí se indexa por el número del cuádruplo visible.
        Map<Integer, NumericPreview.Values> evaluated = NumericPreview.evaluate(result);
        Map<Integer, NumericPreview.Values> byDisplay = new java.util.HashMap<>();
        for (Quadruple q : result.quadruples()) {
            NumericPreview.Values v = evaluated.get(result.executableNumber(q.number()));
            if (v != null) byDisplay.put(q.number(), v);
        }
        numericValues = byDisplay;
        List<Notation> roots = result.notations().stream().filter(IntermediatePanel::isRoot).toList();
        filling = true;
        expressions.removeAllItems();
        for (Notation n : roots) {
            List<Quadruple> quads = result.quadruples().stream()
                    .filter(q -> q.function().equals(n.function()) && belongsTo(n, q))
                    .filter(q -> !q.operator().equals("VAR") && !q.operator().equals("CONST"))
                    .toList();
            expressions.addItem(new ExpressionView(n.function() + " · L" + n.span().line()
                    + ":" + n.span().column() + " · " + n.infix(), quads, List.of(n), n.span()));
        }
        expressions.addItem(new ExpressionView("Programa completo", result.quadruples(), roots, null));
        expressions.setSelectedIndex(expressions.getItemCount() - 1);
        expressions.setEnabled(true);
        filling = false;
        // Mostrar el programa completo sin desplazar el editor durante el análisis.
        selectExpression(false);
    }

    private static boolean isRoot(Notation n) {
        return switch (n.context()) {
            case "Declaración const", "Declaración var", "Asignación", "Expresión como sentencia",
                    "Retorno", "Condición if", "Condición while", "Inicio del rango",
                    "Fin exclusivo del rango", "Fuente del for", "Selector switch", "Etiqueta de caso" -> true;
            default -> false;
        };
    }

    private static boolean contains(SourceSpan outer, SourceSpan inner) {
        return (outer.line() < inner.line() || outer.line() == inner.line() && outer.column() <= inner.column())
                && (outer.endLine() > inner.endLine()
                || outer.endLine() == inner.endLine() && outer.endColumn() >= inner.endColumn());
    }

    private static boolean belongsTo(Notation notation, Quadruple q) {
        if (contains(notation.span(), q.span())) return true;
        // El span de RETURN incluye la palabra return; el de su notación es sólo el valor.
        return notation.context().equals("Retorno") && q.operator().equals("RETURN")
                && !q.argument1().isEmpty() && contains(q.span(), notation.span());
    }

    private void selectExpression(boolean move) {
        ExpressionView view = (ExpressionView) expressions.getSelectedItem();
        if (view == null) return;
        activeQuads = view.quads();
        StringBuilder text = new StringBuilder();
        for (Notation n : view.notations()) {
            if (!text.isEmpty()) text.append("\n\n");
            text.append("Infija:  ").append(n.infix())
                    .append("\nPosfija: ").append(n.postfix())
                    .append("\nPrefija: ").append(n.prefix());
        }
        if (view.span() != null && !activeQuads.isEmpty()
                && !view.notations().getFirst().context().equals("Fuente del for")) {
            NumericPreview.Values value = numericValues.get(activeQuads.getLast().number());
            if (value != null) text.append("\n\n").append(value.text());
        }
        notations.setText(text.toString());
        notations.setCaretPosition(0);
        expressions.setToolTipText(view.title());
        reveal(activeQuads.size());
        filling = true; quadTable.clearSelection(); filling = false;
        quadTable.scrollRectToVisible(new Rectangle(0, 0, 1, 1));
        if (move && view.span() != null) navigate.accept(view.span());
    }

    public void clear(String message) {
        filling = true;
        expressions.removeAllItems();
        expressions.setEnabled(false);
        expressions.setToolTipText(null);
        activeQuads = List.of();
        numericValues = Map.of();
        visible = 0;
        quadModel.setRowCount(0);
        notations.setText("");
        filling = false;
        status.setText(message);
        updateButtons();
    }

    private void reveal(int count) {
        visible = Math.max(0, Math.min(count, activeQuads.size()));
        filling = true;
        quadModel.setRowCount(0);
        for (int i = 0; i < visible; i++) {
            Quadruple q = activeQuads.get(i);
            quadModel.addRow(new Object[]{q.operator(), q.argument1(), q.argument2(), q.result()});
        }
        if (visible > 0) quadTable.setRowSelectionInterval(visible - 1, visible - 1);
        filling = false;
        fitRows();
        if (visible > 0) quadTable.scrollRectToVisible(quadTable.getCellRect(visible - 1, 0, true));
        status.setText("Paso " + visible + " / " + activeQuads.size());
        updateButtons();
    }

    private void updateButtons() {
        previous.setEnabled(visible > 0); reset.setEnabled(visible > 0);
        next.setEnabled(visible < activeQuads.size()); all.setEnabled(visible < activeQuads.size());
    }

    private void fitRows() {
        for (int row = 0; row < quadTable.getRowCount(); row++) {
            int height = 24;
            for (int column = 0; column < quadTable.getColumnCount(); column++) {
                Component cell = quadTable.prepareRenderer(quadTable.getCellRenderer(row, column), row, column);
                height = Math.max(height, cell.getPreferredSize().height);
            }
            if (quadTable.getRowHeight(row) != height) quadTable.setRowHeight(row, height);
        }
    }

    private static final class WrappedCell extends JTextArea implements TableCellRenderer {
        WrappedCell() { setLineWrap(true); setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4)); }
        @Override public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focused, int row, int column) {
            setText(String.valueOf(value));
            setFont(table.getFont());
            setForeground(selected ? table.getSelectionForeground() : table.getForeground());
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            setToolTipText(getText());
            setSize(Math.max(1, table.getColumnModel().getColumn(column).getWidth()), Short.MAX_VALUE);
            return this;
        }
    }
}
