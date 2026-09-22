package mx.edu.automatas.zigedu.analysis;

import mx.edu.automatas.zigedu.ast.Ast;
import mx.edu.automatas.zigedu.ast.Ast.*;
import mx.edu.automatas.zigedu.ast.AstFormatter;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;
import static mx.edu.automatas.zigedu.analysis.SemanticType.*;

/**
 * Dos pasadas sobre el AST: firmas de funciones y comprobación de sus cuerpos.
 * Cada instancia pertenece a una ejecución. Los tokens sólo precisan posiciones;
 * las reglas se aplican al árbol, nunca mediante búsquedas de texto o regex.
 */
public final class SemanticAnalyzer {
    private final String[] lines;
    private final List<TokenInfo> tokens;
    private final Map<String, FunctionDecl> functions = new LinkedHashMap<>();
    private final Deque<Map<String, Symbol>> scopes = new ArrayDeque<>();
    private final Deque<String> scopeNames = new ArrayDeque<>();
    private final List<Diagnostic> errors = new ArrayList<>();
    private final List<SemanticResult.Entry> entries = new ArrayList<>();
    private final List<SemanticResult.SymbolInfo> symbols = new ArrayList<>();
    private final Map<Ast.Node, String> annotations = new IdentityHashMap<>();
    private FunctionDecl function;
    private int loopDepth;

    private record Value(SemanticType type, BigInteger integer, Double decimal, Boolean bool) {
        static Value of(SemanticType type) { return new Value(type, null, null, null); }
        static Value integer(BigInteger value) { return new Value(INT_LITERAL, value, null, null); }
        Value as(SemanticType type) {
            if (type.equals(F64) || type.equals(FLOAT_LITERAL)) {
                return new Value(type, null, integer == null ? decimal : Double.valueOf(integer.doubleValue()), null);
            }
            return new Value(type, integer, decimal, bool);
        }
        String describe() {
            Object constant = integer != null ? integer : decimal != null ? decimal : bool;
            return type + (constant == null ? "" : " (valor " + constant + ")");
        }
    }
    private record Symbol(String name, Value value, boolean constant, SourceSpan span) { }
    private enum Exit { NEXT, RETURN, BREAK, CONTINUE }

    public SemanticAnalyzer(String source, List<TokenInfo> tokens) {
        this.lines = source.split("\\R", -1);
        this.tokens = List.copyOf(tokens);
    }

    public SemanticResult analyze(Program program) {
        for (FunctionDecl declaration : program.functions()) {
            SourceSpan name = nameSpan(declaration, declaration.name());
            FunctionDecl previous = functions.putIfAbsent(declaration.name(), declaration);
            if (previous != null) {
                duplicate(declaration.name(), name, nameSpan(previous, previous.name()));
            } else {
                symbols.add(new SemanticResult.SymbolInfo(declaration.name(), "función",
                        signature(declaration), "global", name));
                entry("Función", signature(declaration), name);
            }
        }
        for (FunctionDecl declaration : program.functions()) {
            function = declaration;
            loopDepth = 0;
            pushScope(declaration.name());
            for (Parameter parameter : declaration.parameters()) {
                declare(parameter.name(), Value.of(type(parameter.type())), true, parameter.span(), "parámetro");
            }
            Set<Exit> exits = block(declaration.body(), false);
            if (!type(declaration.returnType()).equals(VOID) && exits.contains(Exit.NEXT)) {
                error("SEM_RETORNO", nameSpan(declaration, declaration.name()),
                        "La función '" + declaration.name() + "' puede llegar al final sin devolver un valor.",
                        "return con un valor de tipo " + type(declaration.returnType()) + " en todos los caminos que terminan",
                        "Un camino de ejecución sin retorno. Añade el return que falta o completa las ramas del condicional.");
            }
            popScope();
        }
        errors.sort(Comparator.comparingInt(Diagnostic::line).thenComparingInt(Diagnostic::column));
        entries.sort(Comparator.comparingInt((SemanticResult.Entry e) -> e.span().line())
                .thenComparingInt(e -> e.span().column()));
        return new SemanticResult(errors, entries, symbols, AstFormatter.formatAnnotated(program, annotations));
    }

