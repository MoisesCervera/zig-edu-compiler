package mx.edu.automatas.zigedu.analysis;

public record TokenInfo(
        int number,
        String lexeme,
        String type,
        int line,
        int column,
        int endLine,
        int endColumn
) {
}
