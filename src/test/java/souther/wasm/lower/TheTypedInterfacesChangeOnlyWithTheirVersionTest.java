package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import souther.wasm.link.TypedOffering;
import souther.wasm.link.TypedWitText;

/**
 * The typed interfaces a program offers, held against a file named for
 * {@link TypedOffering#VERSION}.
 *
 * <p>Written from the program {@link TheSurfaceChangesOnlyWithItsVersionTest} reads, which writes
 * every form a value crosses in, so every way a Souther type comes to a WIT one is in the file.
 * What a model's types come to is what a caller generates bindings from, and a change to it that
 * does not move the version is a caller's bindings going stale with nothing saying so. Raising it
 * needs a new {@code souther-typed-<version>.wit}; the file of a version that has been merged is
 * not edited. CI hands the file to {@code wasm-tools}, so it is held to being WIT as well.
 */
class TheTypedInterfacesChangeOnlyWithTheirVersionTest {

    @Test
    void sayWhatTheFileOfTheirVersionSays() throws IOException {
        String file = "souther-typed-" + TypedOffering.VERSION + ".wit";
        try (InputStream in = getClass().getResourceAsStream(file)) {
            assertThat(in).describedAs(file + " for TypedOffering.VERSION").isNotNull();

            assertThat(TypedWitText.written(WasmCompiler.typedOffering(
                    TheSurfaceChangesOnlyWithItsVersionTest.everyForm())))
                    .isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