    private SemanticType type(TypeNode node) {
        if (node instanceof PrimitiveType p) return primitive(p.name());
        ArrayType a = (ArrayType) node;
        return array(new BigInteger(a.size()), type(a.elementType()));
    }

    private String signature(FunctionDecl f) {
        return f.name() + "(" + f.parameters().stream()
                .map(p -> p.name() + ": " + type(p.type())).collect(Collectors.joining(", "))
                + ") " + type(f.returnType());
    }

    private void pushScope(String name) {
        scopes.push(new LinkedHashMap<>());
        scopeNames.push(name);
    }
    private void popScope() { scopes.pop(); scopeNames.pop(); }
    private String scope() { return String.join(" / ", scopeNames.reversed()); }
    private Symbol lookup(String name) {
        for (Map<String, Symbol> scope : scopes) {
            if (scope.containsKey(name)) return scope.get(name);
        }
        return null;
    }
    private void declare(String name, Value value, boolean constant, SourceSpan span, String kind) {
        Symbol previous = lookup(name);
        if (previous != null) {
            duplicate(name, span, previous.span());
            return; // Conservar la primera declaración evita resultados dependientes del error.
        }
        scopes.peek().put(name, new Symbol(name, value, constant, span));
        symbols.add(new SemanticResult.SymbolInfo(name, kind, value.type().toString(), scope(), span));
        entry("Declaración " + kind, name + " : " + value.type() + " · ámbito " + scope(), span);
    }
    private void duplicate(String name, SourceSpan span, SourceSpan previous) {
        error("SEM_DUPLICADO", span, "El identificador '" + name + "' ya fue declarado en un ámbito activo.",
                "Un nombre diferente; si deseas cambiar un valor existente, usa una asignación a una variable var",
                "Nueva declaración de '" + name + "'. La primera está en línea " + previous.line()
                        + ", columna " + previous.column() + ".");
    }

    private Set<Exit> block(Block block, boolean nested) {
        if (nested) pushScope("bloque " + block.span().line() + ":" + block.span().column());
        Set<Exit> exits = EnumSet.of(Exit.NEXT);
        for (Statement statement : block.statements()) {
            Set<Exit> next = statement(statement);
            if (exits.remove(Exit.NEXT)) exits.addAll(next);
        }
        if (nested) popScope();
        return exits;
    }

