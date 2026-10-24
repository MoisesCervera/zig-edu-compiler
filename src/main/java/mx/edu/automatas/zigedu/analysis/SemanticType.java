package mx.edu.automatas.zigedu.analysis;

import java.math.BigInteger;

/** Tipos de la fase semántica; no modifica los nodos sintácticos originales. */
public record SemanticType(String name, BigInteger size, SemanticType element) {
    public static final SemanticType I32 = primitive("i32");
    public static final SemanticType U8 = primitive("u8");
    public static final SemanticType F64 = primitive("f64");
    public static final SemanticType BOOL = primitive("bool");
    public static final SemanticType VOID = primitive("void");
    public static final SemanticType INT_LITERAL = primitive("entero sin tipo");
    public static final SemanticType FLOAT_LITERAL = primitive("decimal sin tipo");
    public static final SemanticType ERROR = primitive("<error>");

    public static SemanticType primitive(String name) {
        return new SemanticType(name, null, null);
    }

    public static SemanticType array(BigInteger size, SemanticType element) {
        return new SemanticType("array", size, element);
    }

    public boolean isArray() { return element != null; }
    public boolean isInteger() { return equals(I32) || equals(U8) || equals(INT_LITERAL); }
    public boolean isNumeric() { return isInteger() || equals(F64) || equals(FLOAT_LITERAL); }
    public boolean isError() { return equals(ERROR); }

    @Override public String toString() {
        return isArray() ? "[" + size + "]" + element : name;
    }
}
