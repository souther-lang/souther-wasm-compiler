package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * What a caller outside the JVM has to agree with this about, and where that agreement is written.
 *
 * <p>A program compiled here is called from a browser, and the file that does the calling is not
 * Java: it holds the numbers a reason goes by and where in the failure record each field is, and
 * nothing but its having been written beside this ever made those the same numbers. A call that
 * ends is where they are read, and a call that ends is what a caller meets on the day something
 * has gone wrong — the worst day for a number to have drifted.
 *
 * <p>The example is where the file lives because that is where somebody reads it. What holds it to
 * this is here, because this is what it has to agree with.
 */
class TheGlueAndTheAbiAgreeOnWhatACallEndedWithTest {

    private static final Path GLUE = Path.of("examples/react-cart/src/souther.js");

    @Test
    void everyReasonThisNamesIsTheNumberTheGlueReadsItAs() {
        Map<String, Integer> said = numbersIn("(\\d+): \"");

        assertThat(said).describedAs("the glue names the reasons a call can end with").isNotEmpty();
        for (AbortReason reason : AbortReason.values()) {
            assertThat(said).describedAs(reason + " is one the glue reads")
                    .containsKey(Integer.toString(reason.code()));
        }
        // And nothing else: a number the glue reads and this does not name is one it would say
        // words about that never happened.
        for (String held : said.keySet()) {
            assertThat(AbortReason.of(Integer.parseInt(held)))
                    .describedAs("the glue reads " + held).isPresent();
        }
    }

    @Test
    void everyFieldOfTheRecordIsWhereTheGlueLooksForIt() {
        Map<String, Integer> where = numbersIn("(\\w+): (\\d+)");

        assertThat(where).describedAs("the glue says where the record's fields are")
                .containsEntry("generation", RuntimeAbi.FAILURE_OFF_GENERATION)
                .containsEntry("reason", RuntimeAbi.FAILURE_OFF_REASON)
                .containsEntry("descriptor", RuntimeAbi.FAILURE_OFF_DESCRIPTOR)
                .containsEntry("aux0", RuntimeAbi.FAILURE_OFF_AUX0)
                .containsEntry("aux1", RuntimeAbi.FAILURE_OFF_AUX1);
    }

    /** What the glue writes down, as a name against a number, however it spells the pair. */
    private static Map<String, Integer> numbersIn(String written) {
        Map<String, Integer> found = new LinkedHashMap<>();
        Matcher over = Pattern.compile(written).matcher(read());
        while (over.find()) {
            found.put(over.group(1), over.groupCount() > 1
                    ? Integer.valueOf(over.group(2)) : Integer.valueOf(over.group(1)));
        }
        return found;
    }

    private static String read() {
        try {
            return Files.readString(GLUE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "this reads " + GLUE + ", and a build that cannot is not one that agrees", e);
        }
    }
}
