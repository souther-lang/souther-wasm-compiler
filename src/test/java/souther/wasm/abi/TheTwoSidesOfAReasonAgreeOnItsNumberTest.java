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
 * The numbers a reason goes by, on both sides of the boundary.
 *
 * <p>A reason is written down twice: once where it is raised and once where it is read. Nothing
 * makes the two agree except that they were written together, and a number that drifted would name
 * one failure as another — quietly, because both sides would still have a name for it.
 */
class TheTwoSidesOfAReasonAgreeOnItsNumberTest {

    private static final Pattern DECLARED =
            Pattern.compile("pub const REASON_([A-Z_]+): u32 = (\\d+);");

    @Test
    void everyReasonTheRuntimeRaisesIsOneThisSideCanName() {
        Map<String, Integer> runtime = declaredByTheRuntime();

        assertThat(runtime).isNotEmpty();
        for (Map.Entry<String, Integer> each : runtime.entrySet()) {
            assertThat(AbortReason.of(each.getValue()))
                    .describedAs(each.getKey() + " is " + each.getValue())
                    .isPresent()
                    .get()
                    .extracting(Enum::name)
                    .isEqualTo(each.getKey());
        }
    }

    @Test
    void everyReasonThisSideNamesIsOneTheRuntimeWroteDown() {
        Map<String, Integer> runtime = declaredByTheRuntime();

        for (AbortReason each : AbortReason.values()) {
            assertThat(runtime)
                    .describedAs(each.name())
                    .containsEntry(each.name(), each.code());
        }
    }

    /** What the runtime's own source says each reason is, read out of it. */
    private static Map<String, Integer> declaredByTheRuntime() {
        try {
            String source = Files.readString(
                    Path.of("runtime", "src", "lib.rs"), StandardCharsets.UTF_8);
            Map<String, Integer> held = new LinkedHashMap<>();
            Matcher found = DECLARED.matcher(source);
            while (found.find()) {
                held.put(found.group(1), Integer.parseInt(found.group(2)));
            }
            return held;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
