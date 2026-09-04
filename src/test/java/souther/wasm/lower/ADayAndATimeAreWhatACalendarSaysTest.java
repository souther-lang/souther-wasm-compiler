package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dylibso.chicory.wasm.ChicoryException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * A day, a time of day, and the two together, against what a calendar and a clock say.
 *
 * <p>Neither what a day is called nor what it comes to a month later is this backend's to decide,
 * so the tests run the same question through {@code java.time} — which is what the other backend
 * holds a day as — and require the two to agree.
 */
class ADayAndATimeAreWhatACalendarSaysTest {

    private static final String[] DAYS = {
        "1970-01-01", "2026-09-04", "2024-02-29", "1999-12-31", "2000-03-01", "0001-01-01",
        "2023-01-31", "2023-05-31", "2100-01-31", "1900-01-31",
    };

    private static final String[] TIMES = {"00:00", "09:30", "23:59:59", "12:00:01"};

    @Test
    void callsADayWhatACalendarCallsIt() {
        Running module = compiled();

        for (String written : DAYS) {
            LocalDate held = LocalDate.parse(written);
            assertThat(answerOf(module, "diary.same", quoted(written)))
                    .describedAs(written)
                    .isEqualTo(value(quoted(held.toString())));
            assertThat(answerOf(module, "diary.year", quoted(written)))
                    .describedAs(written).isEqualTo(value(Integer.toString(held.getYear())));
            assertThat(answerOf(module, "diary.month", quoted(written)))
                    .describedAs(written).isEqualTo(value(Integer.toString(held.getMonthValue())));
            assertThat(answerOf(module, "diary.day", quoted(written)))
                    .describedAs(written).isEqualTo(value(Integer.toString(held.getDayOfMonth())));
        }
    }

    @Test
    void movesADayWhereACalendarMovesIt() {
        Running module = compiled();

        for (String written : DAYS) {
            LocalDate held = LocalDate.parse(written);
            for (long by : new long[] {-400, -31, -1, 0, 1, 31, 400}) {
                assertThat(answerOf(module, "diary.later", quoted(written) + "," + by))
                        .describedAs(written + " + " + by + " days")
                        .isEqualTo(value(quoted(held.plusDays(by).toString())));
                assertThat(answerOf(module, "diary.monthsOn", quoted(written) + "," + by))
                        .describedAs(written + " + " + by + " months")
                        .isEqualTo(value(quoted(held.plusMonths(by).toString())));
                assertThat(answerOf(module, "diary.yearsOn", quoted(written) + "," + by))
                        .describedAs(written + " + " + by + " years")
                        .isEqualTo(value(quoted(held.plusYears(by).toString())));
            }
        }
    }

