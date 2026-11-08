package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One call made both ways, in JSON and typed, on one instance, answering the same thing.
 *
 * <p>What can differ is what each boundary does itself rather than hand to the readers and the
 * body: the order a set's members and a map's entries go out in, the text an amount and a date
 * are written as, and which issues a value that breaks its type's rules is refused with, where,
 * and what each carries. Only what both can say is compared: a typed caller cannot hand over what
 * is not JSON, nor a number where text goes.
 */
class AValueMeansTheSameWhicheverWayItCrossesTest {

    private static final String SAME = """
            module same exposing ( Code, Line, distinct, counted, amount, day, coded, priced )

            data Code = String
                invariant short = String.length(value) < 4
                invariant String.length(value) > 0

            behavior distinct : (xs: List<String>) -> Set<String>

            let distinct (xs) = Set.fromList(xs)

            behavior counted : (xs: List<String>) -> Map<String, Int>

            let counted (xs) = Map.fromList(List.map(x -> (x, String.length(x)), xs))

            behavior amount : (d: Decimal) -> Decimal

            let amount (d) = d

            behavior day : (d: Date) -> Date

            let day (d) = d

            behavior coded : (codes: List<Code>) -> Int

            let coded (codes) = List.length(codes)

            data Line = { price: Decimal }

            behavior priced : (line: Line) -> Decimal

            let priced (line) = line.price
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void handsASetsMembersOverInTheOrderJsonWritesThem() {
        Running module = module();
        List<String> members = List.of("b", "a", "b", "c", "aa");

        JsonNode json = json(module, "same.distinct", "[[\"b\",\"a\",\"b\",\"c\",\"aa\"]]");
        int area = typed(module, "same.distinct", list(module, members));

        List<String> read = new ArrayList<>();
        json.get("value").forEach(each -> read.add(each.asString()));
        assertThat(byteAt(module, area)).isZero();
        assertThat(strings(module, area + 4)).isEqualTo(read);
    }

    @Test
    void handsAMapsEntriesOverInTheOrderJsonWritesThem() {
        Running module = module();

        JsonNode json = json(module, "same.counted", "[[\"bb\",\"a\",\"bb\",\"ccc\"]]");
        int area = typed(module, "same.counted", list(module, List.of("bb", "a", "bb", "ccc")));

        // list<tuple<string, s64>>: each entry its key's text, then at eight its value.
        List<String> written = new ArrayList<>();
        json.get("value").properties().forEach(each ->
                written.add(each.getKey() + "=" + each.getValue().asLong()));
        List<String> laid = new ArrayList<>();
        int entries = wordAt(module, area + 4);
        for (int i = 0; i < wordAt(module, area + 8); i++) {
            laid.add(textAt(module, entries + 16 * i) + "=" + longAt(module, entries + 16 * i + 8));
        }
        assertThat(laid).isEqualTo(written);
    }

    @Test
    void writesAnAmountAndADateAsTheTextJsonWritesThemAs() {
        Running module = module();

        JsonNode amount = json(module, "same.amount", "[1.50]");
        int typedAmount = typed(module, "same.amount", text(module, "1.50"));
        assertThat(textAt(module, typedAmount + 4)).isEqualTo(amount.get("value").toString());

        JsonNode day = json(module, "same.day", "[\"2026-11-08\"]");
        int typedDay = typed(module, "same.day", text(module, "2026-11-08"));
        assertThat(textAt(module, typedDay + 4)).isEqualTo(day.get("value").asString());
    }

    @Test
    void refusesWhatBreaksATypesRulesWithTheIssuesJsonIsRefusedWith() {
        Running module = module();

        JsonNode json = json(module, "same.coded", "[[\"\",\"abcd\",\"ok\"]]");
        int area = typed(module, "same.coded", list(module, List.of("", "abcd", "ok")));

        List<String> said = new ArrayList<>();
        json.get("issues").forEach(each -> said.add(each.get("path").asString() + " "
                + each.get("code").asString() + " " + each.get("messageKey").asString() + " "
                + each.get("meta")));
        assertThat(said).hasSize(2);
        // err at eight, a refusal of issues, and at four the list of them: path, code, key, meta.
        assertThat(byteAt(module, area)).isEqualTo(1);
        int issues = wordAt(module, area + 12);
        List<String> laid = new ArrayList<>();
        for (int i = 0; i < wordAt(module, area + 16); i++) {
            int issue = issues + 44 * i;
            laid.add(textAt(module, issue) + " " + textAt(module, issue + 8) + " "
                    + textAt(module, issue + 16) + " " + JSON.readTree(textAt(module, issue + 24)));
        }
        assertThat(laid).isEqualTo(said);
    }

    /**
     * What only a typed caller can get wrong: an amount is handed over as text, and text that is
     * no amount is refused as text is that is no date, at the place it stands, rather than as a
     * string where a number goes, which is what JSON says of the same text and what a caller
     * told to hand over a string cannot act on.
     */
    @Test
    void refusesAnAmountWrittenWrongAsTextThatIsNoAmountAtItsPlace() {
        Running module = module();

        int area = typed(module, "same.amount", text(module, "1.5x"));
        assertThat(byteAt(module, area)).describedAs("err").isEqualTo(1);
        int issues = wordAt(module, area + 8);
        assertThat(wordAt(module, area + 12)).describedAs("how many").isEqualTo(1);
        assertThat(textAt(module, issues)).isEqualTo("/0");
        assertThat(textAt(module, issues + 8)).isEqualTo("invalid_format");
        assertThat(byteAt(module, issues + 32)).describedAs("a message").isEqualTo(1);
        assertThat(textAt(module, issues + 36)).startsWith("is not a Decimal");

        area = typed(module, "same.priced", text(module, "12,50"));
        assertThat(textAt(module, wordAt(module, area + 8))).isEqualTo("/0/price");

        area = (int) module.callWith(Component.Lifted.typedReading("same.Line"),
                text(module, "")[0], 0)[0];
        assertThat(textAt(module, wordAt(module, area + 8)))
                .describedAs("a value read on its own, at its root").isEqualTo("/price");
    }

    private static Running module() {
        return Running.linked(Compiled.typedCore(Compiled.program(List.of(SAME))));
    }

    private static JsonNode json(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return JSON.readTree(new String(module.read((int) answer[0], (int) answer[1]),
                StandardCharsets.UTF_8));
    }

    /** Calls a behavior's typed function with the pointer and the length of what it takes. */
    private static int typed(Running module, String behavior, int[] pointerAndLength) {
        return (int) module.callWith(Component.Lifted.typed(behavior),
                pointerAndLength[0], pointerAndLength[1])[0];
    }

    private static int[] text(Running module, String text) {
        return new int[] {module.staged(text), text.getBytes(StandardCharsets.UTF_8).length};
    }

    /** A list of strings as the Canonical ABI lays one out: a pointer and a length to each. */
    private static int[] list(Running module, List<String> texts) {
        int elements = module.call(RuntimeAbi.ALLOC, 8 * texts.size());
        for (int i = 0; i < texts.size(); i++) {
            int[] each = text(module, texts.get(i));
            module.write(elements + 8 * i, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(each[0]).putInt(each[1]).array());
        }
        return new int[] {elements, texts.size()};
    }

    private static List<String> strings(Running module, int at) {
        List<String> read = new ArrayList<>();
        int elements = wordAt(module, at);
        for (int i = 0; i < wordAt(module, at + 4); i++) {
            read.add(textAt(module, elements + 8 * i));
        }
        return read;
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
