package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * Typed calls whose values do not all fit in core values, run.
 *
 * <p>That the core functions have the types the Canonical ABI gives them is held to
 * {@code wasm-tools} elsewhere; a type says nothing of whether a value handed through memory is
 * read from where it lies. So these hand values over on each side of the sixteen core values a
 * function may take, a function the program reaches out to whose arguments and answer both lie in
 * memory, values nested in values, and an answer from outside that is outside its type. Where a
 * part lies is worked out here by hand from CanonicalABI.md.
 */
class ATypedCallCrossesThroughMemoryWhereItIsTooBigForCoreValuesTest {

    private static final String WIDE = """
            module wide exposing ( Sixteen, Seventeen, lastOfSixteen, sumOfSeventeen, priced )

            data Sixteen = { a: String, b: String, c: String, d: String,
                             e: String, f: String, g: String, h: String }
            data Seventeen = { a: String, b: String, c: String, d: String,
                               e: String, f: String, g: String, h: String, n: Int }

            behavior lastOfSixteen : (w: Sixteen) -> String

            let lastOfSixteen (w) = w.h

            behavior sumOfSeventeen : (w: Seventeen) -> Int

            let sumOfSeventeen (w) = w.n + String.length(w.h)

            behavior quote : (w: Seventeen) -> Seventeen

            behavior priced : (w: Seventeen) -> Int depends on quote

            let priced (w, quote) = quote(w).n
            """;

    private static final String RATES = """
            module rates exposing ( priced )

            behavior today : (pair: String) -> Decimal

            behavior priced : (n: Decimal) -> Decimal depends on today

            let priced (n, today) = n * today("JPY")
            """;

    private static final String ORDERS = """
            module orders exposing ( Code, Part, Holder, Kind, Order )

            data Code = String
                invariant String.length(value) > 0

            data Part = { code: Code }
            data Empty
            data Holder = Part | Empty
            data Small
            data Large
            data Kind = Small | Large

            data Order = { note: String?, kind: Kind, holder: Holder, parts: List<Part> }
            """;

    @Test
    void takesSixteenCoreValuesAsThemselvesAndSeventeenThroughMemory() {
        Running module = Running.linked(Compiled.typedCore(Compiled.program(List.of(WIDE))));

        long[] sixteen = new long[16];
        for (int i = 0; i < 8; i++) {
            int[] text = text(module, "s" + i);
            sixteen[2 * i] = text[0];
            sixteen[2 * i + 1] = text[1];
        }
        int area = (int) module.callWith(Component.Lifted.typed("wide.lastOfSixteen"), sixteen)[0];
        assertThat(byteAt(module, area)).isZero();
        assertThat(textAt(module, area + 4)).isEqualTo("s7");

        // sumOfSeventeen: one record of eight strings and an Int at sixty-four, seventy-two bytes, and
        // the function handed where it lies.
        area = (int) module.callWith(Component.Lifted.typed("wide.sumOfSeventeen"),
                seventeen(module, 5L))[0];
        assertThat(byteAt(module, area)).isZero();
        assertThat(longAt(module, area + 8)).isEqualTo(5L + "h".length());
    }

    @Test
    void reachesOutWithArgumentsAndAnAnswerThatBothLieInMemory() {
        // What answers: the record it was handed, laid out where the answer goes, its Int raised.
        Running module = Running.linkedAnswering(
                Compiled.typedCore(Compiled.program(List.of(WIDE))),
                (instance, arguments) -> {
                    int at = (int) arguments[1];
                    int answer = (int) arguments[2];
                    instance.memory().write(answer, instance.memory().readBytes(at, 72));
                    long n = ByteBuffer.wrap(instance.memory().readBytes(at + 64, 8))
                            .order(ByteOrder.LITTLE_ENDIAN).getLong();
                    instance.memory().write(answer + 64, ByteBuffer.allocate(8)
                            .order(ByteOrder.LITTLE_ENDIAN).putLong(n + 1000).array());
                    return new long[] {0};
                });

        int area = (int) module.callWith(Component.Lifted.typed("wide.priced"),
                seventeen(module, 7L))[0];
        assertThat(byteAt(module, area)).describedAs("ok").isZero();
        assertThat(longAt(module, area + 8)).isEqualTo(1007L);
    }

