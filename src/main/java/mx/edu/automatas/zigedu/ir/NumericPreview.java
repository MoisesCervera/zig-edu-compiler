package mx.edu.automatas.zigedu.ir;

import java.math.BigInteger;
import java.util.*;

/** Evaluación acotada de la IR para acompañar las notaciones con valores reales. */
public final class NumericPreview {
    private static final int MAX_STEPS = 50_000, MAX_DEPTH = 100, MAX_ARRAY = 10_000, MAX_SAMPLES = 8;
    private final Map<String, List<IntermediateResult.Quadruple>> functions = new LinkedHashMap<>();
    private final Map<Integer, History> histories = new HashMap<>();
    private int remaining = MAX_STEPS;

    public record Values(List<String> samples, int count, String last) {
        public Values { samples = List.copyOf(samples); }
        public String text() {
            if (count == 1) return "Resultado numérico: " + last;
            return "Resultados numéricos (" + count + " evaluaciones): " + String.join(", ", samples)
                    + (count > samples.size() ? ", … (último: " + last + ")" : "");
        }
    }
    private static final class History {
        final List<String> samples = new ArrayList<>();
        int count;
        String last;
        void add(Num value) {
            last = value.integer() == null ? Double.toString(value.decimal()) : value.integer().toString();
            if (samples.size() < MAX_SAMPLES) samples.add(last);
            count++;
        }
    }
    private record Num(BigInteger integer, Double decimal, String type) { }
    private record ArrayValue(String elementType, List<Object> values) { }
    private record Address(ArrayValue array, int index) { }
    private record Slot(String type, boolean constant) { }
    private enum Empty { VALUE }
    private static final class Unavailable extends RuntimeException { }

    private NumericPreview(IntermediateResult ir) {
        for (var q : ir.quadruples()) functions.computeIfAbsent(q.function(), key -> new ArrayList<>()).add(q);
    }

    /** No asigna valores ficticios a parámetros. Un fallo de evaluación no cambia los diagnósticos. */
    public static Map<Integer, Values> evaluate(IntermediateResult ir) {
        NumericPreview preview = new NumericPreview(ir);
        try {
            if (preview.functions.containsKey("main")) {
                if (!preview.functions.get("main").getFirst().argument2().equals("0")) return Map.of();
                preview.call("main", List.of(), 0);
            } else {
                for (var entry : preview.functions.entrySet()) {
                    if (entry.getValue().getFirst().argument2().equals("0")) preview.call(entry.getKey(), List.of(), 0);
                }
            }
        } catch (Unavailable | ArithmeticException failure) {
            // Evitar números parciales o engañosos en programas que no terminan o fallan al evaluarse.
            return Map.of();
        }
        Map<Integer, Values> result = new HashMap<>();
        preview.histories.forEach((number, h) -> result.put(number, new Values(h.samples, h.count, h.last)));
        return Map.copyOf(result);
    }

