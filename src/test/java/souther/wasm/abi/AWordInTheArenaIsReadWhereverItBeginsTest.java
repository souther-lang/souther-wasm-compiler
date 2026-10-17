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
 * piece by piece stays one run. A word in it is read with {@code read_unaligned}; words read in
 * place, as a slice or through a pointer, are taken from {@code alloc_words}, which pads first. A
 * slice, an index or an aligned read over an address {@code alloc} answered is undefined in Rust,
 * and works on wasm only until the compiler is told it may assume otherwise; the component model's realloc ignoring its alignment trapped the first
 * time a component that reaches out was called. Nothing that runs a module can see the first, so
 * the source is read for it.
 */
class AWordInTheArenaIsReadWhereverItBeginsTest {

    private static final Path SOURCES = Path.of("runtime/src");

    /** A cast to a pointer at something wider than a byte. */
    private static final Pattern WIDE = Pattern.compile("\\bas \\*(const|mut) (u16|u32|u64|i16|i32|i64|usize)\\b");

    @Test
    void pointsAtAWordOnlyToReadItUnalignedOrWhereItsAlignmentWasTaken() throws IOException {
        // Every pointer at something wider than a byte, whatever is done with it: a slice made of
        // it, a read or a write in place, an index through it. The failure record is placed at a
        // multiple of eight when the arena is, and is the one thing read in place.
        List<String> unaligned = new ArrayList<>();
        try (Stream<Path> files = Files.list(SOURCES)) {
            for (Path file : files.filter(each -> each.toString().endsWith(".rs")).sorted().toList()) {
                String source = withoutComments(Files.readString(file));
                Matcher cast = WIDE.matcher(source);
                while (cast.find()) {
                    String statement = statementAround(source, cast.start());
                    if (!statement.contains("_unaligned(") && !statement.contains("alloc_aligned(")
                            && !statement.contains("FAILURE")) {
                        unaligned.add(file.getFileName() + ":"
                                + source.substring(0, cast.start()).lines().count());
                    }
                }
            }
        }
        assertThat(unaligned)
                .as("a pointer at a word that is neither read unaligned nor from alloc_aligned")
                .isEmpty();
    }

    /** The statement {@code at} falls in: from the brace or semicolon before it to the next semicolon. */
    private static String statementAround(String source, int at) {
        int start = Math.max(source.lastIndexOf(';', at),
                Math.max(source.lastIndexOf('{', at), source.lastIndexOf('}', at))) + 1;
        int end = source.indexOf(';', at);
        return source.substring(start, end < 0 ? source.length() : end);
    }

    /** The source with each line comment blanked, keeping every line where it was. */
    private static String withoutComments(String source) {
        return source.replaceAll("//[^\n]*", "");
    }
}