    private Set<Exit> statement(Statement node) {
        if (node instanceof Block b) return block(b, true);
        if (node instanceof VariableDecl v) {
            // El nombre aún no está en la tabla: detecta var x = x y uso antes de declarar.
            Value initial = expression(v.initializer());
            SemanticType target = v.declaredType().map(this::type).orElse(initial.type());
            if (!v.constant() && v.declaredType().isEmpty()) {
                if (target.equals(INT_LITERAL)) target = I32;
                if (target.equals(FLOAT_LITERAL)) target = F64;
            }
            boolean valid = compatible(target, initial, v.initializer().span(), "inicializar '" + v.name() + "'");
            Value value = valid ? initial.as(target) : Value.of(target);
            if (!v.constant()) value = Value.of(target); // Nunca tratar una variable mutable como constante.
            declare(v.name(), value, v.constant(), nameSpan(v, v.name()), v.constant() ? "const" : "var");
            annotations.put(v, "tipo=" + target + "; ámbito=" + scope());
        } else if (node instanceof AssignmentStmt a) {
            if (a.target() instanceof IdentifierExpr id && id.name().equals("_") && a.operator().equals("=")) {
                Value value = expression(a.value());
                entry("Descarte", "_ recibe " + value.type(), a.span());
            } else {
                Value target = expression(a.target());
                checkWritable(a.target());
                Value value = expression(a.value());
                if (!a.operator().equals("=")) {
                    value = binary(a.operator().substring(0, 1), target, value, a.target().span(), a.value().span());
                }
                compatible(target.type(), value, a.value().span(), "asignar con '" + a.operator() + "'");
                entry("Asignación", a.operator() + " · destino " + target.type() + " · valor " + value.type(), a.span());
            }
        } else if (node instanceof ExpressionStmt e) {
            Value value = expression(e.expression());
            if (!value.type().equals(VOID) && !value.type().isError()) {
                error("SEM_VALOR_IGNORADO", e.span(), "La sentencia produce un valor que no se utiliza.",
                        "Asignarlo, devolverlo o descartarlo explícitamente con _ = expresión;",
                        "Resultado de tipo " + value.type());
            }
        } else if (node instanceof IfStmt i) {
            Value condition = expression(i.condition());
            compatible(BOOL, condition, i.condition().span(), "evaluar la condición de if");
            Set<Exit> yes = block(i.thenBlock(), true);
            Set<Exit> no = i.elseBranch().map(this::statement).orElseGet(() -> EnumSet.of(Exit.NEXT));
            if (condition.bool() != null) return condition.bool() ? yes : no;
            yes.addAll(no);
            return yes;
        } else if (node instanceof WhileStmt w) {
            Value condition = expression(w.condition());
            compatible(BOOL, condition, w.condition().span(), "evaluar la condición de while");
            loopDepth++;
            Set<Exit> body = block(w.body(), true);
            loopDepth--;
            Set<Exit> exits = EnumSet.noneOf(Exit.class);
            if (!Boolean.TRUE.equals(condition.bool()) || body.contains(Exit.BREAK)) exits.add(Exit.NEXT);
            if (!Boolean.FALSE.equals(condition.bool()) && body.contains(Exit.RETURN)) exits.add(Exit.RETURN);
            return exits;
        } else if (node instanceof ForStmt f) {
            SemanticType element = ERROR;
            if (f.source() instanceof IterableSource it) {
                Value iterable = expression(it.expression());
                if (iterable.type().isArray()) element = iterable.type().element();
                else if (!iterable.type().isError()) error("SEM_ITERABLE", it.span(),
                        "El for necesita un arreglo para obtener sus elementos.", "Un arreglo [N]T",
                        "Expresión de tipo " + iterable.type());
            } else if (f.source() instanceof RangeSource r) {
                Value start = expression(r.start());
                Value end = expression(r.end());
                integerRequired(start, r.start().span(), "iniciar el rango");
                integerRequired(end, r.end().span(), "terminar el rango");
                if (start.integer() != null && end.integer() != null
                        && (start.integer().signum() < 0 || end.integer().compareTo(start.integer()) < 0)) {
                    error("SEM_RANGO", r.span(), "Los límites constantes del rango no son válidos.",
                            "0 <= inicio <= fin; el límite final es exclusivo",
                            start.integer() + ".." + end.integer());
                }
                element = I32;
            }
            pushScope("for " + f.span().line() + ":" + f.span().column());
            if (!f.capture().equals("_")) declare(f.capture(), Value.of(element), true,
                    captureSpan(f), "captura");
            loopDepth++;
            Set<Exit> body = block(f.body(), false);
            loopDepth--;
            popScope();
            Set<Exit> exits = EnumSet.of(Exit.NEXT); // Un for puede no ejecutar su cuerpo.
            if (body.contains(Exit.RETURN)) exits.add(Exit.RETURN);
            return exits;
        } else if (node instanceof SwitchStmt s) {
            Value subject = expression(s.subject());
            if (!subject.type().isError() && !subject.type().isInteger() && !subject.type().equals(BOOL)) {
                error("SEM_SWITCH", s.subject().span(), "El selector no admite este tipo en el subconjunto.",
                        "Un entero o bool", subject.type().toString());
            }
            Set<String> labels = new HashSet<>();
            Set<Exit> exits = EnumSet.noneOf(Exit.class);
            for (SwitchCase c : s.cases()) {
                for (Expression label : c.labels()) {
                    Value value = expression(label);
                    compatible(subject.type(), value, label.span(), "comparar una etiqueta de switch");
                    String key = value.integer() != null ? "n:" + value.integer() : "b:" + value.bool();
                    if (!labels.add(key)) error("SEM_CASO_DUPLICADO", label.span(),
                            "Dos etiquetas del switch representan el mismo valor.", "Valores diferentes en cada caso", value.describe());
                }
                exits.addAll(block(c.body(), true));
            }
            return exits;
        } else if (node instanceof ReturnStmt r) {
            Value value = r.value().map(this::expression).orElseGet(() -> Value.of(VOID));
            compatible(type(function.returnType()), value, r.value().map(Expression::span).orElse(r.span()),
                    "devolver el resultado de '" + function.name() + "'");
            entry("Retorno", "función " + function.name() + " · tipo " + value.type(), r.span());
            return EnumSet.of(Exit.RETURN);
        } else if (node instanceof BreakStmt || node instanceof ContinueStmt) {
            String keyword = node instanceof BreakStmt ? "break" : "continue";
            if (loopDepth == 0) error("SEM_CONTEXTO", node.span(),
                    "'" + keyword + "' se encuentra fuera de un ciclo.",
                    "Una sentencia " + keyword + " dentro de while o for", "No hay ningún ciclo activo");
            return EnumSet.of(node instanceof BreakStmt ? Exit.BREAK : Exit.CONTINUE);
        }
        return EnumSet.of(Exit.NEXT);
    }