    private Object call(String name, List<Object> arguments, int depth) {
        if (depth >= MAX_DEPTH) throw new Unavailable();
        List<IntermediateResult.Quadruple> code = functions.get(name);
        if (code == null) throw new Unavailable();
        Map<String, Object> vars = new HashMap<>();
        Map<String, Slot> slots = new HashMap<>();
        Map<String, Integer> labels = new HashMap<>();
        for (int i = 0; i < code.size(); i++) if (code.get(i).operator().equals("LABEL")) labels.put(code.get(i).result(), i);
        List<Object> pending = new ArrayList<>();
        for (int pc = 0; pc < code.size(); pc++) {
            if (--remaining < 0) throw new Unavailable();
            var q = code.get(pc);
            String op = q.operator(), a = q.argument1(), b = q.argument2(), r = q.result();
            Object produced = Empty.VALUE;
            switch (op) {
                case "FUNC", "END_FUNC", "LABEL" -> { }
                case "VAR", "CONST", "PARAM_CAPTURE" -> {
                    slots.put(r, new Slot(op.equals("PARAM_CAPTURE") ? "inferido" : a, !op.equals("VAR")));
                    vars.put(r, Empty.VALUE);
                }
                case "PARAM_DEF" -> {
                    int index = Integer.parseInt(b);
                    if (index >= arguments.size()) throw new Unavailable();
                    slots.put(r, new Slot(a, false));
                    produced = assign(r, arguments.get(index), vars, slots);
                }
                case "=" -> produced = assign(r, value(a, vars), vars, slots);
                case "PARAM" -> pending.add(copy(value(a, vars)));
                case "CALL" -> {
                    produced = call(a, pending, depth + 1); pending.clear();
                    if (!r.isEmpty()) vars.put(r, copy(produced));
                }
                case "RETURN" -> {
                    produced = a.isEmpty() ? Empty.VALUE : coerce(value(a, vars), code.getFirst().argument1());
                    remember(q.number(), produced);
                    return copy(produced);
                }
                case "DISCARD" -> produced = value(a, vars);
                case "GOTO" -> pc = destination(labels, r);
                case "IF_FALSE" -> { if (!bool(value(a, vars))) pc = destination(labels, r); }
                case "IF_TRUE" -> { if (bool(value(a, vars))) pc = destination(labels, r); }
                case "ARRAY" -> {
                    int size = Integer.parseInt(a);
                    if (size < 0 || size > MAX_ARRAY) throw new Unavailable();
                    vars.put(r, new ArrayValue(b, new ArrayList<>(Collections.nCopies(size, Empty.VALUE))));
                }
                case "LEN" -> {
                    produced = integer(BigInteger.valueOf(array(value(a, vars)).values().size()), "literal"); vars.put(r, produced);
                }
                case "LOAD_INDEX" -> {
                    ArrayValue array = array(value(a, vars));
                    produced = copy(array.values().get(index(value(b, vars), array)));
                    vars.put(r, produced);
                }
                case "STORE_INDEX" -> {
                    ArrayValue array = array(value(r, vars));
                    produced = coerce(value(a, vars), array.elementType());
                    array.values().set(index(integer(new BigInteger(b), "literal"), array), copy(produced));
                }
                case "INDEX_ADDR" -> {
                    Object base = value(a.startsWith("&") ? a.substring(1) : a, vars);
                    if (base instanceof Address address) base = address.array().values().get(address.index());
                    ArrayValue array = array(base);
                    vars.put(r, new Address(array, index(value(b, vars), array)));
                }
                case "LOAD" -> {
                    Address address = address(value(a, vars));
                    produced = copy(address.array().values().get(address.index())); vars.put(r, produced);
                }
                case "STORE" -> {
                    Address address = address(value(r, vars));
                    produced = coerce(value(a, vars), address.array().elementType());
                    address.array().values().set(address.index(), copy(produced));
                }
                case "NEG" -> {
                    Num n = number(value(a, vars));
                    produced = n.integer() == null ? decimal(-n.decimal(), n.type()) : integer(n.integer().negate(), n.type());
                    vars.put(r, produced);
                }
                case "!" -> { produced = !bool(value(a, vars)); vars.put(r, produced); }
                default -> { produced = binary(op, value(a, vars), value(b, vars)); vars.put(r, produced); }
            }
            remember(q.number(), produced);
        }
        return Empty.VALUE;
    }

