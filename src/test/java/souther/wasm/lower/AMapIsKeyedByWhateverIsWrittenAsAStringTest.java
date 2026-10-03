package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * A map keyed by something other than text.
 *
 * <p>A map's external form is an object, whose member names are strings, so a type keys a map
 * exactly when it is written as a bare string. Every one that is is written the same way in key
 * position as anywhere else — a day as the day a calendar writes, a moment as the moment a
 * timestamp writes, a case of a set of alternatives as its name — so a key crosses through the
 * same reading and the same writing a value of that type does.
 */
class AMapIsKeyedByWhateverIsWrittenAsAStringTest {

    @Test
    void readsAndWritesADayKeyedMapAsTheDaysItNames() {
        Running module = compiled();

        assertThat(answerOf(module, "keyed.days", "{\"2026-09-04\":2,\"1970-01-01\":1}"))
                .isEqualTo(value("{\"1970-01-01\":1,\"2026-09-04\":2}"));
    }

    @Test
    void putsTheEntriesInTheOrderTheirNamesSort() {
        Running module = compiled();

        // The order a document's members stand in, which is the order their names sort — not the
        // order the values stand in, and for a set of alternatives those are two different orders.
        assertThat(answerOf(module, "keyed.kinds", "{\"Red\":1,\"Amber\":2,\"Green\":3}"))
                .isEqualTo(value("{\"Amber\":2,\"Green\":3,\"Red\":1}"));
    }

    @Test
    void namesOneEntryWhereTwoMemberNamesSpellOneKey() {
        Running module = compiled();

        // A moment written to three places and to six is one moment, so an object naming both
        // names one entry — and what stands is what the document said last.
        assertThat(answerOf(module, "keyed.moments",
                        "{\"2026-09-04T09:30:15.500Z\":1,\"2026-09-04T09:30:15.5Z\":2}"))
                .isEqualTo(value("{\"2026-09-04T09:30:15.500Z\":2}"));
    }

    @Test
    void findsAnEntryByWhatItsKeyIsWrittenAsRatherThanHowItWasSpelt() {
        Running module = compiled();

        // Every entry of a map with many of them, and one key that is in none of them. One entry
        // would be found by any way of looking at all, including a wrong one.
        List<String> moments = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            moments.add(Instant.EPOCH.plusSeconds(i * 3607L).toString());
        }
        String written = "{" + moments.stream()
                .map(at -> "\"" + at + "\":" + moments.indexOf(at))
                .collect(Collectors.joining(",")) + "}";

