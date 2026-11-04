package mx.edu.automatas.zigedu.ui;

import mx.edu.automatas.zigedu.opt.OptimizationResult;
import mx.edu.automatas.zigedu.opt.OptimizationResult.CodeMetrics;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;

/** Bitácora de optimización: comparación de métricas entre el código original y el optimizado. */
public final class OptimizationLogPanel extends JPanel {
    private final JEditorPane view = new JEditorPane();

    public OptimizationLogPanel() {
        super(new BorderLayout());
        view.setEditable(false);
        view.setContentType("text/html");
        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet css = new StyleSheet();
        css.addRule("body { font-family: SansSerif; font-size: 10px; color: #232832; margin: 6px 8px; }");
        css.addRule("h3 { font-size: 11px; color: #1c2743; margin-top: 2px; margin-bottom: 4px; }");
        css.addRule("td { font-size: 10px; }");
        css.addRule("th { font-size: 10px; background-color: #eef1f7; color: #1c2743; text-align: left; }");
        css.addRule(".ok { color: #24804c; font-weight: bold; }");
        css.addRule(".bad { color: #be3737; font-weight: bold; }");
        css.addRule(".muted { color: #5c6375; }");
        kit.setStyleSheet(css);
        view.setEditorKit(kit);
        view.setBackground(new Color(252, 252, 253));
        add(new JScrollPane(view), BorderLayout.CENTER);
        clear("Aún no se ha ejecutado el análisis.");
    }

    public void clear(String message) {
        setHtml("<p class='muted'>" + escape(message) + "</p>");
    }

    public String text() {
        try {
            return view.getDocument().getText(0, view.getDocument().getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    public void showResult(OptimizationResult result) {
        if (!result.available()) {
            clear(result.message());
            return;
        }
        CodeMetrics o = result.original().orElseThrow(), n = result.optimized().orElseThrow();
        StringBuilder html = new StringBuilder("<h3>Comparación: código original vs. optimizado</h3>");
        html.append("<table width='100%' cellspacing='0' cellpadding='3' border='0'><tr><th>Métrica</th><th>Original</th>"
                + "<th>Optimizado</th><th>Diferencia</th></tr>");
        if (o.executed() && n.executed()) {
            row(html, "Tiempo de ejecución", OptimizationResult.formatNanos(o.nanos()),
                    OptimizationResult.formatNanos(n.nanos()), OptimizationResult.formatChange(o.nanos(), n.nanos()));
            row(html, "Instrucciones ejecutadas", o.operations(), n.operations());
        }
        row(html, "Tamaño del archivo (bytes)", o.bytes(), n.bytes());
        row(html, "Líneas de código", o.lines(), n.lines());
        row(html, "Tokens", o.tokens(), n.tokens());
        html.append("</table>");
        setHtml(html.toString());
    }

    private static void row(StringBuilder html, String label, long before, long after) {
        row(html, label, Long.toString(before), Long.toString(after), OptimizationResult.formatChange(before, after));
    }

    private static void row(StringBuilder html, String label, String before, String after, String change) {
        String css = change.startsWith("−") ? "ok" : change.startsWith("+") ? "bad" : "muted";
        html.append("<tr><td>").append(label).append("</td><td align='right'>").append(escape(before))
                .append("</td><td align='right'>").append(escape(after)).append("</td><td class='").append(css)
                .append("'>").append(escape(change)).append("</td></tr>");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void setHtml(String body) {
        view.setText("<html><body>" + body + "</body></html>");
        view.setCaretPosition(0);
    }
}
