package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * A type carries the index of its names where something reads it from a document, and the index of
 * its cases where something writes, orders or carries it, so a module does not carry a table for
 * a type nothing asks that of (issue #77).
 *
 * <p>Read off the module's memory: the descriptor of a set of forty-eight cases is found by what
 * begins it, and the first two of the twenty-four bytes before it are its indexes.
 */
class AnIndexIsCarriedOnlyByATypeTheProgramAsksItOfTest {

    private static final int LEAVES = 48;
    private static final int ENUMERATION = 10;

    /** A module offering only its behaviors: a type it offers is read and written, which is asked. */
    private static String module(String exposed, String behaviors) {
        String leaves = IntStream.range(0, LEAVES).mapToObj(i -> "L" + i).collect(Collectors.joining(" | "));
        String declared = IntStream.range(0, LEAVES).mapToObj(i -> "data L" + i).collect(Collectors.joining("\n"));
        return "module held exposing ( " + exposed + " )\n\n" + declared + "\n\ndata W = " + leaves + "\n\n" + behaviors;
    }

    /** The names index and the cases index of the forty-eight cases' descriptor. */
    private static int[] indexesOf(String source) {
        Running running = Running.linked(Compiled.module(Compiled.program(List.of(source))));
        byte[] memory = running.read(0, running.memoryBytes());
        ByteBuffer words = ByteBuffer.wrap(memory).order(ByteOrder.LITTLE_ENDIAN);
        for (int at = 24; at + 8 <= memory.length; at += 4) {
            if (words.getInt(at) == ENUMERATION && words.getInt(at + 4) == LEAVES) {
                return new int[] {words.getInt(at - 24), words.getInt(at - 20)};
            }
        }
        throw new AssertionError("no set of " + LEAVES + " cases is in the module");
    }

    @Test
    void aTypeAModuleOffersIsReadAndWrittenSoAskedItsNamesAndItsCases() {
        int[] indexes = indexesOf(module("echo, W", """
                behavior echo : (xs: List<W>) -> List<W>

                let echo (xs) = xs
                """));

        assertThat(indexes[0]).as("names").isNotZero();
        assertThat(indexes[1]).as("cases").isNotZero();
    }

    @Test
    void aTypeOrderedIsAskedItsCasesAndNotItsNames() {
        int[] indexes = indexesOf(module("distinct", """
                behavior distinct : (n: Int) -> Int

                let distinct (n) = {
                    let xs: List<W> = [L1, L2, L3]
                    Set.size(Set.fromList(xs))
                }
                """));

        assertThat(indexes[0]).as("names").isZero();
        assertThat(indexes[1]).as("cases").isNotZero();
    }
}
