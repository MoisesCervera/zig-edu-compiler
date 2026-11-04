package mx.edu.automatas.zigedu.opt;

import mx.edu.automatas.zigedu.analysis.SemanticType;
import mx.edu.automatas.zigedu.ast.Ast.*;

import java.math.BigInteger;
import java.util.*;

import static mx.edu.automatas.zigedu.analysis.SemanticType.*;

/**
 * Optimizador sobre el AST validado: ensamblamiento, código muerto, programación de variables,
 * reducción de potencias y eliminación de subexpresiones redundantes. Las transformaciones conservan
 * el tipo que el analizador semántico asigna a cada expresión.
 */
public final class CodeOptimizer {
    /**
     * @param program  programa optimizado
     * @param verified el mismo programa antes de agregar "_ = llamada;" y "_ = parametro;", que se ejecuta
     *                 para comprobar que el comportamiento no cambió
     */
    public record Output(Program program, Program verified) { }

    private static final int MAX_ROUNDS = 12;
    private static final Set<String> ARITHMETIC = Set.of("+", "-", "*", "/", "%");
    private static final Set<String> COMPARISON = Set.of("<", "<=", ">", ">=", "==", "!=");

    private final Set<String> usedNames = new HashSet<>();
    private final Set<String> deferredDiscards = new HashSet<>();
    private Map<String, FunctionDecl> functions = Map.of();
    private String currentFunction = "";
    private int temporaries;

    public Output optimize(Program program) {
        usedNames.clear();
        deferredDiscards.clear();
        temporaries = 0;
        collectNames(program);
        Map<String, Map<String, Integer>> originalReads = new HashMap<>();
        for (FunctionDecl f : program.functions()) originalReads.put(f.name(), parameterReads(f));

        Program current = program;
        String previous = ZigPrinter.print(current);
        for (int round = 0; round < MAX_ROUNDS; round++) {
            current = hoistPass(deadCodePass(rewritePass(current)));
            String now = ZigPrinter.print(current);
            if (now.equals(previous)) break;
            previous = now;
        }
        current = commonSubexpressionPass(current);
        Program verified = current;
        current = discardCalls(current);
        current = keepParameters(current, originalReads);
        return new Output(current, verified);
    }

    // ---------------------------------------------------------------- tipos y constantes

    record Const(BigInteger integer, Double decimal, Boolean bool) {
        static Const of(BigInteger value) { return new Const(value, null, null); }
        static Const of(double value) { return new Const(null, value, null); }
        static Const of(boolean value) { return new Const(null, null, value); }

        Const as(SemanticType type) {
            if (type.equals(F64) || type.equals(FLOAT_LITERAL)) {
                return new Const(null, integer != null ? integer.doubleValue() : decimal, null);
            }
            return this;
        }

        boolean isPositiveZero() {
            return integer != null ? integer.signum() == 0
                    : decimal != null && Double.doubleToRawLongBits(decimal) == 0L;
        }

        boolean is(int n) {
            return integer != null ? integer.equals(BigInteger.valueOf(n)) : decimal != null && decimal == n;
        }
    }

    private record Folded(Const value, SemanticType type) { }

    static SemanticType typeOf(TypeNode node) {
        if (node instanceof PrimitiveType p) return primitive(p.name());
        ArrayType a = (ArrayType) node;
        return array(new BigInteger(a.size()), typeOf(a.elementType()));
    }

    private static boolean isLiteralType(SemanticType t) { return t.equals(INT_LITERAL) || t.equals(FLOAT_LITERAL); }

    private static SemanticType varType(SemanticType t) {
        if (t.equals(INT_LITERAL)) return I32;
        if (t.equals(FLOAT_LITERAL)) return F64;
        return t;
    }

    private static SemanticType commonNumeric(SemanticType x, SemanticType y) {
        if (!x.isNumeric() || !y.isNumeric()) return ERROR;
        if (x.equals(y)) return x;
        if (x.equals(INT_LITERAL)) return y;
        if (y.equals(INT_LITERAL)) return x;
        if (x.isInteger() && y.isInteger()) return I32;
        if (x.equals(F64) && y.equals(FLOAT_LITERAL) || y.equals(F64) && x.equals(FLOAT_LITERAL)) return F64;
        return ERROR;
    }

    private static SemanticType operandType(String op, SemanticType left, SemanticType right) {
        if (op.equals("and") || op.equals("or")) return BOOL;
        if ((op.equals("==") || op.equals("!=")) && left.equals(BOOL) && right.equals(BOOL)) return BOOL;
        return commonNumeric(left, right);
    }

    private static boolean inRange(BigInteger value, SemanticType type) {
        if (value.bitLength() > 2048) return false;
        if (type.equals(I32)) return value.bitLength() < 32;
        if (type.equals(U8)) return value.signum() >= 0 && value.bitLength() <= 8;
        return true;
    }

    private static Folded fold(String op, Const a, SemanticType ta, Const b, SemanticType tb) {
        if (op.equals("and") || op.equals("or")) {
            if (a.bool() == null || b.bool() == null) return null;
            return new Folded(Const.of(op.equals("and") ? a.bool() && b.bool() : a.bool() || b.bool()), BOOL);
        }
        if ((op.equals("==") || op.equals("!=")) && ta.equals(BOOL) && tb.equals(BOOL)) {
            return new Folded(Const.of(a.bool().equals(b.bool()) == op.equals("==")), BOOL);
        }
        SemanticType common = commonNumeric(ta, tb);
        if (common.isError() || op.equals("%") && !common.isInteger()) return null;
        Const x = a.as(common), y = b.as(common);
        if (COMPARISON.contains(op)) {
            int order;
            if (x.integer() != null && y.integer() != null) order = x.integer().compareTo(y.integer());
            else if (x.decimal() != null && y.decimal() != null) {
                order = x.decimal().doubleValue() == y.decimal().doubleValue() ? 0 : Double.compare(x.decimal(), y.decimal());
            } else return null;
            boolean result = switch (op) {
                case "==" -> order == 0; case "!=" -> order != 0; case "<" -> order < 0;
                case "<=" -> order <= 0; case ">" -> order > 0; default -> order >= 0;
            };
            return new Folded(Const.of(result), BOOL);
        }
        if (x.integer() != null && y.integer() != null) {
            BigInteger p = x.integer(), q = y.integer();
            if ((op.equals("/") || op.equals("%")) && q.signum() == 0) return null;
            BigInteger result = switch (op) {
                case "+" -> p.add(q); case "-" -> p.subtract(q); case "*" -> p.multiply(q);
                case "/" -> p.divide(q); case "%" -> p.remainder(q);
                default -> null;
            };
            if (result == null || !inRange(result, common)) return null;
            return new Folded(Const.of(result), common);
        }
        if (x.decimal() != null && y.decimal() != null) {
            double p = x.decimal(), q = y.decimal();
            if (op.equals("/") && q == 0.0) return null;
            double result = switch (op) {
                case "+" -> p + q; case "-" -> p - q; case "*" -> p * q; case "/" -> p / q;
                default -> Double.NaN;
            };
            if (!Double.isFinite(result)) return null;
            return new Folded(Const.of(result), common);
        }
        return null;
    }

    private static Folded foldUnary(String op, Const value, SemanticType type) {
        if (op.equals("!")) return value.bool() == null ? null : new Folded(Const.of(!value.bool()), BOOL);
        if (!type.isNumeric() || type.equals(U8)) return null;
        if (value.integer() != null) {
            BigInteger result = value.integer().negate();
            return inRange(result, type) ? new Folded(Const.of(result), type) : null;
        }
        return value.decimal() == null ? null : new Folded(Const.of(-value.decimal()), type);
    }

