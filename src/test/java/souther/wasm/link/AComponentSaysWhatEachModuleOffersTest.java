package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import souther.compiler.program.CheckedProgram;
import souther.wasm.Compiled;
import souther.wasm.lower.WasmCompiler;

/**
 * The component a program is wrapped as, read back out of what was written.
 *
 * <p>A Souther module comes out as an interface and a behavior as a function of it, under the name
 * an interface gives it rather than the one Souther wrote. Nothing here runs the component — what
 * it checks is that what was written says what it was meant to say.
 */
class AComponentSaysWhatEachModuleOffersTest {

    @Test
    void offersOneInterfacePerModuleAndOneFunctionPerBehavior() {
        byte[] component = Compiled.component(Compiled.program(List.of("""
                module counting

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n

                behavior withVat : (n: Int) -> String

                let withVat (n) = String.fromDecimal(Decimal.fromInt(n) * 1.10m)
                """, """
                module greeting

                behavior hello : (name: String) -> String

                let hello (name) = String.append("hello ", name)
                """)));

        assertThat(offered(component)).containsOnlyKeys(
                "souther:program/counting", "souther:program/greeting");
        assertThat(offered(component).get("souther:program/counting"))
                .containsExactlyInAnyOrder("doubled", "with-vat");
        assertThat(offered(component).get("souther:program/greeting"))
                .containsExactly("hello");
    }

    @Test
    void beginsAsAComponentAndCarriesTheProgramAsACoreModule() {
        byte[] component = Compiled.component(oneBehavior());

        assertThat(component[0]).isEqualTo((byte) 0x00);
        assertThat(new String(component, 1, 3, StandardCharsets.UTF_8)).isEqualTo("asm");
        // Version and layer: a component says layer one where a core module says nothing.
        assertThat(component[6]).isEqualTo((byte) 0x01);
        assertThat(sections(component).keySet())
                .contains(SEC_CORE_MODULE, SEC_CORE_INSTANCE, SEC_ALIAS,
                        SEC_TYPE, SEC_CANON, SEC_INSTANCE, SEC_EXPORT);
    }

    @Test
    void asksForABehaviorSuppliedFromOutsideRatherThanOfferingIt() {
        byte[] component = Compiled.component(Compiled.program(List.of("""
                module rates

                behavior today : (pair: String) -> Decimal

                behavior priced : (n: Decimal) -> Decimal depends on today

                let priced (n, today) = n * today("JPY")
                """)));

        // Nothing in the module answers `today`, so a component that offered it would be offering
        // the caller's own answer back. It is asked for, under a name of its own: one interface
        // says what a program answers and the other what it has to be given.
        assertThat(offered(component)).containsOnlyKeys("souther:program/rates");
        assertThat(offered(component).get("souther:program/rates")).containsExactly("priced");
        assertThat(askedFor(component)).containsExactly("souther:reached/rates");
    }

    @Test
    void offersNothingItsModuleKeeps() {
        byte[] component = Compiled.component(Compiled.program(List.of("""
                module rates exposing ( spread )

                behavior today : (pair: String) -> Int

                behavior lowered : (n: Int) -> Int

                let lowered (n) = n - 1

                behavior spread : (pair: String) -> Int
                    depends on today

                let spread (pair, today) = lowered(today(pair))
                """)));

        // What the module keeps is no function of its interface, whoever answers it; one kept and
        // answered outside is still asked for, since the program cannot run without it.
        assertThat(offered(component).get("souther:program/rates")).containsExactly("spread");
        assertThat(askedFor(component)).containsExactly("souther:reached/rates");
    }

    @Test
    void offersEachTypeItsModulePublishesToBeReadOnItsOwn() {
        CheckedProgram program = Compiled.program(List.of("""
                module cart exposing ( Sku, LineItem, line_item )

                data Sku = String

                data LineItem = { sku: Sku, quantity: Int }

                data Note = String

                behavior line_item : (s: Sku) -> LineItem

                let line_item (s) = LineItem { sku = s, quantity = 1 }
                """, """
                module shared.money

                data Amount = Decimal
                """));
        byte[] component = Compiled.component(program);

        // A type and a behavior of one module may come to one name, so the types are read under an
        // interface of their own; a kept type is no caller's to make a value of; and a module that
        // publishes types and no behavior still offers them.
        assertThat(offered(component)).containsOnlyKeys("souther:program/cart",
                "souther:program/shared-money", "souther:decode/cart",
                "souther:decode/shared-money");
        assertThat(offered(component).get("souther:program/cart")).containsExactly("line-item");
        assertThat(offered(component).get("souther:decode/cart"))
                .containsExactly("sku", "line-item");
        assertThat(offered(component).get("souther:decode/shared-money")).containsExactly("amount");

        // What is written down for a reader of interfaces is what the component offers.
        assertThat(WitText.of(WasmCompiler.offering(program)))
                .contains("  export souther:decode/cart;\n", "  export souther:decode/shared-money;\n")
                .contains("package souther:decode {\n  interface cart {\n"
                        + "    record ended { reason: u32 }\n"
                        + "    sku: func(value: string) -> result<string, ended>;\n"
                        + "    line-item: func(value: string) -> result<string, ended>;\n  }\n");
    }

