package mx.edu.automatas.zigedu.opt;

import mx.edu.automatas.zigedu.ast.Ast.*;

import java.util.List;
import java.util.stream.Collectors;

/** Genera código Zig desde el AST; sólo agrega los paréntesis que exige la precedencia. */
public final class ZigPrinter {
    private static final String INDENT = "    ";

    private ZigPrinter() {
    }

    public static String print(Program program) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < program.functions().size(); i++) {
            if (i > 0) out.append('\n');
            function(program.functions().get(i), out);
        }
        return out.toString();
    }

    private static void function(FunctionDecl f, StringBuilder out) {
        if (f.isPublic()) out.append("pub ");
        out.append("fn ").append(f.name()).append('(')
                .append(f.parameters().stream().map(p -> p.name() + ": " + type(p.type()))
                        .collect(Collectors.joining(", ")))
                .append(") ").append(type(f.returnType())).append(' ');
        block(f.body(), 0, out);
        out.append('\n');
    }

    public static String type(TypeNode t) {
        if (t instanceof PrimitiveType p) return p.name();
        ArrayType a = (ArrayType) t;
        return "[" + a.size() + "]" + type(a.elementType());
    }

    private static void block(Block b, int depth, StringBuilder out) {
        if (b.statements().isEmpty()) {
            out.append("{}");
            return;
        }
        out.append("{\n");
        for (Statement s : b.statements()) {
            out.append(INDENT.repeat(depth + 1));
            statement(s, depth + 1, out);
            out.append('\n');
        }
        out.append(INDENT.repeat(depth)).append('}');
    }

    private static void statement(Statement s, int depth, StringBuilder out) {
        if (s instanceof Block b) {
            block(b, depth, out);
        } else if (s instanceof VariableDecl v) {
            out.append(v.constant() ? "const " : "var ").append(v.name());
            v.declaredType().ifPresent(t -> out.append(": ").append(type(t)));
            out.append(" = ").append(expression(v.initializer())).append(';');
        } else if (s instanceof AssignmentStmt a) {
            out.append(expression(a.target())).append(' ').append(a.operator()).append(' ')
                    .append(expression(a.value())).append(';');
        } else if (s instanceof ExpressionStmt e) {
            out.append(expression(e.expression())).append(';');
        } else if (s instanceof IfStmt i) {
            out.append("if (").append(expression(i.condition())).append(") ");
            block(i.thenBlock(), depth, out);
            if (i.elseBranch().isPresent()) {
                out.append(" else ");
                Statement other = i.elseBranch().get();
                if (other instanceof IfStmt) statement(other, depth, out);
                else block(asBlock(other), depth, out);
            }
        } else if (s instanceof WhileStmt w) {
            out.append("while (").append(expression(w.condition())).append(") ");
            block(w.body(), depth, out);
        } else if (s instanceof ForStmt f) {
            out.append("for (");
            if (f.source() instanceof RangeSource r) {
                out.append(expression(r.start())).append("..").append(expression(r.end()));
            } else {
                out.append(expression(((IterableSource) f.source()).expression()));
            }
            out.append(") |").append(f.capture()).append("| ");
            block(f.body(), depth, out);
        } else if (s instanceof SwitchStmt sw) {
            out.append("switch (").append(expression(sw.subject())).append(") {\n");
            for (SwitchCase c : sw.cases()) {
                out.append(INDENT.repeat(depth + 1));
                out.append(c.elseCase() ? "else" : c.labels().stream().map(ZigPrinter::expression)
                        .collect(Collectors.joining(", ")));
                out.append(" => ");
                block(c.body(), depth + 1, out);
                out.append(",\n");
            }
            out.append(INDENT.repeat(depth)).append('}');
        } else if (s instanceof ReturnStmt r) {
            out.append("return");
            r.value().ifPresent(v -> out.append(' ').append(expression(v)));
            out.append(';');
        } else if (s instanceof BreakStmt) {
            out.append("break;");
        } else if (s instanceof ContinueStmt) {
            out.append("continue;");
        } else {
            throw new IllegalArgumentException("Sentencia no soportada: " + s.getClass().getSimpleName());
        }
    }

    private static Block asBlock(Statement s) {
        return s instanceof Block b ? b : new Block(List.of(s), s.span());
    }

    private static final int OR = 1, AND = 2, EQUALITY = 3, COMPARISON = 4, ADDITIVE = 5,
            MULTIPLICATIVE = 6, UNARY = 7, POSTFIX = 8;

    private static int precedence(String operator) {
        return switch (operator) {
            case "or" -> OR;
            case "and" -> AND;
            case "==", "!=" -> EQUALITY;
            case "<", "<=", ">", ">=" -> COMPARISON;
            case "+", "-" -> ADDITIVE;
            case "*", "/", "%" -> MULTIPLICATIVE;
            default -> throw new IllegalArgumentException("Operador desconocido: " + operator);
        };
    }

    private static int precedence(Expression e) {
        if (e instanceof BinaryExpr b) return precedence(b.operator());
        if (e instanceof UnaryExpr) return UNARY;
        return POSTFIX;
    }

    public static String expression(Expression e) {
        if (e instanceof BinaryExpr b) {
            int own = precedence(b.operator());
            int left = precedence(b.left());
            int right = precedence(b.right());
            boolean leftParens = left < own || own == COMPARISON && left == COMPARISON;
            boolean rightParens = right <= own;
            return wrap(expression(b.left()), leftParens) + " " + b.operator() + " "
                    + wrap(expression(b.right()), rightParens);
        }
        if (e instanceof UnaryExpr u) {
            boolean parens = u.operand() instanceof BinaryExpr || u.operand() instanceof UnaryExpr;
            return u.operator() + wrap(expression(u.operand()), parens);
        }
        if (e instanceof CallExpr c) {
            return postfixTarget(c.callee()) + "(" + c.arguments().stream().map(ZigPrinter::expression)
                    .collect(Collectors.joining(", ")) + ")";
        }
        if (e instanceof IndexExpr i) return postfixTarget(i.target()) + "[" + expression(i.index()) + "]";
        if (e instanceof LengthExpr l) return postfixTarget(l.target()) + ".len";
        if (e instanceof ArrayLiteral a) {
            String elements = a.elements().stream().map(ZigPrinter::expression).collect(Collectors.joining(", "));
            return "[" + a.explicitSize().orElse("_") + "]" + type(a.elementType())
                    + (elements.isEmpty() ? "{}" : "{ " + elements + " }");
        }
        if (e instanceof IntegerLiteral l) return l.image();
        if (e instanceof FloatLiteral l) return l.image();
        if (e instanceof CharLiteral l) return l.image();
        if (e instanceof StringLiteral l) return l.image();
        if (e instanceof BooleanLiteral l) return String.valueOf(l.value());
        if (e instanceof IdentifierExpr i) return i.name();
        throw new IllegalArgumentException("Expresión no soportada: " + e.getClass().getSimpleName());
    }

    private static String postfixTarget(Expression target) {
        return wrap(expression(target), precedence(target) < POSTFIX);
    }

    private static String wrap(String text, boolean parens) {
        return parens ? "(" + text + ")" : text;
    }
}
