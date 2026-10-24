package mx.edu.automatas.zigedu.ir;

import mx.edu.automatas.zigedu.ast.Ast.*;
import java.util.*;
import static mx.edu.automatas.zigedu.ir.ExpressionNotation.type;

/**
 * Traducción directa del AST validado, sin optimización.
 *
 * <p>Sólo las operaciones matemáticas (+ - * / % y el signo menos unario, incluidas las asignaciones
 * compuestas) forman los cuádruplos y las notaciones que se muestran y exportan. Declaraciones sin
 * operaciones, firmas de función, tipos, retornos, saltos y llamadas no se analizan. La traducción completa se
 * conserva únicamente como entrada interna del resultado numérico ({@link IntermediateResult#executable()}).
 */
public final class IntermediateGenerator {
    private final List<IntermediateResult.Quadruple> quads = new ArrayList<>();
    private final List<IntermediateResult.Notation> notations = new ArrayList<>();
    private final Set<Expression> recorded = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, FunctionDecl> functions = new HashMap<>();
    private final Deque<Loop> loops = new ArrayDeque<>();
    /** Cuádruplos (por número en la traducción completa) que son operaciones matemáticas o guardan su resultado. */
    private final Set<Integer> shown = new HashSet<>();
    /** Temporales que no son operaciones matemáticas; se muestran como la expresión que los produjo. */
    private final Map<String, String> aliases = new HashMap<>();
    private static final Set<String> ARITHMETIC = Set.of("+", "-", "*", "/", "%");
    private String function;
    private int temporary, label;
    private record Loop(String continueLabel, String endLabel) { }
    private record Target(String name, boolean address) { }

