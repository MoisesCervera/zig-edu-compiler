package mx.edu.automatas.zigedu.opt;

import mx.edu.automatas.zigedu.analysis.AnalysisResult;
import mx.edu.automatas.zigedu.analysis.SourceAnalyzer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ZigPrinterTest {
    private final SourceAnalyzer analyzer = new SourceAnalyzer();

    private String print(String source) {
        AnalysisResult result = analyzer.analyze(source);
        assertTrue(result.program().isPresent(), source);
        return ZigPrinter.print(result.program().get());
    }

    @Test void keepsOnlyTheParenthesesThatPrecedenceRequires() {
        String printed = print("fn f(a: i32, b: i32, c: i32) bool { return ((a - (b - c)) * -(a + b)) > ((a)) and !(a < b); }");
        assertTrue(printed.contains("return (a - (b - c)) * -(a + b) > a and !(a < b);"), printed);
        String comparison = print("fn f(a: i32, b: i32) bool { return (a < b) == (b < a); }");
        assertTrue(comparison.contains("return a < b == b < a;"), comparison);
    }

    @Test void printsEveryStatementKind() {
        String printed = print("""
                pub fn main() void {
                    var v = [3]u8{ 'a', '\\n', 'c' };
                    v[0] += 1;
                    for (v) |x| { if (x > 1) { continue; } else if (x == 0) { break; } else { _ = x; } }
                    for (0..v.len) |_| {}
                    while (false) {}
                    switch (v[1]) { 'a', 'b' => {}, 'c' => { return; }, else => {}, }
                    { const w = 1.5e3; _ = w; }
                }
                """);
        assertTrue(analyzer.analyze(printed).successful(), printed);
        assertTrue(printed.contains("var v = [3]u8{ 'a', '\\n', 'c' };"), printed);
        assertTrue(printed.contains("'a', 'b' => {},"), printed);
        assertTrue(printed.contains("for (0..v.len) |_| {}"), printed);
    }

    @Test void everyExampleRoundTripsToTheSameProgram() throws Exception {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of("examples"))) {
            files = walk.filter(p -> p.toString().endsWith(".zig")).sorted().toList();
        }
        for (Path file : files) {
            AnalysisResult original = analyzer.analyze(Files.readString(file));
            if (!original.successful()) continue;
            String once = ZigPrinter.print(original.program().get());
            AnalysisResult again = analyzer.analyze(once);
            assertTrue(again.successful(), file.toString());
            assertEquals(once, ZigPrinter.print(again.program().get()), file.toString());
            assertEquals(original.intermediate().get().quadruples().size(),
                    again.intermediate().get().quadruples().size(), file.toString());
        }
    }
}
