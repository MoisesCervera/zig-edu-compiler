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
        write("errores_semanticos.txt", semanticText(result));
        write("analisis_semantico.txt", result.semantic().map(this::semanticTable).orElse("Análisis semántico no ejecutado.\n"));
        write("tabla_simbolos.txt", result.semantic().map(this::symbolTable).orElse("Tabla de símbolos no disponible.\n"));
        write("ast_anotado.txt", result.semantic().map(SemanticResult::annotatedAst).orElse("AST anotado no disponible.\n"));
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    private String semanticText(AnalysisResult result) {
        if (!result.semanticExecuted()) return "El análisis semántico no se ejecutó; corrige primero los errores léxicos o sintácticos.\n";
        return diagnosticsText(result.semanticErrors(), "Sin errores semánticos.");
    }

    private String semanticTable(SemanticResult result) {
        StringBuilder text = new StringBuilder("CONSTRUCCIÓN\tDETALLE\tUBICACIÓN\n");
        for (SemanticResult.Entry entry : result.entries()) {
            text.append(entry.construction()).append('\t').append(entry.detail()).append('\t').append(entry.location()).append('\n');
        }
        return text.toString();
    }

    private String symbolTable(SemanticResult result) {
        StringBuilder text = new StringBuilder("NOMBRE\tCLASE\tTIPO O FIRMA\tÁMBITO\tUBICACIÓN\n");
        for (SemanticResult.SymbolInfo symbol : result.symbols()) {
            text.append(symbol.name()).append('\t').append(symbol.kind()).append('\t').append(symbol.type())
                    .append('\t').append(symbol.scope()).append('\t').append(symbol.location()).append('\n');
        }
        return text.toString();
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
        text.append(String.format("%-28s %-22s %-10s %-10s%n",
                "LEXEMA", "TIPO", "INICIO", "FIN"));
        for (TokenInfo token : tokens) {
            text.append(String.format("%-28s %-22s %-10s %-10s%n",
                    printable(token.lexeme()),
                    token.type(),
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
