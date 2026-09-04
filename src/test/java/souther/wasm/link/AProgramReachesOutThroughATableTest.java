package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportMemory;
import com.dylibso.chicory.runtime.ImportTable;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.ValType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.abi.RuntimeAbi;

/**
 * The two modules a component reaches out through, run.
 *
 * <p>Nothing here is a component. What a component adds is who the lowered calls are and where the
 * memory came from, and neither of those is what could be got wrong: what could is that a call out
 * of the program reaches the behavior it was numbered for, and that what comes back keeps the
 * bargain the program's own crossing has always made — the buffer is the program's, and what is
 * answered is how long the answer wanted to be.
 *
 * <p>So the lowerings are stood in for by functions that write what a lowering writes. Reaching
 * one is the whole of what these two modules do.
 */
class AProgramReachesOutThroughATableTest {

    /** Where the stand-in for the arena hands out the two words a lowering answers in. */
    private static final int RETURN_AREA = 0x100;

    /** Where a stand-in lowering puts the answer itself. */
    private static final int ANSWER = 0x200;

    /** Where the program's own buffer is, in the memory these are run against. */
    private static final int BUFFER = 0x400;

    @Test
    void reachesTheBehaviorTheCallWasNumberedFor() {
        Reached reached = new Reached(List.of("first", "second"));

        assertThat(reached.call(0, "asked", BUFFER, 64)).isEqualTo(5);
        assertThat(reached.at(BUFFER, 5)).isEqualTo("first");

        assertThat(reached.call(1, "asked", BUFFER, 64)).isEqualTo(6);
        assertThat(reached.at(BUFFER, 6)).isEqualTo("second");
    }

    @Test
    void handsTheCallWhatTheProgramWroteAsItsArguments() {
        Reached reached = new Reached(List.of("answered"));

        reached.call(0, "[\"JPY\"]", BUFFER, 64);

        assertThat(reached.asked).containsExactly("[\"JPY\"]");
    }

    @Test
    void writesNothingWhereTheAnswerDoesNotFitAndSaysHowLongItWanted() {
        Reached reached = new Reached(List.of("second"));
        reached.write(BUFFER, "......");

        // Six characters into five bytes: the program asks again against a longer buffer, and what
        // it holds until then is what it held, not half an answer.
        assertThat(reached.call(0, "asked", BUFFER, 5)).isEqualTo(6);
        assertThat(reached.at(BUFFER, 6)).isEqualTo("......");
    }

    @Test
    void writesTheWholeAnswerWhereItFitsExactly() {
        Reached reached = new Reached(List.of("first"));

        assertThat(reached.call(0, "asked", BUFFER, 5)).isEqualTo(5);
        assertThat(reached.at(BUFFER, 5)).isEqualTo("first");
    }

    /** The two modules, instantiated against a memory and lowerings that answer what they are told. */
    private static final class Reached {

        private final Memory memory = new ByteArrayMemory(new MemoryLimits(1, 1));
        private final Instance answering;
        private final List<String> asked = new ArrayList<>();

        Reached(List<String> answers) {
            answering = Instance.builder(
                            Parser.parse(ReachingOut.answeringThroughATable(answers.size())))
                    .build();
            List<HostFunction> supplied = new ArrayList<>();
            supplied.add(new HostFunction(RuntimeAbi.IMPORT_MODULE, RuntimeAbi.CANONICAL_REALLOC,
                    List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                    List.of(ValType.I32),
                    (instance, arguments) -> new long[] {RETURN_AREA}));
            for (int i = 0; i < answers.size(); i++) {
                supplied.add(lowering(i, answers.get(i)));
            }
            Instance.builder(Parser.parse(ReachingOut.fillingTheTable(answers.size())))
                    .withImportValues(ImportValues.builder()
                            .addMemory(new ImportMemory(
                                    RuntimeAbi.IMPORT_MODULE, RuntimeAbi.MEMORY, memory))
                            .addTable(new ImportTable(RuntimeAbi.IMPORT_MODULE, ReachingOut.TABLE,
                                    answering.table(0)))
                            .addFunction(supplied.toArray(new HostFunction[0]))
                            .build())
                    .build();
        }

        /** What a lowering does: put the answer somewhere, and say where it is and how long. */
        private HostFunction lowering(int ordinal, String answer) {
            return new HostFunction(RuntimeAbi.IMPORT_MODULE, ReachingOut.lowered(ordinal),
                    List.of(ValType.I32, ValType.I32, ValType.I32), List.of(),
                    (instance, arguments) -> {
                        asked.add(at((int) arguments[0], (int) arguments[1]));
                        int where = ANSWER + ordinal * 0x40;
                        write(where, answer);
                        memory.writeI32((int) arguments[2], where);
                        memory.writeI32((int) arguments[2] + 4,
                                answer.getBytes(StandardCharsets.UTF_8).length);
                        return null;
                    });
        }

        /** One call out of the program, as the program itself would make it. */
        int call(int ordinal, String arguments, int into, int room) {
            int at = 0x800;
            write(at, arguments);
            return (int) answering.export(RuntimeAbi.IMPORT_HOST_CALL).apply(ordinal, at,
                    arguments.getBytes(StandardCharsets.UTF_8).length, into, room)[0];
        }

        void write(int at, String text) {
            memory.write(at, text.getBytes(StandardCharsets.UTF_8));
        }

        String at(int address, int length) {
            return new String(memory.readBytes(address, length), StandardCharsets.UTF_8);
        }
    }
}
