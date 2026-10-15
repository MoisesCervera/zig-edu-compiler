package mx.edu.automatas.zigedu.ir;

import mx.edu.automatas.zigedu.ast.Ast.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Recorridos inorden, postorden y preorden del AST; no vuelve a interpretar el texto. */
public final class ExpressionNotation {
    private ExpressionNotation() { }
    public record Forms(String infix, String postfix, String prefix) { }
    public static Forms of(Expression e) {
        if (e instanceof BinaryExpr b) {
            Forms l = of(b.left()), r = of(b.right());
            return new Forms("(" + l.infix() + " " + b.operator() + " " + r.infix() + ")",
                    l.postfix() + " " + r.postfix() + " " + b.operator(),
                    b.operator() + " " + l.prefix() + " " + r.prefix());
        }
        if (e instanceof UnaryExpr u) {
            Forms a = of(u.operand()); String op = u.operator().equals("-") ? "NEG" : "!";
            return new Forms("(" + u.operator() + a.infix() + ")", a.postfix() + " " + op, op + " " + a.prefix());
        }
        if (e instanceof CallExpr c) {
            List<Expression> all = new ArrayList<>(); all.add(c.callee()); all.addAll(c.arguments());
            return composite(of(c.callee()).infix() + "(" + c.arguments().stream().map(x -> of(x).infix())
                    .collect(Collectors.joining(", ")) + ")", "CALL[" + c.arguments().size() + "]", all);
        }
        if (e instanceof IndexExpr i) return composite(of(i.target()).infix() + "[" + of(i.index()).infix() + "]",
                "INDEX", List.of(i.target(), i.index()));
        if (e instanceof LengthExpr l) return composite(of(l.target()).infix() + ".len", "LEN", List.of(l.target()));
        if (e instanceof ArrayLiteral a) {
            String type = "[" + a.explicitSize().orElse("_") + "]" + type(a.elementType());
            return composite(type + "{" + a.elements().stream().map(x -> of(x).infix())
                    .collect(Collectors.joining(", ")) + "}", "ARRAY[" + a.elements().size() + "," + type + "]", a.elements());
        }
        String atom;
        if (e instanceof IntegerLiteral l) atom = l.image();
        else if (e instanceof FloatLiteral l) atom = l.image();
        else if (e instanceof CharLiteral l) atom = l.image();
        else if (e instanceof StringLiteral l) atom = l.image();
        else if (e instanceof BooleanLiteral l) atom = String.valueOf(l.value());
        else if (e instanceof IdentifierExpr i) atom = i.name();
        else throw new IllegalArgumentException("Expresión no soportada: " + e.getClass());
        return new Forms(atom, atom, atom);
    }
    private static Forms composite(String infix, String op, List<Expression> children) {
        String post = children.stream().map(e -> of(e).postfix()).collect(Collectors.joining(" "));
        String pre = children.stream().map(e -> of(e).prefix()).collect(Collectors.joining(" "));
        return new Forms(infix, (post.isEmpty() ? "" : post + " ") + op, op + (pre.isEmpty() ? "" : " " + pre));
    }
    public static String type(TypeNode t) {
        if (t instanceof PrimitiveType p) return p.name();
        ArrayType a = (ArrayType)t; return "[" + a.size() + "]" + type(a.elementType());
    }
}
