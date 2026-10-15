package mx.edu.automatas.zigedu.ir;

import mx.edu.automatas.zigedu.analysis.*;
import mx.edu.automatas.zigedu.ast.Ast;
import mx.edu.automatas.zigedu.ast.AstFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IntermediateGeneratorTest {
    private final SourceAnalyzer analyzer = new SourceAnalyzer();
    private IntermediateResult valid(String source) {
        AnalysisResult r = analyzer.analyze(source);
        assertTrue(r.successful(), () -> r.lexicalErrors() + " " + r.syntacticErrors() + " " + r.semanticErrors());
        assertTrue(r.intermediateExecuted()); return r.intermediate().orElseThrow();
    }
    @Test void preservesPrecedenceAndAllThreeNotations() {
        var ir = valid("fn calc() i32 { const x = 5 + 4 * 8 / 4 - 16; return x; }");
        var n = ir.notations().stream().filter(x -> x.context().equals("Declaración const")).findFirst().orElseThrow();
        assertEquals("x 5 4 8 * 4 / + 16 - =", n.postfix());
        assertEquals("= x - + 5 / * 4 8 4 16", n.prefix());
        assertEquals(List.of("*", "/", "+", "-"), ir.quadruples().stream().map(IntermediateResult.Quadruple::operator)
                .filter(Set.of("+", "-", "*", "/")::contains).toList());
        assertEquals(-3, new QuadrupleMachine(ir).call("calc"));
    }
    @Test void supportsParenthesesUnaryAndCompoundAssignment() {
        var ir = valid("fn calc() i32 { var x: i32 = -(2 + 3) * 4; x += 7; x -= 1; x *= 2; x /= 7; x %= 3; return x; }");
        assertTrue(ir.notations().stream().anyMatch(n -> n.postfix().contains("2 3 + NEG")));
        assertEquals(-1, new QuadrupleMachine(ir).call("calc"));
    }
    @Test void emitsFunctionsNestedCallsRecursionAndForwardCalls() {
        var ir = valid("""
                fn calc() i32 { return add(add(1, 2), factorial(4)); }
                fn add(a: i32, b: i32) i32 { return a + b; }
                fn factorial(n: i32) i32 {
                    if (n <= 1) { return 1; }
                    return n * factorial(n - 1);
                }
                """);
        assertEquals(27, new QuadrupleMachine(ir).call("calc"));
    }
    @Test void translatesWhileForRangeBreakContinueAndElseIf() {
        var ir = valid("""
                fn calc() i32 {
                    var total: i32 = 0;
                    for (0..8) |i| {
                        if (i == 2) { continue; }
                        if (i == 6) { break; }
                        total += i;
                    }
                    var j: i32 = 0;
                    while (j < 3) { j += 1; total += j; }
                    if (total == 19) { total += 1; }
                    else if (total > 0) { total += 2; } else { total = 0; }
                    return total;
                }
                """);
        assertEquals(20, new QuadrupleMachine(ir).call("calc"));
    }
    @Test void translatesNestedLoopsWithCorrectContinueDestination() {
        var ir = valid("""
                fn calc() i32 {
                    var n: i32 = 0;
                    for (0..3) |i| {
                        for (0..4) |j| { if (j == 1) { continue; } if (j == 3) { break; } n += i + j; }
                    }
                    return n;
                }
                """);
        assertEquals(12, new QuadrupleMachine(ir).call("calc"));
    }
    @Test void translatesSwitchWithMultipleLabelsAndDefault() {
        var ir = valid("""
                fn choose(n: i32) i32 {
                    switch (n) { 0 => { return 10; }, 1, 2 => { return 20; }, else => { return -1; }, }
                }
                """);
        var vm = new QuadrupleMachine(ir);
        assertEquals(10, vm.call("choose", 0)); assertEquals(20, vm.call("choose", 2)); assertEquals(-1, vm.call("choose", 7));
    }
    @Test void usesShortCircuitInsteadOfAlwaysEvaluatingTheRightOperand() {
        var ir = valid("""
                fn dangerous(n: i32) bool { return 10 / n > 0; }
                fn yes() bool { return true or dangerous(0); }
                fn no() bool { return false and dangerous(0); }
                """);
        var vm = new QuadrupleMachine(ir); assertEquals(true, vm.call("yes")); assertEquals(false, vm.call("no"));
    }
    @Test void supportsArraysCopyLenIndexedUpdatesAndForCapture() {
        var ir = valid("""
                fn calc() i32 {
                    const a = [_]i32{1, 2, 3};
                    var b = a;
                    b[1] += 5;
                    var total: i32 = 0;
                    for (b) |item| { total += item; }
                    for (a) |_| { total += 1; }
                    return total + a[1] + b.len;
                }
                """);
        assertEquals(19, new QuadrupleMachine(ir).call("calc"));
    }
    @Test void writesNestedArrayThroughAnAddressAndEvaluatesIndexOnce() {
        var ir = valid("""
                fn index() i32 { return 1; }
                fn calc() i32 {
                    var a = [2][2]i32{ [2]i32{1, 2}, [2]i32{3, 4} };
                    a[1][index()] *= 5;
                    return a[1][1];
                }
                """);
        assertEquals(20, new QuadrupleMachine(ir).call("calc"));
        assertEquals(1, ir.quadruples().stream().filter(q -> q.operator().equals("CALL") && q.argument1().equals("index")).count());
    }
    @Test void translatesDecimalCharactersEmptyArraysAndVoidCalls() {
        var ir = valid("""
                fn noop() void { return; }
                fn decimal() f64 { return 2.5 * 4.0 + 1.0; }
                fn calc() i32 { noop(); _ = noop(); const a = [_]i32{}; const c: u8 = 'A'; return c + a.len; }
                """);
        var vm = new QuadrupleMachine(ir); assertEquals(11.0, vm.call("decimal")); assertEquals(65, vm.call("calc"));
    }
    @Test void everyExpressionInTheCompleteExampleHasANotationAndLocation() throws Exception {
        var r = analyzer.analyze(Files.readString(Path.of("examples/todas_las_funciones.zig")));
        assertTrue(r.successful()); var ir = r.intermediate().orElseThrow();
        Set<Ast.SourceSpan> spans = new HashSet<>(); ir.notations().forEach(n -> spans.add(n.span()));
        // El formateador recorre todos los nodos. Las expresiones se cuentan mediante reflexión del AST.
        visit(r.program().orElseThrow(), spans);
        assertEquals(30, ir.quadruples().stream().filter(q -> q.operator().equals("FUNC")).count());
        assertTrue(ir.quadruples().size() > 300);
        for (int i = 0; i < ir.quadruples().size(); i++) assertEquals(i + 1, ir.quadruples().get(i).number());
        assertTrue(ir.quadruples().stream().allMatch(q -> q.span().line() > 0 && !q.explanation().isBlank()));
    }
    private void visit(Ast.Node n, Set<Ast.SourceSpan> spans) throws Exception {
        if (n instanceof Ast.Expression) assertTrue(spans.contains(n.span()), () -> "Expresión omitida " + n);
        for (var c : n.getClass().getRecordComponents()) {
            Object v = c.getAccessor().invoke(n);
            if (v instanceof Ast.Node node) visit(node, spans);
            else if (v instanceof Optional<?> o && o.orElse(null) instanceof Ast.Node node) visit(node, spans);
            else if (v instanceof List<?> list) for (Object x : list) if (x instanceof Ast.Node node) visit(node, spans);
        }
    }
    @ParameterizedTest @ValueSource(strings={"fn f() void { _ = @; }", "fn f() void { const x = ; }", "fn f() void { const x: i32 = true; }"})
    void blocksIntermediateCodeAfterAnyPreviousError(String source) { assertFalse(analyzer.analyze(source).intermediateExecuted()); }
    @ParameterizedTest @ValueSource(strings={"\"hola\"", "\"123\"", "\"\"", "\"hola \\\"Zig\\\"\\n\""})
    void stringAssignedToIntegerIsSemanticInsteadOfLexicalOrSyntactic(String literal) {
        var r = analyzer.analyze("fn f() void { var x: i32 = " + literal + "; }");
        assertTrue(r.lexicalErrors().isEmpty()); assertTrue(r.syntacticErrors().isEmpty());
        assertTrue(r.semanticExecuted()); assertEquals(1, r.semanticErrors().size());
        assertTrue(r.semanticErrors().getFirst().summary().contains("SEM_TIPO"));
        assertTrue(r.semanticErrors().getFirst().found().contains("cadena")); assertFalse(r.intermediateExecuted());
        assertTrue(AstFormatter.format(r.program().get()).contains("StringLiteral"));
    }
    @ParameterizedTest @ValueSource(strings={"fn f() void { _ = \"sin cerrar; }", "fn f() void { _ = \"escape \\q\"; }", "fn f() void { _ = 'ab'; }"})
    void malformedLiteralsRemainLexicalErrors(String source) { assertFalse(analyzer.analyze(source).lexicalErrors().isEmpty()); }
    @Test void reportsAreReplacedAndGeneratorCanBeReused(@TempDir Path out) throws Exception {
        var generator = new IntermediateGenerator(); var good = analyzer.analyze("fn f() i32 { return 2 + 3; }");
        assertEquals(generator.generate(good.program().get()), generator.generate(good.program().get()));
        ResultExporter exporter = new ResultExporter(out); exporter.overwrite(good);
        assertTrue(Files.readString(out.resolve("cuadruplos.txt")).contains("+\t2\t3"));
        assertTrue(Files.readString(out.resolve("notaciones.txt")).contains("Prefija:"));
        assertTrue(Files.readString(out.resolve("pasos_codigo_intermedio.txt")).contains("Paso 1"));
        exporter.overwrite(analyzer.analyze("fn f() void { _ = missing; }"));
        for (String name : List.of("cuadruplos.txt", "notaciones.txt", "pasos_codigo_intermedio.txt")) {
            String text = Files.readString(out.resolve(name)); assertTrue(text.contains("no generado")); assertFalse(text.contains("$t"));
        }
    }
}
