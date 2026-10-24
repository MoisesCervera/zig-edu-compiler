package mx.edu.automatas.zigedu.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultExporterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void semanticReportsReplaceErrorsAndDoNotKeepStaleSymbolsWhenSkipped() throws Exception {
        ResultExporter exporter = new ResultExporter(temporaryDirectory);
        SourceAnalyzer analyzer = new SourceAnalyzer();
        exporter.overwrite(analyzer.analyze("fn f() void { _ = missing; }"));
        assertTrue(Files.readString(temporaryDirectory.resolve("errores_semanticos.txt")).contains("SEM_NO_DECLARADO"));
        exporter.overwrite(analyzer.analyze("fn f() void { const x: i32 = 1; _ = x; }"));
        assertTrue(Files.readString(temporaryDirectory.resolve("errores_semanticos.txt")).contains("Sin errores"));
        assertTrue(Files.readString(temporaryDirectory.resolve("tabla_simbolos.txt")).contains("x\tconst\ti32"));
        assertTrue(Files.readString(temporaryDirectory.resolve("ast_anotado.txt")).contains("tipo=i32"));
        exporter.overwrite(analyzer.analyze("fn f() void { var x = ; }"));
        assertTrue(Files.readString(temporaryDirectory.resolve("errores_semanticos.txt")).contains("no se ejecutó"));
        assertFalse(Files.readString(temporaryDirectory.resolve("tabla_simbolos.txt")).contains("x\tconst"));
        assertTrue(Files.readString(temporaryDirectory.resolve("ast_anotado.txt")).contains("no disponible"));
    }

    @Test
    void overwritesAllReportsOnEveryRun() throws Exception {
        ResultExporter exporter = new ResultExporter(temporaryDirectory);
        SourceAnalyzer analyzer = new SourceAnalyzer();

        exporter.overwrite(analyzer.analyze("pub fn main() void { _ = 1; }"));
        Path syntax = temporaryDirectory.resolve("errores_sintacticos.txt");
        Files.writeString(syntax, "contenido que debe desaparecer");

        exporter.overwrite(analyzer.analyze("pub fn main() void { const x = ; }"));

        String contents = Files.readString(syntax);
        assertFalse(contents.contains("contenido que debe desaparecer"));
        assertTrue(contents.contains("Error sintáctico"));
        assertTrue(Files.exists(temporaryDirectory.resolve("errores_lexicos.txt")));
        assertTrue(Files.exists(temporaryDirectory.resolve("tokens.txt")));
        assertTrue(Files.exists(temporaryDirectory.resolve("ast.txt")));
        String tokenReport = Files.readString(temporaryDirectory.resolve("tokens.txt"));
        assertTrue(tokenReport.startsWith("LEXEMA"));
        assertFalse(tokenReport.lines().findFirst().orElseThrow().contains("#"));
    }

    @Test
    void exportsTheExplicitArrayLiteralSizeInTheAstReport() throws Exception {
        ResultExporter exporter = new ResultExporter(temporaryDirectory);
        AnalysisResult result = new SourceAnalyzer().analyze("""
                pub fn main() void {
                    const values = [3]i32{ 1, 2, 3 };
                    _ = values;
                }
                """);

        exporter.overwrite(result);

        String ast = Files.readString(temporaryDirectory.resolve("ast.txt"));
        assertTrue(ast.contains("explicitSize=3"), ast);
    }
}
