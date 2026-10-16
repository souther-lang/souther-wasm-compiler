package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What the runtime reads and writes wider than a byte asks nothing of where it begins, unless it
 * came from the one place an alignment is taken.
 *
 * <p>The arena hands out exactly what it is asked for, wherever it stands, so that a run written
 * piece by piece stays one run. A word in it is read with {@code read_unaligned}; a slice of words
 * is taken from {@code alloc_words}, which pads first. A slice or an aligned read over an address
 * {@code alloc} answered is undefined in Rust, and works on wasm only until the compiler is told it
 * may assume otherwise; the component model's realloc ignoring its alignment trapped the first
 * time a component that reaches out was called. Nothing that runs a module can see the first, so
 * the source is read for it.
 */
class AWordInTheArenaIsReadWhereverItBeginsTest {

    private static final Path SOURCES = Path.of("runtime/src");

    /** A cast to a pointer at something wider than a byte. */
    private static final Pattern WIDE = Pattern.compile("\\*(const|mut) (u16|u32|u64|i16|i32|i64|usize)\\b");

    @Test
    void takesASliceOfWordsOnlyWhereItsAlignmentWasTaken() throws IOException {
        assertThat(statements("core::slice::from_raw_parts").stream()
                .filter(each -> WIDE.matcher(each.text()).find())
                .filter(each -> !each.text().contains("alloc_aligned("))
                .map(Statement::where))
                .as("a slice of words over an address alloc answered; take it from alloc_words")
                .isEmpty();
    }

    @Test
    void readsAWordAlignedOnlyInTheFailureRecord() throws IOException {
        // The failure record is placed at a multiple of eight when the arena is, and is the one
        // thing read in place rather than through read_unaligned.
        List<Statement> aligned = new ArrayList<>(statements("core::ptr::read("));
        aligned.addAll(statements("core::ptr::write("));
        assertThat(aligned.stream()
                .filter(each -> WIDE.matcher(each.text()).find())
                .filter(each -> !each.text().contains("FAILURE"))
                .map(Statement::where))
                .as("an aligned read or write of a word; use read_unaligned or write_unaligned")
                .isEmpty();
    }

    private record Statement(String where, String text) {}

    /** Each statement of the runtime's source that begins with {@code call}, up to its semicolon. */
    private static List<Statement> statements(String call) throws IOException {
        List<Statement> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(SOURCES)) {
            for (Path file : files.filter(each -> each.toString().endsWith(".rs")).sorted().toList()) {
                String source = withoutComments(Files.readString(file));
                Matcher at = Pattern.compile(Pattern.quote(call)).matcher(source);
                while (at.find()) {
                    int end = source.indexOf(';', at.start());
                    int line = (int) source.substring(0, at.start()).lines().count();
                    found.add(new Statement(file.getFileName() + ":" + line,
                            source.substring(at.start(), end < 0 ? source.length() : end)));
                }
            }
        }
        assertThat(found).as("calls to " + call + " to look at").isNotEmpty();
        return found;
    }

    /** The source with each line comment blanked, keeping every line where it was. */
    private static String withoutComments(String source) {
        return source.replaceAll("//[^\n]*", "");
    }
}