    private void remember(int number, Object value) {
        if (value instanceof Num numeric) histories.computeIfAbsent(number, key -> new History()).add(numeric);
    }
    private static int destination(Map<String, Integer> labels, String name) {
        Integer pc = labels.get(name); if (pc == null) throw new Unavailable(); return pc;
    }
    private static Object assign(String name, Object value, Map<String, Object> vars, Map<String, Slot> slots) {
        Slot slot = slots.get(name);
        if (slot != null) {
            String type = slot.type();
            if (type.equals("inferido")) {
                if (value instanceof Num n) {
                    type = n.type();
                    if (!slot.constant() && type.equals("literal")) type = "i32";
                    if (!slot.constant() && type.equals("decimal")) type = "f64";
                } else if (value instanceof Boolean) type = "bool";
                else if (value instanceof ArrayValue) type = "array";
                slots.put(name, new Slot(type, slot.constant()));
            }
            value = coerce(value, type);
        }
        value = copy(value); vars.put(name, value); return value;
    }
    private static Object value(String text, Map<String, Object> vars) {
        if (vars.containsKey(text)) {
            Object value = vars.get(text); if (value == Empty.VALUE) throw new Unavailable(); return value;
        }
        if (text.equals("void")) return Empty.VALUE;
        if (text.equals("true") || text.equals("false")) return Boolean.valueOf(text);
        if (text.startsWith("'")) {
            String body = text.substring(1, text.length() - 1);
            int character = body.startsWith("\\") ? switch (body.charAt(1)) {
                case 'n' -> 10; case 'r' -> 13; case 't' -> 9; case '\\' -> 92; case '\'' -> 39;
                default -> throw new Unavailable();
            } : body.codePointAt(0);
            return integer(BigInteger.valueOf(character), "literal");
        }
        if (text.isEmpty() || !Character.isDigit(text.charAt(0))) throw new Unavailable();
        if (text.contains(".") || text.contains("e") || text.contains("E")) return decimal(Double.parseDouble(text), "decimal");
        return integer(new BigInteger(text), "literal");
    }
    private static Object binary(String op, Object left, Object right) {
        if (left instanceof Boolean a && right instanceof Boolean b) return switch (op) {
            case "==" -> a.equals(b); case "!=" -> !a.equals(b); default -> throw new Unavailable();
        };
        Num a = number(left), b = number(right);
        String type = common(a.type(), b.type());
        a = number(coerce(a, type)); b = number(coerce(b, type));
        if (Set.of("==", "!=", "<", "<=", ">", ">=").contains(op)) {
            int order = a.integer() != null ? a.integer().compareTo(b.integer())
                    : a.decimal().doubleValue() == b.decimal().doubleValue() ? 0 : Double.compare(a.decimal(), b.decimal());
            return switch (op) {
                case "==" -> order == 0; case "!=" -> order != 0; case "<" -> order < 0;
                case "<=" -> order <= 0; case ">" -> order > 0; default -> order >= 0;
            };
        }
        if (a.integer() != null) {
            BigInteger x = a.integer(), y = b.integer();
            return integer(switch (op) {
                case "+" -> x.add(y); case "-" -> x.subtract(y); case "*" -> x.multiply(y);
                case "/" -> x.divide(y); case "%" -> x.remainder(y); default -> throw new Unavailable();
            }, type);
        }
        double x = a.decimal(), y = b.decimal();
        if (op.equals("/") && y == 0.0) throw new Unavailable();
        return decimal(switch (op) {
            case "+" -> x + y; case "-" -> x - y; case "*" -> x * y; case "/" -> x / y;
            default -> throw new Unavailable();
        }, type);
    }
    private static String common(String a, String b) {
        if (a.equals(b)) return a;
        if (a.equals("literal")) return b;
        if (b.equals("literal")) return a;
        if (Set.of(a, b).equals(Set.of("i32", "u8"))) return "i32";
        if (Set.of(a, b).equals(Set.of("decimal", "f64"))) return "f64";
        throw new Unavailable();
    }
    private static Object coerce(Object value, String type) {
        if (value instanceof Num n) {
            if (type.equals("f64") || type.equals("decimal")) return decimal(n.integer() == null ? n.decimal() : n.integer().doubleValue(), type);
            if (Set.of("i32", "u8", "literal").contains(type) && n.integer() != null) return integer(n.integer(), type);
            throw new Unavailable();
        }
        if (value instanceof ArrayValue array && type.startsWith("[")) {
            String element = type.substring(type.indexOf(']') + 1);
            List<Object> items = new ArrayList<>();
            for (Object item : array.values()) items.add(coerce(item, element));
            return new ArrayValue(element, items);
        }
        return value;
    }
    private static Num integer(BigInteger value, String type) {
        if (value.bitLength() > 4096) throw new Unavailable();
        if (type.equals("i32") && (value.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                || value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0)) throw new Unavailable();
        if (type.equals("u8") && (value.signum() < 0 || value.compareTo(BigInteger.valueOf(255)) > 0)) throw new Unavailable();
        return new Num(value, null, type);
    }
    private static Num decimal(double value, String type) {
        if (!Double.isFinite(value)) throw new Unavailable(); return new Num(null, value, type);
    }
    private static Num number(Object value) { if (value instanceof Num n) return n; throw new Unavailable(); }
    private static boolean bool(Object value) { if (value instanceof Boolean b) return b; throw new Unavailable(); }
    private static ArrayValue array(Object value) { if (value instanceof ArrayValue a) return a; throw new Unavailable(); }
    private static Address address(Object value) { if (value instanceof Address a) return a; throw new Unavailable(); }
    private static int index(Object value, ArrayValue array) {
        Num n = number(value); if (n.integer() == null) throw new Unavailable();
        int i = n.integer().intValueExact();
        if (i < 0 || i >= array.values().size()) throw new Unavailable(); return i;
    }
    private static Object copy(Object value) {
        if (value instanceof ArrayValue a) {
            List<Object> items = new ArrayList<>(); for (Object item : a.values()) items.add(copy(item));
            return new ArrayValue(a.elementType(), items);
        }
        return value;
    }
}