    static Const literalValue(Expression e) {
        if (e instanceof IntegerLiteral i) return Const.of(new BigInteger(i.image()));
        if (e instanceof FloatLiteral f) return Const.of(Double.parseDouble(f.image()));
        if (e instanceof BooleanLiteral b) return Const.of(b.value());
        if (e instanceof CharLiteral c) {
            String body = c.image().substring(1, c.image().length() - 1);
            int value = body.charAt(0) == '\\' ? switch (body.charAt(1)) {
                case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                case '\\' -> '\\'; case '\'' -> '\''; default -> 0;
            } : body.codePointAt(0);
            return Const.of(BigInteger.valueOf(value));
        }
        if (e instanceof UnaryExpr u && u.operator().equals("-")) {
            Const inner = literalValue(u.operand());
            if (inner == null || inner.bool() != null) return null;
            return inner.integer() != null ? Const.of(inner.integer().negate()) : Const.of(-inner.decimal());
        }
        return null;
    }

    private static SemanticType literalType(Expression e) {
        if (e instanceof BooleanLiteral) return BOOL;
        if (e instanceof FloatLiteral) return FLOAT_LITERAL;
        if (e instanceof IntegerLiteral || e instanceof CharLiteral) return INT_LITERAL;
        if (e instanceof UnaryExpr u) return literalType(u.operand());
        return null;
    }

    private static SemanticType literalTypeOf(Expression e, SemanticType fallback) {
        SemanticType t = literalType(e);
        return t == null ? fallback : t;
    }

    private static boolean isLiteral(Expression e) {
        return e instanceof IntegerLiteral || e instanceof FloatLiteral || e instanceof BooleanLiteral
                || e instanceof CharLiteral
                || e instanceof UnaryExpr u && u.operator().equals("-")
                && (u.operand() instanceof IntegerLiteral || u.operand() instanceof FloatLiteral);
    }

    static String floatImage(double value) {
        return Double.toString(value).replace('E', 'e');
    }

    static Expression literal(Const value, SourceSpan span) {
        if (value.bool() != null) return new BooleanLiteral(value.bool(), span);
        if (value.decimal() != null) {
            double v = value.decimal();
            boolean negative = v < 0 || v == 0.0 && Double.doubleToRawLongBits(v) != 0L;
            Expression magnitude = new FloatLiteral(floatImage(Math.abs(v)), span);
            return negative ? new UnaryExpr("-", magnitude, span) : magnitude;
        }
        BigInteger v = value.integer();
        Expression magnitude = new IntegerLiteral(v.abs().toString(), span);
        return v.signum() < 0 ? new UnaryExpr("-", magnitude, span) : magnitude;
    }

    private static Expression withSpan(Expression literal, SourceSpan span) {
        if (literal instanceof IntegerLiteral i) return new IntegerLiteral(i.image(), span);
        if (literal instanceof FloatLiteral f) return new FloatLiteral(f.image(), span);
        if (literal instanceof BooleanLiteral b) return new BooleanLiteral(b.value(), span);
        if (literal instanceof CharLiteral c) return new CharLiteral(c.image(), span);
        if (literal instanceof UnaryExpr u) return new UnaryExpr(u.operator(), withSpan(u.operand(), span), span);
        return literal;
    }

    private static TypeNode typeNode(SemanticType type, SourceSpan span) {
        if (type.isArray()) return new ArrayType(type.size().toString(), typeNode(type.element(), span), span);
        return new PrimitiveType(type.name(), span);
    }

    private static Double exactReciprocal(Const value) {
        double v = value.decimal() != null ? value.decimal() : value.integer() == null ? 0 : value.integer().doubleValue();
        if (!(v > 0) || v == 1.0 || !Double.isFinite(v)) return null;
        int exponent = Math.getExponent(v);
        if (Math.abs(exponent) > 1000 || v != Math.scalb(1.0, exponent)) return null;
        return Math.scalb(1.0, -exponent);
    }

    // ---------------------------------------------------------------- utilidades del AST

    static boolean pure(Expression e) {
        if (e instanceof CallExpr) return false;
        if (e instanceof BinaryExpr b) return pure(b.left()) && pure(b.right());
        if (e instanceof UnaryExpr u) return pure(u.operand());
        if (e instanceof IndexExpr i) return pure(i.target()) && pure(i.index());
        if (e instanceof LengthExpr l) return pure(l.target());
        if (e instanceof ArrayLiteral a) return a.elements().stream().allMatch(CodeOptimizer::pure);
        return true;
    }

    private static boolean simple(Statement s) {
        return s instanceof VariableDecl || s instanceof AssignmentStmt || s instanceof ExpressionStmt;
    }

    private static boolean terminates(Statement s) {
        return s instanceof ReturnStmt || s instanceof BreakStmt || s instanceof ContinueStmt;
    }

    private static String rootName(Expression target) {
        Expression root = target;
        while (root instanceof IndexExpr i) root = i.target();
        return root instanceof IdentifierExpr id ? id.name() : null;
    }

    private static boolean mentions(Statement s, String name) {
        Set<String> names = new HashSet<>();
        names(s, names);
        return names.contains(name);
    }

    private static boolean mentions(Expression e, String name) {
        Set<String> names = new HashSet<>();
        names(e, names);
        return names.contains(name);
    }

    private static void names(Expression e, Set<String> out) {
        if (e instanceof IdentifierExpr id) out.add(id.name());
        else if (e instanceof BinaryExpr b) { names(b.left(), out); names(b.right(), out); }
        else if (e instanceof UnaryExpr u) names(u.operand(), out);
        else if (e instanceof CallExpr c) c.arguments().forEach(a -> names(a, out));
        else if (e instanceof IndexExpr i) { names(i.target(), out); names(i.index(), out); }
        else if (e instanceof LengthExpr l) names(l.target(), out);
        else if (e instanceof ArrayLiteral a) a.elements().forEach(x -> names(x, out));
    }

    private static void names(Statement s, Set<String> out) {
        if (s instanceof VariableDecl d) names(d.initializer(), out);
        else if (s instanceof AssignmentStmt a) { names(a.target(), out); names(a.value(), out); }
        else if (s instanceof ExpressionStmt e) names(e.expression(), out);
        else if (s instanceof ReturnStmt r) r.value().ifPresent(v -> names(v, out));
        else if (s instanceof Block b) b.statements().forEach(x -> names(x, out));
        else if (s instanceof IfStmt i) {
            names(i.condition(), out); names(i.thenBlock(), out); i.elseBranch().ifPresent(x -> names(x, out));
        } else if (s instanceof WhileStmt w) { names(w.condition(), out); names(w.body(), out); }
        else if (s instanceof ForStmt f) {
            if (f.source() instanceof RangeSource r) { names(r.start(), out); names(r.end(), out); }
            else names(((IterableSource) f.source()).expression(), out);
            names(f.body(), out);
        } else if (s instanceof SwitchStmt sw) {
            names(sw.subject(), out);
            for (SwitchCase c : sw.cases()) names(c.body(), out);
        }
    }

    private static void assigned(Statement s, Set<String> out) {
        if (s instanceof AssignmentStmt a) {
            String root = rootName(a.target());
            if (root != null && !root.equals("_")) out.add(root);
        } else if (s instanceof Block b) b.statements().forEach(x -> assigned(x, out));
        else if (s instanceof IfStmt i) { assigned(i.thenBlock(), out); i.elseBranch().ifPresent(x -> assigned(x, out)); }
        else if (s instanceof WhileStmt w) assigned(w.body(), out);
        else if (s instanceof ForStmt f) assigned(f.body(), out);
        else if (s instanceof SwitchStmt sw) sw.cases().forEach(c -> assigned(c.body(), out));
    }

    private static boolean containsOperation(Expression e) {
        return e instanceof BinaryExpr || e instanceof UnaryExpr u && !isLiteral(u) || e instanceof IndexExpr
                || e instanceof LengthExpr l && containsOperation(l.target());
    }

    private void collectNames(Program program) {
        for (FunctionDecl f : program.functions()) {
            usedNames.add(f.name());
            f.parameters().forEach(p -> usedNames.add(p.name()));
            collectDeclared(f.body(), usedNames);
            names(f.body(), usedNames);
        }
    }

    private static void collectDeclared(Statement s, Collection<String> out) {
        if (s instanceof VariableDecl d) out.add(d.name());
        else if (s instanceof Block b) b.statements().forEach(x -> collectDeclared(x, out));
        else if (s instanceof IfStmt i) { collectDeclared(i.thenBlock(), out); i.elseBranch().ifPresent(x -> collectDeclared(x, out)); }
        else if (s instanceof WhileStmt w) collectDeclared(w.body(), out);
        else if (s instanceof ForStmt f) { if (!f.capture().equals("_")) out.add(f.capture()); collectDeclared(f.body(), out); }
        else if (s instanceof SwitchStmt sw) sw.cases().forEach(c -> collectDeclared(c.body(), out));
    }

