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
}
