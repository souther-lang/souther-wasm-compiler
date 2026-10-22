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
 * of the program reaches the behavior it was numbered for, that what comes back is where the
 * lowering put the answer and how long it is, packed as the program's own crossing answers them, and
 * that the lowering is called once whatever it answers.
 *
 * <p>So the lowerings are stood in for by functions that write what a lowering writes. Reaching
 * one is the whole of what these two modules do.
 */
class AProgramReachesOutThroughATableTest {

    /** Where the stand-in for the arena hands out the two words a lowering answers in. */
    private static final int RETURN_AREA = 0x100;

    /** Where a stand-in lowering puts the answer itself. */
    private static final int ANSWER = 0x200;

    @Test
    void reachesTheBehaviorTheCallWasNumberedFor() {
        Reached reached = new Reached(List.of("first", "second"));

        assertThat(reached.answerOf(0, "asked")).isEqualTo("first");
        assertThat(reached.answerOf(1, "asked")).isEqualTo("second");
    }

    @Test
    void handsTheCallWhatTheProgramWroteAsItsArguments() {
        Reached reached = new Reached(List.of("answered"));

        reached.call(0, "[\"JPY\"]");

        assertThat(reached.asked).containsExactly("[\"JPY\"]");
    }

    @Test
    void answersWhereTheLoweringPutTheAnswerRatherThanACopyOfIt() {
        Reached reached = new Reached(List.of("first", "second"));

        long answered = reached.call(1, "asked");

        assertThat((int) answered).isEqualTo(ANSWER + 0x40);
        assertThat((int) (answered >>> 32)).isEqualTo(6);
    }

    @Test
    void callsTheLoweringOnceWhateverTheLengthOfItsAnswer() {
        Reached reached = new Reached(List.of("x".repeat(1000)));

        assertThat(reached.answerOf(0, "asked")).isEqualTo("x".repeat(1000));
        assertThat(reached.asked).hasSize(1);
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

        /**
         * One call out of the program, as the program itself would make it: where the answer is in
         * the low half, how long in the high.
         */
        long call(int ordinal, String arguments) {
            int at = 0x8000;
            write(at, arguments);
            return answering.export(RuntimeAbi.IMPORT_HOST_CALL).apply(ordinal, at,
                    arguments.getBytes(StandardCharsets.UTF_8).length)[0];
        }

        /** What one call out of the program answered, read where it says it is. */
        String answerOf(int ordinal, String arguments) {
            long answered = call(ordinal, arguments);
            return at((int) answered, (int) (answered >>> 32));
        }

        void write(int at, String text) {
            memory.write(at, text.getBytes(StandardCharsets.UTF_8));
        }

        String at(int address, int length) {
            return new String(memory.readBytes(address, length), StandardCharsets.UTF_8);
        }
    }
}
