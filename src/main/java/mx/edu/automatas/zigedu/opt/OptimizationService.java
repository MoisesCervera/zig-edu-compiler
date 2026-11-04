package mx.edu.automatas.zigedu.opt;

import mx.edu.automatas.zigedu.analysis.AnalysisResult;
import mx.edu.automatas.zigedu.analysis.SourceAnalyzer;
import mx.edu.automatas.zigedu.ast.Ast.*;
import mx.edu.automatas.zigedu.ir.IntermediateResult;
import mx.edu.automatas.zigedu.ir.NumericPreview;
import mx.edu.automatas.zigedu.opt.OptimizationResult.CodeMetrics;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Optimiza el programa, vuelve a analizar el código generado y compara ambas versiones ejecutando sus
 * cuádruplos en la máquina del compilador.
 */
public final class OptimizationService {
    private static final long MAX_INSTRUCTIONS = 3_000_000;
    private static final long TIMING_BUDGET_NANOS = 250_000_000L;

    private final SourceAnalyzer analyzer = new SourceAnalyzer();

    public OptimizationResult optimize(AnalysisResult analysis) {
        if (!analysis.successful() || analysis.program().isEmpty() || analysis.intermediate().isEmpty()) {
            return OptimizationResult.notRun(
                    "La optimización no se ejecutó: primero deben corregirse los errores léxicos, sintácticos o semánticos.");
        }
        Program program = analysis.program().get();
        String normalized = ZigPrinter.print(program);
        CodeOptimizer.Output output;
        try {
            output = new CodeOptimizer().optimize(program);
        } catch (RuntimeException failure) {
            return OptimizationResult.failed("No fue posible optimizar el código: " + failure.getMessage(), normalized);
        }
        String optimized = ZigPrinter.print(output.program());
        AnalysisResult check = analyzer.analyze(optimized);
        AnalysisResult verified = output.verified() == output.program() ? check
                : analyzer.analyze(ZigPrinter.print(output.verified()));
        if (!check.successful() || check.intermediate().isEmpty() || verified.intermediate().isEmpty()) {
            return OptimizationResult.failed("El código optimizado no pasó la verificación; se conserva el original.", normalized);
        }

        Set<String> outputs = mainVariables(program);
        IntermediateResult before = analysis.intermediate().get(), after = check.intermediate().get();
        NumericPreview.Execution original = NumericPreview.execute(before, MAX_INSTRUCTIONS, outputs);
        NumericPreview.Execution reference = NumericPreview.execute(verified.intermediate().get(), MAX_INSTRUCTIONS, outputs);
        if (original.completed() && reference.completed() && !original.observations().equals(reference.observations())) {
            return OptimizationResult.failed("El código optimizado produjo resultados distintos; se conserva el original.", normalized);
        }
        NumericPreview.Execution run = NumericPreview.execute(after, MAX_INSTRUCTIONS, outputs);
        long[] times = original.completed() && run.completed() ? bestTimes(before, after) : new long[]{-1, -1};

        return new OptimizationResult(OptimizationResult.Status.OPTIMIZED, "", normalized, optimized,
                Optional.of(metrics(normalized, analysis, original, times[0])),
                Optional.of(metrics(optimized, check, run, times[1])));
    }

    private static Set<String> mainVariables(Program program) {
        Set<String> names = new TreeSet<>();
        for (FunctionDecl f : program.functions()) {
            if (!f.name().equals("main")) continue;
            for (Statement s : f.body().statements()) if (s instanceof VariableDecl d) names.add(d.name());
        }
        return names;
    }

    private static long[] bestTimes(IntermediateResult first, IntermediateResult second) {
        long deadline = System.nanoTime() + TIMING_BUDGET_NANOS;
        long bestFirst = Long.MAX_VALUE, bestSecond = Long.MAX_VALUE;
        int rounds = 0;
        do {
            bestFirst = Math.min(bestFirst, time(first));
            bestSecond = Math.min(bestSecond, time(second));
            rounds++;
        } while (rounds < 400 && (rounds < 5 || System.nanoTime() < deadline));
        return new long[]{bestFirst, bestSecond};
    }

    private static long time(IntermediateResult ir) {
        long start = System.nanoTime();
        NumericPreview.execute(ir, MAX_INSTRUCTIONS, Set.of());
        return System.nanoTime() - start;
    }

    private static CodeMetrics metrics(String source, AnalysisResult analysis, NumericPreview.Execution run, long nanos) {
        int lines = source.isEmpty() ? 0 : (int) source.lines().count();
        int bytes = source.getBytes(StandardCharsets.UTF_8).length;
        int tokens = (int) analysis.tokens().stream().filter(t -> !t.lexeme().equals("<EOF>")).count();
        return new CodeMetrics(lines, bytes, tokens, run.completed(), run.operations(), nanos);
    }
}