    private static String code(Expression e) { return ZigPrinter.expression(e); }

    // ---------------------------------------------------------------- lecturas y escrituras por declaración

    enum Kind { PARAM, CONST, VAR, CAPTURE }

    static final class Info {
        final Kind kind;
        final Node declaration;
        final boolean protectedOutput;
        int reads;
        boolean mutated;
        boolean impureStore;
        final List<AssignmentStmt> stores = new ArrayList<>();

        Info(Kind kind, Node declaration, boolean protectedOutput) {
            this.kind = kind;
            this.declaration = declaration;
            this.protectedOutput = protectedOutput;
        }
    }

    /** Las variables declaradas directamente en main se consideran el resultado del programa. */
    static final class Usage {
        final Map<Node, Info> byDeclaration = new IdentityHashMap<>();
        final List<Info> ordered = new ArrayList<>();
        private final Deque<Map<String, Info>> scopes = new ArrayDeque<>();
        private Info writing;

        Usage(FunctionDecl f) {
            scopes.push(new HashMap<>());
            for (Parameter p : f.parameters()) declare(p.name(), Kind.PARAM, p, false);
            boolean main = f.name().equals("main");
            for (Statement s : f.body().statements()) statement(s, main);
            scopes.pop();
        }

        Info of(Node declaration) { return byDeclaration.get(declaration); }

        private void declare(String name, Kind kind, Node declaration, boolean protectedOutput) {
            Info info = new Info(kind, declaration, protectedOutput);
            byDeclaration.put(declaration, info);
            ordered.add(info);
            scopes.peek().put(name, info);
        }

        private Info lookup(String name) {
            for (Map<String, Info> scope : scopes) if (scope.containsKey(name)) return scope.get(name);
            return null;
        }

        private void block(Block b) {
            scopes.push(new HashMap<>());
            for (Statement s : b.statements()) statement(s, false);
            scopes.pop();
        }

        private void statement(Statement s, boolean topOfMain) {
            if (s instanceof VariableDecl d) {
                expression(d.initializer());
                declare(d.name(), d.constant() ? Kind.CONST : Kind.VAR, d, topOfMain);
            } else if (s instanceof AssignmentStmt a) {
                if (a.target() instanceof IdentifierExpr id && id.name().equals("_")) {
                    expression(a.value());
                    return;
                }
                String root = rootName(a.target());
                Info target = root == null ? null : lookup(root);
                writing = target;
                if (target != null) {
                    target.mutated = true;
                    target.stores.add(a);
                    if (!pure(a.value()) || !pure(a.target())) target.impureStore = true;
                }
                Expression t = a.target();
                while (t instanceof IndexExpr i) { expression(i.index()); t = i.target(); }
                if (!(t instanceof IdentifierExpr)) expression(t);
                expression(a.value());
                writing = null;
            } else if (s instanceof ExpressionStmt e) {
                expression(e.expression());
            } else if (s instanceof IfStmt i) {
                expression(i.condition());
                block(i.thenBlock());
                i.elseBranch().ifPresent(other -> {
                    if (other instanceof Block b) block(b); else statement(other, false);
                });
            } else if (s instanceof WhileStmt w) {
                expression(w.condition());
                block(w.body());
            } else if (s instanceof ForStmt f) {
                if (f.source() instanceof RangeSource r) { expression(r.start()); expression(r.end()); }
                else expression(((IterableSource) f.source()).expression());
                scopes.push(new HashMap<>());
                if (!f.capture().equals("_")) declare(f.capture(), Kind.CAPTURE, f, false);
                for (Statement x : f.body().statements()) statement(x, false);
                scopes.pop();
            } else if (s instanceof SwitchStmt sw) {
                expression(sw.subject());
                for (SwitchCase c : sw.cases()) block(c.body());
            } else if (s instanceof ReturnStmt r) {
                r.value().ifPresent(this::expression);
            } else if (s instanceof Block b) {
                block(b);
            }
        }

        private void expression(Expression e) {
            if (e instanceof IdentifierExpr id) {
                Info info = lookup(id.name());
                if (info != null && info != writing) info.reads++;
            } else if (e instanceof BinaryExpr b) { expression(b.left()); expression(b.right()); }
            else if (e instanceof UnaryExpr u) expression(u.operand());
            else if (e instanceof CallExpr c) c.arguments().forEach(this::expression);
            else if (e instanceof IndexExpr i) { expression(i.target()); expression(i.index()); }
            else if (e instanceof LengthExpr l) expression(l.target());
            else if (e instanceof ArrayLiteral a) a.elements().forEach(this::expression);
        }
    }

    private static Map<String, Integer> parameterReads(FunctionDecl f) {
        Usage usage = new Usage(f);
        Map<String, Integer> reads = new HashMap<>();
        for (Parameter p : f.parameters()) reads.put(p.name(), usage.of(p).reads);
        return reads;
    }

    // ---------------------------------------------------------------- ensamblamiento, propagación y reducción

    private Program rewritePass(Program program) {
        functions = new LinkedHashMap<>();
        for (FunctionDecl f : program.functions()) functions.put(f.name(), f);
        List<FunctionDecl> result = new ArrayList<>();
        for (FunctionDecl f : program.functions()) result.add(new Rewriter(f, new Usage(f)).run());
        return new Program(result, program.span());
    }

    private static final class Binding {
        final String name;
        final Kind kind;
        final SemanticType type;
        Const value;
        Expression literalNode;
        Binding alias;

        Binding(String name, Kind kind, SemanticType type) {
            this.name = name;
            this.kind = kind;
            this.type = type;
        }

        boolean immutable() { return kind != Kind.VAR; }
    }

    /**
     * @param expr  forma preferida (literal si el valor es constante)
     * @param type  tipo de la expresión original
     * @param value valor constante, si se conoce en compilación
     * @param typed forma con exactamente el tipo {@code type}, para cuando el literal cambiaría el tipo
     */
    private record R(Expression expr, SemanticType type, Const value, Expression typed) {
        static R plain(Expression e, SemanticType type) { return new R(e, type, null, e); }
        boolean constant() { return value != null; }
        SemanticType literalType() { return constant() ? literalTypeOf(expr, type) : type; }
        Expression typedForm() { return constant() ? typed : expr; }
    }

    private final class Rewriter {
        private final FunctionDecl function;
        private final Usage usage;
        private final Deque<Map<String, Binding>> scopes = new ArrayDeque<>();

        Rewriter(FunctionDecl function, Usage usage) {
            this.function = function;
            this.usage = usage;
        }

        FunctionDecl run() {
            scopes.push(new HashMap<>());
            for (Parameter p : function.parameters()) declare(new Binding(p.name(), Kind.PARAM, typeOf(p.type())));
            List<Statement> body = new ArrayList<>(statements(function.body().statements()));
            scopes.pop();
            if (typeOf(function.returnType()).equals(VOID) && !body.isEmpty()
                    && body.getLast() instanceof ReturnStmt r && r.value().isEmpty()) {
                body.removeLast();
            }
            return new FunctionDecl(function.isPublic(), function.name(), function.parameters(),
                    function.returnType(), new Block(body, function.body().span()), function.span());
        }

        private void declare(Binding b) { scopes.peek().put(b.name, b); }

        private Binding lookup(String name) {
            for (Map<String, Binding> scope : scopes) if (scope.containsKey(name)) return scope.get(name);
            return null;
        }

        private List<Statement> statements(List<Statement> input) {
            List<Statement> out = new ArrayList<>();
            for (Statement s : input) {
                out.addAll(statement(s));
                if (!out.isEmpty() && terminates(out.getLast())) break;
            }
            removeOverwrittenStores(out);
            return out;
        }

