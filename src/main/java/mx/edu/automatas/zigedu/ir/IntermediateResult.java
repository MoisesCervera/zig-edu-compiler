package mx.edu.automatas.zigedu.ir;

import mx.edu.automatas.zigedu.ast.Ast.SourceSpan;
import java.util.List;

/**
 * Representación educativa de tres direcciones; cada instrucción tiene cuatro campos.
 *
 * <p>{@code quadruples} y {@code notations} contienen únicamente lo que se muestra y se exporta: las
 * operaciones matemáticas del código fuente. {@code executable} conserva la traducción completa del
 * programa y sólo sirve de entrada para calcular el resultado numérico ({@link NumericPreview});
 * {@code executableNumbers} relaciona cada cuádruplo visible con su instrucción en esa traducción.
 */
public record IntermediateResult(List<Quadruple> quadruples, List<Notation> notations,
                                 List<Quadruple> executable, List<Integer> executableNumbers) {
    public IntermediateResult {
        quadruples = List.copyOf(quadruples);
        notations = List.copyOf(notations);
        executable = List.copyOf(executable);
        executableNumbers = List.copyOf(executableNumbers);
    }
    /** Resultado sin traducción separada: los cuádruplos visibles son también los ejecutables. */
    public IntermediateResult(List<Quadruple> quadruples, List<Notation> notations) {
        this(quadruples, notations, quadruples, java.util.stream.IntStream.rangeClosed(1, quadruples.size()).boxed().toList());
    }
    /** Número del cuádruplo visible {@code displayNumber} dentro de la traducción completa. */
    public int executableNumber(int displayNumber) { return executableNumbers.get(displayNumber - 1); }
    public record Quadruple(int number, String function, String operator, String argument1,
                            String argument2, String result, SourceSpan span, String explanation) {
        public String location() { return span.line() + ":" + span.column(); }
        public String tuple() { return "(" + operator + ", " + empty(argument1) + ", "
                + empty(argument2) + ", " + empty(result) + ")"; }
        private static String empty(String s) { return s.isEmpty() ? "—" : s; }
    }
    public record Notation(int number, String function, String context, String infix,
                           String postfix, String prefix, SourceSpan span) {
        public String location() { return span.line() + ":" + span.column(); }
    }
    public String quadruplesText() {
        StringBuilder b = new StringBuilder("#\tFUNCIÓN\tOPERADOR\tARGUMENTO 1\tARGUMENTO 2\tRESULTADO\tUBICACIÓN\n");
        for (Quadruple q : quadruples) b.append(q.number()).append('\t').append(q.function()).append('\t')
                .append(q.operator()).append('\t').append(q.argument1()).append('\t').append(q.argument2())
                .append('\t').append(q.result()).append('\t').append(q.location()).append('\n');
        return b.toString();
    }
    public String notationsText() {
        StringBuilder b = new StringBuilder();
        for (Notation n : notations) b.append('#').append(n.number()).append(" · ").append(n.function())
                .append(" · ").append(n.context()).append(" · ").append(n.location())
                .append("\nInfija: ").append(n.infix()).append("\nPosfija: ").append(n.postfix())
                .append("\nPrefija: ").append(n.prefix()).append("\n\n");
        return b.toString();
    }
    public String stepsText() {
        StringBuilder b = new StringBuilder("PASOS DE TRADUCCIÓN DEL AST (no son una ejecución del programa)\n\n");
        for (Quadruple q : quadruples) b.append("Paso ").append(q.number()).append(" · ")
                .append(q.function()).append(" · ").append(q.location()).append('\n')
                .append(q.explanation()).append('\n').append(q.tuple()).append("\n\n");
        return b.toString();
    }
}