    /**
     * The two places an amount written wrong is refused. Handed by the caller, the call is refused
     * with the issue at its place; answered by what the program reaches out to, the answer is
     * outside the behavior's type and the call ends with reason 13, naming the behavior and the
     * same issue at the answer's root.
     */
    @Test
    void refusesAnAmountWrittenWrongWhereverItCameFrom() {
        String[] answering = {"2"};
        Running module = Running.linkedAnswering(
                Compiled.typedCore(Compiled.program(List.of(RATES))),
                (instance, arguments) -> {
                    // today: a string in two slots, a string answered where the answer goes.
                    byte[] text = answering[0].getBytes(StandardCharsets.UTF_8);
                    int into = (int) instance.export(RuntimeAbi.ALLOC).apply(text.length)[0];
                    instance.memory().write(into, text);
                    instance.memory().write((int) arguments[2], ByteBuffer.allocate(8)
                            .order(ByteOrder.LITTLE_ENDIAN).putInt(into).putInt(text.length)
                            .array());
                    return new long[] {0};
                });
        String priced = Component.Lifted.typed("rates.priced");

        int[] five = text(module, "5");
        int area = (int) module.callWith(priced, five[0], five[1])[0];
        assertThat(byteAt(module, area)).describedAs("ok").isZero();
        assertThat(textAt(module, area + 4)).isEqualTo("10");

        int[] wrong = text(module, "5x");
        area = (int) module.callWith(priced, wrong[0], wrong[1])[0];
        assertThat(byteAt(module, area + 4)).describedAs("issues").isZero();
        assertThat(textAt(module, wordAt(module, area + 8))).isEqualTo("/0");
        assertThat(textAt(module, wordAt(module, area + 8) + 8)).isEqualTo("invalid_format");

        answering[0] = "x";
        area = (int) module.callWith(priced, five[0], five[1])[0];
        // err at four: ended, its reason at eight, then an invalid answer at sixteen.
        assertThat(byteAt(module, area + 4)).describedAs("ended").isEqualTo(1);
        assertThat(wordAt(module, area + 8)).describedAs("an answer outside its type")
                .isEqualTo(13);
        assertThat(byteAt(module, area + 12)).describedAs("some").isEqualTo(1);
        assertThat(textAt(module, area + 16)).isEqualTo("rates.today");
        int issues = wordAt(module, area + 24);
        assertThat(textAt(module, issues)).describedAs("at the answer's root").isEmpty();
        assertThat(textAt(module, issues + 8)).isEqualTo("invalid_format");
    }

    /**
     * An order read on its own: an option, an enum, a variant whose case is a shape, and a list of
     * shapes, nine core values, every rule of what is nested in it held to and each issue at its
     * place from the order's root.
     */
    @Test
    void readsValuesNestedInAValueAndRefusesEachBrokenRuleAtItsPlace() {
        Running module = Running.linked(Compiled.typedCore(Compiled.program(List.of(ORDERS))));
        int[] note = text(module, "hi");
        int[] empty = text(module, "");
        // list<part>: one part, its code's pointer and length.
        int parts = module.call(RuntimeAbi.ALLOC, 8);
        module.write(parts, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(empty[0]).putInt(empty[1]).array());

        int area = (int) module.callWith(Component.Lifted.typedReading("orders.Order"),
                1, note[0], note[1],      // note: some("hi")
                1,                        // kind: large
                0, empty[0], empty[1],    // holder: part({code: ""})
                parts, 1)[0];             // parts: [{code: ""}]

        assertThat(byteAt(module, area)).describedAs("err").isEqualTo(1);
        int issues = wordAt(module, area + 8);
        assertThat(wordAt(module, area + 12)).describedAs("how many").isEqualTo(2);
        assertThat(textAt(module, issues)).isEqualTo("/holder/code");
        assertThat(textAt(module, issues + 44)).isEqualTo("/parts/0/code");
    }

    /** A Seventeen laid out in memory: eight strings, then at sixty-four the Int. */
    private static int seventeen(Running module, long n) {
        int at = module.call(RuntimeAbi.ALLOC, 72 + 8);
        at = (at + 7) & ~7;
        ByteBuffer record = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
        for (char field = 'a'; field <= 'h'; field++) {
            int[] text = text(module, String.valueOf(field));
            record.putInt(text[0]).putInt(text[1]);
        }
        module.write(at, record.putLong(n).array());
        return at;
    }

    private static int[] text(Running module, String text) {
        return new int[] {module.staged(text), text.getBytes(StandardCharsets.UTF_8).length};
    }

    private static int byteAt(Running module, int at) {
        return module.read(at, 1)[0] & 0xff;
    }

    private static int wordAt(Running module, int at) {
        return ByteBuffer.wrap(module.read(at, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long longAt(Running module, int at) {
        return ByteBuffer.wrap(module.read(at, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static String textAt(Running module, int at) {
        return new String(module.read(wordAt(module, at), wordAt(module, at + 4)),
                StandardCharsets.UTF_8);
    }
}
