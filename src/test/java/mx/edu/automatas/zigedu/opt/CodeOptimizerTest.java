package mx.edu.automatas.zigedu.opt;

import mx.edu.automatas.zigedu.analysis.AnalysisResult;
import mx.edu.automatas.zigedu.analysis.SourceAnalyzer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;


class CodeOptimizerTest {
    private final SourceAnalyzer analyzer = new SourceAnalyzer();
    private final OptimizationService service = new OptimizationService();

    private OptimizationResult optimize(String source) {
        AnalysisResult analysis = analyzer.analyze(source);
        assertTrue(analysis.successful(), () -> "El código de prueba debe ser válido: " + analysis.semanticErrors());
        OptimizationResult result = service.optimize(analysis);
        assertTrue(result.available(), result.message());
        assertTrue(analyzer.analyze(result.optimizedSource()).successful(), result.optimizedSource());
        return result;
    }

    @Test void foldsConstantExpressionsAndLength() {
        var r = optimize("pub fn main() void { const dia = 60 * 60 * 24; const v = [_]i32{ 1, 2, 3 }; _ = dia; _ = v.len; _ = v[0]; }");
        assertTrue(r.optimizedSource().contains("_ = 86400;"), r.optimizedSource());
        assertTrue(r.optimizedSource().contains("_ = 3;"), r.optimizedSource());
    }

    @Test void removesUnreachableCodeConstantBranchesAndUnusedDeclarations() {
        var r = optimize("""
                fn nunca() i32 { return 1; }
                fn f(x: i32) i32 {
                    var temporal: i32 = x * 10;
                    temporal += 1;
                    if (false) { return 99; }
                    while (false) { _ = x; }
                    for (3..3) |i| { _ = i; }
                    return x;
                    _ = x + 1;
                }
                pub fn main() void { _ = f(4); }
                """);
        String code = r.optimizedSource();
        assertFalse(code.contains("fn nunca"), code);
        assertFalse(code.contains("temporal"), code);
        assertFalse(code.contains("99"), code);
        assertFalse(code.contains("while"), code);
        assertFalse(code.contains("for"), code);
        assertFalse(code.contains("x + 1"), code);
    }

    @Test void turnsUnmodifiedVarIntoConstAndPropagatesIt() {
        var r = optimize("pub fn main() void { var a: i32 = 7; var b: i32 = 3; const total = a + b * 2; _ = total; }");
        assertTrue(r.optimizedSource().contains("const a: i32 = 7;"), r.optimizedSource());
        assertTrue(r.optimizedSource().contains("_ = 13;"), r.optimizedSource());
    }

    @Test void keepsMainVariablesAsProgramResult() {
        var r = optimize("pub fn main() void { const a: i32 = 10; var r: i32 = a * 2; r = a + 1; }");
        assertTrue(r.optimizedSource().contains("const r: i32 = 11;"), r.optimizedSource());
        assertTrue(r.optimizedSource().contains("const a: i32 = 10;"), r.optimizedSource());
    }

    @Test void appliesStrengthReductionAndAlgebraicIdentities() {
        var r = optimize("""
                fn f(x: i32, y: f64) f64 {
                    const doble = x * 2;
                    const igual = doble * 1 + 0;
                    _ = igual;
                    return y / 4.0;
                }
                pub fn main() void { _ = f(3, 2.0); }
                """);
        assertTrue(r.optimizedSource().contains("x + x"), r.optimizedSource());
        assertTrue(r.optimizedSource().contains("y * 0.25"), r.optimizedSource());
    }

    @Test void eliminatesRedundantSubexpressionsOnlyWhileOperandsAreUnchanged() {
        var r = optimize("""
                fn f(a: i32, b: i32, c: i32) i32 {
                    const x = (a + b) * c;
                    const y = (a + b) * 2 + c;
                    return x + y + (a + b);
                }
                fn g(p: i32, q: i32) i32 {
                    var a: i32 = p;
                    const s1 = a + q;
                    a = 5;
                    const s2 = a + q;
                    return s1 + s2;
                }
                pub fn main() void { _ = f(1, 2, 3); _ = g(1, 2); }
                """);
        String code = r.optimizedSource();
        assertTrue(code.contains("const cse1 = a + b;"), code);
        assertTrue(code.contains("return x + y + cse1;"), code);
        assertTrue(code.contains("const s2 = a + q;"), code);
    }