        private List<Statement> statement(Statement s) {
            if (s instanceof VariableDecl d) return List.of(declaration(d));
            if (s instanceof AssignmentStmt a) return assignment(a);
            if (s instanceof ExpressionStmt e) return List.of(new ExpressionStmt(expr(e.expression()).expr(), e.span()));
            if (s instanceof IfStmt i) return ifStatement(i);
            if (s instanceof WhileStmt w) return whileStatement(w);
            if (s instanceof ForStmt f) return forStatement(f);
            if (s instanceof SwitchStmt sw) return switchStatement(sw);
            if (s instanceof ReturnStmt r) return List.of(new ReturnStmt(r.value().map(v -> expr(v).expr()), r.span()));
            if (s instanceof Block b) return inline(b);
            return List.of(s);
        }

        private List<Statement> inline(Block b) {
            scopes.push(new HashMap<>());
            List<Statement> list = statements(b.statements());
            scopes.pop();
            if (list.stream().anyMatch(x -> x instanceof VariableDecl)) return List.of(new Block(list, b.span()));
            return list;
        }

        private Block block(Block b) {
            scopes.push(new HashMap<>());
            List<Statement> list = statements(b.statements());
            scopes.pop();
            return new Block(list, b.span());
        }

        private Statement declaration(VariableDecl d) {
            R init = expr(d.initializer());
            boolean constant = d.constant();
            Optional<TypeNode> declared = d.declaredType();
            Info info = usage.of(d);
            if (!constant && info != null && !info.mutated) {
                constant = true;
                if (declared.isEmpty() && isLiteralType(init.type())) {
                    declared = Optional.of(typeNode(varType(init.type()), d.span()));
                }
            }
            SemanticType bindType = declared.map(CodeOptimizer::typeOf)
                    .orElse(constant ? init.type() : varType(init.type()));
            Expression initializer = init.expr();
            if (declared.isEmpty() && init.constant() && !bindType.isArray()) {
                SemanticType now = literalTypeOf(initializer, init.type());
                SemanticType effective = constant ? now : varType(now);
                if (!effective.equals(bindType) || !constant && !isLiteral(d.initializer())) {
                    declared = Optional.of(typeNode(bindType, d.span()));
                }
            }
            Binding binding = new Binding(d.name(), constant ? Kind.CONST : Kind.VAR, bindType);
            if (constant && init.constant() && !bindType.isArray()) {
                binding.value = init.value().as(bindType);
                boolean floatBinding = bindType.equals(F64) || bindType.equals(FLOAT_LITERAL);
                if (isLiteral(initializer) && floatBinding == FLOAT_LITERAL.equals(literalType(initializer))) {
                    binding.literalNode = initializer;
                }
            } else if (constant && initializer instanceof IdentifierExpr id) {
                Binding target = lookup(id.name());
                if (target != null && target.immutable() && target.type.equals(bindType)) binding.alias = target;
            }
            declare(binding);
            return new VariableDecl(constant, d.name(), declared, initializer, d.span());
        }

        private SemanticType targetType(Expression target) {
            if (target instanceof IdentifierExpr id) {
                Binding b = lookup(id.name());
                return b == null ? ERROR : b.type;
            }
            if (target instanceof IndexExpr i) {
                SemanticType base = targetType(i.target());
                return base.isArray() ? base.element() : ERROR;
            }
            return ERROR;
        }

        private Expression rewriteTarget(Expression target) {
            if (target instanceof IndexExpr i) return new IndexExpr(rewriteTarget(i.target()), expr(i.index()).expr(), i.span());
            return target;
        }

        private List<Statement> assignment(AssignmentStmt a) {
            if (a.target() instanceof IdentifierExpr id && id.name().equals("_")) {
                return List.of(new AssignmentStmt(a.target(), "=", expr(a.value()).expr(), a.span()));
            }
            Expression target = rewriteTarget(a.target());
            SemanticType type = targetType(a.target());
            R value = expr(a.value());
            String op = a.operator();
            if (pure(target) && type.isNumeric() && value.constant()) {
                boolean additive = (op.equals("+=") || op.equals("-=")) && type.isInteger() && value.value().isPositiveZero();
                boolean multiplicative = (op.equals("*=") || op.equals("/=")) && value.value().is(1);
                if (additive || multiplicative) return List.of();
            }
            if (op.equals("=") && target instanceof IdentifierExpr t && value.expr() instanceof IdentifierExpr v
                    && t.name().equals(v.name())) {
                return List.of();
            }
            if (target instanceof IdentifierExpr t && type.isNumeric() && value.constant()) {
                if (op.equals("*=") && value.value().is(2)) {
                    return List.of(new AssignmentStmt(target, "+=", new IdentifierExpr(t.name(), t.span()), a.span()));
                }
                Double reciprocal = exactReciprocal(value.value());
                if (op.equals("/=") && type.equals(F64) && reciprocal != null) {
                    return List.of(new AssignmentStmt(target, "*=",
                            new FloatLiteral(floatImage(reciprocal), a.value().span()), a.span()));
                }
            }
            return List.of(new AssignmentStmt(target, op, value.expr(), a.span()));
        }

        private List<Statement> ifStatement(IfStmt i) {
            R condition = expr(i.condition());
            if (condition.constant() && condition.value().bool() != null) {
                if (condition.value().bool()) return inline(i.thenBlock());
                if (i.elseBranch().isEmpty()) return List.of();
                Statement other = i.elseBranch().get();
                return other instanceof IfStmt nested ? ifStatement(nested) : inline((Block) other);
            }
            Block then = block(i.thenBlock());
            Optional<Statement> other = i.elseBranch().flatMap(this::elseBranch);
            Expression test = condition.expr();
            if (then.statements().isEmpty() && other.isEmpty() && pure(test)) return List.of();
            return List.of(new IfStmt(test, then, other, i.span()));
        }

        private Optional<Statement> elseBranch(Statement other) {
            if (other instanceof IfStmt nested) {
                List<Statement> list = ifStatement(nested);
                if (list.isEmpty()) return Optional.empty();
                if (list.size() == 1 && (list.getFirst() instanceof IfStmt || list.getFirst() instanceof Block)) {
                    return Optional.of(list.getFirst());
                }
                return Optional.of(new Block(list, other.span()));
            }
            Block b = block((Block) other);
            return b.statements().isEmpty() ? Optional.empty() : Optional.of(b);
        }

        private List<Statement> whileStatement(WhileStmt w) {
            R condition = expr(w.condition());
            if (condition.constant() && Boolean.FALSE.equals(condition.value().bool())) return List.of();
            return List.of(new WhileStmt(condition.expr(), block(w.body()), w.span()));
        }

        private List<Statement> forStatement(ForStmt f) {
            ForSource source;
            SemanticType captureType;
            if (f.source() instanceof RangeSource r) {
                R start = expr(r.start()), end = expr(r.end());
                if (start.constant() && end.constant() && start.value().integer() != null && end.value().integer() != null
                        && start.value().integer().compareTo(end.value().integer()) >= 0) {
                    return List.of();
                }
                source = new RangeSource(start.expr(), end.expr(), r.span());
                captureType = I32;
            } else {
                IterableSource it = (IterableSource) f.source();
                R iterable = expr(it.expression());
                if (iterable.type().isArray() && iterable.type().size().signum() == 0 && pure(iterable.expr())) {
                    return List.of();
                }
                source = new IterableSource(iterable.expr(), it.span());
                captureType = iterable.type().isArray() ? iterable.type().element() : ERROR;
            }
            scopes.push(new HashMap<>());
            if (!f.capture().equals("_")) declare(new Binding(f.capture(), Kind.CAPTURE, captureType));
            List<Statement> body = statements(f.body().statements());
            scopes.pop();
            boolean pureSource = source instanceof RangeSource rs ? pure(rs.start()) && pure(rs.end())
                    : pure(((IterableSource) source).expression());
            if (body.isEmpty() && pureSource) return List.of();
            return List.of(new ForStmt(source, f.capture(), new Block(body, f.body().span()), f.span()));
        }