    private Value expression(Expression node) {
        Value value = infer(node);
        annotations.put(node, "tipo=" + value.type() + "; ámbito=" + scope());
        entry(expressionLabel(node), value.describe() + " · ámbito " + scope(), node.span());
        return value;
    }

    private String expressionLabel(Expression node) {
        if (node instanceof IdentifierExpr id) return "Identificador " + id.name();
        if (node instanceof IntegerLiteral) return "Literal entero";
        if (node instanceof FloatLiteral) return "Literal decimal";
        if (node instanceof BooleanLiteral) return "Literal booleano";
        if (node instanceof CharLiteral c) return "Carácter " + c.image();
        if (node instanceof ArrayLiteral) return "Literal de arreglo";
        if (node instanceof BinaryExpr b) return "Operación " + b.operator();
        if (node instanceof UnaryExpr u) return "Operación " + u.operator();
        if (node instanceof CallExpr) return "Resultado de llamada";
        if (node instanceof IndexExpr) return "Acceso por índice";
        if (node instanceof LengthExpr) return "Longitud .len";
        return "Expresión";
    }

    private Value infer(Expression node) {
        if (node instanceof IntegerLiteral i) return Value.integer(new BigInteger(i.image()));
        if (node instanceof FloatLiteral f) {
            double value = Double.parseDouble(f.image());
            if (!Double.isFinite(value)) {
                error("SEM_DESBORDAMIENTO", f.span(), "El decimal excede el rango representable de f64.",
                        "Un valor decimal finito", f.image());
                return Value.of(ERROR);
            }
            return new Value(FLOAT_LITERAL, null, value, null);
        }
        if (node instanceof BooleanLiteral b) return new Value(BOOL, null, null, b.value());
        if (node instanceof CharLiteral c) {
            String body = c.image().substring(1, c.image().length() - 1);
            int value = body.charAt(0) == '\\' ? switch (body.charAt(1)) {
                case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                case '\\' -> '\\'; case '\'' -> '\''; default -> 0;
            } : body.codePointAt(0);
            return Value.integer(BigInteger.valueOf(value));
        }
        if (node instanceof IdentifierExpr id) {
            Symbol symbol = lookup(id.name());
            if (symbol != null) return symbol.value();
            if (id.name().equals("_")) {
                error("SEM_DESCARTE", id.span(), "'_' descarta valores y no es una variable que se pueda leer.",
                        "Un identificador declarado o un literal", "Uso de '_' como valor");
            } else if (functions.containsKey(id.name())) {
                error("SEM_FUNCION_COMO_VALOR", id.span(), "Una función no se almacena como valor en este subconjunto.",
                        "Una llamada " + id.name() + "(...) con sus argumentos", "Nombre de función sin llamada");
            } else undeclared(id.name(), id.span());
            return Value.of(ERROR);
        }
        if (node instanceof ArrayLiteral a) {
            SemanticType element = type(a.elementType());
            BigInteger size = a.explicitSize().map(BigInteger::new).orElse(BigInteger.valueOf(a.elements().size()));
            if (!size.equals(BigInteger.valueOf(a.elements().size()))) {
                error("SEM_TAMANO_ARREGLO", a.span(), "La cantidad de elementos no coincide con el tamaño declarado del arreglo.",
                        size + " elemento(s) de tipo " + element, a.elements().size() + " elemento(s)");
            }
            for (Expression item : a.elements()) compatible(element, expression(item), item.span(), "inicializar un elemento del arreglo");
            return Value.of(array(size, element));
        }
        if (node instanceof BinaryExpr b) {
            return binary(b.operator(), expression(b.left()), expression(b.right()), b.left().span(), b.right().span());
        }
        if (node instanceof UnaryExpr u) {
            Value operand = expression(u.operand());
            if (operand.type().isError()) return operand;
            if (u.operator().equals("!")) {
                if (!compatible(BOOL, operand, u.operand().span(), "aplicar el operador !")) return Value.of(ERROR);
                return new Value(BOOL, null, null, operand.bool() == null ? null : !operand.bool());
            }
            if (!operand.type().isNumeric() || operand.type().equals(U8)) {
                error("SEM_OPERADOR", u.span(), "El operador '-' requiere un número con signo.",
                        "i32, f64 o literal numérico", operand.type().toString());
                return Value.of(ERROR);
            }
            Value result = new Value(operand.type(), operand.integer() == null ? null : operand.integer().negate(),
                    operand.decimal() == null ? null : -operand.decimal(), null);
            return rangeChecked(result, u.span());
        }
        if (node instanceof CallExpr c) return call(c);
        if (node instanceof IndexExpr i) {
            Value target = expression(i.target());
            Value index = expression(i.index());
            integerRequired(index, i.index().span(), "indexar un arreglo");
            if (target.type().isArray()) {
                if (index.integer() != null && (index.integer().signum() < 0
                        || index.integer().compareTo(target.type().size()) >= 0)) {
                    error("SEM_INDICE", i.index().span(), "El índice constante está fuera de los límites del arreglo.",
                            target.type().size().signum() == 0 ? "No indexar un arreglo vacío"
                                    : "Un índice entre 0 y " + target.type().size().subtract(BigInteger.ONE),
                            "Índice " + index.integer() + " en un arreglo de tamaño " + target.type().size());
                }
                return Value.of(target.type().element());
            }
            if (!target.type().isError()) error("SEM_INDICE", i.target().span(),
                    "Se intentó indexar un valor que no es un arreglo.", "Un arreglo [N]T", target.type().toString());
            return Value.of(ERROR);
        }
        if (node instanceof LengthExpr l) {
            Value target = expression(l.target());
            if (target.type().isArray()) return Value.integer(target.type().size());
            if (!target.type().isError()) error("SEM_LONGITUD", l.span(),
                    "La propiedad .len sólo está disponible en arreglos.", "Un arreglo [N]T", target.type().toString());
            return Value.of(ERROR);
        }
        throw new IllegalStateException("Nodo de expresión no contemplado: " + node.getClass());
    }

