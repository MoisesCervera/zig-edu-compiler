package mx.edu.automatas.zigedu.analysis;

import mx.edu.automatas.zigedu.ast.Ast;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceAnalyzerTest {
    private final SourceAnalyzer analyzer = new SourceAnalyzer();

    @Test
    void acceptsFunctionsExpressionsControlFlowArraysAndCalls() {
        String source = """
                fn choose(value: i32) i32 {
                    var result: i32 = 0;
                    switch (value) {
                        0 => { result = 10; },
                        1, 2 => { result = 20; },
                        else => { result = -1; },
                    }
                    return result;
                }

                pub fn main() void {
                    const values = [_]i32{ 1, 2, 3, 4 };
                    const letters = [_]u8{ 'Z', 'i', 'g' };
                    var total: i32 = 0;
                    var cursor: i32 = 0;
                    for (values) |value| {
                        if (value == 2) { continue; }
                        total += value * 2;
                    }
                    for (0..4) |index| {
                        _ = index;
                    }
                    while (cursor < 10 and total >= 0) {
                        cursor += 1;
                        if (cursor > 8) { break; }
                    }
                    total = choose(total % 3);
                    _ = letters[0];
                    _ = letters.len;
                    _ = !false or true;
                    _ = total;
                }
                """;

        AnalysisResult result = analyzer.analyze(source);

        assertTrue(result.successful(), () -> result.syntacticErrors().toString());
        assertTrue(result.lexicalErrors().isEmpty());
        assertTrue(result.syntacticErrors().isEmpty());
        Ast.Program program = result.program().orElseThrow();
        assertEquals(2, program.functions().size());
        assertEquals("main", program.functions().get(1).name());
        assertTrue(result.tokens().stream().anyMatch(token -> token.type().equals("SWITCH")));
        assertTrue(result.tokens().stream().anyMatch(token -> token.type().equals("CHAR_LITERAL")));
    }

    @Test
    void reportsAnInvalidCharacterAsALexicalError() {
        AnalysisResult result = analyzer.analyze("pub fn main() void { const bad = @; }");

        assertFalse(result.successful());
        assertEquals(1, result.lexicalErrors().size());
        Diagnostic error = result.lexicalErrors().getFirst();
        assertEquals(Diagnostic.Phase.LEXICAL, error.phase());
        assertTrue(error.line() >= 1);
        assertTrue(error.format().contains("Se esperaba"));
        assertTrue(error.format().contains("Se encontró"));
        assertTrue(result.program().isEmpty());
    }

    @Test
    void continuesAfterLexicalErrorsAndReportsEveryInvalidCharacter() {
        AnalysisResult result = analyzer.analyze("""
                pub fn main() void {
                    const first = @;
                    const second = #;
                    const third = $;
                }
                """);

        assertFalse(result.successful());
        assertEquals(3, result.lexicalErrors().size(), result.lexicalErrors()::toString);
        assertEquals(List.of(2, 3, 4), result.lexicalErrors().stream().map(Diagnostic::line).toList());
        assertTrue(result.syntacticErrors().isEmpty());
        assertTrue(result.tokens().stream().anyMatch(token -> token.lexeme().equals("second")));
        assertTrue(result.tokens().stream().anyMatch(token -> token.lexeme().equals("third")));
    }

    @Test
    void reportsExpectedAndFoundTokensForSyntaxErrors() {
        AnalysisResult result = analyzer.analyze("""
                pub fn main() void {
                    const value: i32 = 10
                    _ = value;
                }
                """);

        assertFalse(result.successful());
        assertTrue(result.lexicalErrors().isEmpty());
        assertEquals(1, result.syntacticErrors().size());
        Diagnostic error = result.syntacticErrors().getFirst();
        assertEquals(3, error.line());
        assertTrue(error.expected().contains(";"), error::expected);
        assertTrue(error.found().contains("UNDERSCORE"), error::found);
        assertTrue(error.sourceLine().contains("_ = value"));
    }

    @Test
    void buildsTypedAstNodes() {
        AnalysisResult result = analyzer.analyze("fn double(value: i32) i32 { return value * 2; }");

        Ast.FunctionDecl function = result.program().orElseThrow().functions().getFirst();
        assertEquals("double", function.name());
        assertInstanceOf(Ast.PrimitiveType.class, function.returnType());
        assertInstanceOf(Ast.ReturnStmt.class, function.body().statements().getFirst());
    }

    @Test
    void acceptsTheCompleteExampleWithMoreThanTwoHundredLines() throws Exception {
        String source = Files.readString(Path.of("examples", "todas_las_funciones.zig"));

        AnalysisResult result = analyzer.analyze(source);

        assertTrue(source.lines().count() >= 200);
        assertTrue(result.successful(), () -> result.lexicalErrors() + "\n" + result.syntacticErrors());
        assertTrue(result.program().orElseThrow().functions().size() >= 20);
    }
}
