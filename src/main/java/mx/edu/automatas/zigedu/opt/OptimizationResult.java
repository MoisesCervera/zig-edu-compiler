package mx.edu.automatas.zigedu.opt;

import java.util.Locale;
import java.util.Optional;

/** Código optimizado y comparación de métricas contra el código original. */
public record OptimizationResult(
        Status status,
        String message,
        String normalizedSource,
        String optimizedSource,
        Optional<CodeMetrics> original,
        Optional<CodeMetrics> optimized
) {
    public enum Status { OPTIMIZED, NOT_RUN, FAILED }

    /**
     * @param operations cuádruplos ejecutados sin contar etiquetas ni declaraciones
     * @param nanos      mejor tiempo de ejecución medido, o -1 si no se pudo ejecutar
     */
    public record CodeMetrics(int lines, int bytes, int tokens, boolean executed, long operations, long nanos) { }

    public static OptimizationResult notRun(String message) {
        return new OptimizationResult(Status.NOT_RUN, message, "", "", Optional.empty(), Optional.empty());
    }

    public static OptimizationResult failed(String message, String normalizedSource) {
        return new OptimizationResult(Status.FAILED, message, normalizedSource, normalizedSource,
                Optional.empty(), Optional.empty());
    }

    public boolean available() { return status == Status.OPTIMIZED; }

    public static String formatNanos(long nanos) {
        if (nanos < 0) return "—";
        if (nanos < 1_000) return nanos + " ns";
        if (nanos < 1_000_000) return String.format(Locale.ROOT, "%.1f µs", nanos / 1_000.0);
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
    }

    public static String formatChange(double before, double after) {
        if (before <= 0) return "—";
        double r = (before - after) * 100.0 / before;
        if (Math.abs(r) < 0.05) return "sin cambio";
        return String.format(Locale.ROOT, "%s%.1f %%", r > 0 ? "−" : "+", Math.abs(r));
    }

    /** Tabla de comparación en texto plano (se exporta a out/bitacora_optimizacion.txt). */
    public String comparisonText() {
        if (!available()) return message + "\n";
        CodeMetrics o = original.orElseThrow(), n = optimized.orElseThrow();
        StringBuilder b = new StringBuilder("Comparación: código original vs. optimizado\n\n");
        b.append(String.format(Locale.ROOT, "%-28s %14s %14s %12s%n", "Métrica", "Original", "Optimizado", "Diferencia"));
        if (o.executed() && n.executed()) {
            row(b, "Tiempo de ejecución", formatNanos(o.nanos()), formatNanos(n.nanos()), formatChange(o.nanos(), n.nanos()));
            row(b, "Instrucciones ejecutadas", o.operations(), n.operations());
        }
        row(b, "Tamaño del archivo (bytes)", o.bytes(), n.bytes());
        row(b, "Líneas de código", o.lines(), n.lines());
        row(b, "Tokens", o.tokens(), n.tokens());
        return b.toString();
    }

    private static void row(StringBuilder b, String label, long before, long after) {
        row(b, label, Long.toString(before), Long.toString(after), formatChange(before, after));
    }

    private static void row(StringBuilder b, String label, String before, String after, String change) {
        b.append(String.format(Locale.ROOT, "%-28s %14s %14s %12s%n", label, before, after, change));
    }
}