    private Value call(CallExpr c) {
        FunctionDecl called = null;
        if (c.callee() instanceof IdentifierExpr id) {
            Symbol local = lookup(id.name());
            if (local != null) error("SEM_LLAMADA", id.span(),
                    "'" + id.name() + "' es una variable, no una función invocable.",
                    "El nombre de una función declarada", "Variable de tipo " + local.value().type());
            else {
                called = functions.get(id.name());
                if (called == null) undeclared(id.name(), id.span());
                else annotations.put(id, "firma=" + signature(called));
            }
        } else {
            Value callee = expression(c.callee());
            if (!callee.type().isError()) error("SEM_LLAMADA", c.callee().span(),
                    "La expresión no se puede invocar como función.", "Una función declarada con fn", callee.type().toString());
        }
        if (called != null && c.arguments().size() != called.parameters().size()) {
            error("SEM_ARGUMENTOS", c.span(), "La llamada a '" + called.name() + "' tiene una cantidad incorrecta de argumentos.",
                    signature(called) + " · " + called.parameters().size() + " argumento(s)",
                    c.arguments().size() + " argumento(s)");
        }
        for (int i = 0; i < c.arguments().size(); i++) {
            Expression argument = c.arguments().get(i);
            Value value = expression(argument);
            if (called != null && i < called.parameters().size()) {
                Parameter p = called.parameters().get(i);
                compatible(type(p.type()), value, argument.span(), "pasar el argumento " + (i + 1)
                        + " ('" + p.name() + "') de '" + called.name() + "'");
            }
        }
        if (called != null) entry("Llamada", signature(called), c.span());
        return Value.of(called == null ? ERROR : type(called.returnType()));
    }

