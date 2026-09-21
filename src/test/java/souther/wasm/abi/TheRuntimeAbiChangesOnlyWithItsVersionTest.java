package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;

/**
 * What the runtime exports under {@code __souther_}, with each function's parameters and results,
 * held against a file that is named for {@link RuntimeAbi#VERSION}.
 *
 * <p>A version number is only as good as the change that remembers to move it. Renaming an
 * export or giving it another signature makes this fail until the version is raised, and raising
 * it needs a new {@code surface-v<N>.txt}; the file of a version that has shipped is not edited.
 */
class TheRuntimeAbiChangesOnlyWithItsVersionTest {

    @Test
    void exportsWhatTheFileOfItsVersionSays() throws IOException {
        String file = "surface-v" + RuntimeAbi.VERSION + ".txt";
        try (InputStream in = getClass().getResourceAsStream(file)) {
            assertThat(in).describedAs(file + " for RuntimeAbi.VERSION").isNotNull();

            assertThat(surface()).isEqualTo(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing());
        }
    }

    private static String surface() {
        WasmModule module = Parser.parse(Running.runtimeModule());
        int imported = 0;
        for (int i = 0; i < module.importSection().importCount(); i++) {
            if (module.importSection().getImport(i).importType() == ExternalType.FUNCTION) {
                imported++;
            }
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            var export = module.exportSection().getExport(i);
            if (export.exportType() != ExternalType.FUNCTION
                    || !export.name().startsWith("__souther_")) {
                continue;
            }
            var type = module.typeSection().getType(
                    module.functionSection().getFunctionType(export.index() - imported));
            lines.add(export.name() + " " + type.params() + " -> " + type.returns());
        }
        Collections.sort(lines);
        return String.join("\n", lines);
    }
}
