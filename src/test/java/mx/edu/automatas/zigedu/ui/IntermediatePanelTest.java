package mx.edu.automatas.zigedu.ui;

import mx.edu.automatas.zigedu.analysis.SourceAnalyzer;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Presentación y correspondencia con la IR, sin requerir pantalla. */
class IntermediatePanelTest {
    private static <T> T field(IntermediatePanel panel, String name, Class<T> type) throws Exception {
        Field f = IntermediatePanel.class.getDeclaredField(name); f.setAccessible(true);
        return type.cast(f.get(panel));
    }
    private static IntermediatePanel panel(String source) throws Exception {
        var analysis = new SourceAnalyzer().analyze(source); assertTrue(analysis.successful());
        IntermediatePanel panel = new IntermediatePanel(s -> { }); panel.showResult(analysis);
        field(panel,"expressions",JComboBox.class).setSelectedIndex(0);
        return panel;
    }
    @FunctionalInterface private interface Check { void run() throws Exception; }
    private static void onEdt(Check check) throws Exception {
        SwingUtilities.invokeAndWait(() -> { try { check.run(); } catch (Exception e) { throw new RuntimeException(e); } });
    }
    @Test void referenceExpressionShowsOnlyFourColumnsAndFiveOperations() throws Exception {
        onEdt(() -> {
            var p=panel("pub fn main() void { var x:i32=5+4*8/4-16; }");
            JTable table=field(p,"quadTable",JTable.class);
            assertEquals(4,table.getColumnCount());
            assertEquals(List.of("Operador","Arg. 1","Arg. 2","Resultado"),
                    java.util.stream.IntStream.range(0,4).mapToObj(table::getColumnName).toList());
            assertEquals(5,table.getRowCount());
            assertEquals(List.of("*","/","+","-","="),
                    java.util.stream.IntStream.range(0,5).mapToObj(i -> table.getValueAt(i,0)).toList());
            assertEquals("4",table.getValueAt(0,1)); assertEquals("8",table.getValueAt(0,2));
            assertEquals("x",table.getValueAt(4,3));
            String notation=field(p,"notations",JTextArea.class).getText();
            assertTrue(notation.contains("Posfija: x 5 4 8 * 4 / + 16 - ="));
            assertTrue(notation.contains("Prefija: = x - + 5 / * 4 8 4 16"));
            assertEquals(5,notation.lines().count());
            assertTrue(notation.endsWith("Resultado numérico: -3")); assertFalse(notation.contains("Pulsa"));
            assertFalse(notation.contains("Selecciona")); assertFalse(field(p,"notations",JTextArea.class).isEditable());
            assertEquals(JTable.AUTO_RESIZE_ALL_COLUMNS,table.getAutoResizeMode());
        });
    }
    @Test void buttonsRevealSelectedStepsAndNavigateSource() throws Exception {
        onEdt(() -> {
            AtomicInteger visits=new AtomicInteger(); var p=new IntermediatePanel(s -> visits.incrementAndGet());
            p.showResult(new SourceAnalyzer().analyze("fn main() void { var x:i32=2+3*4; }"));
            assertEquals(0,visits.get());
            field(p,"expressions",JComboBox.class).setSelectedIndex(0); visits.set(0);
            JTable table=field(p,"quadTable",JTable.class);
            JButton reset=field(p,"reset",JButton.class), next=field(p,"next",JButton.class),
                    previous=field(p,"previous",JButton.class), all=field(p,"all",JButton.class);
            reset.doClick(); assertEquals(0,table.getRowCount()); assertFalse(previous.isEnabled());
            next.doClick(); assertEquals(1,table.getRowCount()); assertEquals(1,visits.get());
            assertEquals("*",table.getValueAt(0,0)); assertEquals("Paso 1 / 3",field(p,"status",JLabel.class).getText());
            all.doClick(); assertEquals(3,table.getRowCount()); assertFalse(next.isEnabled());
            previous.doClick(); assertEquals(2,table.getRowCount());
            table.setRowSelectionInterval(0,0); assertEquals(2,visits.get());
        });
    }
    @Test void selectorSeparatesStatementsEvenOnTheSameLine() throws Exception {
        onEdt(() -> {
            var p=panel("fn main() void { var a:i32=10; var b:i32=4; var r:i32=a+b*2; r=(a+b)*2; _=r; }");
            JComboBox<?> selector=field(p,"expressions",JComboBox.class); JTable table=field(p,"quadTable",JTable.class);
            // Sólo las dos líneas con operaciones y el programa completo; las declaraciones sin operaciones no se listan.
            assertEquals(3,selector.getItemCount());
            selector.setSelectedIndex(0); assertEquals(3,table.getRowCount()); assertEquals("*",table.getValueAt(0,0));
            assertEquals("r",table.getValueAt(2,3)); assertFalse(field(p,"notations",JTextArea.class).getText().contains("a = 10"));
            selector.setSelectedIndex(1); assertEquals(3,table.getRowCount());
            assertEquals("+",table.getValueAt(0,0)); assertEquals("*",table.getValueAt(1,0));
            selector.setSelectedIndex(2); assertEquals(6,table.getRowCount());
        });
    }
    @Test void fullProgramIsDefaultAfterEachAnalysisAndPreservesEveryQuadruple() throws Exception {
        onEdt(() -> {
            String source=java.nio.file.Files.readString(java.nio.file.Path.of("examples/todas_las_funciones.zig"));
            var analysis=new SourceAnalyzer().analyze(source); var p=new IntermediatePanel(s -> { }); p.showResult(analysis);
            JComboBox<?> selector=field(p,"expressions",JComboBox.class);
            assertEquals("Programa completo",selector.getSelectedItem().toString());
            selector.setSelectedIndex(0);
            assertNotEquals("Programa completo",selector.getSelectedItem().toString());
            p.showResult(analysis);
            assertEquals("Programa completo",selector.getSelectedItem().toString());
            var expected=analysis.intermediate().orElseThrow().quadruples(); JTable table=field(p,"quadTable",JTable.class);
            assertEquals(expected.size(),table.getRowCount());
            for(int i=0;i<expected.size();i++) {
                var q=expected.get(i);
                assertEquals(List.of(q.operator(),q.argument1(),q.argument2(),q.result()),
                        List.of(table.getValueAt(i,0),table.getValueAt(i,1),table.getValueAt(i,2),table.getValueAt(i,3)));
            }
        });
    }
    @Test void onlyLinesWithOperationsAreListedAcrossFunctions() throws Exception {
        onEdt(() -> {
            var p=panel("fn first() void { var x:i32=1; if(x>0) { x=x+2; } } fn second() void { var x:i32=8+9; }");
            JComboBox<?> selector=field(p,"expressions",JComboBox.class);
            assertEquals(3,selector.getItemCount()); // x = x + 2, x = 8 + 9 y el programa completo; "x > 0" no tiene operaciones
            selector.setSelectedIndex(0); assertTrue(selector.getSelectedItem().toString().startsWith("first"));
            JTable table=field(p,"quadTable",JTable.class); assertEquals(2,table.getRowCount());
            assertEquals("+",table.getValueAt(0,0)); assertEquals("=",table.getValueAt(1,0));
            selector.setSelectedIndex(1); assertTrue(selector.getSelectedItem().toString().startsWith("second"));
            assertEquals(2,table.getRowCount()); assertEquals("8",table.getValueAt(0,1));
        });
    }
    @Test void errorsAndEditsClearOutputAndDisableControls() throws Exception {
        onEdt(() -> {
            var p=panel("fn main() void { var x:i32=1+2; }");
            p.showResult(new SourceAnalyzer().analyze("fn main() void { var x:i32=\"hola\"; }"));
            assertEquals(0,field(p,"quadTable",JTable.class).getRowCount());
            assertEquals("",field(p,"notations",JTextArea.class).getText());
            assertFalse(field(p,"expressions",JComboBox.class).isEnabled());
            for(String button:List.of("reset","next","previous","all")) assertFalse(field(p,button,JButton.class).isEnabled());
            p.showResult(new SourceAnalyzer().analyze("fn main() void { var x:i32=1; }"));
            assertTrue(field(p,"expressions",JComboBox.class).isEnabled()); p.clear("Código modificado");
            assertEquals(0,field(p,"quadTable",JTable.class).getRowCount()); assertEquals("",field(p,"notations",JTextArea.class).getText());
            assertEquals("Código modificado",field(p,"status",JLabel.class).getText());
        });
    }
    @Test void functionWithoutExpressionsStillShowsCompleteCode() throws Exception {
        onEdt(() -> {
            var analysis=new SourceAnalyzer().analyze("fn main() void { return; }");
            var p=new IntermediatePanel(s -> { }); p.showResult(analysis);
            assertEquals(1,field(p,"expressions",JComboBox.class).getItemCount());
            assertEquals(0,analysis.intermediate().orElseThrow().quadruples().size());
            assertEquals(0,field(p,"quadTable",JTable.class).getRowCount());
            assertEquals("",field(p,"notations",JTextArea.class).getText());
        });
    }
    @Test void returnsShowOnlyTheirOperationAndLiteralReturnsAreIgnored() throws Exception {
        onEdt(() -> {
            var p=panel("fn first() i32 { return 2; } fn second() i32 { return 3+4; }");
            JTable table=field(p,"quadTable",JTable.class);
            assertEquals(2,field(p,"expressions",JComboBox.class).getItemCount()); // return 3+4 y el programa completo
            assertEquals(1,table.getRowCount()); assertEquals("+",table.getValueAt(0,0));
            assertEquals("3",table.getValueAt(0,1)); assertEquals("4",table.getValueAt(0,2));
        });
    }
    @Test void numericalNotesUseTheValueAtEachAssignmentAndKeepTheFullViewUnchanged() throws Exception {
        onEdt(() -> {
            var analysis=new SourceAnalyzer().analyze("pub fn main() void { const a:i32=10; const b:i32=4; const c:i32=2; var resultado:i32=a+b*c; resultado=(a+b)*c; }");
            var p=new IntermediatePanel(s -> { }); p.showResult(analysis);
            JTextArea notes=field(p,"notations",JTextArea.class);
            assertFalse(notes.getText().contains("Resultado numérico:"));
            JComboBox<?> selector=field(p,"expressions",JComboBox.class);
            assertEquals(3,selector.getItemCount()); // sólo las dos líneas con operaciones y el programa completo
            selector.setSelectedIndex(0); assertTrue(notes.getText().endsWith("Resultado numérico: 18"));
            selector.setSelectedIndex(1); assertTrue(notes.getText().endsWith("Resultado numérico: 28"));
            assertEquals(3,field(p,"quadTable",JTable.class).getRowCount());
            selector.setSelectedIndex(0); assertTrue(notes.getText().endsWith("Resultado numérico: 18"));
            p.showResult(new SourceAnalyzer().analyze("fn main() void { const value:bool=true; }"));
            selector.setSelectedIndex(0); assertEquals("",notes.getText()); // sin operaciones no hay nada que analizar
            assertEquals(0,field(p,"quadTable",JTable.class).getRowCount());
        });
    }
    @Test void arraysAndTheirForSourceDoNotDisplayTheirLengthAsANumericResult() throws Exception {
        onEdt(() -> {
            var p=panel("fn main() void { const a=[_]i32{2,3}; for(a) |x| { const d:i32=x*1; _=d; } }");
            JComboBox<?> selector=field(p,"expressions",JComboBox.class);
            assertEquals(2,selector.getItemCount()); // sólo x * 1 y el programa completo; el arreglo y el for no se analizan
            assertTrue(field(p,"notations",JTextArea.class).getText().contains("Resultados numéricos (2 evaluaciones): 2, 3"));
            selector.setSelectedIndex(1);
            assertFalse(field(p,"notations",JTextArea.class).getText().contains("numérico"));
        });
    }
}