    private void checkWritable(Expression target) {
        Expression root = target;
        while (root instanceof IndexExpr i) root = i.target();
        if (root instanceof IdentifierExpr id) {
            Symbol symbol = lookup(id.name());
            if (symbol != null && symbol.constant()) error("SEM_CONSTANTE", target.span(),
                    "No se puede modificar '" + id.name() + "' porque es const, parámetro o captura inmutable.",
                    "Una variable declarada con var (o un elemento de un arreglo var)",
                    "Declaración inmutable en línea " + symbol.span().line() + ", columna " + symbol.span().column());
        } else error("SEM_DESTINO", target.span(), "El lado izquierdo de la asignación no es modificable.",
                "Una variable var o un elemento de un arreglo var", "Una expresión, llamada o propiedad calculada");
    }

    private void undeclared(String name, SourceSpan span) {
        error("SEM_NO_DECLARADO", span, "El identificador '" + name + "' no está declarado en un ámbito visible.",
                "Una variable o parámetro declarado antes de este uso, o una función declarada en el programa",
                "Uso de '" + name + "' sin declaración accesible. Decláralo, revisa su escritura o úsalo dentro de su bloque.");
    }

    private void integerRequired(Value value, SourceSpan span, String purpose) {
        if (!value.type().isInteger() && !value.type().isError()) error("SEM_TIPO", span,
                "Tipo incorrecto para " + purpose + ".", "Un entero i32, u8 o literal entero", value.describe());
    }

    /** Conversiones sin pérdida: u8 -> i32 y literales representables. */
    private boolean accepts(SemanticType target, Value value) {
        SemanticType source = value.type();
        if (target.isError() || source.isError()) return true;
        if (target.equals(source)) return true;
        if (target.equals(I32) && source.equals(U8)) return true;
        if (source.equals(INT_LITERAL)) return target.equals(I32) || target.equals(U8) || target.equals(F64) || target.equals(FLOAT_LITERAL);
        return source.equals(FLOAT_LITERAL) && target.equals(F64);
    }