    @Test void movesLoopInvariantsOutOfLoopsThatAlwaysRun() {
        var r = optimize("""
                fn f(limite: i32) i32 {
                    var total: i32 = 0;
                    for (0..10) |i| {
                        const factor = limite * 3;
                        total += factor;
                        _ = i;
                    }
                    return total;
                }
                pub fn main() void { _ = f(4); }
                """);
        String code = r.optimizedSource();
        assertTrue(code.indexOf("const factor") < code.indexOf("for ("), code);
    }

    @Test void neverChangesTheTypeOfAnExpression() {
        var r = optimize("pub fn main() void { var b: u8 = 250; const n: i32 = 10; const s = b + n; _ = s; b -= 1; _ = b; }");
        assertTrue(r.optimizedSource().contains("b + n"), r.optimizedSource());
        var f = optimize("pub fn main() void { const x: f64 = 10; const y = x / 4; var z = y; z += 1.0; _ = z; }");
        assertTrue(f.optimizedSource().contains("const y: f64 = 2.5;"), f.optimizedSource());
        var c = optimize("pub fn main() void { const c: u8 = 'A'; const d = c + 1; var e = d; e += 1; _ = e; }");
        assertTrue(c.optimizedSource().contains("const d: u8 = 66;"), c.optimizedSource());
    }

    @Test void resolvesConstantSwitchesAndIfElseChains() {
        var r = optimize("""
                pub fn main() void {
                    const sel: i32 = 2;
                    var out: i32 = 0;
                    switch (sel) {
                        1 => { out = 1; },
                        2 => { out = 2; },
                        else => { out = 3; },
                    }
                    if (1 > 2) { out += 10; } else if (2 > 1) { out += 20; } else { out += 30; }
                    _ = out;
                }
                """);
        String code = r.optimizedSource();
        assertFalse(code.contains("switch"), code);
        assertFalse(code.contains("if"), code);
        assertTrue(code.contains("out += 20;"), code);
    }

    @Test void keepsCallsOfUnusedVariablesAndDiscardsLostParameters() {
        var r = optimize("""
                fn f(p: i32) i32 { return p + 1; }
                fn g(x: i32) i32 { const nadie = f(x); return x * 0; }
                pub fn main() void { _ = g(5); }
                """);
        String code = r.optimizedSource();
        assertTrue(code.contains("_ = f(x);"), code);
        assertTrue(code.contains("return 0;"), code);
    }

    @Test void doesNotRunWhenPreviousPhasesFail() {
        String source = "fn main() void { var x: i32 = true; }";
        var result = service.optimize(analyzer.analyze(source));
        assertEquals(OptimizationResult.Status.NOT_RUN, result.status());
        assertTrue(result.comparisonText().contains("no se ejecutó"));
    }

    @Test void measuresTheComparisonTable() {
        var r = optimize(read("examples/optimizacion/01_todas_las_tecnicas.zig"));
        var before = r.original().orElseThrow();
        var after = r.optimized().orElseThrow();
        assertTrue(before.executed() && after.executed());
        assertTrue(after.operations() < before.operations());
        assertTrue(after.bytes() < before.bytes());
        assertTrue(after.lines() < before.lines());
        assertTrue(after.tokens() < before.tokens());
        assertTrue(before.nanos() > 0 && after.nanos() > 0);
        String table = r.comparisonText();
        assertTrue(table.contains("Tiempo de ejecución"), table);
        assertTrue(table.contains("Tokens"), table);
    }

    @Test void everyValidExampleStillPassesAllPhases() throws Exception {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of("examples"))) {
            files = walk.filter(p -> p.toString().endsWith(".zig")).sorted().toList();
        }
        assertFalse(files.isEmpty());
        for (Path file : files) {
            String source = Files.readString(file);
            AnalysisResult analysis = analyzer.analyze(source);
            var result = service.optimize(analysis);
            if (!analysis.successful()) {
                assertEquals(OptimizationResult.Status.NOT_RUN, result.status(), file.toString());
                continue;
            }
            assertTrue(result.available(), file + ": " + result.message());
            assertTrue(analyzer.analyze(result.optimizedSource()).successful(), file.toString());
        }
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
