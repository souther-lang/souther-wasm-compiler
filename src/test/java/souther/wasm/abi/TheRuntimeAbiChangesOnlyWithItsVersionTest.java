package souther.wasm.abi;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.Export;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.GlobalImport;
import com.dylibso.chicory.wasm.types.Import;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;

/**
 * Everything the runtime module shows across its boundary, held against a file that is named for
 * {@link RuntimeAbi#VERSION}: what it imports, and what it exports of every kind, with the
 * signature of each function and the type of each global.
 *
 * <p>A version number is only as good as the change that remembers to move it. What the boundary
 * holds is read off the module rather than from a list written here, so a rename or another
 * signature anywhere on it — an entry point, the allocator, the host call, a table or a global —
 * fails this until the version is raised. Raising it needs a new {@code surface-v<N>.txt}; the
 * file of a version that has shipped is not edited.
 */
class TheRuntimeAbiChangesOnlyWithItsVersionTest {

    @Test
    void showsWhatTheFileOfItsVersionSays() throws IOException {
        String file = "surface-v" + RuntimeAbi.VERSION + ".txt";
        try (InputStream in = getClass().getResourceAsStream(file)) {
            assertThat(in).describedAs(file + " for RuntimeAbi.VERSION").isNotNull();

            assertThat(surface()).isEqualTo(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing());
        }
    }

    private static String surface() {
        WasmModule module = Parser.parse(Running.runtimeModule());
        List<String> lines = new ArrayList<>();
        int importedFunctions = 0;
        int importedGlobals = 0;
        for (int i = 0; i < module.importSection().importCount(); i++) {
            Import each = module.importSection().getImport(i);
            String what = switch (each) {
                case FunctionImport f -> {
                    importedFunctions++;
                    yield "FUNCTION " + signature(module.typeSection().getType(f.typeIndex()));
                }
                case GlobalImport g -> {
                    importedGlobals++;
                    yield "GLOBAL " + g.type() + " " + g.mutabilityType();
                }
                default -> each.importType().toString();
            };
            lines.add("import " + each.module() + "." + each.name() + " " + what);
        }
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            Export each = module.exportSection().getExport(i);
            String what = switch (each.exportType()) {
                case FUNCTION -> "FUNCTION " + signature(module.typeSection().getType(
                        module.functionSection().getFunctionType(each.index() - importedFunctions)));
                case GLOBAL -> {
                    var global = module.globalSection().getGlobal(each.index() - importedGlobals);
                    yield "GLOBAL " + global.valueType() + " " + global.mutabilityType();
                }
                default -> each.exportType().toString();
            };
            lines.add("export " + each.name() + " " + what);
        }
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    private static String signature(FunctionType type) {
        return type.params() + " -> " + type.returns();
    }
}
