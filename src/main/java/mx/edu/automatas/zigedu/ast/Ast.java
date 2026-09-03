package mx.edu.automatas.zigedu.ast;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Nodos sintácticos del subconjunto educativo de Zig. */
public final class Ast {
    private Ast() {
    }

    public record SourceSpan(int line, int column, int endLine, int endColumn) {
        public SourceSpan {
            if (line < 1 || column < 1 || endLine < 1 || endColumn < 1) {
                throw new IllegalArgumentException("Las posiciones del código son de base 1");
            }
        }
    }

    public interface Node {
        SourceSpan span();
    }

    public interface TypeNode extends Node {
    }

    public interface Statement extends Node {
    }

    public interface Expression extends Node {
    }

    public record Program(List<FunctionDecl> functions, SourceSpan span) implements Node {
        public Program {
            functions = List.copyOf(functions);
        }
    }

    public record FunctionDecl(
            boolean isPublic,
            String name,
            List<Parameter> parameters,
            TypeNode returnType,
            Block body,
            SourceSpan span
    ) implements Node {
        public FunctionDecl {
            Objects.requireNonNull(name);
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(returnType);
            Objects.requireNonNull(body);
        }
    }

    public record Parameter(String name, TypeNode type, SourceSpan span) implements Node {
        public Parameter {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
        }
    }

    public record PrimitiveType(String name, SourceSpan span) implements TypeNode {
        public PrimitiveType {
            Objects.requireNonNull(name);
        }
    }

    public record ArrayType(int size, TypeNode elementType, SourceSpan span) implements TypeNode {
        public ArrayType {
            Objects.requireNonNull(elementType);
        }
    }

    public record Block(List<Statement> statements, SourceSpan span) implements Statement {
        public Block {
            statements = List.copyOf(statements);
        }
    }

    public record VariableDecl(
            boolean constant,
            String name,
            Optional<TypeNode> declaredType,
            Expression initializer,
            SourceSpan span
    ) implements Statement {
        public VariableDecl {
            Objects.requireNonNull(name);
            declaredType = Objects.requireNonNull(declaredType);
            Objects.requireNonNull(initializer);
        }
    }

    public record AssignmentStmt(
            Expression target,
            String operator,
            Expression value,
            SourceSpan span
    ) implements Statement {
    }

    public record ExpressionStmt(Expression expression, SourceSpan span) implements Statement {
    }

    public record IfStmt(
            Expression condition,
            Block thenBlock,
            Optional<Statement> elseBranch,
            SourceSpan span
    ) implements Statement {
        public IfStmt {
            elseBranch = Objects.requireNonNull(elseBranch);
        }
    }

    public record WhileStmt(Expression condition, Block body, SourceSpan span) implements Statement {
    }

    public interface ForSource extends Node {
    }

    public record IterableSource(Expression expression, SourceSpan span) implements ForSource {
    }

    public record RangeSource(Expression start, Expression end, SourceSpan span) implements ForSource {
    }

    public record ForStmt(ForSource source, String capture, Block body, SourceSpan span)
            implements Statement {
    }

    public record SwitchStmt(Expression subject, List<SwitchCase> cases, SourceSpan span)
            implements Statement {
        public SwitchStmt {
            cases = List.copyOf(cases);
        }
    }

    public record SwitchCase(
            boolean elseCase,
            List<Expression> labels,
            Block body,
            SourceSpan span
    ) implements Node {
        public SwitchCase {
            labels = List.copyOf(labels);
        }
    }

    public record ReturnStmt(Optional<Expression> value, SourceSpan span) implements Statement {
        public ReturnStmt {
            value = Objects.requireNonNull(value);
        }
    }

    public record BreakStmt(SourceSpan span) implements Statement {
    }

    public record ContinueStmt(SourceSpan span) implements Statement {
    }

    public record IntegerLiteral(String image, SourceSpan span) implements Expression {
    }

    public record FloatLiteral(String image, SourceSpan span) implements Expression {
    }

    public record BooleanLiteral(boolean value, SourceSpan span) implements Expression {
    }

    public record CharLiteral(String image, SourceSpan span) implements Expression {
    }

    public record IdentifierExpr(String name, SourceSpan span) implements Expression {
    }

    public record ArrayLiteral(
            Optional<Integer> explicitSize,
            TypeNode elementType,
            List<Expression> elements,
            SourceSpan span
    ) implements Expression {
        public ArrayLiteral {
            explicitSize = Objects.requireNonNull(explicitSize);
            elements = List.copyOf(elements);
        }
    }

    public record BinaryExpr(
            Expression left,
            String operator,
            Expression right,
            SourceSpan span
    ) implements Expression {
    }

    public record UnaryExpr(String operator, Expression operand, SourceSpan span)
            implements Expression {
    }

    public record CallExpr(Expression callee, List<Expression> arguments, SourceSpan span)
            implements Expression {
        public CallExpr {
            arguments = List.copyOf(arguments);
        }
    }

    public record IndexExpr(Expression target, Expression index, SourceSpan span)
            implements Expression {
    }

    public record LengthExpr(Expression target, SourceSpan span) implements Expression {
    }
}
