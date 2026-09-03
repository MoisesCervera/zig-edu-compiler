package mx.edu.automatas.zigedu.analysis;

import mx.edu.automatas.zigedu.ast.AstFormatter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class ResultExporter {
    private final Path outputDirectory;

    public ResultExporter(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public void overwrite(AnalysisResult result) throws IOException {
        Files.createDirectories(outputDirectory);
        write("errores_lexicos.txt", diagnosticsText(result.lexicalErrors(), "Sin errores léxicos."));
        write("errores_sintacticos.txt", syntacticText(result));
        write("tokens.txt", tokensText(result.tokens()));
        write("ast.txt", result.program().map(AstFormatter::format).orElse("AST no disponible.\n"));
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    private String syntacticText(AnalysisResult result) {
        if (!result.lexicalErrors().isEmpty()) {
            return "El análisis sintáctico no se ejecutó debido a errores léxicos.\n";
        }
        return diagnosticsText(result.syntacticErrors(), "Sin errores sintácticos.");
    }

    private String diagnosticsText(List<Diagnostic> diagnostics, String success) {
        if (diagnostics.isEmpty()) {
            return success + System.lineSeparator();
        }
        return diagnostics.stream()
                .map(Diagnostic::format)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String tokensText(List<TokenInfo> tokens) {
        StringBuilder text = new StringBuilder();
        text.append(String.format("%-6s %-22s %-28s %-10s %-10s%n",
                "#", "TIPO", "LEXEMA", "INICIO", "FIN"));
        for (TokenInfo token : tokens) {
            text.append(String.format("%-6d %-22s %-28s %-10s %-10s%n",
                    token.number(),
                    token.type(),
                    printable(token.lexeme()),
                    token.line() + ":" + token.column(),
                    token.endLine() + ":" + token.endColumn()));
        }
        return text.toString();
    }

    private String printable(String value) {
        return value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(outputDirectory.resolve(name), content, StandardCharsets.UTF_8);
    }
}
