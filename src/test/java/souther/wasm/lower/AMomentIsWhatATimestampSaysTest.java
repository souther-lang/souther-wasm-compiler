package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * A moment on the timeline, against what an outside timestamp says one is.
 *
 * <p>Neither what a moment is written as nor what a written one comes to is this backend's to
 * decide, so the tests run the same question through {@code java.time} — which is what the other
 * backend holds a moment as — and require the two to agree. A moment keeps a sub-second reading,
 * which is the whole reason it is not a {@code DateTime}: a value quietly rounded reads downstream
 * as the value that was sent.
 */
class AMomentIsWhatATimestampSaysTest {

    private static final String[] MOMENTS = {
        "1970-01-01T00:00:00Z",
        "1970-01-01T00:00:00.000Z",
        "2026-09-04T09:30:00Z",
        "2026-09-04T09:30:15Z",
        "2026-09-04T09:30:15.100Z",
        "2026-09-04T09:30:15.123Z",
        "2026-09-04T09:30:15.123456Z",
        "2026-09-04T09:30:15.123456789Z",
        "2026-09-04T09:30:15.000000001Z",
        "1969-12-31T23:59:59Z",
        "1969-12-31T23:59:59.999999999Z",
        "1900-01-01T00:00:00Z",
        "2024-02-29T12:00:00Z",
        "2026-09-04T09:30:15+09:00",
        "2026-09-04T09:30:15-05:00",
        "2026-09-04T09:30:15.5+09:00",
        "2026-09-01T00:00:00+09:00",
        "2026-09-04T24:00:00Z",
        "2026-12-31T24:00:00Z",
        "2026-09-04T09:30:15.Z",
        "2026-09-04T09:30:15.0Z",
        "2026-09-04t09:30:15z",
        "+12026-09-04T09:30:15Z",
        "-0001-09-04T09:30:15Z",
    };

    private static final String[] NOT_MOMENTS = {
        "",
        "2026-09-04",
        "2026-09-04T09:30",
        "09:30:00Z",
        "2026-09-04T09:30:15",
        "2026-13-04T09:30:15Z",
        "2026-09-31T09:30:15Z",
        "2026-09-04T09:60:00Z",
        "2026-09-04T24:00:01Z",
        "2026-09-04 09:30:15Z",
        "2026-09-04T09:30:15.1234567890Z",
        "2026-9-04T09:30:15Z",
        "not a moment",
    };

    @Test
    void writesAMomentWhereATimestampWritesIt() {
        Running module = compiled();

        for (String written : MOMENTS) {
            Instant held = Instant.parse(written);
            assertThat(answerOf(module, "timeline.same", quoted(written)))
                    .describedAs(written)
                    .isEqualTo(value(quoted(held.toString())));
        }
    }

    @Test
    void saysSoForTextThatNamesNoMoment() {
        Running module = compiled();

        for (String written : NOT_MOMENTS) {
            assertThat(readableByTheJvm(written))
                    .describedAs(written + " is no moment to java.time either").isFalse();
            assertThat(answerOf(module, "timeline.same", quoted(written)))
                    .describedAs(written)
                    .contains("\"expected\":\"Instant\"");
        }
        assertThat(answerOf(module, "timeline.same", "7")).contains("\"actual\":\"number\"");
    }

    @Test
    void refusesASecondTheTimelineDoesNotHave() {
        Running module = compiled();

        // A leap second is the one thing a parse would quietly move: what an outside timestamp
        // said would come back as the second before it, and nothing downstream could tell.
        assertThat(answerOf(module, "timeline.same", quoted("2016-12-31T23:59:60Z")))
                .contains("\"expected\":\"Instant\"");
    }

    @Test
    void placesTwoMomentsWhereTheTimelineDoes() {
        Running module = compiled();

        for (String first : MOMENTS) {
            for (String second : MOMENTS) {
                int expected = Integer.signum(Instant.parse(first).compareTo(Instant.parse(second)));
                assertThat(answerOf(module, "timeline.before",
                                quoted(first) + "," + quoted(second)))
                        .describedAs(first + " against " + second)
                        .isEqualTo(value(Boolean.toString(expected < 0)));
            }
        }
    }

    @Test
    void keepsAMomentABodyWroteDown() {
        Running module = compiled();

        assertThat(answerOf(module, "timeline.landing", "0"))
                .isEqualTo(value(quoted(Instant.parse("1969-07-20T20:17:40Z").toString())));
    }

    /** Whether {@code java.time} reads it, which is what this backend has to agree with. */
    private static boolean readableByTheJvm(String written) {
        try {
            Instant.parse(written);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static String quoted(String text) {
        return "\"" + text + "\"";
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module timeline

                behavior same : (at: Instant) -> Instant

                let same (at) = at

                behavior before : (a: Instant, b: Instant) -> Bool

                let before (a, b) = a < b

                behavior landing : (ignored: Int) -> Instant

                let landing (ignored) = Instant("1969-07-20T20:17:40Z")
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
