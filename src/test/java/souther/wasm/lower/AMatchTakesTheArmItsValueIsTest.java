package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * A match, and a name bound to what a body works out on its way to an answer.
 *
 * <p>Which arm a value takes is asked of the value: a cell holds the type it was made as.
 */
class AMatchTakesTheArmItsValueIsTest {

    private static final String SHIPPING = """
            module shipping

            data Standard = { days: Int }

            data Express = { fee: Int }

            data Method = Standard | Express

            behavior costOf : (m: Method) -> Int

            let costOf (m) = match m with
                | Standard as s -> s.days
                | Express as e -> e.fee
            """;

    @Test
    void takesTheArmTheValueWasMadeAs() {
        Running module = compiled(SHIPPING);

        assertThat(answerOf(module, "shipping.costOf", "[{\"type\": \"Standard\", \"days\": 3}]"))
                .isEqualTo("{\"value\":3}");
        assertThat(answerOf(module, "shipping.costOf", "[{\"type\": \"Express\", \"fee\": 500}]"))
                .isEqualTo("{\"value\":500}");
    }

    @Test
    void answersForEachLeafAnArmNames() {
        Running module = compiled("""
                module routing

                data First = { n: Int }

                data Second = { n: Int }

                data Third = { n: Int }

                data Reach = First | Second | Third

                behavior early : (r: Reach) -> Bool

                let early (r) = match r with
                    | First | Second -> true
                    | Third -> false
                """);

        assertThat(answerOf(module, "routing.early", "[{\"type\": \"First\", \"n\": 1}]"))
                .isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "routing.early", "[{\"type\": \"Second\", \"n\": 1}]"))
                .isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "routing.early", "[{\"type\": \"Third\", \"n\": 1}]"))
                .isEqualTo("{\"value\":false}");
    }

    @Test
    void readsWhatItBoundInTheArmItTook() {
        Running module = compiled("""
                module shipping

                data Standard = { days: Int }

                data Express = { fee: Int }

                data Method = Standard | Express

                behavior twice : (m: Method) -> Int

                let twice (m) = match m with
                    | Standard as s -> s.days + s.days
                    | Express as e -> e.fee + e.fee
                """);

        assertThat(answerOf(module, "shipping.twice", "[{\"type\": \"Standard\", \"days\": 3}]"))
                .isEqualTo("{\"value\":6}");
    }

    @Test
    void worksOutAValueUnderANameOnItsWayToTheAnswer() {
        Running module = compiled("""
                module counting

                behavior quadrupled : (n: Int) -> Int

                let quadrupled (n) = {
                    let doubled = n + n
                    doubled + doubled
                }
                """);

        assertThat(answerOf(module, "counting.quadrupled", "[3]")).isEqualTo("{\"value\":12}");
    }

    @Test
    void takesAnArmWrittenAsASumForEachLeafUnderIt() {
        Running module = compiled("""
                module matching

                data A = { n: Int }

                data B = { n: Int }

                data AB = A | B

                data Bad = { n: Int }

                data Pick = AB | Bad

                behavior read : (p: Pick) -> String

                let read (p) = match p with
                    | AB -> "AB"
                    | Bad -> "Bad"
                """);

        assertThat(answerOf(module, "matching.read", "[{\"type\": \"A\", \"n\": 1}]"))
                .isEqualTo("{\"value\":\"AB\"}");
        assertThat(answerOf(module, "matching.read", "[{\"type\": \"B\", \"n\": 1}]"))
                .isEqualTo("{\"value\":\"AB\"}");
        assertThat(answerOf(module, "matching.read", "[{\"type\": \"Bad\", \"n\": 1}]"))
                .isEqualTo("{\"value\":\"Bad\"}");
    }

    @Test
    void takesAnArmWrittenAsASumOfSumsAndAnOrOfASumAndALeaf() {
        Running module = compiled("""
                module nesting

                data A = { n: Int }

                data B = { n: Int }

                data C = { n: Int }

                data D = { n: Int }

                data AB = A | B

                data ABC = AB | C

                data Wide = ABC | D

                behavior read : (w: Wide) -> String

                let read (w) = match w with
                    | ABC -> "abc"
                    | D -> "d"

                behavior pair : (w: Wide) -> String

                let pair (w) = match w with
                    | AB | C -> "abc"
                    | D -> "d"
                """);

        for (String leaf : List.of("A", "B", "C")) {
            String input = "[{\"type\": \"" + leaf + "\", \"n\": 1}]";
            assertThat(answerOf(module, "nesting.read", input)).isEqualTo("{\"value\":\"abc\"}");
            assertThat(answerOf(module, "nesting.pair", input)).isEqualTo("{\"value\":\"abc\"}");
        }
        assertThat(answerOf(module, "nesting.read", "[{\"type\": \"D\", \"n\": 1}]"))
                .isEqualTo("{\"value\":\"d\"}");
    }

    @Test
    void bindsTheValueOfAnArmWrittenAsASum() {
        Running module = compiled("""
                module binding

                data A = { n: Int }

                data B = { n: Int }

                data AB = A | B

                data Bad = { n: Int }

                data Pick = AB | Bad

                behavior read : (p: Pick) -> Int

                let read (p) = match p with
                    | AB as v -> 1
                    | Bad as b -> b.n
                """);

        assertThat(answerOf(module, "binding.read", "[{\"type\": \"B\", \"n\": 7}]"))
                .isEqualTo("{\"value\":1}");
        assertThat(answerOf(module, "binding.read", "[{\"type\": \"Bad\", \"n\": 7}]"))
                .isEqualTo("{\"value\":7}");
    }

    private static Running compiled(String... sources) {
        return Running.linked(Compiled.module(Compiled.program(List.of(sources))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
