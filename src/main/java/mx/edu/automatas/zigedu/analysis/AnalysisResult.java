package mx.edu.automatas.zigedu.analysis;

import mx.edu.automatas.zigedu.ast.Ast;
import mx.edu.automatas.zigedu.ir.IntermediateResult;

import java.util.List;
import java.util.Optional;

public record AnalysisResult(
        List<TokenInfo> tokens,
        Optional<Ast.Program> program,
        List<Diagnostic> lexicalErrors,
        List<Diagnostic> syntacticErrors,
        Optional<SemanticResult> semantic,
        Optional<IntermediateResult> intermediate
) {
    public AnalysisResult {
        tokens = List.copyOf(tokens);
        lexicalErrors = List.copyOf(lexicalErrors);
        syntacticErrors = List.copyOf(syntacticErrors);
    }

    /** Constructor compatible con los consumidores anteriores a la fase intermedia. */
    public AnalysisResult(List<TokenInfo> tokens, Optional<Ast.Program> program,
                          List<Diagnostic> lexicalErrors, List<Diagnostic> syntacticErrors,
                          Optional<SemanticResult> semantic) {
        this(tokens, program, lexicalErrors, syntacticErrors, semantic, Optional.empty());
    }

    public boolean intermediateExecuted() { return intermediate.isPresent(); }

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
