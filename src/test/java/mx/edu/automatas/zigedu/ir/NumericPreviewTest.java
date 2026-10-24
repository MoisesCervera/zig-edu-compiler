package mx.edu.automatas.zigedu.ir;

import mx.edu.automatas.zigedu.analysis.SourceAnalyzer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NumericPreviewTest {
    private IntermediateResult valid(String source) {
        var result=new SourceAnalyzer().analyze(source);
        assertTrue(result.successful(), () -> result.lexicalErrors()+" "+result.syntacticErrors()+" "+result.semanticErrors());
        return result.intermediate().orElseThrow();
    }
    private List<NumericPreview.Values> assignments(IntermediateResult ir,String function,String variable) {
        var values=NumericPreview.evaluate(ir);
        return ir.executable().stream().filter(q -> q.function().equals(function) && q.operator().equals("=") && q.result().equals(variable))
                .map(q -> values.get(q.number())).filter(Objects::nonNull).toList();
    }
    private String last(IntermediateResult ir,String variable) { return assignments(ir,"main",variable).getLast().last(); }

    @Test void preservesEighteenAndTwentyEightAtTheirOwnSourceAssignments() throws Exception {
        var ir=valid(Files.readString(Path.of("examples/intermedio/03_resultados_numericos.zig")));
        assertEquals(List.of("18","28"),assignments(ir,"main","resultado").stream().map(NumericPreview.Values::last).toList());
        assertEquals("10",last(ir,"a")); assertEquals("4",last(ir,"b")); assertEquals("2",last(ir,"c"));
    }
    @Test void supportsUnaryIntegerDivisionRemainderAndCompoundUpdates() {
        var ir=valid("fn main() void { var base:i32=10; var r:i32=-base+3*4; r=7/2; r%=2; }");
        assertEquals(List.of("2","3","1"),assignments(ir,"main","r").stream().map(NumericPreview.Values::last).toList());
    }
    @Test void retainsDecimalTypesWhenInitializedFromAnInteger() {
        var ir=valid("fn main() void { var f:f64=5; f=f/2.0; const r=f+0.25; }");
        assertEquals(List.of("5.0","2.5"),assignments(ir,"main","f").stream().map(NumericPreview.Values::last).toList());
        assertEquals("2.75",last(ir,"r"));
    }
    @Test void respectsTheTakenBranchAndLeavesTheOtherOneWithoutAValue() {
        var ir=valid("fn main() void { var x:i32=1; var flag:bool=false; if(flag) { x=99; } else { x=7; } _=x; }");
        assertEquals(List.of("1","7"),assignments(ir,"main","x").stream().map(NumericPreview.Values::last).toList());
        assertTrue(assignments(ir,"main","flag").isEmpty());
    }
    @Test void handlesNestedCallsRecursionAndTypedReturns() {
        var ir=valid("fn fact(n:i32) i32 { if(n<=1) { return 1; } return n*fact(n-1); } fn add(a:i32,b:i32) i32 { return a+b; } fn main() void { const r=add(add(1,2),fact(5)); }");
        assertEquals("123",last(ir,"r"));
    }
    @Test void showsDifferentResultsForRepeatedExecutionsOfTheSameStatement() {
        var ir=valid("fn main() void { var total:i32=0; for(0..4) |i| { total+=i; } _=total; }");
        var history=assignments(ir,"main","total").getLast();
        assertEquals(List.of("0","1","3","6"),history.samples()); assertEquals(4,history.count());
        assertEquals("Resultados numéricos (4 evaluaciones): 0, 1, 3, 6",history.text());
    }
    @Test void functionCallsDoNotShareLocalVariables() {
        var ir=valid("fn inc(n:i32) i32 { var x:i32=n+1; return x; } fn main() void { const a=inc(1); const b=inc(7); }");
        assertEquals("2",last(ir,"a")); assertEquals("8",last(ir,"b"));
        var q=ir.executable().stream().filter(v -> v.function().equals("inc") && v.operator().equals("RETURN")).findFirst().orElseThrow();
        assertEquals(List.of("2","8"),NumericPreview.evaluate(ir).get(q.number()).samples());
    }
    @Test void evaluatesArraysByValueAndMutatesTheOriginalIndexedDestination() {
        var ir=valid("fn main() void { var a=[_][2]i32{[_]i32{1,2},[_]i32{3,4}}; var b=a; b[1][0]+=7; const r=b[1][0]+a[1][0]; }");
        assertEquals("13",last(ir,"r"));
    }
    @Test void supportsForWhileBreakContinueSwitchAndArrayLength() throws Exception {
        var ir=valid(Files.readString(Path.of("examples/intermedio/02_control_y_arreglos.zig")));
        assertEquals("50",last(ir,"total"));
        assertEquals(4,assignments(ir,"main","j").getLast().count());
    }
    @Test void observesBooleanShortCircuitAndDoesNotExecuteUncalledFunctions() {
        var ir=valid("fn bad(n:i32) i32 { return 10/n; } fn unused() i32 { return 77; } fn main() void { var n:i32=0; const flag=true or bad(n)>0; var r:i32=4; }");
        assertEquals("4",last(ir,"r"));
        var values=NumericPreview.evaluate(ir);
        assertTrue(ir.executable().stream().filter(q -> !q.function().equals("main")).noneMatch(q -> values.containsKey(q.number())));
    }
    @Test void evaluatesAStandaloneFunctionOnlyWhenItsArgumentsAreKnown() {
        var ir=valid("fn calc() f64 { return 5; }");
        var q=ir.executable().stream().filter(v -> v.operator().equals("RETURN")).findFirst().orElseThrow();
        assertEquals("5.0",NumericPreview.evaluate(ir).get(q.number()).last());
        assertTrue(NumericPreview.evaluate(valid("fn calc(n:i32) i32 { return n+1; }")).isEmpty());
    }
    @Test void runtimeZeroAndOutOfBoundsDoNotChangeCompilerDiagnosticsOrInventAValue() {
        for(String source:List.of("fn main() void { var z:i32=0; const r=10/z; }", "fn main() void { const a=[_]i32{1}; var i:i32=2; const r=a[i]; }")) {
            var ir=valid(source); assertTrue(NumericPreview.evaluate(ir).isEmpty());
        }
    }
    @Test void checkedNumericRangesNeverWrapOrShowInfinity() {
        for(String source:List.of("fn main() void { var x:u8=255; x+=1; }", "fn main() void { var x:i32=2147483647; x+=1; }", "fn main() void { var x:f64=1e308; x*=2.0; }")) {
            assertTrue(NumericPreview.evaluate(valid(source)).isEmpty());
        }
    }
    @Test void executionAndRecursionLimitsPreventTheInterfaceFromHanging() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertTrue(NumericPreview.evaluate(valid("fn main() void { var x:i32=0; while(true) { x+=1; } }")).isEmpty());
            assertTrue(NumericPreview.evaluate(valid("fn loop(n:i32) i32 { return loop(n); } fn main() void { const r=loop(1); }")).isEmpty());
        });
    }
    @Test void historiesKeepOnlyABoundedSampleAndTheRealLastValue() {
        var ir=valid("fn main() void { var total:i32=0; for(0..20) |i| { total+=i; } }");
        var history=assignments(ir,"main","total").getLast();
        assertEquals(20,history.count()); assertEquals(8,history.samples().size()); assertEquals("190",history.last());
        assertTrue(history.text().contains("último: 190"));
    }
    @Test void completeThirtyFunctionExampleCanBeEvaluated() throws Exception {
        var ir=valid(Files.readString(Path.of("examples/todas_las_funciones.zig")));
        assertEquals("25.4",last(ir,"decimalResult"));
        assertFalse(assignments(ir,"main","accumulator").isEmpty());
    }
}
