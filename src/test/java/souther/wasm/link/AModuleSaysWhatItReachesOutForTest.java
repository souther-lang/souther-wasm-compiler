package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.lower.WasmCompiler;

/**
 * What a module reaches out for, said by the module.
 *
 * <p>A call out carries a number rather than a name, because a name would travel as bytes on every
 * call for something a caller looks up once. So what the numbers are has to be said somewhere, and
 * the module says it in a section of itself: a caller holding the module holds this, and there is
 * no second file to be handed the wrong one of.
 *
 * <p>Two reasons a program holds no implementation for a behavior — the caller supplies it, or
 * another build did — and one call either way. Which of them it is is what the module says about
 * itself rather than how the call is made, because to a caller reaching in they are the same call
 * and only one of them has an artifact to be found somewhere.
 */
class AModuleSaysWhatItReachesOutForTest {

    @Test
    void namesEveryBehaviorItReachesOutFor() {
        String said = crossingsIn(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module store

                behavior findIt : (id: Int) -> String

                behavior storeIt : (id: Int, held: String) -> Int

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n
                """))));

        // The behaviors written here are not among them, and the numbers run from nothing.
        assertThat(said)
                .contains("{\"ordinal\":0,\"behavior\":\"store.findIt\"")
                .contains("{\"ordinal\":1,\"behavior\":\"store.storeIt\"")
                .doesNotContain("store.doubled");
    }

    @Test
    void saysWhichOfThemAnotherBuildImplements() {
        String said = crossingsIn(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module store

                behavior findIt : (id: Int) -> String
                """))));

        // Nothing on any module path here, so what this program holds no implementation for is
        // what a caller supplies.
        assertThat(said).contains("\"implementedElsewhere\":false");
    }

    @Test
    void saysNothingWhereItReachesOutForNothing() {
        byte[] module = WasmCompiler.compile(CheckedProgram.of(List.of("""
                module counting

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n
                """)));

        assertThat(new String(module, StandardCharsets.UTF_8)).doesNotContain("souther:crossings");
    }

    /** What the module says it reaches out for, read back out of the module. */
    private static String crossingsIn(byte[] module) {
        String held = new String(module, StandardCharsets.ISO_8859_1);
        int name = held.indexOf("souther:crossings");
        assertThat(name).describedAs("the module says what it reaches out for").isNotEqualTo(-1);
        int from = name + "souther:crossings".length();
        int to = held.indexOf(']', from);
        assertThat(to).describedAs("what it says is one list").isNotEqualTo(-1);
        return new String(module, from, to + 1 - from, StandardCharsets.UTF_8);
    }
}
