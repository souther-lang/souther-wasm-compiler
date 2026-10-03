package souther.wasm.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

/**
 * What a value read at the boundary comes to, held for every backend to one fixture.
 *
 * <p>The boundary's failures are Raoh's issues (spec §decoder-error), and which issue a value is
 * — its path, its code, its message key and its metadata — is the language's to say and not a
 * backend's. Two backends each working it out for themselves came apart without a word: this one
 * said a broken pattern as an invariant violation numbering the clause, where the JVM said it as
 * the format Raoh says it as. So each case in {@code conformance/issues} says what the issues are,
 * once, and every backend that reads a boundary is held to it — neither is the other's oracle, and
 * a case where the two differ fails for both until the fixture says which is right.
 *
 * <p>The fixtures are read by the TypeScript side too, so the one file says what a value comes to
 * from the model down to the message a person reads.
 */
class EveryBackendSaysWhatTheBoundarySaysTest {

    private static final Path FIXTURES = Path.of("conformance/issues");

    @TestFactory
    Stream<DynamicNode> theJvm() {
        return over("jvm", Boundaries::jvm);
    }

    @TestFactory
    Stream<DynamicNode> theWasmModule() {
        return over("wasm", Boundaries::wasm);
    }

    private interface Reader {
        JsonNode read(String model, String type, JsonNode input);
    }

    private static Stream<DynamicNode> over(String backend, Reader reader) {
        List<DynamicNode> files = new ArrayList<>();
        for (Path file : fixtures()) {
            JsonNode fixture = Boundaries.document(read(file));
            String model = fixture.get("model").asString();
            List<DynamicNode> cases = new ArrayList<>();
            for (JsonNode each : fixture.get("cases")) {
                cases.add(DynamicTest.dynamicTest(each.get("about").asString(), () -> {
                    JsonNode answered = Boundaries.document(reader.read(
                            model, each.get("type").asString(), each.get("input")).toString());
                    // A case written without what it expects says what was answered, so the
                    // fixture is finished by deciding whether that is right rather than by
                    // working it out by hand.
                    assertThat(each.has("expect")).describedAs("a case says what it expects; "
                            + backend + " answered " + answered).isTrue();
                    assertThat(answered).describedAs(backend + " reading " + each.get("input"))
                            .isEqualTo(expected(each.get("expect")));
                }));
            }
            files.add(DynamicContainer.dynamicContainer(file.getFileName().toString(), cases));
        }
        return files.stream();
    }

    /** What a case expects, written as a backend's answer is. */
    private static JsonNode expected(JsonNode expect) {
        return Boundaries.document(expect.isString()
                ? "{\"value\":\"decoded\"}"
                : "{\"issues\":" + expect + "}");
    }

    private static List<Path> fixtures() {
        try (Stream<Path> files = Files.list(FIXTURES)) {
            return files.filter(each -> each.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