        for (int i = 0; i < moments.size(); i++) {
            assertThat(answerOf(module, "keyed.at", written + ",\"" + moments.get(i) + "\""))
                    .describedAs(moments.get(i)).isEqualTo(value(Integer.toString(i)));
        }
        assertThat(answerOf(module, "keyed.at", written + ",\"2999-01-01T00:00:00Z\""))
                .isEqualTo(value("-1"));
        assertThat(answerOf(module, "keyed.at", written + ",\"1900-01-01T00:00:00Z\""))
                .isEqualTo(value("-1"));
        // The same moment, spelt another way.
        assertThat(answerOf(module, "keyed.at", written + ",\"1970-01-01T01:00:07.000Z\""))
                .isEqualTo(value("1"));
    }

    @Test
    void keepsAnEntryPutInUnderTheKeyItWasPutInWith() {
        Running module = compiled();

        assertThat(answerOf(module, "keyed.plus", "{\"2026-09-04\":1},\"1970-01-01\""))
                .isEqualTo(value("{\"1970-01-01\":99,\"2026-09-04\":1}"));
        assertThat(answerOf(module, "keyed.plus", "{\"2026-09-04\":1},\"2026-09-04\""))
                .isEqualTo(value("{\"2026-09-04\":99}"));
    }

    @Test
    void saysSoForAMemberNameThatIsNoKeyOfThatType() {
        Running module = compiled();

        assertThat(answerOf(module, "keyed.days", "{\"2026-02-30\":1}"))
                .contains("\"expected\":\"Date\"");
        // A name that is no case of the set is not a mismatch of kind — it is a string where a
        // string belongs, naming nothing — so it answers with what it is.
        assertThat(answerOf(module, "keyed.kinds", "{\"Purple\":1}"))
                .contains("\"code\":\"not_allowed\"")
                .contains("\"path\":\"/0/Purple\"");
    }

    @Test
    void answersTheKeysAsValuesOfTheirOwnTypeRatherThanAsText() {
        Running module = compiled();

        // Map.keys of a day-keyed map is a list of days, so what comes back is a list of the text
        // a calendar writes and not the member names the document happened to carry.
        assertThat(answerOf(module, "keyed.firstOf", "{\"2026-09-04\":1,\"1970-01-01\":2}"))
                .isEqualTo(value("1970"));
    }

    @Test
    void putsManyEntriesInOrderWhateverOrderTheyWereWrittenIn() {
        Running module = compiled();

        // Enough entries, in enough orders, that a sort which merely looks sorted on three of them
        // does not. Every run is the same set of days, so every answer is the same string.
        List<String> days = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            days.add(LocalDate.of(1970, 1, 1).plusDays(i * 37L).toString());
        }
        String expected = value("{" + days.stream().sorted()
                .map(day -> "\"" + day + "\":1").collect(Collectors.joining(",")) + "}");

        for (long seed : new long[] {1, 2, 3, 4, 5}) {
            List<String> shuffled = new ArrayList<>(days);
            Collections.shuffle(shuffled, new Random(seed));
            String written = "{" + shuffled.stream()
                    .map(day -> "\"" + day + "\":1").collect(Collectors.joining(",")) + "}";

            assertThat(answerOf(module, "keyed.days", written))
                    .describedAs("shuffled with " + seed).isEqualTo(expected);
        }
    }

    @Test
    void ordersDaysAsTheirTextsSortWhereSomeYearsAreNotWrittenInFourDigits() {
        Running module = compiled();

        // A day in a year written with four digits is compared by which day it is, and one in a
        // year written with a sign by its text. The two have to land in the one order the texts
        // sort in, whichever way each pair was compared, and every key has to be found again.
        List<String> days = new ArrayList<>();
        for (int year : new int[] {-12000, -10, -1, 0, 1, 999, 1970, 2026, 9999, 10000, 12345}) {
            days.add(LocalDate.of(year, 1, 1).toString());
            days.add(LocalDate.of(year, 12, 31).toString());
        }
        List<String> shuffled = new ArrayList<>(days);
        Collections.shuffle(shuffled, new Random(20261003));
        String written = "{" + shuffled.stream()
                .map(day -> "\"" + day + "\":" + days.indexOf(day))
                .collect(Collectors.joining(",")) + "}";

        assertThat(answerOf(module, "keyed.days", written)).isEqualTo(value("{"
                + days.stream().sorted()
                        .map(day -> "\"" + day + "\":" + days.indexOf(day))
                        .collect(Collectors.joining(","))
                + "}"));
        for (String day : days) {
            assertThat(answerOf(module, "keyed.on", written + ",\"" + day + "\""))
                    .describedAs(day).isEqualTo(value(Integer.toString(days.indexOf(day))));
        }
        assertThat(answerOf(module, "keyed.on", written + ",\"2026-06-15\""))
                .isEqualTo(value("-1"));
    }

    @Test
    void ordersTimesAndDayTimesAsTheirTextsSort() {
        Running module = compiled();

        // A time written without its seconds stands before the same minute written with them.
        assertThat(answerOf(module, "keyed.times",
                        "{\"10:00:30\":3,\"09:59:59\":1,\"10:00\":2,\"23:00\":4}"))
                .isEqualTo(value("{\"09:59:59\":1,\"10:00\":2,\"10:00:30\":3,\"23:00\":4}"));
        assertThat(answerOf(module, "keyed.stamps", """
                {"2026-01-01T10:00:30":3,"2025-12-31T23:59":1,"2026-01-01T10:00":2,\
                "-0001-01-01T00:00":0}"""))
                .isEqualTo(value("{\"-0001-01-01T00:00\":0,\"2025-12-31T23:59\":1,"
                        + "\"2026-01-01T10:00\":2,\"2026-01-01T10:00:30\":3}"));
    }

    @Test
    void keepsWhatTheDocumentWroteLastWhereverTheRepeatWas() {
        Running module = compiled();

        // Three names for one moment, and the answer is what stands at the last of them — which is
        // not what stands at the first, and not what a sort would leave there by accident.
        assertThat(answerOf(module, "keyed.moments", """
                {"2026-09-04T09:30:15.5Z":1,"2026-01-01T00:00:00Z":9,\
                "2026-09-04T09:30:15.500Z":2,"2026-09-04T09:30:15.500000Z":3}"""))
                .isEqualTo(value(
                        "{\"2026-01-01T00:00:00Z\":9,\"2026-09-04T09:30:15.500Z\":3}"));
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static Running compiled() {
        return Running.linked(Compiled.module(Compiled.program(List.of("""
                module keyed

                data Kind = Red | Amber | Green

                behavior days : (m: Map<Date, Int>) -> Map<Date, Int>

                let days (m) = m

                behavior kinds : (m: Map<Kind, Int>) -> Map<Kind, Int>

                let kinds (m) = m

                behavior moments : (m: Map<Instant, Int>) -> Map<Instant, Int>

                let moments (m) = m

                behavior at : (m: Map<Instant, Int>, k: Instant) -> Int

                let at (m, k) = Option.withDefault(-1, Map.get(k, m))

                behavior on : (m: Map<Date, Int>, k: Date) -> Int

                let on (m, k) = Option.withDefault(-1, Map.get(k, m))

                behavior times : (m: Map<Time, Int>) -> Map<Time, Int>

                let times (m) = m

                behavior stamps : (m: Map<DateTime, Int>) -> Map<DateTime, Int>

                let stamps (m) = m

                behavior plus : (m: Map<Date, Int>, k: Date) -> Map<Date, Int>

                let plus (m, k) = Map.insert(k, 99, m)

                behavior firstOf : (m: Map<Date, Int>) -> Int

                let firstOf (m) = Option.withDefault(0,
                    Option.map(d -> Date.year(d), List.get(0, Map.keys(m))))
                """))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        String written = "[" + arguments + "]";
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int address = module.staged(written);
        long[] answer = module.callWithString(
                export, address, written.getBytes(StandardCharsets.UTF_8).length);
        String held = new String(
                module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
        return held;
    }
}
