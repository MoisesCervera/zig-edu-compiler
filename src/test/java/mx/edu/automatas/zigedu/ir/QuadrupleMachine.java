package mx.edu.automatas.zigedu.ir;

import java.util.*;

/** Intérprete de referencia EXCLUSIVO de pruebas: valida saltos, llamadas y orden de evaluación. */
final class QuadrupleMachine {
    private final Map<String, List<IntermediateResult.Quadruple>> functions = new HashMap<>();
    private int fuel = 100_000;
    private record Address(List<Object> array, int index) { }
    QuadrupleMachine(IntermediateResult ir) {
        for (var q : ir.quadruples()) functions.computeIfAbsent(q.function(), k -> new ArrayList<>()).add(q);
    }
    Object call(String name, Object... args) {
        List<IntermediateResult.Quadruple> code = functions.get(name);
        Map<String,Object> vars = new HashMap<>(); Map<String,Integer> labels = new HashMap<>();
        for (int i = 0; i < code.size(); i++) if (code.get(i).operator().equals("LABEL")) labels.put(code.get(i).result(), i);
        List<Object> pending = new ArrayList<>();
        for (int pc = 0; pc < code.size(); pc++) {
            if (--fuel < 0) throw new AssertionError("Posible bucle incorrecto en IR");
            var q = code.get(pc); String op = q.operator(), a = q.argument1(), b = q.argument2(), r = q.result();
            switch (op) {
                case "FUNC", "END_FUNC", "LABEL" -> { }
                case "VAR", "CONST", "PARAM_CAPTURE" -> vars.put(r, null);
                case "PARAM_DEF" -> vars.put(r, copy(args[Integer.parseInt(b)]));
                case "=" -> vars.put(r, copy(value(a,vars)));
                case "PARAM" -> pending.add(copy(value(a,vars)));
                case "CALL" -> { Object returned = call(a, pending.toArray()); pending.clear(); if (!r.isEmpty()) vars.put(r, returned); }
                case "RETURN" -> { return a.isEmpty() ? null : copy(value(a,vars)); }
                case "DISCARD" -> value(a,vars);
                case "GOTO" -> pc = labels.get(r);
                case "IF_FALSE" -> { if (!(Boolean)value(a,vars)) pc = labels.get(r); }
                case "IF_TRUE" -> { if ((Boolean)value(a,vars)) pc = labels.get(r); }
                case "ARRAY" -> vars.put(r, new ArrayList<>(Collections.nCopies(Integer.parseInt(a), 0)));
                case "LEN" -> vars.put(r, ((List<?>)value(a,vars)).size());
                case "LOAD_INDEX" -> vars.put(r, copy(((List<?>)value(a,vars)).get(((Number)value(b,vars)).intValue())));
                case "STORE_INDEX" -> list(vars.get(r)).set(Integer.parseInt(b), copy(value(a,vars)));
                case "INDEX_ADDR" -> {
                    Object base = a.startsWith("&") ? vars.get(a.substring(1)) : vars.get(a);
                    if (base instanceof Address addr) base = addr.array().get(addr.index());
                    vars.put(r, new Address(list(base), ((Number)value(b,vars)).intValue()));
                }
                case "LOAD" -> { Address addr = (Address)vars.get(a); vars.put(r, copy(addr.array().get(addr.index()))); }
                case "STORE" -> { Address addr = (Address)vars.get(r); addr.array().set(addr.index(), copy(value(a,vars))); }
                case "NEG" -> { Number n = (Number)value(a,vars); if (n instanceof Double) vars.put(r,-n.doubleValue()); else vars.put(r,-n.intValue()); }
                case "!" -> vars.put(r, !(Boolean)value(a,vars));
                default -> vars.put(r, binary(op,value(a,vars),value(b,vars)));
            }
        }
        return null;
    }
    private Object value(String s,Map<String,Object> vars) {
        if (vars.containsKey(s)) return vars.get(s);
        if (s.equals("void")) return null;
        if (s.equals("true") || s.equals("false")) return Boolean.valueOf(s);
        if (s.startsWith("'")) {
            String body=s.substring(1,s.length()-1); if (!body.startsWith("\\")) return body.codePointAt(0);
            return switch(body.charAt(1)) { case 'n' -> 10; case 'r' -> 13; case 't' -> 9; case '\\' -> 92; case '\'' -> 39; default -> throw new AssertionError(s); };
        }
        if (s.contains(".") || s.contains("e") || s.contains("E")) return Double.valueOf(s);
        return Integer.valueOf(s);
    }
    private Object binary(String op,Object a,Object b) {
        if (op.equals("==")) return a.equals(b); if (op.equals("!=")) return !a.equals(b);
        Number x=(Number)a,y=(Number)b; double xd=x.doubleValue(), yd=y.doubleValue();
        if (Set.of("<","<=",">",">=").contains(op)) return switch(op) { case "<" -> xd<yd; case "<=" -> xd<=yd; case ">" -> xd>yd; default -> xd>=yd; };
        if (x instanceof Double || y instanceof Double) return switch(op) { case "+" -> xd+yd; case "-" -> xd-yd; case "*" -> xd*yd; case "/" -> xd/yd; default -> throw new AssertionError(op); };
        int xi=x.intValue(),yi=y.intValue(); return switch(op) { case "+" -> xi+yi; case "-" -> xi-yi; case "*" -> xi*yi; case "/" -> xi/yi; case "%" -> xi%yi; default -> throw new AssertionError(op); };
    }
    @SuppressWarnings("unchecked") private static List<Object> list(Object x) { return (List<Object>)x; }
    private Object copy(Object x) { if (x instanceof List<?> l) { List<Object> result=new ArrayList<>(); for(Object v:l) result.add(copy(v)); return result; } return x; }
}
