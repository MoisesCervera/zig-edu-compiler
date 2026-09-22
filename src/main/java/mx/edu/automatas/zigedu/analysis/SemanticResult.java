package mx.edu.automatas.zigedu.analysis;

import mx.edu.automatas.zigedu.ast.Ast;
import java.util.List;

/** Resultado independiente por ejecución, compartido por Swing y la exportación. */
public record SemanticResult(List<Diagnostic> errors, List<Entry> entries,
                             List<SymbolInfo> symbols, String annotatedAst) {
    public SemanticResult {
        errors = List.copyOf(errors);
        entries = List.copyOf(entries);
        symbols = List.copyOf(symbols);
    }

    public record Entry(String construction, String detail, Ast.SourceSpan span, boolean error) {
        public String location() { return span.line() + ":" + span.column(); }
    }

    public record SymbolInfo(String name, String kind, String type, String scope, Ast.SourceSpan span) {
        public String location() { return span.line() + ":" + span.column(); }
    }
}