        private List<Statement> switchStatement(SwitchStmt sw) {
            R subject = expr(sw.subject());
            if (subject.constant()) {
                SwitchCase chosen = null;
                for (SwitchCase c : sw.cases()) {
                    if (c.elseCase()) continue;
                    for (Expression label : c.labels()) {
                        Const v = literalValue(label);
                        if (v != null && sameValue(subject.value(), v)) { chosen = c; break; }
                    }
                    if (chosen != null) break;
                }
                if (chosen == null) chosen = sw.cases().stream().filter(SwitchCase::elseCase).findFirst().orElse(null);
                if (chosen != null) return inline(chosen.body());
            }
            List<SwitchCase> cases = new ArrayList<>();
            for (SwitchCase c : sw.cases()) cases.add(new SwitchCase(c.elseCase(), c.labels(), block(c.body()), c.span()));
            return List.of(new SwitchStmt(subject.expr(), cases, sw.span()));
        }

        private boolean sameValue(Const a, Const b) {
            if (a.bool() != null || b.bool() != null) return Objects.equals(a.bool(), b.bool());
            return a.integer() != null && b.integer() != null && a.integer().equals(b.integer());
        }

        /** {@code x = a; x = b;} → {@code x = b;} y {@code var x = a; x = b;} → {@code var x = b;}. */
        private void removeOverwrittenStores(List<Statement> list) {
            for (int i = 0; i < list.size(); i++) {
                Statement s = list.get(i);
                String name;
                Expression first;
                if (s instanceof AssignmentStmt a && a.operator().equals("=") && a.target() instanceof IdentifierExpr id
                        && !id.name().equals("_")) {
                    name = id.name();
                    first = a.value();
                } else if (s instanceof VariableDecl d && !d.constant()) {
                    name = d.name();
                    first = d.initializer();
                } else continue;
                if (!pure(first)) continue;
                int limit = s instanceof VariableDecl ? i + 1 : list.size() - 1;
                for (int j = i + 1; j <= limit && j < list.size(); j++) {
                    Statement next = list.get(j);
                    if (next instanceof AssignmentStmt b && b.operator().equals("=")
                            && b.target() instanceof IdentifierExpr t && t.name().equals(name)
                            && !mentions(b.value(), name)) {
                        if (s instanceof VariableDecl d) {
                            Binding binding = lookup(name);
                            Optional<TypeNode> type = d.declaredType();
                            if (type.isEmpty() && binding != null && !binding.type.isError()) {
                                type = Optional.of(typeNode(binding.type, d.span()));
                            }
                            list.set(i, new VariableDecl(false, name, type, b.value(), d.span()));
                            list.remove(j);
                        } else {
                            list.remove(i);
                        }
                        i = Math.max(-1, i - 1);
                        break;
                    }
                    if (!simple(next) || mentions(next, name)) break;
                }
            }
        }

        private R expr(Expression e) {
            if (isLiteral(e)) return new R(e, literalType(e), literalValue(e), e);
            if (e instanceof IdentifierExpr id) return identifier(id);
            if (e instanceof BinaryExpr b) return binary(b);
            if (e instanceof UnaryExpr u) return unary(u);
            if (e instanceof CallExpr c) {
                List<Expression> arguments = c.arguments().stream().map(x -> expr(x).expr()).toList();
                SemanticType type = ERROR;
                if (c.callee() instanceof IdentifierExpr callee && functions.containsKey(callee.name())) {
                    type = typeOf(functions.get(callee.name()).returnType());
                }
                return R.plain(new CallExpr(c.callee(), arguments, c.span()), type);
            }
            if (e instanceof IndexExpr i) {
                R target = expr(i.target());
                SemanticType type = target.type().isArray() ? target.type().element() : ERROR;
                return R.plain(new IndexExpr(target.typedForm(), expr(i.index()).expr(), i.span()), type);
            }
            if (e instanceof LengthExpr l) {
                R target = expr(l.target());
                if (target.type().isArray() && pure(target.expr())) {
                    Const size = Const.of(target.type().size());
                    return new R(literal(size, l.span()), INT_LITERAL, size, new LengthExpr(target.typedForm(), l.span()));
                }
                return R.plain(new LengthExpr(target.typedForm(), l.span()), INT_LITERAL);
            }
            if (e instanceof ArrayLiteral a) {
                List<Expression> items = a.elements().stream().map(x -> expr(x).expr()).toList();
                BigInteger size = a.explicitSize().map(BigInteger::new).orElse(BigInteger.valueOf(items.size()));
                return R.plain(new ArrayLiteral(a.explicitSize(), a.elementType(), items, a.span()),
                        array(size, typeOf(a.elementType())));
            }
            return R.plain(e, ERROR);
        }

        private R identifier(IdentifierExpr id) {
            Binding b = lookup(id.name());
            if (b == null) return R.plain(id, ERROR);
            if (b.value != null) {
                Expression lit = b.literalNode != null ? withSpan(b.literalNode, id.span()) : literal(b.value, id.span());
                return new R(lit, b.type, b.value, id);
            }
            if (b.alias != null) return R.plain(new IdentifierExpr(b.alias.name, id.span()), b.type);
            return R.plain(id, b.type);
        }

        private R unary(UnaryExpr u) {
            R operand = expr(u.operand());
            SemanticType type = u.operator().equals("!") ? BOOL : operand.type();
            if (operand.constant()) {
                Folded f = foldUnary(u.operator(), operand.value(), operand.type());
                if (f != null) {
                    return new R(literal(f.value(), u.span()), f.type(), f.value(),
                            new UnaryExpr(u.operator(), operand.typedForm(), u.span()));
                }
            }
            if (operand.expr() instanceof UnaryExpr inner && inner.operator().equals(u.operator()) && !operand.constant()) {
                return R.plain(inner.operand(), type);
            }
            return R.plain(new UnaryExpr(u.operator(), operand.expr(), u.span()), type);
        }

        private R binary(BinaryExpr b) {
            String op = b.operator();
            if (op.equals("and") || op.equals("or")) return logical(b);
            R left = expr(b.left()), right = expr(b.right());
            SemanticType common = operandType(op, left.type(), right.type());
            SemanticType type = COMPARISON.contains(op) ? BOOL : common;
            if (common.isError()) return R.plain(new BinaryExpr(left.typedForm(), op, right.typedForm(), b.span()), type);
            if (left.constant() && right.constant()) {
                Folded f = fold(op, left.value(), left.type(), right.value(), right.type());
                if (f != null) {
                    return new R(literal(f.value(), b.span()), f.type(), f.value(),
                            new BinaryExpr(left.typedForm(), op, right.typedForm(), b.span()));
                }
            }
            boolean leftLiteral = left.constant(), rightLiteral = right.constant();
            if (!operandType(op, leftLiteral ? left.literalType() : left.type(),
                    rightLiteral ? right.literalType() : right.type()).equals(common)) {
                leftLiteral = false;
                if (!operandType(op, left.type(), rightLiteral ? right.literalType() : right.type()).equals(common)) {
                    rightLiteral = false;
                }
            }
            Expression le = leftLiteral ? left.expr() : left.typedForm();
            Expression re = rightLiteral ? right.expr() : right.typedForm();
            SemanticType lt = leftLiteral ? left.literalType() : left.type();
            SemanticType rt = rightLiteral ? right.literalType() : right.type();
            if (ARITHMETIC.contains(op) && type.isNumeric()) {
                R simplified = simplify(b, left, right, le, re, lt, rt, type);
                if (simplified != null) return simplified;
            }
            return R.plain(new BinaryExpr(le, op, re, b.span()), type);
        }

