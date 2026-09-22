package mx.edu.automatas.zigedu.analysis;

import mx.edu.automatas.zigedu.ast.Ast;

import java.util.List;
import java.util.Optional;

public record AnalysisResult(
        List<TokenInfo> tokens,
        Optional<Ast.Program> program,
        List<Diagnostic> lexicalErrors,
        List<Diagnostic> syntacticErrors,
        Optional<SemanticResult> semantic
) {
    public AnalysisResult {
        tokens = List.copyOf(tokens);
        lexicalErrors = List.copyOf(lexicalErrors);
        syntacticErrors = List.copyOf(syntacticErrors);
    }

    public AnalysisResult(List<TokenInfo> tokens, Optional<Ast.Program> program,
                          List<Diagnostic> lexicalErrors, List<Diagnostic> syntacticErrors) {
        this(tokens, program, lexicalErrors, syntacticErrors, Optional.empty());
    }

    public boolean semanticExecuted() { return semantic.isPresent(); }

    public List<Diagnostic> semanticErrors() {
        return semantic.map(SemanticResult::errors).orElseGet(List::of);
    }

    public boolean successful() {
        return lexicalErrors.isEmpty() && syntacticErrors.isEmpty() && program.isPresent()
                && semanticExecuted() && semanticErrors().isEmpty();
    }
}
