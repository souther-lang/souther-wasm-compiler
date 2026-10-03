package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;

/**
 * What the runtime keeps between calls that lives in the arena is forgotten as the arena is popped.
 *
 * <p>Popping hands the memory out again, so anything kept that still pointed into it would read, or
 * write through, whatever was put there next. Not left to the next call to set straight: these pop
 * and then reach the runtime the way nothing in an export's own start does.
 */
class WhatLivedInAPoppedArenaIsForgottenTest {

    private static final String ISSUE = "__souther_issue";

    @Test
    void forgetsTheIssuesFoundBeforeTheArenaWasReset() {
        Running runtime = Running.bareRuntime();
        int mark = runtime.call(RuntimeAbi.ALLOC_MARK);
        issue(runtime);
        assertThat(runtime.call(RuntimeAbi.ISSUES_COUNT)).isEqualTo(1);

        runtime.call(RuntimeAbi.ALLOC_RESET, mark);

        assertThat(runtime.call(RuntimeAbi.ISSUES_COUNT)).isZero();
        // What is staged now lies where the forgotten record did. An issue found after this is the
        // first of a new list, and nothing is written through the old one into what was staged.
        String staged = "x".repeat(256);
        int at = runtime.staged(staged);
        issue(runtime);
        assertThat(runtime.call(RuntimeAbi.ISSUES_COUNT)).isEqualTo(1);
        assertThat(new String(runtime.read(at, staged.length()), StandardCharsets.UTF_8))
                .isEqualTo(staged);
    }

    @Test
    void forgetsTheIssuesFoundBeforeTheArenaWasRewound() {
        Running runtime = Running.bareRuntime();
        issue(runtime);

        runtime.run(RuntimeAbi.ARENA_REWIND);

        assertThat(runtime.call(RuntimeAbi.ISSUES_COUNT)).isZero();
    }

    private static void issue(Running runtime) {
        int text = runtime.staged("type_mismatch");
        runtime.runWith(ISSUE, text, 13, text, 0, text, 13, text, 13);
    }
}
