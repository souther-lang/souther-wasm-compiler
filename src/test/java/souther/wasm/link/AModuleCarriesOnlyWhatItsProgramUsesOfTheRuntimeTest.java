package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.emit.WasmTreeShaker;

/**
 * A module carries the parts of the runtime its program uses, and not the others.
 *
 * <p>The runtime holds what normalizes text, what changes its case, a pattern's machine, exact
 * arithmetic and the calendar, and each is large. A program reading and answering an {@code Int}
 * once carried all of them, because the runtime chose what reads, writes and orders a value by the
 * value's kind, and every kind's was reached from any (issue #44). What is held here is what each
 * of a few programs, each using one more part than the first, keeps of the runtime, named by what
 * the runtime's linker calls its functions: which parts it carries is a property of how the
 * compiler and the runtime meet, and does not move when a toolchain changes how many functions a
 * part is or how many bytes.
 *
 * <p>The size of each module is held too, but loosely, at about half again what it is: a change
 * that carries a part back in is several times that, and a toolchain moving a few bytes is not one.
 * A module that normalizes text is held closer, at a round number just above what it is. Since
 * notation-199x 0.2.0 normalization reads tables a code point indexes, which made it faster and
 * every such module about 220 KB larger, all of it data normalization reads; making those tables
 * smaller is notation-199x's, raoh-project/notation-199x#68. Until then a bound of half again would
 * let as much again through unseen, so these are held to what they are now.
 */
class AModuleCarriesOnlyWhatItsProgramUsesOfTheRuntimeTest {

    /** A part of the runtime, by what the names of its functions hold. */
    enum Part {
        NORMALIZATION("notation199x13normalization"),
        CASE("notation199x4case"),
        PATTERN("notation199x7pattern"),
        EXACT_ARITHMETIC("souther_exact", "num_bigint"),
        CALENDAR("8temporal");

        private final List<String> named;

        Part(String... named) {
            this.named = List.of(named);
        }

        boolean names(String function) {
            return named.stream().anyMatch(function::contains);
        }
    }

    private record Row(String program, Set<Part> carries, Set<Part> unasked, int atMost) {
    }

    private static final List<Row> ROWS = List.of(
            new Row("""
                    behavior fee : (total: Int) -> Int
                    let fee (total) = if total >= 5000 then 0 else 500
                    """, Set.of(), Set.of(), 16_000),
            new Row("""
                    behavior same : (s: String) -> String
                    let same (s) = s
                    """, Set.of(Part.NORMALIZATION), Set.of(), 300_000),
            new Row("""
                    behavior quiet : (s: String) -> String
                    let quiet (s) = String.lowercase(s)
                    """, Set.of(Part.NORMALIZATION, Part.CASE), Set.of(), 420_000),
            new Row("""
                    behavior digits : (s: String) -> Bool
                    let digits (s) = String.matches("[0-9]+", s)
                    """, Set.of(Part.NORMALIZATION, Part.PATTERN), Set.of(), 340_000),
            new Row("""
                    behavior same : (d: Decimal) -> Decimal
                    let same (d) = d
                    """, Set.of(Part.EXACT_ARITHMETIC), Set.of(), 110_000),
            new Row("""
                    behavior same : (d: Date) -> Date
                    let same (d) = d
                    """, Set.of(Part.CALENDAR), Set.of(), 24_000),
            new Row("""
                    behavior same : (xs: List<Int>) -> List<Int>
                    let same (xs) = xs
                    """, Set.of(), Set.of(), 18_000),
            // A map's keys are written and ordered by what the key's kind is written as, chosen by
            // the runtime from the kind, so a map carries the calendar's writers whatever its keys
            // are. Not this test's to settle, and left out of what it asks.
            new Row("""
                    behavior same : (m: Map<String, Int>) -> Map<String, Int>
                    let same (m) = m
                    """, Set.of(Part.NORMALIZATION), Set.of(Part.CALENDAR), 305_000),
            new Row("""
                    data P = { x: Int, y: Int }
                    behavior eq : (a: P, b: P) -> Bool
                    let eq (a, b) = a == b
                    """, Set.of(), Set.of(), 22_000));

    private static final Map<Integer, String> NAMES = RuntimeData.functionNames(Running.runtimeModule());

    @Test
    void namesTheRuntimesFunctions() {
        assertThat(NAMES).isNotEmpty();
        for (Part part : Part.values()) {
            assertThat(NAMES.values()).describedAs("the runtime's functions of %s", part)
                    .anyMatch(part::names);
        }
    }

    @Test
    void carriesThePartsItsProgramUsesAndNoOthers() {
        for (Row row : ROWS) {
            Set<Part> carried = carried(module(row));
            Set<Part> asked = Arrays.stream(Part.values())
                    .filter(part -> !row.unasked().contains(part)).collect(Collectors.toSet());
            assertThat(carried.stream().filter(asked::contains).collect(Collectors.toSet()))
                    .describedAs("the parts of the runtime carried for%n%s", row.program())
                    .isEqualTo(row.carries());
        }
    }

    @Test
    void isNoLargerThanAboutHalfAgainWhatItIs() {
        for (Row row : ROWS) {
            assertThat(Compiled.module(Compiled.program(List.of(source(row)))).length)
                    .describedAs("the bytes of the module for%n%s", row.program())
                    .isLessThanOrEqualTo(row.atMost());
        }
    }

    private static byte[] module(Row row) {
        return Compiled.unshaken(Compiled.program(List.of(source(row))));
    }

    private static String source(Row row) {
        return "module m\n\n" + row.program();
    }

    /** The parts of the runtime the functions a module keeps belong to. */
    private static Set<Part> carried(byte[] unshaken) {
        WasmTreeShaker.Reach[] reached = WasmTreeShaker.reached(unshaken);
        return IntStream.range(0, reached.length)
                .filter(i -> reached[i] != null && NAMES.containsKey(i))
                .mapToObj(NAMES::get)
                .flatMap(name -> Arrays.stream(Part.values()).filter(part -> part.names(name)))
                .collect(Collectors.toSet());
    }
}
