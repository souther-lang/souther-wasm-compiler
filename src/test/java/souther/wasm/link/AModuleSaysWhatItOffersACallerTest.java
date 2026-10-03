package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Running;
import souther.wasm.lower.WasmCompiler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a module offers a caller, said by the module.
 *
 * <p>A caller writing code against a module — a type for each value, a function for each behavior
 * — reads what it is writing against out of the module, for the reason it reads what the module
 * reaches out for there: there is no second file to be handed the wrong one of.
 */
class AModuleSaysWhatItOffersACallerTest {

    private static final String CODES = """
            module codes exposing ( Sku )

            data Sku = String
                invariant written = String.matches("[A-Z]{3}-[0-9]{4}", value)
                invariant String.length(value) == 8
            """;

    private static final String CART = """
            module cart exposing ( Line, Membership, Cart, Priced, EmptyCart, price, rate )

            import codes ( Sku )

            data Line = { sku: Sku, quantity: Int, note: String? }
                invariant atLeastOne = quantity >= 1

            data Membership = Standard | Premium

            data Cart = { lines: List<Line>, member: Membership }

            data Priced = { total: Decimal }

            data EmptyCart

            data Kept = { value: Int }

            behavior price : (cart: Cart) -> Priced | EmptyCart

            let price (cart) = {
                guard List.length(cart.lines) >= 1 else EmptyCart
                Priced { total = 1.00m }
            }

            behavior rate : (code: Sku) -> Decimal
            """;

    /** The cart's surface, compiled once: every test here only reads it. */
    private static final JsonNode CARTS =
            surfaceOf(WasmCompiler.compile(CheckedProgram.of(List.of(CODES, CART))));

    private static JsonNode surface() {
        return CARTS;
    }

    @Test
    void saysItsVersion() {
        assertThat(surface().get("version").asInt()).isEqualTo(1);
    }

    /** A behavior: what it is exported as, who answers it, what it takes and what it answers. */
    @Test
    void saysWhatEachBehaviorTakesAndAnswers() {
        JsonNode price = behavior(surface(), "cart", "price");

        assertThat(price.get("export").asString()).isEqualTo("cart.price");
        assertThat(price.get("published").asBoolean()).isTrue();
        assertThat(price.get("implementation").asString()).isEqualTo("here");
        assertThat(price.get("parameters").toString()).isEqualTo(
                "[{\"name\":\"cart\",\"type\":{\"is\":\"declared\",\"module\":\"cart\","
                        + "\"name\":\"Cart\"}}]");
        JsonNode answers = price.get("answers");
        assertThat(answers.get("is").asString()).isEqualTo("union");
        assertThat(names(answers.get("members"))).containsExactlyInAnyOrder("Priced", "EmptyCart");
        assertThat(names(answers.get("crossing").get("cases")))
                .containsExactlyInAnyOrder("Priced", "EmptyCart");
        assertThat(answers.get("crossing").get("form").toString())
                .isEqualTo("{\"is\":\"discriminated\",\"tag\":\"type\",\"contents\":\"value\"}");

        JsonNode rate = behavior(surface(), "cart", "rate");
        assertThat(rate.get("implementation").asString()).isEqualTo("injected");
        assertThat(rate.get("answers").toString())
                .isEqualTo("{\"is\":\"scalar\",\"scalar\":\"decimal\"}");
    }

