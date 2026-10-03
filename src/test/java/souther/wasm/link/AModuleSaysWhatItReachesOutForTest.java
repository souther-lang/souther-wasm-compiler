package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a module reaches out for, said by the module.
 *
 * <p>A call out carries a number rather than a name, because a name would travel as bytes on every
 * call for something a caller looks up once. So what the numbers are has to be said somewhere, and
 * the module says it on the surface it carries, as each such behavior's {@code reachOut}: a caller
 * holding the module holds this, and there is no second file to be handed the wrong one of, and no
 * second list in the module to say a behavior differently from the first.
 *
 * <p>Two reasons a program holds no implementation for a behavior — the caller supplies it, or
 * another build did — and one call either way. Which of them it is is the behavior's
 * {@code implementation}, beside the number, and not how the call is made.
 */
class AModuleSaysWhatItReachesOutForTest {

    @Test
    void numbersEveryBehaviorItReachesOutFor() {
        JsonNode behaviors = behaviorsOf(Compiled.module(Compiled.program(List.of("""
                module store

                behavior findIt : (id: Int) -> String

                behavior storeIt : (id: Int, held: String) -> Int

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n
                """))));

        // The behaviors written here are not among them, and the numbers run from nothing.
        List<String> numbered = new ArrayList<>();
        behaviors.forEach(each -> numbered.add(each.get("export").asString() + " "
                + (each.has("reachOut") ? each.get("reachOut").asInt() : "-")
                + " " + each.get("implementation").asString()));
        assertThat(numbered).containsExactly(
                "store.findIt 0 injected", "store.storeIt 1 injected", "store.doubled - here");
    }

    @Test
    void carriesNoSectionBesideTheSurface() {
        byte[] module = Compiled.module(Compiled.program(List.of("""
                module store

                behavior findIt : (id: Int) -> String
                """)));

        assertThat(new String(module, StandardCharsets.ISO_8859_1))
                .doesNotContain("souther:crossings");
    }

    private static JsonNode behaviorsOf(byte[] module) {
        return new ObjectMapper().readTree(Running.customSection(module, "souther:surface"))
                .get("modules").get(0).get("behaviors");
    }
}
