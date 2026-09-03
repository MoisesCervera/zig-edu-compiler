package mx.edu.automatas.zigedu.ui;

import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;

/** Canal lateral ligero para numerar las líneas del editor. */
public final class LineNumberView extends JComponent implements DocumentListener {
    private final JTextArea editor;
    private int digits;

    public LineNumberView(JTextArea editor) {
        this.editor = editor;
        setFont(editor.getFont());
        setForeground(new Color(119, 126, 144));
        setBackground(new Color(245, 247, 251));
        editor.getDocument().addDocumentListener(this);
        updateWidth();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(widthForDigits(), editor.getHeight());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        graphics.setColor(getBackground());
        graphics.fillRect(0, 0, getWidth(), getHeight());
        graphics.setColor(new Color(224, 228, 236));
        graphics.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());

        Rectangle clip = graphics.getClipBounds();
        int start = editor.viewToModel2D(new java.awt.Point(0, clip.y));
        int end = editor.viewToModel2D(new java.awt.Point(0, clip.y + clip.height));
        int startLine = Math.max(0, editor.getDocument().getDefaultRootElement().getElementIndex(start));
        int endLine = Math.max(startLine, editor.getDocument().getDefaultRootElement().getElementIndex(end));
        FontMetrics metrics = graphics.getFontMetrics(getFont());
        Insets insets = editor.getInsets();

        graphics.setColor(getForeground());
        for (int line = startLine; line <= endLine; line++) {
            try {
                int offset = editor.getLineStartOffset(line);
                Rectangle position = editor.modelToView2D(offset).getBounds();
                String number = Integer.toString(line + 1);
                int x = getWidth() - metrics.stringWidth(number) - 10;
                int y = position.y + position.height - metrics.getDescent();
                graphics.drawString(number, x, y);
            } catch (BadLocationException ignored) {
                // El documento pudo cambiar durante el repintado; el siguiente repintado lo corrige.
            }
        }
    }

    private int widthForDigits() {
        int currentDigits = Math.max(3, Integer.toString(Math.max(editor.getLineCount(), 1)).length());
        return 20 + getFontMetrics(getFont()).charWidth('0') * currentDigits;
    }

    private void updateWidth() {
        int newDigits = Math.max(3, Integer.toString(Math.max(editor.getLineCount(), 1)).length());
        if (newDigits != digits) {
            digits = newDigits;
            revalidate();
        }
        repaint();
    }

    @Override
    public void insertUpdate(DocumentEvent event) {
        updateWidth();
    }

    @Override
    public void removeUpdate(DocumentEvent event) {
        updateWidth();
    }

    @Override
    public void changedUpdate(DocumentEvent event) {
        updateWidth();
    }
}