    private boolean compatible(SemanticType target, Value value, SourceSpan span, String purpose) {
        if (target.isError() || value.type().isError()) return false;
        if (!accepts(target, value)) {
            error("SEM_TIPO", span, "Tipos incompatibles al " + purpose + ". No se realiza una conversión implícita que cambie el significado del valor.",
                    "Un valor compatible con " + target, value.describe() + ". Corrige el valor o el tipo declarado.");
            return false;
        }
        return !rangeChecked(value.as(target), span).type().isError();
    }

    private Value rangeChecked(Value value, SourceSpan span) {
        if (value.integer() != null && (value.type().equals(I32) || value.type().equals(U8))) {
            BigInteger min = value.type().equals(U8) ? BigInteger.ZERO : BigInteger.valueOf(Integer.MIN_VALUE);
            BigInteger max = value.type().equals(U8) ? BigInteger.valueOf(255) : BigInteger.valueOf(Integer.MAX_VALUE);
            if (value.integer().compareTo(min) < 0 || value.integer().compareTo(max) > 0) {
                error("SEM_DESBORDAMIENTO", span, "El valor constante no cabe en el tipo " + value.type() + ".",
                        "Un valor entre " + min + " y " + max, value.integer().toString());
                return Value.of(ERROR);
            }
        }
        if (value.decimal() != null && !Double.isFinite(value.decimal())) {
            error("SEM_DESBORDAMIENTO", span, "La operación excede el rango de f64.", "Un resultado decimal finito", value.describe());
            return Value.of(ERROR);
        }
        return value;
    }

    private Value binary(String op, Value left, Value right, SourceSpan leftSpan, SourceSpan rightSpan) {
        if (left.type().isError() || right.type().isError()) return Value.of(ERROR);
        if (op.equals("and") || op.equals("or")) {
            boolean a = compatible(BOOL, left, leftSpan, "aplicar '" + op + "'");
            boolean b = compatible(BOOL, right, rightSpan, "aplicar '" + op + "'");
            if (!a || !b) return Value.of(ERROR);
            Boolean value = left.bool() != null && right.bool() != null
                    ? (op.equals("and") ? left.bool() && right.bool() : left.bool() || right.bool()) : null;
            return new Value(BOOL, null, null, value);
        }
        boolean equality = op.equals("==") || op.equals("!=");
        boolean comparison = equality || List.of("<", "<=", ">", ">=").contains(op);
        if (equality && left.type().equals(BOOL) && right.type().equals(BOOL)) {
            Boolean result = left.bool() != null && right.bool() != null
                    ? left.bool().equals(right.bool()) == op.equals("==") : null;
            return new Value(BOOL, null, null, result);
        }
        SemanticType common = commonNumeric(left, right);
        if (common.isError()) {
            error("SEM_OPERADOR", rightSpan, "Los operandos no son compatibles con el operador '" + op + "'.",
                    equality ? "Dos bool o dos números de tipos compatibles" : "Dos números de tipos compatibles",
                    "Operando izquierdo: " + left.describe() + "; operando derecho: " + right.describe());
            return Value.of(ERROR);
        }
        if (!compatible(common, left, leftSpan, "evaluar el operando izquierdo")
                | !compatible(common, right, rightSpan, "evaluar el operando derecho")) return Value.of(ERROR);
        if (op.equals("%") && !common.isInteger()) {
            error("SEM_OPERADOR", rightSpan, "El residuo '%' sólo está definido para enteros en este subconjunto.",
                    "Operandos enteros", left.type() + " y " + right.type());
            return Value.of(ERROR);
        }
        if ((op.equals("/") || op.equals("%")) && (BigInteger.ZERO.equals(right.integer())
                || right.decimal() != null && right.decimal() == 0.0)) {
            error("SEM_DIVISION_CERO", rightSpan, "El divisor constante es cero; la operación no está definida.",
                    "Un divisor distinto de cero", right.describe());
            return Value.of(ERROR);
        }
        Value a = left.as(common), b = right.as(common);
        if (comparison) {
            Boolean result = null;
            if (a.integer() != null && b.integer() != null) result = compare(op, a.integer().compareTo(b.integer()));
            else if (a.decimal() != null && b.decimal() != null) {
                result = compare(op, a.decimal().doubleValue() == b.decimal().doubleValue() ? 0
                        : Double.compare(a.decimal(), b.decimal()));
            }
            return new Value(BOOL, null, null, result);
        }
        if (a.integer() != null && b.integer() != null) {
            BigInteger x = a.integer(), y = b.integer();
            BigInteger result = switch (op) {
                case "+" -> x.add(y); case "-" -> x.subtract(y); case "*" -> x.multiply(y);
                case "/" -> x.divide(y); case "%" -> x.remainder(y);
                default -> throw new IllegalStateException(op);
            };
            return rangeChecked(new Value(common, result, null, null), leftSpan);
        }
        if (a.decimal() != null && b.decimal() != null) {
            double x = a.decimal(), y = b.decimal();
            double result = switch (op) {
                case "+" -> x + y; case "-" -> x - y; case "*" -> x * y; case "/" -> x / y;
                default -> throw new IllegalStateException(op);
            };
            return rangeChecked(new Value(common, null, result, null), leftSpan);
        }
        return Value.of(common);
    }