    @Test
    void countsTheDaysBetweenTwoWhereACalendarCountsThem() {
        Running module = compiled();

        for (String from : DAYS) {
            for (String to : DAYS) {
                long between = ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to));
                assertThat(answerOf(module, "diary.apart", quoted(from) + "," + quoted(to)))
                        .describedAs(from + " to " + to)
                        .isEqualTo(value(Long.toString(between)));
            }
        }
    }

    @Test
    void callsATimeWhatAClockCallsIt() {
        Running module = compiled();

        for (String written : TIMES) {
            LocalTime held = LocalTime.parse(written);
            assertThat(answerOf(module, "diary.sameTime", quoted(written)))
                    .describedAs(written)
                    .isEqualTo(value(quoted(held.toString())));
            assertThat(answerOf(module, "diary.hour", quoted(written)))
                    .describedAs(written).isEqualTo(value(Integer.toString(held.getHour())));
        }
    }

    @Test
    void putsADayAndATimeTogetherAndTakesThemApart() {
        Running module = compiled();

        LocalDateTime held = LocalDateTime.of(2026, 9, 4, 9, 30);
        assertThat(answerOf(module, "diary.joined", quoted("2026-09-04") + "," + quoted("09:30")))
                .isEqualTo(value(quoted(held.toString())));
        assertThat(answerOf(module, "diary.dayOf", quoted(held.toString())))
                .isEqualTo(value(quoted(held.toLocalDate().toString())));
        assertThat(answerOf(module, "diary.timeOf", quoted(held.toString())))
                .isEqualTo(value(quoted(held.toLocalTime().toString())));
        assertThat(answerOf(module, "diary.minutesOn", quoted(held.toString()) + ",90"))
                .isEqualTo(value(quoted(held.plusMinutes(90).toString())));
    }

    @Test
    void answersTheCaseWhereThePartsNameNoDay() {
        Running module = compiled();

        String fallback = "," + quoted("1970-01-01");
        assertThat(answerOf(module, "diary.of", "2023,2,29" + fallback))
                .isEqualTo(value(quoted("1970-01-01")));
        assertThat(answerOf(module, "diary.of", "2024,2,29" + fallback))
                .isEqualTo(value(quoted("2024-02-29")));
        assertThat(answerOf(module, "diary.of", "2024,13,1" + fallback))
                .isEqualTo(value(quoted("1970-01-01")));
    }

    @Test
    void saysSoWhereADayIsPastWhatOneIsHeldIn() {
        Running module = compiled();

        // A day is held as one number of days from a fixed one, and a reader takes years this
        // cannot count that far to. Taking such a year would put a value here saying a different
        // day than the text did — which is the one thing the reading exists not to do.
        for (String written : new String[] {
            "+999999999-12-31", "+10000000-01-01", "-999999999-01-01", "-10000000-01-01",
        }) {
            assertThat(readableByTheJvm(written))
                    .describedAs(written + " is a day to java.time").isTrue();
            assertThat(answerOf(module, "diary.same", quoted(written)))
                    .describedAs(written).contains("\"expected\":\"Date\"");
        }
    }

    @Test
    void endsTheCallWhereMovingADayLeavesTheCalendar() {
        Running module = compiled();

        // The same answer moving by days already gave. Moving by months and by years reached one
        // by wrapping round, which is a day nobody asked for and nothing marks as wrong.
        for (String[] far : new String[][] {
            {"diary.monthsOn", "90000000000"}, {"diary.yearsOn", "9000000000"},
            {"diary.later", "9000000000"},
        }) {
            assertThatThrownBy(() -> answerOf(module, far[0], quoted("2026-09-04") + "," + far[1]))
                    .describedAs(far[0] + " by " + far[1])
                    .isInstanceOf(ChicoryException.class);
        }
    }

    /** Whether {@code java.time} reads it, which is what says the day itself is not the problem. */
    private static boolean readableByTheJvm(String written) {
        try {
            LocalDate.parse(written);
            return true;
        } catch (java.time.format.DateTimeParseException ignored) {
            return false;
        }
    }

    @Test
    void saysSoWhereWhatWasWrittenIsNoDay() {
        Running module = compiled();

        assertThat(answerOf(module, "diary.same", quoted("2026-02-30")))
                .contains("\"expected\":\"Date\"");
        assertThat(answerOf(module, "diary.same", quoted("26-01-01")))
                .contains("\"expected\":\"Date\"");
        assertThat(answerOf(module, "diary.same", "7")).contains("\"actual\":\"number\"");
    }

    private static String value(String written) {
        return "{\"value\":" + written + "}";
    }

    private static String quoted(String text) {
        return "\"" + text + "\"";
    }

    private static Running compiled() {
        return Running.linked(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module diary

                behavior same : (d: Date) -> Date

                let same (d) = d

                behavior year : (d: Date) -> Int

                let year (d) = Date.year(d)

                behavior month : (d: Date) -> Int

                let month (d) = Date.month(d)

                behavior day : (d: Date) -> Int

                let day (d) = Date.day(d)

                behavior later : (d: Date, by: Int) -> Date

                let later (d, by) = Date.addDays(by, d)

                behavior monthsOn : (d: Date, by: Int) -> Date

                let monthsOn (d, by) = Date.addMonths(by, d)

                behavior yearsOn : (d: Date, by: Int) -> Date

                let yearsOn (d, by) = Date.addYears(by, d)

                behavior apart : (from: Date, to: Date) -> Int

                let apart (from, to) = Date.daysBetween(from, to)

                behavior sameTime : (t: Time) -> Time

                let sameTime (t) = t

                behavior hour : (t: Time) -> Int

                let hour (t) = Time.hour(t)

                behavior joined : (d: Date, t: Time) -> DateTime

                let joined (d, t) = DateTime.fromDateAndTime(d, t)

                behavior dayOf : (dt: DateTime) -> Date

                let dayOf (dt) = DateTime.toDate(dt)

                behavior timeOf : (dt: DateTime) -> Time

                let timeOf (dt) = DateTime.toTime(dt)

                behavior minutesOn : (dt: DateTime, by: Int) -> DateTime

                let minutesOn (dt, by) = DateTime.addMinutes(by, dt)

                behavior of : (y: Int, m: Int, d: Int, fallback: Date) -> Date

                let of (y, m, d, fallback) = match Date.fromParts(y, m, d) with
                    | Date as held -> held
                    | NotADate -> fallback
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