    public IntermediateResult generate(Program program) {
        quads.clear(); notations.clear(); recorded.clear(); functions.clear(); loops.clear();
        shown.clear(); aliases.clear(); temporary = label = 0;
        for (FunctionDecl f : program.functions()) functions.put(f.name(), f);
        for (FunctionDecl f : program.functions()) {
            function = f.name();
            emit("FUNC", type(f.returnType()), "" + f.parameters().size(), f.name(), f.span(),
                    "Iniciar la traducción de la función " + f.name() + "; sus variables pertenecen a esta función.");
            for (int i = 0; i < f.parameters().size(); i++) {
                Parameter p = f.parameters().get(i);
                emit("PARAM_DEF", type(p.type()), "" + i, p.name(), p.span(), "Registrar el parámetro " + p.name() + " en la posición " + (i + 1) + ".");
            }
            statement(f.body());
            // Retorno implícito de void. En otras funciones, la semántica ya exige un retorno o un bucle sin salida.
            if (type(f.returnType()).equals("void")) emit("RETURN", "", "", "", f.body().span(), "Retorno implícito al finalizar una función void.");
            emit("END_FUNC", "", "", f.name(), f.span(), "Finalizar la traducción de " + f.name() + ".");
        }
        return mathView();
    }
    /** Arma lo que se muestra: sólo las operaciones matemáticas, con temporales renumerados sin huecos. */
    private IntermediateResult mathView() {
        List<IntermediateResult.Quadruple> visible = quads.stream().filter(q -> shown.contains(q.number())).toList();
        Map<String, String> renamed = new HashMap<>();
        for (var q : visible) if (q.result().startsWith("$t") && !aliases.containsKey(q.result()))
            renamed.putIfAbsent(q.result(), "$t" + (renamed.size() + 1));
        List<IntermediateResult.Quadruple> math = new ArrayList<>(); List<Integer> origin = new ArrayList<>();
        for (var q : visible) {
            math.add(new IntermediateResult.Quadruple(math.size() + 1, q.function(), q.operator(), shownName(q.argument1(), renamed),
                    shownName(q.argument2(), renamed), shownName(q.result(), renamed), q.span(), shownText(q.explanation(), renamed)));
            origin.add(q.number());
        }
        return new IntermediateResult(math, notations, quads, origin);
    }
    private String shownName(String name, Map<String, String> renamed) {
        String alias = aliases.get(name);
        return alias != null ? alias : renamed.getOrDefault(name, name);
    }
    private String shownText(String text, Map<String, String> renamed) {
        var m = java.util.regex.Pattern.compile("\\$t\\d+").matcher(text);
        return m.replaceAll(r -> java.util.regex.Matcher.quoteReplacement(shownName(r.group(), renamed)));
    }
    private static boolean hasMath(Expression e) {
        if (e instanceof BinaryExpr b) return ARITHMETIC.contains(b.operator()) || hasMath(b.left()) || hasMath(b.right());
        if (e instanceof UnaryExpr u) return u.operator().equals("-") || hasMath(u.operand());
        if (e instanceof CallExpr c) return hasMath(c.callee()) || c.arguments().stream().anyMatch(IntermediateGenerator::hasMath);
        if (e instanceof IndexExpr i) return hasMath(i.target()) || hasMath(i.index());
        if (e instanceof LengthExpr l) return hasMath(l.target());
        if (e instanceof ArrayLiteral a) return a.elements().stream().anyMatch(IntermediateGenerator::hasMath);
        return false;
    }
    private static boolean hasMath(AssignmentStmt a) { return !a.operator().equals("=") || hasMath(a.value()) || hasMath(a.target()); }
    private String temp() { return "$t" + (++temporary); }
    private String label() { return "$L" + (++label); }
    private void emit(String op, String a, String b, String r, SourceSpan s, String why) {
        quads.add(new IntermediateResult.Quadruple(quads.size() + 1, function, op, a, b, r, s, why));
    }
    private void emitShown(String op, String a, String b, String r, SourceSpan s, String why) {
        emit(op, a, b, r, s, why); shown.add(quads.size());
    }
    private void mark(String l, SourceSpan s) { emit("LABEL", "", "", l, s, "Definir el destino de salto " + l + "."); }
    private void go(String l, SourceSpan s) { emit("GOTO", "", "", l, s, "Continuar en " + l + "."); }
    private String copy(String v, SourceSpan s, String reason) {
        String t = temp(); emit("=", v, "", t, s, reason); return t;
    }
    /** Registra la expresión raíz sólo si contiene una operación matemática. */
    private void notation(Expression e, String context) { if (hasMath(e)) record(e, context); }
    private void record(Expression e, String context) {
        if (!recorded.add(e)) return;
        var f = ExpressionNotation.of(e);
        notations.add(new IntermediateResult.Notation(notations.size() + 1, function, context,
                f.infix(), f.postfix(), f.prefix(), e.span()));
        if (e instanceof BinaryExpr b) { record(b.left(), "Operando izquierdo"); record(b.right(), "Operando derecho"); }
        else if (e instanceof UnaryExpr u) record(u.operand(), "Operando unario");
        else if (e instanceof CallExpr c) { record(c.callee(), "Función llamada"); for (Expression a : c.arguments()) record(a, "Argumento"); }
        else if (e instanceof IndexExpr i) { record(i.target(), "Arreglo"); record(i.index(), "Índice"); }
        else if (e instanceof LengthExpr l) record(l.target(), "Arreglo de .len");
        else if (e instanceof ArrayLiteral a) for (Expression item : a.elements()) record(item, "Elemento del arreglo");
    }
    private void assignmentNotation(Expression target, String op, Expression value, SourceSpan s, String context) {
        var t = ExpressionNotation.of(target); var v = ExpressionNotation.of(value);
        notations.add(new IntermediateResult.Notation(notations.size() + 1, function, context,
                t.infix() + " " + op + " " + v.infix(), t.postfix() + " " + v.postfix() + " " + op,
                op + " " + t.prefix() + " " + v.prefix(), s));
        record(target, "Destino"); record(value, "Valor de la asignación");
    }
    private void statement(Statement s) {
        if (s instanceof Block b) { for (Statement item : b.statements()) statement(item); }
        else if (s instanceof VariableDecl d) {
            if (hasMath(d.initializer())) assignmentNotation(new IdentifierExpr(d.name(), d.span()), "=", d.initializer(), d.span(), d.constant() ? "Declaración const" : "Declaración var");
            emit(d.constant() ? "CONST" : "VAR", d.declaredType().map(ExpressionNotation::type).orElse("inferido"), "", d.name(), d.span(), "Reservar " + d.name() + " para su inicializador.");
            String value = expression(d.initializer());
            if (hasMath(d.initializer())) emitShown("=", value, "", d.name(), d.span(), "Guardar " + value + " en " + d.name() + ".");
            else emit("=", value, "", d.name(), d.span(), "Guardar " + value + " en " + d.name() + ".");
        } else if (s instanceof AssignmentStmt a) {
            if (hasMath(a)) assignmentNotation(a.target(), a.operator(), a.value(), a.span(), "Asignación");
            if (a.target() instanceof IdentifierExpr id && id.name().equals("_")) {
                emit("DISCARD", expression(a.value()), "", "", a.span(), "Descartar el valor, conservando las operaciones que lo producen."); return;
            }
            Target target = target(a.target());
            String old = null;
            if (!a.operator().equals("=")) {
                if (target.address()) { old = temp(); emit("LOAD", target.name(), "", old, a.target().span(), "Leer una vez el elemento que se actualizará."); aliases.put(old, ExpressionNotation.of(a.target()).infix()); }
                else { old = copy(target.name(), a.target().span(), "Conservar el valor anterior para la asignación compuesta."); aliases.put(old, target.name()); }
            }
            String value = expression(a.value());
            if (old != null) {
                String t = temp(); String op = a.operator().substring(0, 1);
                emitShown(op, old, value, t, a.span(), "Descomponer " + a.operator() + " en una operación " + op + " y una asignación."); value = t;
            }
            if (!target.address() && hasMath(a)) emitShown("=", value, "", target.name(), a.span(), "Guardar " + value + " en el destino de la asignación.");
            else emit(target.address() ? "STORE" : "=", value, "", target.name(), a.span(), "Guardar " + value + " en el destino de la asignación.");
        } else if (s instanceof ExpressionStmt e) { notation(e.expression(), "Expresión como sentencia"); expression(e.expression()); }
        else if (s instanceof ReturnStmt r) {
            r.value().ifPresent(v -> notation(v, "Retorno"));
            emit("RETURN", r.value().map(this::expression).orElse(""), "", "", r.span(), "Devolver el valor al llamador y terminar esta función.");
        } else if (s instanceof IfStmt i) {
            notation(i.condition(), "Condición if"); String no = label(), end = label();
            emit("IF_FALSE", expression(i.condition()), "", no, i.condition().span(), "Si la condición es falsa, omitir la rama if.");
            statement(i.thenBlock());
            if (i.elseBranch().isPresent()) { go(end, i.span()); mark(no, i.span()); statement(i.elseBranch().get()); mark(end, i.span()); }
            else mark(no, i.span());
        } else if (s instanceof WhileStmt w) {
            notation(w.condition(), "Condición while"); String head = label(), end = label(); mark(head, w.span());
            emit("IF_FALSE", expression(w.condition()), "", end, w.condition().span(), "Volver a evaluar la condición en cada iteración; salir si es falsa.");
            loops.push(new Loop(head, end)); statement(w.body()); loops.pop(); go(head, w.span()); mark(end, w.span());
        } else if (s instanceof ForStmt f) forLoop(f);
        else if (s instanceof SwitchStmt sw) switchStatement(sw);
        else if (s instanceof BreakStmt b) go(loops.getFirst().endLabel(), b.span());
        else if (s instanceof ContinueStmt c) go(loops.getFirst().continueLabel(), c.span());
        else throw new IllegalArgumentException("Sentencia no soportada: " + s.getClass());
    }
    private void forLoop(ForStmt f) {
        String index = temp(), endValue, array = null;
        if (f.source() instanceof RangeSource r) {
            notation(r.start(), "Inicio del rango"); notation(r.end(), "Fin exclusivo del rango");
            String start = expression(r.start());
            emit("=", start, "", index, r.start().span(), "Inicializar el contador del rango.");
            endValue = copy(expression(r.end()), r.end().span(), "Evaluar una sola vez el límite superior exclusivo del rango.");
        } else {
            Expression src = ((IterableSource)f.source()).expression(); notation(src, "Fuente del for");
            array = copy(expression(src), src.span(), "Evaluar una vez el arreglo recorrido y conservar su valor.");
            endValue = temp(); emit("LEN", array, "", endValue, src.span(), "Obtener la longitud del arreglo recorrido.");
            emit("=", "0", "", index, f.span(), "Iniciar el índice del recorrido en cero.");
        }
        String head = label(), next = label(), end = label(), condition = temp(); mark(head, f.span());
        emit("<", index, endValue, condition, f.span(), "Comparar el índice con el límite exclusivo del for.");
        emit("IF_FALSE", condition, "", end, f.span(), "Salir del for cuando se agotan los elementos.");
        if (!f.capture().equals("_")) {
            emit("PARAM_CAPTURE", "", "", f.capture(), f.span(), "Crear la captura inmutable de esta iteración.");
            if (array == null) emit("=", index, "", f.capture(), f.span(), "La captura contiene el valor actual del rango.");
            else emit("LOAD_INDEX", array, index, f.capture(), f.span(), "Leer el elemento actual del arreglo en la captura.");
        }
        loops.push(new Loop(next, end)); statement(f.body()); loops.pop(); mark(next, f.span());
        String increment = temp(); emit("+", index, "1", increment, f.span(), "Incrementar el contador; continue salta a este paso.");
        emit("=", increment, "", index, f.span(), "Actualizar el contador del for."); go(head, f.span()); mark(end, f.span());
    }
    private void switchStatement(SwitchStmt sw) {
        notation(sw.subject(), "Selector switch");
        String subject = copy(expression(sw.subject()), sw.subject().span(), "Evaluar una sola vez el selector del switch.");
        String end = label(); List<String> labels = sw.cases().stream().map(c -> label()).toList(); String otherwise = end;
        for (int i = 0; i < sw.cases().size(); i++) {
            SwitchCase c = sw.cases().get(i); if (c.elseCase()) { otherwise = labels.get(i); continue; }
            for (Expression v : c.labels()) {
                notation(v, "Etiqueta de caso"); String cmp = temp();
                emit("==", subject, expression(v), cmp, v.span(), "Comparar el selector con una etiqueta de caso.");
                emit("IF_TRUE", cmp, "", labels.get(i), v.span(), "Si coincide, ejecutar sólo el cuerpo de este caso.");
            }
        }
        go(otherwise, sw.span());
        for (int i = 0; i < sw.cases().size(); i++) {
            SwitchCase c = sw.cases().get(i); mark(labels.get(i), c.span()); statement(c.body()); go(end, c.span());
        }
        mark(end, sw.span());
    }
    private Target target(Expression e) {
        if (e instanceof IdentifierExpr i) return new Target(i.name(), false);
        IndexExpr i = (IndexExpr)e; Target base = target(i.target()); String index = expression(i.index()), address = temp();
        emit("INDEX_ADDR", base.address() ? base.name() : "&" + base.name(), index, address, i.span(), "Calcular una vez la dirección del elemento que se modificará.");
        return new Target(address, true);
    }
    private String expression(Expression e) {
        if (e instanceof IdentifierExpr i) return i.name();
        if (e instanceof IntegerLiteral l) return l.image();
        if (e instanceof FloatLiteral l) return l.image();
        if (e instanceof BooleanLiteral l) return "" + l.value();
        if (e instanceof CharLiteral l) return l.image();
        if (e instanceof BinaryExpr b) {
            if (b.operator().equals("and") || b.operator().equals("or")) return logical(b);
            String left = expression(b.left()), right = expression(b.right()), t = temp();
            String why = "Traducir " + ExpressionNotation.of(b).infix() + ": usar los operandos " + left + " y " + right + "; guardar el resultado en " + t + ".";
            if (ARITHMETIC.contains(b.operator())) emitShown(b.operator(), left, right, t, b.span(), why);
            else emit(b.operator(), left, right, t, b.span(), why);
            return t;
        }
        if (e instanceof UnaryExpr u) {
            String a = expression(u.operand()), t = temp(), op = u.operator().equals("-") ? "NEG" : "!";
            if (op.equals("NEG")) emitShown(op, a, "", t, u.span(), "Aplicar el operador unario " + op + " a " + a + ".");
            else emit(op, a, "", t, u.span(), "Aplicar el operador unario " + op + " a " + a + ".");
            return t;
        }
        if (e instanceof CallExpr c) {
            String name = ((IdentifierExpr)c.callee()).name();
            // Primero todos los valores; después PARAM. Una llamada anidada no consume los argumentos externos.
            List<String> args = c.arguments().stream().map(this::expression).toList();
            for (int i = 0; i < args.size(); i++) emit("PARAM", args.get(i), "" + i, "", c.arguments().get(i).span(), "Preparar el argumento " + (i + 1) + " de " + name + ".");
            String t = type(functions.get(name).returnType()).equals("void") ? "" : temp();
            emit("CALL", name, "" + args.size(), t, c.span(), "Llamar a " + name + " con " + args.size() + " argumento(s)" + (t.isEmpty() ? " sin valor de retorno." : "; conservar el retorno en " + t + "."));
            if (!t.isEmpty()) aliases.put(t, ExpressionNotation.of(c).infix());
            return t.isEmpty() ? "void" : t;
        }
        if (e instanceof ArrayLiteral a) {
            // Evaluar antes de crear el arreglo conserva el orden incluso con literales anidados.
            List<String> values = a.elements().stream().map(this::expression).toList(); String t = temp();
            emit("ARRAY", "" + values.size(), type(a.elementType()), t, a.span(), "Crear un arreglo de " + values.size() + " elemento(s).");
            for (int i = 0; i < values.size(); i++) emit("STORE_INDEX", values.get(i), "" + i, t, a.elements().get(i).span(), "Inicializar la posición " + i + " del arreglo " + t + ".");
            aliases.put(t, ExpressionNotation.of(a).infix());
            return t;
        }
        if (e instanceof IndexExpr i) {
            String base = expression(i.target()), index = expression(i.index()), t = temp();
            emit("LOAD_INDEX", base, index, t, i.span(), "Leer el elemento " + index + " del arreglo " + base + ".");
            aliases.put(t, ExpressionNotation.of(i).infix());
            return t;
        }
        if (e instanceof LengthExpr l) { String base = expression(l.target()), t = temp(); emit("LEN", base, "", t, l.span(), "Obtener la longitud de " + base + "."); aliases.put(t, ExpressionNotation.of(l).infix()); return t; }
        throw new IllegalArgumentException("Expresión no soportada: " + e.getClass());
    }
    private String logical(BinaryExpr b) {
        String t = temp(), end = label(); boolean and = b.operator().equals("and");
        emit("=", and ? "false" : "true", "", t, b.span(), "Preparar el valor de cortocircuito de " + b.operator() + ".");
        emit(and ? "IF_FALSE" : "IF_TRUE", expression(b.left()), "", end, b.left().span(), "Omitir el operando derecho cuando el izquierdo determina el resultado lógico.");
        String right = expression(b.right()); emit("=", right, "", t, b.right().span(), "Usar el operando derecho sólo si es necesario."); mark(end, b.span()); return t;
    }
}