    @Test
    void refusesABehaviorOrATypeAnInterfaceWouldNameAsWhatACallThatEndedAnswers() {
        CheckedProgram behavior = Compiled.program(List.of("""
                module demo

                behavior ended : (n: Int) -> Int

                let ended (n) = n
                """));
        CheckedProgram type = Compiled.program(List.of("""
                module demo

                data Ended = String
                """));

        assertThatThrownBy(() -> Compiled.component(behavior))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declares ended");
        assertThatThrownBy(() -> WitText.of(WasmCompiler.offering(type)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declares Ended");
    }

    @Test
    void refusesTwoTypesOneInterfaceWouldCallByOneName() {
        CheckedProgram program = Compiled.program(List.of("""
                module demo

                data SkuCode = String

                data Sku_code = String
                """));

        assertThatThrownBy(() -> Compiled.component(program))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SkuCode")
                .hasMessageContaining("Sku_code")
                .hasMessageContaining("sku-code");
    }

    @Test
    void liftsTheWrapperRatherThanTheCrossingItself() {
        byte[] component = Compiled.component(oneBehavior());

        // The crossing answers where its answer is and how long it is, and a component reads a
        // string result out of memory, so what a lift names is never the crossing.
        assertThat(aliased(component))
                .contains(Component.Lifted.wrapping("counting.doubled"))
                .doesNotContain("counting.doubled");
    }

    /** What the component takes out of the core instance, which is what its lifts can name. */
    private static List<String> aliased(byte[] component) {
        List<String> found = new ArrayList<>();
        for (byte[] payload : sections(component).getOrDefault(SEC_ALIAS, List.of())) {
            Cursor at = new Cursor(payload);
            int entries = at.leb();
            for (int i = 0; i < entries; i++) {
                // What sort of thing it is, in two bytes: a core one, and which core one.
                assertThat(at.byteAt()).isEqualTo(0x00);
                at.byteAt();
                // Every alias here reaches into a core instance for something it exports.
                assertThat(at.byteAt()).isEqualTo(0x01);
                at.leb();
                found.add(new String(at.take(at.leb()), StandardCharsets.UTF_8));
            }
        }
        return found;
    }

    @Test
    void offersAModuleNamedInPartsUnderOneNameAnInterfaceCanCarry() {
        byte[] component = Compiled.component(Compiled.program(List.of("""
                module shared.money

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n
                """)));

        // A module is named in parts and an interface is named in one. This is the one name a
        // component carries that reading the component back cannot check, because it stands in the
        // export where a reader that cannot spell it cannot read past it.
        assertThat(offered(component)).containsOnlyKeys("souther:program/shared-money");
    }

    @Test
    void namesEveryExportSomethingAComponentMayBeExportedUnder() {
        byte[] component = Compiled.component(Compiled.program(List.of("""
                module a.b.c

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n
                """)));

        for (String name : offered(component).keySet()) {
            assertThat(name).describedAs("an export name")
                    .matches("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*/[a-z][a-z0-9-]*");
        }
    }

    @Test
    void refusesTwoBehaviorsOneInterfaceWouldCallByOneName() {
        byte[] core = Compiled.module(oneBehavior());

        for (String[] pair : new String[][] {{"doubled", "Doubled"}, {"withVat", "with_vat"}}) {
            Map<String, String> both = new LinkedHashMap<>();
            both.put(pair[0], "demo." + pair[0]);
            both.put(pair[1], "demo." + pair[1]);

            assertThatThrownBy(() -> Component.around(core, Map.of("demo", both)))
                    .describedAs(pair[0] + " beside " + pair[1])
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(Component.interfaceName(pair[0]));
        }
    }

    @Test
    void refusesANameAnInterfaceCouldNotWriteDown() {
        Map<String, String> named = new LinkedHashMap<>();
        named.put("_hidden", "demo._hidden");

        assertThatThrownBy(() -> Component.around(
                        Compiled.module(oneBehavior()), Map.of("demo", named)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a name an interface writes");
    }

    @Test
    void callsABehaviorWhatAnInterfaceCallsIt() {
        assertThat(Component.interfaceName("doubled")).isEqualTo("doubled");
        assertThat(Component.interfaceName("withVat")).isEqualTo("with-vat");
        assertThat(Component.interfaceName("toIso8601")).isEqualTo("to-iso8601");
        assertThat(Component.interfaceName("parseURL")).isEqualTo("parse-u-r-l");
        assertThat(Component.interfaceName("with_vat")).isEqualTo("with-vat");
        assertThat(Component.interfaceName("Doubled")).isEqualTo("doubled");
    }

    private static CheckedProgram oneBehavior() {
        return Compiled.program(List.of("""
                module counting

                behavior doubled : (n: Int) -> Int

                let doubled (n) = n + n
                """));
    }

    private static final int SEC_CORE_MODULE = 1;
    private static final int SEC_CORE_INSTANCE = 2;
    private static final int SEC_ALIAS = 6;
    private static final int SEC_TYPE = 7;
    private static final int SEC_CANON = 8;
    private static final int SEC_INSTANCE = 5;
    private static final int SEC_EXPORT = 11;
    private static final int SORT_TYPE = 0x03;

    /**
     * What each exported interface offers, by the name it is exported under.
     *
     * <p>The two are read together rather than apart, because an export is a name and an index and
     * a component that named every interface right while pointing them all at one is one this
     * would otherwise call correct.
     */
    /** What the component says it has to be given, which is the interfaces it imports. */
    private static List<String> askedFor(byte[] component) {
        List<String> found = new ArrayList<>();
        for (byte[] payload : sections(component).getOrDefault(SEC_IMPORT, List.of())) {
            Cursor at = new Cursor(payload);
            int entries = at.leb();
            for (int i = 0; i < entries; i++) {
                found.add(at.declaredName());
                assertThat(at.byteAt()).describedAs("asked for as an interface").isEqualTo(0x05);
                at.leb();
            }
        }
        return found;
    }

    private static final int SEC_IMPORT = 10;

    private static Map<String, List<String>> offered(byte[] component) {
        List<List<String>> instances = new ArrayList<>();
        for (byte[] payload : sections(component).getOrDefault(SEC_INSTANCE, List.of())) {
            Cursor at = new Cursor(payload);
            int held = at.leb();
            for (int i = 0; i < held; i++) {
                // Every instance here is one built out of functions already lifted, beside the
                // one type they name, which an interface has to export for them to be exported.
                assertThat(at.byteAt()).isEqualTo(0x01);
                List<String> functions = new ArrayList<>();
                List<String> types = new ArrayList<>();
                int names = at.leb();
                for (int k = 0; k < names; k++) {
                    String name = at.declaredName();
                    int sort = at.byteAt();
                    at.leb();
                    (sort == SORT_TYPE ? types : functions).add(name);
                }
                assertThat(types).describedAs("the types " + functions + " are exported beside")
                        .containsExactly(Component.ENDED);
                instances.add(functions);
            }
        }
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (byte[] payload : sections(component).getOrDefault(SEC_EXPORT, List.of())) {
            Cursor at = new Cursor(payload);
            int entries = at.leb();
            for (int i = 0; i < entries; i++) {
                String name = at.declaredName();
                int sort = at.byteAt();
                int index = at.leb();
                at.byteAt();
                assertThat(sort).describedAs(name + " is exported as an interface").isEqualTo(0x05);
                assertThat(found.put(name, instances.get(index)))
                        .describedAs(name + " is exported once").isNull();
            }
        }
        return found;
    }

    /** The component's sections, by id, in the order they were written. */
    private static Map<Integer, List<byte[]>> sections(byte[] component) {
        Map<Integer, List<byte[]>> found = new LinkedHashMap<>();
        Cursor at = new Cursor(component);
        at.skip(8);
        while (at.more()) {
            int id = at.byteAt();
            int length = at.leb();
            found.computeIfAbsent(id, ignored -> new ArrayList<>()).add(at.take(length));
        }
        return found;
    }

    /** A walk over what was written, which is the only way to ask it what it says. */
    private static final class Cursor {

        private final byte[] bytes;
        private int at;

        Cursor(byte[] bytes) {
            this.bytes = bytes;
        }

        boolean more() {
            return at < bytes.length;
        }

        void skip(int by) {
            at += by;
        }

        int byteAt() {
            return bytes[at++] & 0xff;
        }

        int leb() {
            int held = 0;
            int shift = 0;
            while (true) {
                int piece = byteAt();
                held |= (piece & 0x7f) << shift;
                if ((piece & 0x80) == 0) {
                    return held;
                }
                shift += 7;
            }
        }

        byte[] take(int length) {
            byte[] held = new byte[length];
            System.arraycopy(bytes, at, held, 0, length);
            at += length;
            return held;
        }

        /** An export's name, which is written with a byte saying how it is spelt in front of it. */
        String declaredName() {
            byteAt();
            return new String(take(leb()), StandardCharsets.UTF_8);
        }
    }
}
