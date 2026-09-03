package mx.edu.automatas.zigedu.ast;

import javax.swing.tree.DefaultMutableTreeNode;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AstFormatter {
    private AstFormatter() {
    }

    public static String format(Ast.Node node) {
        StringBuilder output = new StringBuilder();
        appendText(node, output, 0);
        return output.toString();
    }

    public static DefaultMutableTreeNode toTree(Ast.Node node) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(label(node));
        for (Ast.Node child : childNodes(node)) {
            treeNode.add(toTree(child));
        }
        return treeNode;
    }

    /** Representación plana del AST, adecuada para una lectura secuencial en la interfaz. */
    public static List<AstEntry> flatten(Ast.Node node) {
        List<AstEntry> entries = new ArrayList<>();
        appendEntry(node, entries, 0);
        return List.copyOf(entries);
    }

    private static void appendEntry(Ast.Node node, List<AstEntry> entries, int depth) {
        entries.add(new AstEntry(
                entries.size() + 1,
                depth,
                displayName(node),
                detail(node),
                node.span().line() + ":" + node.span().column()
                        + " – " + node.span().endLine() + ":" + node.span().endColumn()
        ));
        for (Ast.Node child : childNodes(node)) {
            appendEntry(child, entries, depth + 1);
        }
    }

    private static String displayName(Ast.Node node) {
        if (node instanceof Ast.Program) return "Programa";
        if (node instanceof Ast.FunctionDecl) return "Función";
        if (node instanceof Ast.Parameter) return "Parámetro";
        if (node instanceof Ast.PrimitiveType) return "Tipo primitivo";
        if (node instanceof Ast.ArrayType) return "Tipo arreglo";
        if (node instanceof Ast.Block) return "Bloque";
        if (node instanceof Ast.VariableDecl declaration) return declaration.constant() ? "Declaración const" : "Declaración var";
        if (node instanceof Ast.AssignmentStmt) return "Asignación";
        if (node instanceof Ast.ExpressionStmt) return "Expresión como sentencia";
        if (node instanceof Ast.IfStmt) return "Condicional if";
        if (node instanceof Ast.WhileStmt) return "Ciclo while";
        if (node instanceof Ast.ForStmt) return "Ciclo for";
        if (node instanceof Ast.IterableSource) return "Fuente iterable";
        if (node instanceof Ast.RangeSource) return "Rango";
        if (node instanceof Ast.SwitchStmt) return "Selección switch";
        if (node instanceof Ast.SwitchCase switchCase) return switchCase.elseCase() ? "Caso else" : "Caso switch";
        if (node instanceof Ast.ReturnStmt) return "Retorno";
        if (node instanceof Ast.BreakStmt) return "Break";
        if (node instanceof Ast.ContinueStmt) return "Continue";
        if (node instanceof Ast.IntegerLiteral) return "Entero";
        if (node instanceof Ast.FloatLiteral) return "Decimal";
        if (node instanceof Ast.BooleanLiteral) return "Booleano";
        if (node instanceof Ast.CharLiteral) return "Carácter";
        if (node instanceof Ast.IdentifierExpr) return "Identificador";
        if (node instanceof Ast.ArrayLiteral) return "Literal de arreglo";
        if (node instanceof Ast.BinaryExpr) return "Operación binaria";
        if (node instanceof Ast.UnaryExpr) return "Operación unaria";
        if (node instanceof Ast.CallExpr) return "Llamada";
        if (node instanceof Ast.IndexExpr) return "Acceso por índice";
        if (node instanceof Ast.LengthExpr) return "Acceso .len";
        return node.getClass().getSimpleName();
    }

    private static String detail(Ast.Node node) {
        if (node instanceof Ast.Program program) return program.functions().size() + " función(es)";
        if (node instanceof Ast.FunctionDecl function) {
            return function.name() + " · " + (function.isPublic() ? "pública" : "privada");
        }
        if (node instanceof Ast.Parameter parameter) return parameter.name();
        if (node instanceof Ast.PrimitiveType type) return type.name();
        if (node instanceof Ast.ArrayType type) return "tamaño " + type.size();
        if (node instanceof Ast.Block block) return block.statements().size() + " sentencia(s)";
        if (node instanceof Ast.VariableDecl declaration) return declaration.name();
        if (node instanceof Ast.AssignmentStmt assignment) return "operador " + assignment.operator();
        if (node instanceof Ast.ForStmt loop) return "captura " + loop.capture();
        if (node instanceof Ast.SwitchCase switchCase) {
            return switchCase.elseCase() ? "alternativa predeterminada" : switchCase.labels().size() + " etiqueta(s)";
        }
        if (node instanceof Ast.IntegerLiteral literal) return literal.image();
        if (node instanceof Ast.FloatLiteral literal) return literal.image();
        if (node instanceof Ast.BooleanLiteral literal) return Boolean.toString(literal.value());
        if (node instanceof Ast.CharLiteral literal) return literal.image();
        if (node instanceof Ast.IdentifierExpr identifier) return identifier.name();
        if (node instanceof Ast.ArrayLiteral literal) {
            String size = literal.explicitSize().map(String::valueOf).orElse("inferido");
            return "tamaño " + size + " · " + literal.elements().size() + " elemento(s)";
        }
        if (node instanceof Ast.BinaryExpr expression) return "operador " + expression.operator();
        if (node instanceof Ast.UnaryExpr expression) return "operador " + expression.operator();
        if (node instanceof Ast.CallExpr call) return call.arguments().size() + " argumento(s)";
        return "";
    }

    public record AstEntry(int number, int depth, String construction, String detail, String location) {
    }

    private static void appendText(Ast.Node node, StringBuilder output, int depth) {
        output.append("  ".repeat(depth)).append(label(node)).append('\n');
        for (Ast.Node child : childNodes(node)) {
            appendText(child, output, depth + 1);
        }
    }

    private static String label(Ast.Node node) {
        StringBuilder label = new StringBuilder(node.getClass().getSimpleName());
        List<String> values = new ArrayList<>();
        for (RecordComponent component : node.getClass().getRecordComponents()) {
            Object value = componentValue(node, component);
            if (value == null || value instanceof Ast.Node || value instanceof Ast.SourceSpan
                    || value instanceof List<?> || value instanceof Optional<?>) {
                continue;
            }
            values.add(component.getName() + "=" + value);
        }
        if (!values.isEmpty()) {
            label.append(" (").append(String.join(", ", values)).append(')');
        }
        label.append(" [").append(node.span().line()).append(':').append(node.span().column()).append(']');
        return label.toString();
    }

    private static List<Ast.Node> childNodes(Ast.Node node) {
        List<Ast.Node> children = new ArrayList<>();
        for (RecordComponent component : node.getClass().getRecordComponents()) {
            Object value = componentValue(node, component);
            if (value instanceof Ast.Node child) {
                children.add(child);
            } else if (value instanceof Optional<?> optional && optional.orElse(null) instanceof Ast.Node child) {
                children.add(child);
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Ast.Node child) {
                        children.add(child);
                    }
                }
            }
        }
        return children;
    }

    private static Object componentValue(Ast.Node node, RecordComponent component) {
        try {
            return component.getAccessor().invoke(node);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("No se pudo recorrer el AST", exception);
        }
    }
}
