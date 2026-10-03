package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * Two operands of different types, compared as what the checker read the pair as.
 *
 * <p>A literal beside a newtype is read as the newtype, and a case beside the sum listing it as the
 * sum. Neither operand's type says that, so a comparison that took its type from one side read the
 * other side's cell as what it was not: every amount came out unequal to and not below any literal,
 * and a case written on the left was ordered as a type with one value. Each pair here is written
 * both ways round, because the answer may not turn on which side came first.
 */
class APairIsComparedAsWhatTheCheckerReadItAsTest {

    private static final String SOURCE = """
            module reading

            data Money = Int

            data Price = Money

            data Rate = Decimal

            data Stage = Draft | Review | Won

            data Role = Staff | Lead

            data Lead = { team: Int }

            behavior under : (m: Money) -> List<Bool>

            let under (m) = [m < 100, 100 > m, m <= 100, 100 >= m]

            behavior same : (m: Money) -> List<Bool>

            let same (m) = [m == 100, 100 == m, m /= 100, 100 /= m]

            behavior wrapped : (p: Price) -> List<Bool>

            let wrapped (p) = [p < 100, 100 > p, p == 100, 100 == p]

            behavior rated : (r: Rate) -> List<Bool>

            let rated (r) = [r < 2.5m, 2.5m > r, r == 2.50m, 2.50m == r]

            behavior staged : (s: Stage) -> List<Bool>

            let staged (s) = [s < Won, Won > s, Review <= s, s >= Review, Draft == s, s == Draft]

            behavior staffed : (r: Role) -> List<Bool>

            let staffed (r) = [r == Staff, Staff == r, r /= Staff]

            data Rank = Senior | Junior

            behavior unioned : (f: Bool) -> List<Bool>

            let unioned (f) =
                [(if f then Senior else Junior) < (if f then Junior else Senior),
                 (if f then Junior else Senior) >= (if f then Senior else Junior)]

            behavior sorted : (n: Int) -> List<Rank>

            let sorted (n) = List.sort([Junior, Senior, Junior])

            behavior sortedBy : (n: Int) -> List<Rank>

            let sortedBy (n) = List.sortBy(r -> r, [Junior, Senior, Junior])

            behavior highest : (n: Int) -> Rank

            let highest (n) = match List.max([Senior, Junior]) with
                | Some r -> r
                | None -> Senior

            behavior lowest : (n: Int) -> Rank

            let lowest (n) = match List.min([Junior, Senior]) with
                | Some r -> r
                | None -> Junior
            """;

    @Test
    void comparesALiteralBesideANewtypeAsTheNewtype() {
        Running module = compiled();

        assertThat(answerOf(module, "reading.under", "[50]")).isEqualTo(truths(true, true, true, true));
        assertThat(answerOf(module, "reading.under", "[100]"))
                .isEqualTo(truths(false, false, true, true));
        assertThat(answerOf(module, "reading.under", "[150]"))
                .isEqualTo(truths(false, false, false, false));
        assertThat(answerOf(module, "reading.same", "[100]"))
                .isEqualTo(truths(true, true, false, false));
        assertThat(answerOf(module, "reading.same", "[7]"))
                .isEqualTo(truths(false, false, true, true));
        // A newtype over a newtype is opened to what the literal is, however many names it wears.
        assertThat(answerOf(module, "reading.wrapped", "[50]"))
                .isEqualTo(truths(true, true, false, false));
        assertThat(answerOf(module, "reading.wrapped", "[100]"))
                .isEqualTo(truths(false, false, true, true));
        // An amount by how much it is, whatever scale either was written at.
        assertThat(answerOf(module, "reading.rated", "[1.5]"))
                .isEqualTo(truths(true, true, false, false));
        assertThat(answerOf(module, "reading.rated", "[2.5]"))
                .isEqualTo(truths(false, false, true, true));
    }

    @Test
    void comparesACaseBesideItsSumAsTheSum() {
        Running module = compiled();

        assertThat(answerOf(module, "reading.staged", "[\"Draft\"]"))
                .isEqualTo(truths(true, true, false, false, true, true));
        assertThat(answerOf(module, "reading.staged", "[\"Review\"]"))
                .isEqualTo(truths(true, true, true, true, false, false));
        assertThat(answerOf(module, "reading.staged", "[\"Won\"]"))
                .isEqualTo(truths(false, false, true, true, false, false));
        assertThat(answerOf(module, "reading.staffed", "[{\"type\":\"Staff\"}]"))
                .isEqualTo(truths(true, true, false));
        assertThat(answerOf(module, "reading.staffed", "[{\"type\":\"Lead\",\"team\":3}]"))
                .isEqualTo(truths(false, false, true));
    }

    /**
     * Cases of one sum held as a union of them are ordered by the sum, in the order it declares
     * them, and not by the union, whose cases are kept in the order of their names. {@code Rank}
     * declares its cases the other way round from their names, so an order taken from the union
     * would answer every one of these the other way.
     */
    @Test
    void ordersCasesHeldAsAUnionOfThemByTheSumListingThem() {
        Running module = compiled();

        assertThat(answerOf(module, "reading.unioned", "[true]")).isEqualTo(truths(true, true));
        assertThat(answerOf(module, "reading.unioned", "[false]")).isEqualTo(truths(false, false));
        String ranked = "{\"value\":[\"Senior\",\"Junior\",\"Junior\"]}";
        assertThat(answerOf(module, "reading.sorted", "[0]")).isEqualTo(ranked);
        assertThat(answerOf(module, "reading.sortedBy", "[0]")).isEqualTo(ranked);
        assertThat(answerOf(module, "reading.highest", "[0]")).isEqualTo("{\"value\":\"Junior\"}");
        assertThat(answerOf(module, "reading.lowest", "[0]")).isEqualTo("{\"value\":\"Senior\"}");
    }

    private static String truths(boolean... each) {
        StringBuilder written = new StringBuilder("{\"value\":[");
        for (int i = 0; i < each.length; i++) {
            written.append(i == 0 ? "" : ",").append(each[i]);
        }
        return written.append("]}").toString();
    }

    private static Running compiled() {
        return Running.linked(Compiled.module(Compiled.program(List.of(SOURCE))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
