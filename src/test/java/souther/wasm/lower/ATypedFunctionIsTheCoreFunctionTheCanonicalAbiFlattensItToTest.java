package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import souther.wasm.link.CanonicalAbi;
import souther.wasm.link.Component;
import souther.wasm.link.TypedOffering;
import souther.wasm.link.WitType;

/**
 * The core function each typed one is lifted from or lowered to, held to what
 * {@code wasm-tools component embed --dummy} says it is.
 *
 * <p>The file is that tool's account of the interfaces {@code TheTypedInterfacesChangeOnlyWithTheir
 * VersionTest} holds to their version, written by {@code scripts/core-signatures.mjs}; CI writes it
 * again from the same WIT and requires it to be this one. So what is held here is the compiler's
 * flattening against the Canonical ABI's as another implementation of it reads it, over every form
 * a value crosses in: a record past sixteen core values, a variant whose cases join, a string, a
 * list, and a function imported whose answer is handed back through memory.
 */
class ATypedFunctionIsTheCoreFunctionTheCanonicalAbiFlattensItToTest {

    @Test
    void flattensEveryFunctionAsTheCanonicalAbiDoes() throws IOException {
        TypedOffering offering = WasmCompiler.typedOffering(
                TheSurfaceChangesOnlyWithItsVersionTest.everyForm());
        CanonicalAbi abi = CanonicalAbi.of(offering);
        List<String> computed = new ArrayList<>();
        for (var module : offering.reached().entrySet()) {
            for (TypedOffering.Function function : module.getValue().values()) {
                computed.add("import \"" + TypedOffering.REACHED + "/"
                        + Component.interfaceName(module.getKey()) + "\" \"" + function.name()
                        + "\" " + abi.lowered(parameters(function), function.answers()));
            }
        }
        exported(computed, abi, TypedOffering.PROGRAM, offering.behaviors());
        exported(computed, abi, TypedOffering.DECODE, offering.readable());

        assertThat(computed).isEqualTo(said());
    }

    private static void exported(List<String> computed, CanonicalAbi abi, String held,
            Map<String, Map<String, TypedOffering.Function>> modules) {
        for (var module : modules.entrySet()) {
            for (TypedOffering.Function function : module.getValue().values()) {
                computed.add("export \"" + held + "/" + Component.interfaceName(module.getKey())
                        + "|" + function.name() + "\" "
                        + abi.lifted(parameters(function), function.refusing()));
            }
        }
    }

    private static List<WitType> parameters(TypedOffering.Function function) {
        return function.parameters().stream().map(WitType.Field::type).toList();
    }

    /**
     * What the tool said, less what is the tool's own — its memory, its realloc, its start, and the
     * post-returns, which are this backend's to write and say nothing of a function's type — and
     * less the version and the prefix it names an interface with.
     */
    private List<String> said() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("souther-typed-0.1.0.core.txt")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.contains("_post\"") && !line.contains("\"cm32p2_"))
                    .map(line -> line.replace("cm32p2|", "").replaceAll("@[0-9.]+", ""))
                    .toList();
        }
    }
}
