package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import souther.wasm.Running;

/**
 * How much room a cell asks the arena for, against how much of it the cell then writes in.
 *
 * <p>The arena hands out exactly what was asked for and nothing more — that is what lets a run of
 * text written piece by piece be one run — so a cell asked for too little ends where the next one
 * begins, and the last place it writes is somebody else's first word. Nothing reads back wrong at
 * once: the value is still where it was put, and only the tag of what came after it is gone, which
 * is read by what asks whether an optional holds something.
 *
 * <p>Asked of the arena rather than of a program, because a program showing it needs a walk that
 * builds more than a few of something whose tag is then asked about, and there is no reason to
 * wait for one to be written.
 */
class ACellIsAsLongAsWhatItHoldsTest {

    /**
     * What a builder writes in: how many it holds, how many it has room for, and the room.
     *
     * <p>Written down here as well as in the runtime, because the two are one claim and the number
     * this asks against is the one a reader of the runtime would work out.
     */
    private static final int A_BUILDER_HOLDS = 4;
    private static final int HEADER = 8;
    private static final int COUNTED = 4;
    private static final int ROOM = 4;

    @Test
    void aBuilderEndsWhereItStopsWriting() {
        Running module = Running.bareRuntime();

        int builder = module.call(RuntimeAbi.BUILDER, 0);
        int next = module.call(RuntimeAbi.ALLOC, 8);

        assertThat(next - builder)
                .describedAs("a builder's own bytes")
                .isGreaterThanOrEqualTo(HEADER + COUNTED + ROOM + 4 * A_BUILDER_HOLDS);
    }

    @Test
    void aBuilderGrownPastItsRoomEndsWhereItStopsWriting() {
        Running module = Running.bareRuntime();

        // Enough to make it ask for a longer one, which asks the same question a second time.
        int builder = module.call(RuntimeAbi.BUILDER, 0);
        int wider = builder;
        for (int i = 0; i <= A_BUILDER_HOLDS; i++) {
            wider = module.call(RuntimeAbi.GROW, wider, oneOf(module, i));
        }
        int next = module.call(RuntimeAbi.ALLOC, 8);

        // A builder that grew holds one more than twice what it had room for.
        int held = 2 * A_BUILDER_HOLDS + 1;
        assertThat(next - wider)
                .describedAs("a grown builder's own bytes")
                .isGreaterThanOrEqualTo(HEADER + COUNTED + ROOM + 4 * held);
    }

    /** A list of one number, which is what a walk hands a builder at each step. */
    private static int oneOf(Running module, int value) {
        int list = module.call(RuntimeAbi.LIST, 0, 1);
        module.run(RuntimeAbi.LIST_SET, list, 0, module.call(RuntimeAbi.INT, value));
        return list;
    }
}
