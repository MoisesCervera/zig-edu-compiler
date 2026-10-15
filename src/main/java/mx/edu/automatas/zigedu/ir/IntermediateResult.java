package mx.edu.automatas.zigedu.ir;

import mx.edu.automatas.zigedu.ast.Ast.SourceSpan;
import java.util.List;

/** Representación educativa de tres direcciones; cada instrucción tiene cuatro campos. */
public record IntermediateResult(List<Quadruple> quadruples, List<Notation> notations) {
    public IntermediateResult {
        quadruples = List.copyOf(quadruples);
        notations = List.copyOf(notations);
    }
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