    private SemanticType commonNumeric(Value a, Value b) {
        SemanticType x = a.type(), y = b.type();
        if (!x.isNumeric() || !y.isNumeric()) return ERROR;
        if (x.equals(y)) return x;
        if (x.equals(INT_LITERAL)) return y;
        if (y.equals(INT_LITERAL)) return x;
        if (x.isInteger() && y.isInteger()) return I32;
        if (x.equals(F64) && y.equals(FLOAT_LITERAL) || y.equals(F64) && x.equals(FLOAT_LITERAL)) return F64;
        return ERROR;
    }

    private boolean compare(String operator, int order) {
        return switch (operator) {
            case "==" -> order == 0; case "!=" -> order != 0; case "<" -> order < 0;
            case "<=" -> order <= 0; case ">" -> order > 0; case ">=" -> order >= 0;
            default -> throw new IllegalStateException(operator);
        };
    }

    private void entry(String construction, String detail, SourceSpan span) {
        entries.add(new SemanticResult.Entry(construction, detail, span, false));
    }
    private void error(String code, SourceSpan span, String summary, String expected, String found) {
        Diagnostic diagnostic = new Diagnostic(Diagnostic.Phase.SEMANTIC, "[" + code + "] " + summary,
                span.line(), span.column(), expected, found,
                span.line() <= lines.length ? lines[span.line() - 1] : "");
        errors.add(diagnostic);
        entries.add(new SemanticResult.Entry("Error semántico", diagnostic.summary() + " Se esperaba: "
                + expected + " · Se encontró: " + found, span, true));
    }

    private SourceSpan tokenSpan(TokenInfo t) { return new SourceSpan(t.line(), t.column(), t.endLine(), t.endColumn()); }
    private boolean inside(TokenInfo token, SourceSpan span) {
        return (token.line() > span.line() || token.line() == span.line() && token.column() >= span.column())
                && (token.line() < span.endLine() || token.line() == span.endLine() && token.column() <= span.endColumn());
    }
    private SourceSpan nameSpan(Ast.Node node, String name) {
        return tokens.stream().filter(t -> inside(t, node.span()) && t.lexeme().equals(name))
                .findFirst().map(this::tokenSpan).orElse(node.span());
    }
    private SourceSpan captureSpan(ForStmt loop) {
        boolean pipe = false;
        for (TokenInfo t : tokens) {
            if (!inside(t, loop.span())) continue;
            if (pipe) return tokenSpan(t);
            if (t.lexeme().equals("|")) pipe = true;
        }
        return loop.span();
    }
}
