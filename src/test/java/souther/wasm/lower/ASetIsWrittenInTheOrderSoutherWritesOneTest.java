package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import souther.runtime.Representations;
import souther.wasm.Compiled;
import souther.wasm.Running;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A set as an array of its members, each held once, in the order Souther writes one in.
 *
 * <p>What that order is belongs to Souther, so these check against
 * {@link Representations#compareExternalForms} — the JVM backend's own answer to the same question.
 * A backend agreeing with its own idea of the order would agree with nothing.
 *
 * <p>The members here carry nothing JSON escapes, so what is written for one is its text between
 * quotes. What escaping does to a string is the string writer's question and is asked of it.
 */
class ASetIsWrittenInTheOrderSoutherWritesOneTest {

    private static final String TEXTS = """
            module wording

            data Words = { all: Set<String> }

            behavior same : (w: Words) -> Words

            let same (w) = w
            """;

    private static final String AMOUNTS = """
            module paying

            data Prices = { all: Set<Decimal> }

            behavior same : (p: Prices) -> Prices

            let same (p) = p
            """;

    private static final String NUMBERS = """
            module counting

            data Marks = { all: Set<Int> }

            behavior same : (m: Marks) -> Marks

            let same (m) = m
            """;

    @Test
    void writesTheMembersOfASetWhereTheJvmWritesThem() {
        Running module = compiled(TEXTS);
        // A character past the basic plane and one just under it: by code point the first is
        // larger, by the code unit a JVM string compares with it is smaller.
        List<String> members = List.of("b", "a", "￿", "𐀀", "", "Z", "ぁ");

        assertThat(arrayOf(module, "wording.same", quotedIn(members)))
                .isEqualTo(quotedIn(ascending(members)).toString().replace(", ", ","));
    }

    @Test
    void ordersNumbersAsAmountsRatherThanAsTheirDigits() {
        Running module = compiled(NUMBERS);
        List<Long> members = List.of(10L, 9L, -1L, 0L, 100L, -20L);

        assertThat(arrayOf(module, "counting.same", members.stream().map(String::valueOf).toList()))
                .isEqualTo(ascending(members).toString().replace(", ", ","));
    }

    @Test
    void holdsWhatWasWrittenTwiceOnce() {
        Running module = compiled(TEXTS);

        assertThat(arrayOf(module, "wording.same", List.of("\"b\"", "\"a\"", "\"b\"", "\"a\"")))
                .isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void writesAnEmptySetAsAnEmptyArray() {
        Running module = compiled(TEXTS);

        assertThat(arrayOf(module, "wording.same", List.of())).isEqualTo("[]");
    }

    /** The members in the order the JVM's own comparison puts them. */
    private static <T> List<T> ascending(List<T> members) {
        List<T> sorted = new ArrayList<>(members);
        sorted.sort(Representations::compareExternalForms);
        return sorted;
    }

    private static List<String> quotedIn(List<String> members) {
        return members.stream().map(each -> "\"" + each + "\"").toList();
    }

    /** What the module answers for a set written with those members, as its array alone. */
    private static String arrayOf(Running module, String export, List<String> members) {
        String written = "[{\"all\": [" + String.join(",", members) + "]}]";
        String answer = answerOf(module, export, written);
        return answer.substring(answer.indexOf('['), answer.lastIndexOf(']') + 1);
    }

    private static final String SHAPES = """
            module shaping

            data Point = { z: Int, a: Int, note: String? }

            data Wide = { z: Int }
            data Narrow = { a: Int }
            data Shape = Wide | Narrow

            data Code = Int
            data Missing
            data Key = Code | Missing

            data Red
            data Green
            data Colour = Red | Green

            data Points = { all: Set<Point> }
            data Shapes = { all: Set<Shape> }
            data Keys = { all: Set<Key> }
            data Colours = { all: Set<Colour> }
            data Runs = { all: Set<List<Int>> }

            behavior echoPoints : (p: Points) -> Points
            let echoPoints (p) = p

            behavior echoShapes : (s: Shapes) -> Shapes
            let echoShapes (s) = s

            behavior echoKeys : (k: Keys) -> Keys
            let echoKeys (k) = k

            behavior echoColours : (c: Colours) -> Colours
            let echoColours (c) = c

            behavior echoRuns : (r: Runs) -> Runs
            let echoRuns (r) = r
            """;

    /**
     * A set of values written as objects, in the order of what is written: an object by its
     * members read in the order of their keys, which is not the order a shape declares its fields
     * in, and a member an option leaves out is not there to be read. A sum's tag is one key among
     * the case's own, standing where its key sorts, and a newtype case's contents under theirs.
     * Each set here is one whose members a declaration's order would put otherwise.
     */
    @Test
    void writesMembersThatAreObjectsInTheOrderOfWhatIsWritten() {
        Running module = compiled(SHAPES);

        assertWrittenInTheJvmsOrder(module, "shaping.echoPoints",
                "[{\"z\":1,\"a\":2},{\"z\":2,\"a\":1},{\"z\":0,\"a\":1,\"note\":\"x\"},"
                        + "{\"z\":3,\"a\":1}]");
        assertWrittenInTheJvmsOrder(module, "shaping.echoShapes",
                "[{\"type\":\"Wide\",\"z\":1},{\"type\":\"Narrow\",\"a\":9},"
                        + "{\"type\":\"Wide\",\"z\":0}]");
        assertWrittenInTheJvmsOrder(module, "shaping.echoKeys",
                "[{\"type\":\"Code\",\"value\":10},{\"type\":\"Missing\"},"
                        + "{\"type\":\"Code\",\"value\":9}]");
        assertWrittenInTheJvmsOrder(module, "shaping.echoColours", "[\"Red\",\"Green\"]");
        assertWrittenInTheJvmsOrder(module, "shaping.echoRuns", "[[2],[1,5],[1],[]]");
    }

    /** That the members come back in the order the JVM's own comparison puts what was written. */
    private static void assertWrittenInTheJvmsOrder(Running module, String export, String members) {
        String answer = answerOf(module, export, "[{\"all\":" + members + "}]");
        JsonNode written = JSON.readTree(answer).get("value").get("all");
        List<Object> asWritten = new ArrayList<>();
        for (JsonNode each : written) {
            asWritten.add(carried(each));
        }
        assertThat(asWritten).describedAs(answer).hasSize(JSON.readTree(members).size())
                .isEqualTo(ascending(asWritten));
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A written value as the JVM's encoder carries one, which is what its comparison reads. */
    private static Object carried(JsonNode written) {
        if (written.isNull()) {
            return null;
        }
        if (written.isBoolean()) {
            return written.booleanValue();
        }
        if (written.isIntegralNumber()) {
            return written.longValue();
        }
        if (written.isNumber()) {
            return written.decimalValue();
        }
        if (written.isString()) {
            return written.stringValue();
        }
        if (written.isArray()) {
            List<Object> elements = new ArrayList<>();
            for (JsonNode each : written) {
                elements.add(carried(each));
            }
            return elements;
        }
        Map<String, Object> members = new LinkedHashMap<>();
        for (var each : written.properties()) {
            members.put(each.getKey(), carried(each.getValue()));
        }
        return members;
    }

    @Test
    void holdsOneAmountOnceHoweverItWasWritten() {
        Running module = compiled(AMOUNTS);

        // How much it is, and nothing about how it was written: scale is no part of what an amount
        // is, so a set given three ways of writing one holds it once and writes it once. Holding
        // them apart would put the same thing in a document twice, since a boundary writes the one
        // form either way.
        assertThat(answerOf(module, "paying.same", "[{\"all\":[1.5,1.50,1.500]}]"))
                .isEqualTo("{\"value\":{\"all\":[1.5]}}");
        assertThat(answerOf(module, "paying.same", "[{\"all\":[0.10,0.1,0.2]}]"))
                .isEqualTo("{\"value\":{\"all\":[0.1,0.2]}}");
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
