package souther.wasm.lower;

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
 * The number a kind of descriptor goes by, on both sides of the boundary between them.
 *
 * <p>A descriptor is written by this compiler and read by the runtime, and what the number at its
 * head means is written down in two places. Nothing but their being written together has ever made
 * them agree, and a number that drifted would have this compiler write one kind and the runtime
 * read another — a shape for a set of alternatives read as a shape for a list, quietly, because
 * both sides have a kind at that number.
 *
 * <p>So this reads the runtime's own list and requires the two to say the same thing.
 */
class TheTwoSidesOfAKindAgreeOnItsNumberTest {

    @Test
    void everyKindTheRuntimeDeclaresHasTheSameNumberHere() {
        Map<String, Integer> runtime = numbersIn(
                Path.of("runtime/src/descriptor.rs"), "pub const (KIND_\\w+): u32 = (\\d+);");
        Map<String, Integer> here = numbersIn(
                Path.of("src/main/java/souther/wasm/lower/Descriptors.java"),
                "int (KIND_\\w+) = (\\d+);");

        assertThat(runtime).describedAs("the runtime declares kinds").isNotEmpty();
        assertThat(here).containsAllEntriesOf(runtime);
    }

    @Test
    void whatNothingIsAValueOfIsANumberTheRuntimeGivesNoKind() {
        Map<String, Integer> runtime = numbersIn(
                Path.of("runtime/src/descriptor.rs"), "pub const (KIND_\\w+): u32 = (\\d+);");
        Map<String, Integer> here = numbersIn(
                Path.of("src/main/java/souther/wasm/lower/Descriptors.java"),
                "int (KIND_\\w+) = (\\d+);");

        // The element type of a list written with nothing in it. Its whole meaning is that every
        // path which would read through it is the one that ends a call on a kind it does not know,
        // so the day the runtime gives that number a meaning is the day an empty list quietly
        // becomes a list of whatever that is.
        Integer nothing = here.get("KIND_NOTHING");
        assertThat(nothing).describedAs("this compiler writes a kind nothing is a value of")
                .isNotNull();
        assertThat(runtime).doesNotContainValue(nothing);
    }

    /** What a file writes down as a name and a number, by the name. */
    private static Map<String, Integer> numbersIn(Path file, String written) {
        Map<String, Integer> found = new LinkedHashMap<>();
        Matcher over = Pattern.compile(written).matcher(read(file));
        while (over.find()) {
            found.put(over.group(1), Integer.valueOf(over.group(2)));
        }
        return found;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "this test reads " + file + ", and a build that cannot is not one that agrees", e);
        }
    }
}
