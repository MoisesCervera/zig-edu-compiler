package mx.edu.automatas.zigedu.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SemanticAnalyzerTest {
    private final SourceAnalyzer analyzer = new SourceAnalyzer();
    private AnalysisResult body(String body) { return analyzer.analyze("fn main() void { " + body + " }"); }
    private void has(AnalysisResult result, String code) {
        assertTrue(result.lexicalErrors().isEmpty(), result.lexicalErrors()::toString);
        assertTrue(result.syntacticErrors().isEmpty(), result.syntacticErrors()::toString);
        assertTrue(result.semanticExecuted());
        assertFalse(result.successful());
        assertTrue(result.semanticErrors().stream().anyMatch(e -> e.summary().contains(code)),
                () -> code + " no detectado: " + result.semanticErrors());
    }

    @Test void reportsTheThreeRequiredValidationsWithExactLocations() {
        AnalysisResult result = analyzer.analyze("""
                fn main() void {
                    var edad: i32 = true;
                    total = 10;
                    var edad: bool = false;
                }
                """);
        assertEquals(3, result.semanticErrors().size(), result.semanticErrors()::toString);
        assertEquals(List.of(2, 3, 4), result.semanticErrors().stream().map(Diagnostic::line).toList());
        assertEquals(List.of(21, 5, 9), result.semanticErrors().stream().map(Diagnostic::column).toList());
        has(result, "SEM_TIPO"); has(result, "SEM_NO_DECLARADO"); has(result, "SEM_DUPLICADO");
        for (Diagnostic d : result.semanticErrors()) {
            assertEquals(Diagnostic.Phase.SEMANTIC, d.phase());
            assertFalse(d.expected().isBlank()); assertFalse(d.found().isBlank());
            assertTrue(d.format().contains("Contexto:")); assertTrue(d.format().contains("columna"));
        }
        assertTrue(result.semanticErrors().get(2).found().contains("línea 2, columna 9"));
    }

    @ParameterizedTest @CsvSource(delimiter='|', value={
            "_ = missing; | SEM_NO_DECLARADO",
            "_ = x; var x: i32 = 1; | SEM_NO_DECLARADO",
            "var x: i32 = x; | SEM_NO_DECLARADO",
            "{ var x = 1; } _ = x; | SEM_NO_DECLARADO",
            "var x = 1; { var x = 2; } | SEM_DUPLICADO",
            "const x = 1; x = 2; | SEM_CONSTANTE",
            "const a = [_]i32{1}; a[0] = 2; | SEM_CONSTANTE",
            "(1 + 2) = 3; | SEM_DESTINO",
            "var x: i32 = false; | SEM_TIPO",
            "var x: i32 = 1; x = 2.5; | SEM_TIPO",
            "var x: bool = true; x += 1; | SEM_OPERADOR",
            "if (1) { } | SEM_TIPO",
            "while (1) { break; } | SEM_TIPO",
            "_ = true + 1; | SEM_OPERADOR",
            "_ = !1; | SEM_TIPO",
            "_ = true and 1; | SEM_TIPO",
            "_ = [_]i32{true}; | SEM_TIPO",
            "_ = [2]i32{1}; | SEM_TAMANO_ARREGLO",
            "_ = [_]i32{1}[true]; | SEM_TIPO",
            "_ = [_]i32{1}[1]; | SEM_INDICE",
            "_ = [_]i32{1}[-1]; | SEM_INDICE",
            "_ = [0]i32{}[0]; | SEM_INDICE",
            "_ = 1.len; | SEM_LONGITUD",
            "_ = 1[0]; | SEM_INDICE",
            "var x: u8 = 256; | SEM_DESBORDAMIENTO",
            "var x: i32 = 2147483648; | SEM_DESBORDAMIENTO",
            "var x: u8 = -1; | SEM_DESBORDAMIENTO",
            "_ = 1e999; | SEM_DESBORDAMIENTO",
            "_ = 10 / 0; | SEM_DIVISION_CERO",
            "const zero = 1 - 1; _ = 10 % zero; | SEM_DIVISION_CERO",
            "_ = 1.0 % 2.0; | SEM_OPERADOR",
            "return 1; | SEM_TIPO",
            "break; | SEM_CONTEXTO",
            "continue; | SEM_CONTEXTO",
            "_ = _; | SEM_DESCARTE",
            "1 + 2; | SEM_VALOR_IGNORADO",
            "var f = 1; f(); | SEM_LLAMADA",
            "missing(); | SEM_NO_DECLARADO",
            "_ = 1(); | SEM_LLAMADA",
            "switch (1) { 1 => {}, 1 => {}, else => {} } | SEM_CASO_DUPLICADO",
            "switch (1) { true => {}, else => {} } | SEM_TIPO"
    }) void rejectsInvalidPrograms(String source, String code) { has(body(source), code); }

    @ParameterizedTest @ValueSource(strings={
            "fn f(x: i32, x: i32) void {}",
            "fn f() void {} fn f() void {}",
            "fn f(x: i32) void { var x = 1; }",
            "fn f(a: [1]i32) void { for (a) |x| { var x = 1; } }"
    }) void detectsDuplicateFunctionsParametersAndCaptures(String source) { has(analyzer.analyze(source), "SEM_DUPLICADO"); }

    @Test void validatesFunctionSignaturesAndEveryArgument() {
        has(analyzer.analyze("fn f(a: i32, b: bool) i32 { return a; } fn g() void { _ = f(true, 2); }"), "SEM_TIPO");
        has(analyzer.analyze("fn f(a: i32) void {} fn g() void { f(); }"), "SEM_ARGUMENTOS");
        has(analyzer.analyze("fn f() void {} fn g() void { f(missing); }"), "SEM_NO_DECLARADO");
        has(analyzer.analyze("fn f(a: [2]i32) void {} fn g() void { f([_]i32{1}); }"), "SEM_TIPO");
        has(analyzer.analyze("fn f(a: i32) void { a = 1; }"), "SEM_CONSTANTE");
    }

    @ParameterizedTest @ValueSource(strings={
            "fn f() i32 {}",
            "fn f(x: bool) i32 { if (x) { return 1; } }",
            "fn f() i32 { while (true) { break; return 1; } }",
            "fn f() i32 { for (0..0) |_| { return 1; } }"
    }) void requiresAReturnOnPathsThatReachTheEnd(String source) { has(analyzer.analyze(source), "SEM_RETORNO"); }

    @ParameterizedTest @ValueSource(strings={
            "fn f() i32 { return 1; }",
            "fn f(x: bool) i32 { if (x) { return 1; } else { return 2; } }",
            "fn f() i32 { while (true) { return 1; } }",
            "fn f() i32 { while (true) { continue; } }",
            "fn f() i32 { if (true) { return 1; } }",
            "fn f(x: i32) i32 { switch (x) { 1 => {return 1;}, else => {return 2;} } }",
            "fn main() void { _ = twice(2); } fn twice(x: i32) i32 { return x * 2; }",
            "fn fact(x: i32) i32 { if (x == 0) { return 1; } return x * fact(x - 1); }",
            "fn f() void { var x = 1; _ = x; } fn g() void { var x = true; _ = x; }",
            "fn f() void { { const x = 1; _ = x; } { const x = true; _ = x; } }",
            "fn f() void { var a = [_]i32{1,2}; a[0] += 1; for (a) |x| { _ = x; } }",
            "fn f() void { var x: i32 = -2147483648; var c: u8 = 'A'; x += c; _ = x; }",
            "fn f() void { const n = 2; const a: [2]u8 = [_]u8{'A', 'B'}; _ = a[n - 1]; }",
            "fn f() void { const a = [_][1]i32{[_]i32{1}, [_]i32{2}}; _ = a[0][0]; }",
            "fn f() void { for (0..2) |_| {} }"
    }) void acceptsValidPrograms(String source) {
        AnalysisResult result = analyzer.analyze(source);
        assertTrue(result.successful(), () -> result.lexicalErrors() + " " + result.syntacticErrors() + " " + result.semanticErrors());
    }

    @Test void usesTabAndCrLfLocationsFromTheParser() {
        AnalysisResult r = analyzer.analyze("fn f() void {\r\n\tmissing = 1;\r\n}");
        Diagnostic d = r.semanticErrors().getFirst();
        assertEquals(2, d.line()); assertEquals(2, d.column());
        assertTrue(d.format().contains("     missing"));
    }

    @Test void previousPhasesGateSemanticExecutionAndNoStateLeaksBetweenRuns() {
        assertFalse(analyzer.analyze("fn f() void { _ = @; }").semanticExecuted());
        assertFalse(analyzer.analyze("fn f() void { var x = ; _ = missing; }").semanticExecuted());
        has(body("_ = missing;"), "SEM_NO_DECLARADO");
        assertTrue(body("const missing = 1; _ = missing;").successful());
        assertTrue(body("_ = 1;").semanticErrors().isEmpty());
    }

    @Test void completeOriginalExampleHasTypesSymbolsAndNoErrors() throws Exception {
        AnalysisResult result = analyzer.analyze(Files.readString(Path.of("examples/todas_las_funciones.zig")));
        assertTrue(result.successful(), result.semanticErrors()::toString);
        SemanticResult semantic = result.semantic().orElseThrow();
        assertTrue(semantic.symbols().stream().anyMatch(s -> s.name().equals("greeting") && s.type().equals("[12]u8")));
        assertTrue(semantic.annotatedAst().contains("tipo=i32"));
        assertTrue(semantic.entries().stream().anyMatch(e -> e.construction().equals("Llamada")));
    }
}
