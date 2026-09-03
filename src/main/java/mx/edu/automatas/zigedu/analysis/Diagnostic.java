package mx.edu.automatas.zigedu.analysis;

import java.util.Objects;

public record Diagnostic(
        Phase phase,
        String summary,
        int line,
        int column,
        String expected,
        String found,
        String sourceLine
) {
    public enum Phase {
        LEXICAL("léxico"),
        SYNTACTIC("sintáctico");

        private final String displayName;

        Phase(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public Diagnostic {
        Objects.requireNonNull(phase);
        Objects.requireNonNull(summary);
        expected = expected == null ? "No aplica" : expected;
        found = found == null ? "No disponible" : found;
        sourceLine = sourceLine == null ? "" : sourceLine;
    }

    public String format() {
        StringBuilder result = new StringBuilder();
        result.append("Error ").append(phase.displayName())
                .append(" en línea ").append(Math.max(line, 1))
                .append(", columna ").append(Math.max(column, 1)).append(".\n\n")
                .append(summary).append("\n\n")
                .append("Se esperaba:\n    ").append(expected).append("\n\n")
                .append("Se encontró:\n    ").append(found).append("\n");
        if (!sourceLine.isBlank()) {
            result.append("\nContexto:\n    ").append(sourceLine).append("\n    ")
                    .append(" ".repeat(Math.max(column - 1, 0))).append("^\n");
        }
        return result.toString();
    }
}
