package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;

/**
 * What a component's host allocates through, reads a result out of, and hands back.
 *
 * <p>Nothing here builds a component — these are the three runtime functions a component's
 * canonical calls go through, asked directly. The one thing a component asks of them that no
 * other caller does is where a result may begin, and that is the one thing nothing else would
 * catch: a value at an address the arena was free to choose is read by a host that is not.
 */
class WhatACanonicalCallOwesAndGetsBackTest {

    @Test
    void beginsAResultWhereWhatReadsItCanBegin() {
        Running module = Running.bareRuntime();

        // Whatever the arena has handed out, and it hands out runs exactly as long as they were
        // asked for, so it is free to be standing anywhere at all.
        for (int taken = 0; taken < 8; taken++) {
            module.call(RuntimeAbi.ALLOC, 1);
            int area = module.call(RuntimeAbi.LIFT_AREA, 12, 34);
            assertThat(area % 4).describedAs("a result after " + taken + " odd bytes").isZero();
            assertThat(wordAt(module, area)).isEqualTo(12);
            assertThat(wordAt(module, area + 4)).isEqualTo(34);
        }
    }

    @Test
    void handsBackEverythingACallMadeAndNothingBelowIt() {
        Running module = Running.bareRuntime();
        int began = module.call(RuntimeAbi.ALLOC_MARK);
        module.call(RuntimeAbi.ALLOC, 100);
        int partway = module.call(RuntimeAbi.ALLOC_MARK);

        module.call(RuntimeAbi.ALLOC, 1000);
        module.run(RuntimeAbi.ARENA_REWIND);

        // Where the arena began, not where it last stood: what a call owes back is the whole of
        // what it made, and a mark somewhere in the middle of one is not that.
        assertThat(module.call(RuntimeAbi.ALLOC_MARK)).isEqualTo(began).isNotEqualTo(partway);
    }

    @Test
    void keepsWhatWasThereWhenTheHostAsksForMoreRoom() {
        Running module = Running.bareRuntime();

        int held = module.call(RuntimeAbi.CANONICAL_REALLOC, 0, 0, 1, 5);
        module.write(held, "hello".getBytes(StandardCharsets.UTF_8));
        int wider = module.call(RuntimeAbi.CANONICAL_REALLOC, held, 5, 1, 11);

        assertThat(new String(module.read(wider, 5), StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(wider).describedAs("a wider run is somewhere else").isNotEqualTo(held);
    }

    @Test
    void keepsOnlyAsMuchAsFitsWhereTheHostAsksForLessRoom() {
        Running module = Running.bareRuntime();

        int held = module.call(RuntimeAbi.CANONICAL_REALLOC, 0, 0, 1, 5);
        module.write(held, "hello".getBytes(StandardCharsets.UTF_8));
        int shorter = module.call(RuntimeAbi.CANONICAL_REALLOC, held, 5, 1, 2);

        assertThat(new String(module.read(shorter, 2), StandardCharsets.UTF_8)).isEqualTo("he");
    }

    private static int wordAt(Running module, int address) {
        byte[] held = module.read(address, 4);
        return (held[0] & 0xff) | (held[1] & 0xff) << 8 | (held[2] & 0xff) << 16
                | (held[3] & 0xff) << 24;
    }
}
