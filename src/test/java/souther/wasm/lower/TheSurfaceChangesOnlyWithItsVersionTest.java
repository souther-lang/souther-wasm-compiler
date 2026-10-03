package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import souther.compiler.Compiler;
import souther.compiler.jvm.ClassFileImage;
import souther.compiler.meta.ModulePath;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What {@code souther:surface} says, held against a file named for {@link Surface#VERSION}.
 *
 * <p>A version is only as good as the change that remembers to move it. So what the surface says
 * is read off a module rather than written here — every place in it, the kind of thing at each,
 * and the words a closed vocabulary may hold — and a key added, removed or given another kind of
 * value fails this until the version is raised. Raising it needs a new
 * {@code souther-surface-v<N>.txt}; the file of a version that has been merged is not edited.
 *
 * <p>Read off one program that writes every form the surface has: a product, a newtype, both forms
 * of a sum, a unit, every collection, an optional in each place it stands, a map under each kind of
 * key, an answer nobody named, a composition, a behavior the caller supplies, a declaration from the
 * path, and a type that is offered and one that is kept. What this backend does not write yet — a
 * behavior nobody has written, and a call into a build on the path — is not among them, and the
 * day it is, the file moves with it.
 */
class TheSurfaceChangesOnlyWithItsVersionTest {

    /** The words a value at a key of these names is one of, which a reader dispatches on. */
    private static final Set<String> VOCABULARY = Set.of("is", "by", "implementation", "scalar");

    private static final String ON_THE_PATH = """
            module lib.rates exposing ( Rate, rateFor )

            data Rate = { bps: Int }

            behavior rateFor : (of: Int) -> Rate

            let rateFor (of) = Rate { bps = of }
            """;

    private static final String EVERY_FORM = """
            module every exposing ( Code, Shape, Colour, Nothing, Wide, Missing, Point, Line,
                                    price, injected, pick, rated, composed, sizes )

            import lib.rates ( Rate )

            data Code = String
                invariant written = String.length(value) > 0
                invariant String.length(value) < 9

            data Point = { x: Int }
            data Line = { from: Point }
            data Nothing
            data Shape = Point | Line | Code | Nothing
            data Colour = Red | Green
            data Red
            data Green
            data Missing

            data Wide =
                { code: Code
                , note: String?
                , notes: List<Option<String>>
                , tags: Set<String>
                , byCode: Map<Code, Int>
                , byDay: Map<Date, Option<Decimal>>
                , on: Date
                , at: Time
                , stamped: DateTime
                , moment: Instant
                , flag: Bool
                , amount: Decimal
                , shape: Shape
                , colour: Colour
                }
                invariant Map.size(byCode) >= 0

            data Kept = { value: Int }

            behavior price : (wide: Wide, codes: List<Code>, seen: Set<Int>, byCode: Map<String, Int>) -> Int
            let price (wide, codes, seen, byCode) = List.length(codes)

            behavior injected : (code: Code) -> Decimal

            behavior pick : (n: Int) -> Colour | Missing | Int
            let pick (n) = if n > 1 then Red else if n > 0 then Missing else n

            behavior rated : (n: Int) -> Rate

            behavior composed : (n: Int) -> List<Point>
            let composed (n) = [Point { x = n }]

            behavior counted = composed >-> sizes

            behavior sizes : (points: List<Point>) -> Map<String, Set<Int>>
            let sizes (points) = Map.empty
            """;

    @Test
    void saysWhatTheFileOfItsVersionSays() throws IOException {
        String file = "souther-surface-v" + Surface.VERSION + ".txt";
        try (InputStream in = getClass().getResourceAsStream(file)) {
            assertThat(in).describedAs(file + " for Surface.VERSION").isNotNull();

            assertThat(schema()).isEqualTo(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing());
        }
    }

    private static String schema() {
        Map<String, ClassFileImage> published = Compiler.compile(ON_THE_PATH);
        byte[] module = WasmCompiler.compile(
                CheckedProgram.of(List.of(EVERY_FORM), ModulePath.of(published)));
        JsonNode surface = new ObjectMapper().readTree(
                Running.customSection(module, "souther:surface"));
        Set<String> lines = new TreeSet<>();
        walk("$", surface, lines);
        return String.join("\n", lines);
    }

    /** Every place under {@code at}, the kind of value there, and a vocabulary's words. */
    private static void walk(String at, JsonNode node, Set<String> lines) {
        if (node.isObject()) {
            String here = node.has("is") ? at + "{is=" + node.get("is").asString() + "}" : at;
            lines.add(here + " object");
            for (Map.Entry<String, JsonNode> member : node.properties()) {
                String place = here + "." + member.getKey();
                if (VOCABULARY.contains(member.getKey()) && member.getValue().isString()) {
                    lines.add(place + " = " + member.getValue().asString());
                } else {
                    walk(place, member.getValue(), lines);
                }
            }
        } else if (node.isArray()) {
            lines.add(at + " array");
            node.forEach(element -> walk(at + "[]", element, lines));
        } else {
            lines.add(at + " " + (node.isNull() ? "null" : node.isString() ? "string"
                    : node.isBoolean() ? "boolean" : node.isNumber() ? "number" : node.getNodeType()));
        }
    }
}
