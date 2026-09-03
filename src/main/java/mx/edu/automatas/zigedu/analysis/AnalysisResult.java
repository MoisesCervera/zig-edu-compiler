package mx.edu.automatas.zigedu.analysis;

import mx.edu.automatas.zigedu.ast.Ast;

import java.util.List;
import java.util.Optional;

public record AnalysisResult(
        List<TokenInfo> tokens,
        Optional<Ast.Program> program,
        List<Diagnostic> lexicalErrors,
        List<Diagnostic> syntacticErrors
) {
    public AnalysisResult {
        tokens = List.copyOf(tokens);
        lexicalErrors = List.copyOf(lexicalErrors);
        syntacticErrors = List.copyOf(syntacticErrors);
    }

    public boolean successful() {
        return lexicalErrors.isEmpty() && syntacticErrors.isEmpty() && program.isPresent();
    }
}