        private R simplify(BinaryExpr b, R left, R right, Expression le, Expression re,
                           SemanticType lt, SemanticType rt, SemanticType type) {
            Const l = left.value(), r = right.value();
            boolean integer = type.isInteger();
            switch (b.operator()) {
                case "+" -> {
                    if (integer && r != null && r.isPositiveZero() && lt.equals(type)) return R.plain(le, type);
                    if (integer && l != null && l.isPositiveZero() && rt.equals(type)) return R.plain(re, type);
                }
                case "-" -> {
                    if (r != null && r.isPositiveZero() && lt.equals(type)) return R.plain(le, type);
                    if (integer && l == null && r == null && pure(le) && code(le).equals(code(re))) return zero(b, le, re, type);
                }
                case "*" -> {
                    if (r != null && r.is(1) && lt.equals(type)) return R.plain(le, type);
                    if (l != null && l.is(1) && rt.equals(type)) return R.plain(re, type);
                    if (integer && r != null && r.isPositiveZero() && pure(le)) return zero(b, le, re, type);
                    if (integer && l != null && l.isPositiveZero() && pure(re)) return zero(b, le, re, type);
                    if (r != null && r.is(2) && le instanceof IdentifierExpr x && lt.equals(type)) return doubled(b, x, type);
                    if (l != null && l.is(2) && re instanceof IdentifierExpr x && rt.equals(type)) return doubled(b, x, type);
                }
                case "/" -> {
                    if (r != null && r.is(1) && lt.equals(type)) return R.plain(le, type);
                    Double reciprocal = r == null ? null : exactReciprocal(r);
                    if (type.equals(F64) && reciprocal != null && lt.equals(F64) && l == null) {
                        return R.plain(new BinaryExpr(le, "*", new FloatLiteral(floatImage(reciprocal), b.right().span()), b.span()), type);
                    }
                }
                case "%" -> {
                    if (integer && r != null && r.is(1) && pure(le)) return zero(b, le, re, type);
                }
                default -> { }
            }
            return null;
        }

        private R zero(BinaryExpr b, Expression le, Expression re, SemanticType type) {
            Const value = Const.of(BigInteger.ZERO).as(type);
            return new R(literal(value, b.span()), type, value, new BinaryExpr(le, b.operator(), re, b.span()));
        }

        private R doubled(BinaryExpr b, IdentifierExpr x, SemanticType type) {
            return R.plain(new BinaryExpr(x, "+", new IdentifierExpr(x.name(), x.span()), b.span()), type);
        }

        private R logical(BinaryExpr b) {
            boolean and = b.operator().equals("and");
            R left = expr(b.left()), right = expr(b.right());
            BinaryExpr typedForm = new BinaryExpr(left.typedForm(), b.operator(), right.typedForm(), b.span());
            if (left.constant() && right.constant()) {
                Folded f = fold(b.operator(), left.value(), BOOL, right.value(), BOOL);
                if (f != null) return new R(literal(f.value(), b.span()), BOOL, f.value(), typedForm);
            }
            if (left.constant() && left.value().bool() != null) {
                boolean v = left.value().bool();
                if (v == and) return right;
                return new R(literal(Const.of(v), b.span()), BOOL, Const.of(v), typedForm);
            }
            if (right.constant() && right.value().bool() != null) {
                boolean v = right.value().bool();
                if (v == and) return left;
                if (pure(left.expr())) return new R(literal(Const.of(v), b.span()), BOOL, Const.of(v), typedForm);
            }
            return R.plain(new BinaryExpr(left.expr(), b.operator(), right.expr(), b.span()), BOOL);
        }
    }

    // ---------------------------------------------------------------- código muerto