    /**
     * An answer nobody named is said as both of what it is: the members as they were written, and
     * the leaves those descend to with the form they cross in. With a sum among the members the two
     * differ, and neither can be had from the other — the leaves are a union nobody wrote.
     */
    @Test
    void saysAnAnswersMembersApartFromHowItCrosses() {
        JsonNode said = surfaceOf(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module signals

                data Red
                data Green
                data Signal = Red | Green
                data Missing

                behavior units : (n: Int) -> Signal | Missing
                let units (n) = if n > 1 then Red else if n > 0 then Green else Missing
                """))));

        JsonNode answers = behavior(said, "signals", "units").get("answers");
        assertThat(names(answers.get("members"))).containsExactly("Missing", "Signal");
        assertThat(names(answers.get("crossing").get("cases")))
                .containsExactlyInAnyOrder("Red", "Green", "Missing");
        assertThat(answers.get("crossing").get("form").toString())
                .isEqualTo("{\"is\":\"enumeration\"}");
        // A member is named onto the surface as a case is, so a reader writing the answer's type
        // has the sum it names.
        assertThat(declaration(said, "signals", "Signal").get("is").asString()).isEqualTo("sum");
    }

    /** A shape's fields, an optional among them, and its rules by name and nothing more. */
    @Test
    void saysWhatAShapeIsMadeOfAndHeldTo() {
        JsonNode line = declaration(surface(), "cart", "Line");

        assertThat(line.get("is").asString()).isEqualTo("product");
        assertThat(line.get("by").asString()).isEqualTo("module");
        assertThat(line.get("fields").toString()).isEqualTo("["
                + "{\"name\":\"sku\",\"type\":{\"is\":\"declared\",\"module\":\"codes\",\"name\":\"Sku\"}},"
                + "{\"name\":\"quantity\",\"type\":{\"is\":\"scalar\",\"scalar\":\"int\"}},"
                + "{\"name\":\"note\",\"type\":{\"is\":\"option\",\"of\":{\"is\":\"scalar\",\"scalar\":\"string\"}}}"
                + "]");
        assertThat(line.get("rules").toString()).isEqualTo("[{\"name\":\"atLeastOne\"}]");
    }

    /** A name for another type crosses as that type, and an unnamed rule is there by its place. */
    @Test
    void saysWhatANameForAValueWraps() {
        JsonNode sku = declaration(surface(), "codes", "Sku");

        assertThat(sku.get("is").asString()).isEqualTo("newtype");
        assertThat(sku.get("wraps").toString()).isEqualTo("{\"is\":\"scalar\",\"scalar\":\"string\"}");
        assertThat(sku.get("rules").toString()).isEqualTo("[{\"name\":\"written\"},{\"name\":null}]");
    }

    /** A sum's cases and the form they travel in, as the checker settled it. */
    @Test
    void saysWhatASumsCasesAreAndHowTheyTravel() {
        JsonNode membership = declaration(surface(), "cart", "Membership");

        assertThat(membership.get("is").asString()).isEqualTo("sum");
        assertThat(names(membership.get("cases"))).containsExactly("Standard", "Premium");
        assertThat(membership.get("form").toString()).isEqualTo("{\"is\":\"enumeration\"}");
        assertThat(declaration(surface(), "cart", "Standard").get("is").asString()).isEqualTo("unit");
    }

    /** What no behavior names is there, with whether its module publishes it. */
    @Test
    void saysWhatAModuleDeclaresWhetherOrNotABehaviorNamesIt() {
        JsonNode kept = declaration(surface(), "cart", "Kept");

        assertThat(kept.get("published").asBoolean()).isFalse();
        assertThat(declaration(surface(), "cart", "Line").get("published").asBoolean()).isTrue();
    }

    /** A primitive standing as a case of an answer is named as the scalar it is, and is no
     *  declaration. */
    @Test
    void namesAPrimitiveStandingAsACaseAsTheScalarItIs() {
        JsonNode said = surfaceOf(WasmCompiler.compile(CheckedProgram.of(List.of("""
                module dividing

                data Undivided

                behavior halved : (a: Int, b: Int) -> Int | Undivided

                let halved (a, b) = if b == 0 then Undivided else a + b
                """))));

        JsonNode answers = behavior(said, "dividing", "halved").get("answers");
        for (JsonNode named : List.of(answers.get("members"), answers.get("crossing").get("cases"))) {
            assertThat(named.toString())
                    .contains("{\"is\":\"scalar\",\"scalar\":\"int\"}")
                    .contains("{\"is\":\"declared\",\"module\":\"dividing\",\"name\":\"Undivided\"}");
        }
        assertThat(said.get("declarations")).hasSize(1);
    }

    private static JsonNode behavior(JsonNode surface, String module, String name) {
        for (JsonNode each : surface.get("modules")) {
            if (each.get("name").asString().equals(module)) {
                for (JsonNode behavior : each.get("behaviors")) {
                    if (behavior.get("name").asString().equals(name)) {
                        return behavior;
                    }
                }
            }
        }
        throw new AssertionError("no behavior " + module + "." + name + " in " + surface);
    }

    private static JsonNode declaration(JsonNode surface, String module, String name) {
        for (JsonNode each : surface.get("declarations")) {
            if (each.get("module").asString().equals(module)
                    && each.get("name").asString().equals(name)) {
                return each;
            }
        }
        throw new AssertionError("no declaration " + module + "." + name + " in " + surface);
    }

    private static List<String> names(JsonNode types) {
        List<String> names = new ArrayList<>();
        types.forEach(each -> names.add(each.get("name").asString()));
        return names;
    }

    /** The custom section {@code souther:surface}, read as JSON. */
    private static JsonNode surfaceOf(byte[] module) {
        return new ObjectMapper().readTree(Running.customSection(module, "souther:surface"));
    }
}