    private Program deadCodePass(Program program) {
        List<FunctionDecl> kept = new ArrayList<>(program.functions());
        if (program.functions().stream().anyMatch(f -> f.name().equals("main"))) {
            Set<String> reachable = reachableFunctions(program);
            kept.removeIf(f -> !reachable.contains(f.name()));
        }
        List<FunctionDecl> result = new ArrayList<>();
        for (FunctionDecl f : kept) {
            currentFunction = f.name();
            Usage usage = new Usage(f);
            Set<Node> deadDeclarations = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Statement> deadStores = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Node> unusedCaptures = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Info info : usage.ordered) {
                if ((info.kind == Kind.CONST || info.kind == Kind.VAR) && info.reads == 0
                        && !info.protectedOutput && !info.impureStore) {
                    VariableDecl d = (VariableDecl) info.declaration;
                    if (pure(d.initializer())) deadDeclarations.add(d);
                    else deferredDiscards.add(deferredKey(d));
                    deadStores.addAll(info.stores);
                } else if (info.kind == Kind.CAPTURE && info.reads == 0) {
                    unusedCaptures.add(info.declaration);
                }
            }
            if (deadDeclarations.isEmpty() && deadStores.isEmpty() && unusedCaptures.isEmpty()) {
                result.add(f);
                continue;
            }
            Block body = removeDead(f.body(), deadDeclarations, deadStores, unusedCaptures);
            result.add(new FunctionDecl(f.isPublic(), f.name(), f.parameters(), f.returnType(), body, f.span()));
        }
        return new Program(result, program.span());
    }

    private String deferredKey(VariableDecl d) {
        return currentFunction + ":" + d.name() + ":" + d.span().line() + ":" + d.span().column();
    }

    /** Una declaración sin uso cuyo inicializador llama a una función se reduce a "_ = llamada;". */
    private Program discardCalls(Program program) {
        if (deferredDiscards.isEmpty()) return program;
        List<FunctionDecl> result = new ArrayList<>();
        for (FunctionDecl f : program.functions()) {
            currentFunction = f.name();
            Usage usage = new Usage(f);
            Set<Node> targets = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Info info : usage.ordered) {
                if (info.declaration instanceof VariableDecl d && info.reads == 0 && deferredDiscards.contains(deferredKey(d))) {
                    targets.add(d);
                }
            }
            result.add(targets.isEmpty() ? f : new FunctionDecl(f.isPublic(), f.name(), f.parameters(), f.returnType(),
                    removeDead(f.body(), targets, Set.of(), Set.of()), f.span()));
        }
        return new Program(result, program.span());
    }

    private static Set<String> reachableFunctions(Program program) {
        Map<String, FunctionDecl> byName = new HashMap<>();
        program.functions().forEach(f -> byName.putIfAbsent(f.name(), f));
        Deque<String> pending = new ArrayDeque<>();
        program.functions().stream().filter(f -> f.name().equals("main") || f.isPublic()).forEach(f -> pending.add(f.name()));
        Set<String> reachable = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (!reachable.add(name) || !byName.containsKey(name)) continue;
            Set<String> called = new HashSet<>();
            calls(byName.get(name).body(), called);
            pending.addAll(called);
        }
        return reachable;
    }

    private static void calls(Statement s, Set<String> out) {
        if (s instanceof VariableDecl d) calls(d.initializer(), out);
        else if (s instanceof AssignmentStmt a) { calls(a.target(), out); calls(a.value(), out); }
        else if (s instanceof ExpressionStmt e) calls(e.expression(), out);
        else if (s instanceof ReturnStmt r) r.value().ifPresent(v -> calls(v, out));
        else if (s instanceof Block b) b.statements().forEach(x -> calls(x, out));
        else if (s instanceof IfStmt i) { calls(i.condition(), out); calls(i.thenBlock(), out); i.elseBranch().ifPresent(x -> calls(x, out)); }
        else if (s instanceof WhileStmt w) { calls(w.condition(), out); calls(w.body(), out); }
        else if (s instanceof ForStmt f) {
            if (f.source() instanceof RangeSource r) { calls(r.start(), out); calls(r.end(), out); }
            else calls(((IterableSource) f.source()).expression(), out);
            calls(f.body(), out);
        } else if (s instanceof SwitchStmt sw) { calls(sw.subject(), out); sw.cases().forEach(c -> calls(c.body(), out)); }
    }

    private static void calls(Expression e, Set<String> out) {
        if (e instanceof CallExpr c) {
            if (c.callee() instanceof IdentifierExpr id) out.add(id.name());
            c.arguments().forEach(a -> calls(a, out));
        } else if (e instanceof BinaryExpr b) { calls(b.left(), out); calls(b.right(), out); }
        else if (e instanceof UnaryExpr u) calls(u.operand(), out);
        else if (e instanceof IndexExpr i) { calls(i.target(), out); calls(i.index(), out); }
        else if (e instanceof LengthExpr l) calls(l.target(), out);
        else if (e instanceof ArrayLiteral a) a.elements().forEach(x -> calls(x, out));
    }

    private Block removeDead(Block block, Set<Node> declarations, Set<Statement> stores, Set<Node> captures) {
        List<Statement> out = new ArrayList<>();
        for (Statement s : block.statements()) {
            if (declarations.contains(s)) {
                VariableDecl d = (VariableDecl) s;
                if (!pure(d.initializer())) {
                    out.add(new AssignmentStmt(new IdentifierExpr("_", d.span()), "=", d.initializer(), d.span()));
                }
                continue;
            }
            if (stores.contains(s)) continue;
            out.add(removeDeadInside(s, declarations, stores, captures));
        }
        return new Block(out, block.span());
    }

    private Statement removeDeadInside(Statement s, Set<Node> declarations, Set<Statement> stores, Set<Node> captures) {
        if (s instanceof Block b) return removeDead(b, declarations, stores, captures);
        if (s instanceof IfStmt i) {
            return new IfStmt(i.condition(), removeDead(i.thenBlock(), declarations, stores, captures),
                    i.elseBranch().map(x -> removeDeadInside(x, declarations, stores, captures)), i.span());
        }
        if (s instanceof WhileStmt w) return new WhileStmt(w.condition(), removeDead(w.body(), declarations, stores, captures), w.span());
        if (s instanceof ForStmt f) {
            String capture = captures.contains(f) ? "_" : f.capture();
            return new ForStmt(f.source(), capture, removeDead(f.body(), declarations, stores, captures), f.span());
        }
        if (s instanceof SwitchStmt sw) {
            List<SwitchCase> cases = sw.cases().stream().map(c -> new SwitchCase(c.elseCase(), c.labels(),
                    removeDead(c.body(), declarations, stores, captures), c.span())).toList();
            return new SwitchStmt(sw.subject(), cases, sw.span());
        }
        return s;
    }

    // ---------------------------------------------------------------- invariantes fuera de ciclos for

    private Program hoistPass(Program program) {
        List<FunctionDecl> result = new ArrayList<>();
        for (FunctionDecl f : program.functions()) {
            Map<String, Integer> declarations = new HashMap<>();
            Set<String> parameters = new HashSet<>();
            Map<String, BigInteger> arraySizes = new HashMap<>();
            for (Parameter p : f.parameters()) {
                declarations.merge(p.name(), 1, Integer::sum);
                parameters.add(p.name());
                if (p.type() instanceof ArrayType a) arraySizes.put(p.name(), new BigInteger(a.size()));
            }
            List<String> declared = new ArrayList<>();
            collectDeclared(f.body(), declared);
            declared.forEach(name -> declarations.merge(name, 1, Integer::sum));
            Hoister hoister = new Hoister(declarations, arraySizes);
            Block body = hoister.block(f.body(), parameters);
            result.add(hoister.changed
                    ? new FunctionDecl(f.isPublic(), f.name(), f.parameters(), f.returnType(), body, f.span()) : f);
        }
        return new Program(result, program.span());
    }

    private static final class Hoister {
        private final Map<String, Integer> declarations;
        private final Map<String, BigInteger> arraySizes;
        boolean changed;

        Hoister(Map<String, Integer> declarations, Map<String, BigInteger> arraySizes) {
            this.declarations = declarations;
            this.arraySizes = arraySizes;
        }

        Block block(Block block, Set<String> outside) {
            List<Statement> out = new ArrayList<>();
            Set<String> visible = new HashSet<>(outside);
            for (Statement s : block.statements()) {
                if (s instanceof VariableDecl d) {
                    rememberArray(d);
                    visible.add(d.name());
                    out.add(s);
                } else if (s instanceof ForStmt f) {
                    Set<String> inside = new HashSet<>(visible);
                    if (!f.capture().equals("_")) inside.add(f.capture());
                    ForStmt inner = new ForStmt(f.source(), f.capture(), block(f.body(), inside), f.span());
                    List<Statement> hoisted = new ArrayList<>();
                    ForStmt loop = hoist(inner, visible, hoisted);
                    for (Statement h : hoisted) { out.add(h); visible.add(((VariableDecl) h).name()); }
                    out.add(loop);
                } else out.add(nested(s, visible));
            }
            return new Block(out, block.span());
        }

        private Statement nested(Statement s, Set<String> visible) {
            if (s instanceof Block b) return block(b, visible);
            if (s instanceof IfStmt i) return new IfStmt(i.condition(), block(i.thenBlock(), visible),
                    i.elseBranch().map(x -> nested(x, visible)), i.span());
            if (s instanceof WhileStmt w) return new WhileStmt(w.condition(), block(w.body(), visible), w.span());
            if (s instanceof SwitchStmt sw) return new SwitchStmt(sw.subject(), sw.cases().stream()
                    .map(c -> new SwitchCase(c.elseCase(), c.labels(), block(c.body(), visible), c.span())).toList(), sw.span());
            return s;
        }

        private void rememberArray(VariableDecl d) {
            if (d.declaredType().isPresent() && d.declaredType().get() instanceof ArrayType a) {
                arraySizes.put(d.name(), new BigInteger(a.size()));
            } else if (d.initializer() instanceof ArrayLiteral a) {
                arraySizes.put(d.name(), a.explicitSize().map(BigInteger::new).orElse(BigInteger.valueOf(a.elements().size())));
            } else if (d.initializer() instanceof IdentifierExpr id && arraySizes.containsKey(id.name())) {
                arraySizes.put(d.name(), arraySizes.get(id.name()));
            }
        }

        private boolean runsAtLeastOnce(ForStmt f) {
            if (f.source() instanceof RangeSource r) {
                Const start = literalValue(r.start()), end = literalValue(r.end());
                return start != null && end != null && start.integer() != null && end.integer() != null
                        && start.integer().compareTo(end.integer()) < 0;
            }
            Expression source = ((IterableSource) f.source()).expression();
            if (source instanceof ArrayLiteral a) return !a.elements().isEmpty();
            return source instanceof IdentifierExpr id && arraySizes.getOrDefault(id.name(), BigInteger.ZERO).signum() > 0;
        }

        private ForStmt hoist(ForStmt f, Set<String> outside, List<Statement> hoisted) {
            if (!runsAtLeastOnce(f)) return f;
            Set<String> written = new HashSet<>();
            assigned(f.body(), written);
            List<Statement> body = new ArrayList<>(f.body().statements());
            boolean moved = false;
            for (int i = 0; i < body.size(); i++) {
                Statement s = body.get(i);
                if (!simple(s)) break;
                if (!(s instanceof VariableDecl d) || !d.constant() || !pure(d.initializer())
                        || !containsOperation(d.initializer()) || declarations.getOrDefault(d.name(), 0) != 1) continue;
                Set<String> read = new HashSet<>();
                names(d.initializer(), read);
                if (read.isEmpty() || !outside.containsAll(read) || read.contains(f.capture())
                        || read.stream().anyMatch(written::contains)) continue;
                hoisted.add(d);
                body.remove(i--);
                moved = true;
            }
            if (!moved) return f;
            changed = true;
            return new ForStmt(f.source(), f.capture(), new Block(body, f.body().span()), f.span());
        }
    }

    // ---------------------------------------------------------------- subexpresiones redundantes

    private record Occurrence(int statement, BinaryExpr node, String key, Set<String> reads, int size) { }

    private Program commonSubexpressionPass(Program program) {
        List<FunctionDecl> result = new ArrayList<>();
        for (FunctionDecl f : program.functions()) {
            result.add(new FunctionDecl(f.isPublic(), f.name(), f.parameters(), f.returnType(), cseBlock(f.body()), f.span()));
        }
        return new Program(result, program.span());
    }

    private Block cseBlock(Block block) {
        List<Statement> list = new ArrayList<>();
        for (Statement s : block.statements()) list.add(cseNested(s));
        int start = 0;
        while (start < list.size()) {
            if (!simple(list.get(start))) { start++; continue; }
            int end = start;
            while (end < list.size() && simple(list.get(end))) end++;
            if (end < list.size() && list.get(end) instanceof ReturnStmt) end++;
            start = cseRun(list, start, end);
        }
        return new Block(list, block.span());
    }

    private Statement cseNested(Statement s) {
        if (s instanceof Block b) return cseBlock(b);
        if (s instanceof IfStmt i) return new IfStmt(i.condition(), cseBlock(i.thenBlock()), i.elseBranch().map(this::cseNested), i.span());
        if (s instanceof WhileStmt w) return new WhileStmt(w.condition(), cseBlock(w.body()), w.span());
        if (s instanceof ForStmt f) return new ForStmt(f.source(), f.capture(), cseBlock(f.body()), f.span());
        if (s instanceof SwitchStmt sw) return new SwitchStmt(sw.subject(), sw.cases().stream()
                .map(c -> new SwitchCase(c.elseCase(), c.labels(), cseBlock(c.body()), c.span())).toList(), sw.span());
        return s;
    }

    private int cseRun(List<Statement> list, int start, int end) {
        for (int guard = 0; guard < 50; guard++) {
            List<List<Occurrence>> groups = new ArrayList<>();
            Map<String, List<Occurrence>> open = new LinkedHashMap<>();
            for (int i = start; i < end; i++) {
                Statement s = list.get(i);
                List<Occurrence> found = new ArrayList<>();
                candidates(s, i, found);
                for (Occurrence o : found) open.computeIfAbsent(o.key(), k -> new ArrayList<>()).add(o);
                Set<String> written = new HashSet<>();
                assigned(s, written);
                if (written.isEmpty()) continue;
                for (Iterator<Map.Entry<String, List<Occurrence>>> it = open.entrySet().iterator(); it.hasNext(); ) {
                    var entry = it.next();
                    if (entry.getValue().getFirst().reads().stream().anyMatch(written::contains)) {
                        groups.add(entry.getValue());
                        it.remove();
                    }
                }
            }
            groups.addAll(open.values());
            List<Occurrence> best = groups.stream().filter(g -> g.size() >= 2)
                    .max(Comparator.comparingInt((List<Occurrence> g) -> g.getFirst().size())
                            .thenComparingInt(g -> -g.getFirst().statement()))
                    .orElse(null);
            if (best == null) return end;
            end = applyCse(list, best, end);
        }
        return end;
    }

    private int applyCse(List<Statement> list, List<Occurrence> group, int end) {
        Occurrence first = group.getFirst();
        Statement owner = list.get(first.statement());
        Map<Expression, Expression> replacements = new IdentityHashMap<>();
        if (owner instanceof VariableDecl d && d.constant() && d.initializer() == first.node() && d.declaredType().isEmpty()) {
            for (Occurrence o : group.subList(1, group.size())) replacements.put(o.node(), new IdentifierExpr(d.name(), o.node().span()));
            for (int i = first.statement(); i < end; i++) list.set(i, replace(list.get(i), replacements));
            return end;
        }
        String name;
        do { name = "cse" + (++temporaries); } while (usedNames.contains(name));
        usedNames.add(name);
        for (Occurrence o : group) replacements.put(o.node(), new IdentifierExpr(name, o.node().span()));
        for (int i = first.statement(); i < end; i++) list.set(i, replace(list.get(i), replacements));
        list.add(first.statement(), new VariableDecl(true, name, Optional.empty(), first.node(), first.node().span()));
        return end + 1;
    }

    private void candidates(Statement s, int index, List<Occurrence> out) {
        if (s instanceof VariableDecl d) candidates(d.initializer(), index, out);
        else if (s instanceof AssignmentStmt a) {
            Expression t = a.target();
            while (t instanceof IndexExpr i) { candidates(i.index(), index, out); t = i.target(); }
            candidates(a.value(), index, out);
        } else if (s instanceof ExpressionStmt e) candidates(e.expression(), index, out);
        else if (s instanceof ReturnStmt r) r.value().ifPresent(v -> candidates(v, index, out));
    }

    private void candidates(Expression e, int index, List<Occurrence> out) {
        if (e instanceof BinaryExpr b) {
            if (ARITHMETIC.contains(b.operator()) && pure(b)) {
                Set<String> reads = new HashSet<>();
                names(b, reads);
                if (!reads.isEmpty()) out.add(new Occurrence(index, b, canonical(b), reads, size(b)));
            }
            candidates(b.left(), index, out);
            candidates(b.right(), index, out);
        } else if (e instanceof UnaryExpr u) candidates(u.operand(), index, out);
        else if (e instanceof CallExpr c) c.arguments().forEach(a -> candidates(a, index, out));
        else if (e instanceof IndexExpr i) { candidates(i.target(), index, out); candidates(i.index(), index, out); }
        else if (e instanceof LengthExpr l) candidates(l.target(), index, out);
        else if (e instanceof ArrayLiteral a) a.elements().forEach(x -> candidates(x, index, out));
    }

    private static int size(Expression e) {
        if (e instanceof BinaryExpr b) return 1 + size(b.left()) + size(b.right());
        if (e instanceof UnaryExpr u) return 1 + size(u.operand());
        if (e instanceof IndexExpr i) return 1 + size(i.target()) + size(i.index());
        if (e instanceof LengthExpr l) return 1 + size(l.target());
        return 1;
    }

    private static String canonical(Expression e) {
        if (e instanceof BinaryExpr b) {
            String l = canonical(b.left()), r = canonical(b.right());
            if ((b.operator().equals("+") || b.operator().equals("*")) && l.compareTo(r) > 0) { String t = l; l = r; r = t; }
            return "(" + l + " " + b.operator() + " " + r + ")";
        }
        if (e instanceof UnaryExpr u) return "(" + u.operator() + canonical(u.operand()) + ")";
        if (e instanceof IndexExpr i) return canonical(i.target()) + "[" + canonical(i.index()) + "]";
        if (e instanceof LengthExpr l) return canonical(l.target()) + ".len";
        return code(e);
    }

    private static Statement replace(Statement s, Map<Expression, Expression> map) {
        if (s instanceof VariableDecl d) return new VariableDecl(d.constant(), d.name(), d.declaredType(), replace(d.initializer(), map), d.span());
        if (s instanceof AssignmentStmt a) return new AssignmentStmt(replace(a.target(), map), a.operator(), replace(a.value(), map), a.span());
        if (s instanceof ExpressionStmt e) return new ExpressionStmt(replace(e.expression(), map), e.span());
        if (s instanceof ReturnStmt r) return new ReturnStmt(r.value().map(v -> replace(v, map)), r.span());
        return s;
    }

    private static Expression replace(Expression e, Map<Expression, Expression> map) {
        Expression direct = map.get(e);
        if (direct != null) return direct;
        if (e instanceof BinaryExpr b) return new BinaryExpr(replace(b.left(), map), b.operator(), replace(b.right(), map), b.span());
        if (e instanceof UnaryExpr u) return new UnaryExpr(u.operator(), replace(u.operand(), map), u.span());
        if (e instanceof CallExpr c) return new CallExpr(c.callee(), c.arguments().stream().map(x -> replace(x, map)).toList(), c.span());
        if (e instanceof IndexExpr i) return new IndexExpr(replace(i.target(), map), replace(i.index(), map), i.span());
        if (e instanceof LengthExpr l) return new LengthExpr(replace(l.target(), map), l.span());
        if (e instanceof ArrayLiteral a) return new ArrayLiteral(a.explicitSize(), a.elementType(),
                a.elements().stream().map(x -> replace(x, map)).toList(), a.span());
        return e;
    }

    /** Zig exige descartar con "_ = p;" un parámetro cuyos usos desaparecieron. */
    private static Program keepParameters(Program program, Map<String, Map<String, Integer>> originalReads) {
        List<FunctionDecl> result = new ArrayList<>();
        for (FunctionDecl f : program.functions()) {
            Map<String, Integer> before = originalReads.getOrDefault(f.name(), Map.of());
            Usage usage = new Usage(f);
            List<Statement> body = new ArrayList<>();
            for (Parameter p : f.parameters()) {
                if (before.getOrDefault(p.name(), 0) > 0 && usage.of(p).reads == 0) {
                    body.add(new AssignmentStmt(new IdentifierExpr("_", p.span()), "=", new IdentifierExpr(p.name(), p.span()), p.span()));
                }
            }
            if (body.isEmpty()) {
                result.add(f);
                continue;
            }
            body.addAll(f.body().statements());
            result.add(new FunctionDecl(f.isPublic(), f.name(), f.parameters(), f.returnType(), new Block(body, f.body().span()), f.span()));
        }
        return new Program(result, program.span());
    }
}
