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
import souther.wasm.lower.NotLowered;
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
        byte[] component = WasmCompiler.compileAsComponent(CheckedProgram.of(List.of("""
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
        byte[] component = WasmCompiler.compileAsComponent(oneBehavior());

        assertThat(component[0]).isEqualTo((byte) 0x00);
        assertThat(new String(component, 1, 3, StandardCharsets.UTF_8)).isEqualTo("asm");
        // Version and layer: a component says layer one where a core module says nothing.
        assertThat(component[6]).isEqualTo((byte) 0x01);
        assertThat(sections(component).keySet())
                .contains(SEC_CORE_MODULE, SEC_CORE_INSTANCE, SEC_ALIAS,
                        SEC_TYPE, SEC_CANON, SEC_INSTANCE, SEC_EXPORT);
    }

    @Test
    void refusesABehaviorSuppliedFromOutside() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module counting

                behavior doubled : (n: Int) -> Int
                """));

        assertThatThrownBy(() -> WasmCompiler.compileAsComponent(program))
                .isInstanceOf(NotLowered.class)
                .hasMessageContaining("supplied from outside");
    }

    @Test
    void stillWritesTheSameBehaviorAsACoreModule() {
        CheckedProgram program = CheckedProgram.of(List.of("""
                module counting

                behavior doubled : (n: Int) -> Int
                """));

        // A core module reaches out for one, so the refusal is the component's and not the
        // backend's: what a component cannot do is carry the crossing, not write the behavior.
        assertThat(WasmCompiler.compile(program)).isNotEmpty();
    }

    @Test
    void liftsTheWrapperRatherThanTheCrossingItself() {
        byte[] component = WasmCompiler.compileAsComponent(oneBehavior());

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
    void refusesTwoBehaviorsOneInterfaceWouldCallByOneName() {
        byte[] core = WasmCompiler.compile(oneBehavior());

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
                        WasmCompiler.compile(oneBehavior()), Map.of("demo", named)))
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
        return CheckedProgram.of(List.of("""
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

    /**
     * What each exported interface offers, by the name it is exported under.
     *
     * <p>The two are read together rather than apart, because an export is a name and an index and
     * a component that named every interface right while pointing them all at one is one this
     * would otherwise call correct.
     */
    private static Map<String, List<String>> offered(byte[] component) {
        List<List<String>> instances = new ArrayList<>();
        for (byte[] payload : sections(component).getOrDefault(SEC_INSTANCE, List.of())) {
            Cursor at = new Cursor(payload);
            int held = at.leb();
            for (int i = 0; i < held; i++) {
                // Every instance here is one built out of functions already lifted.
                assertThat(at.byteAt()).isEqualTo(0x01);
                List<String> functions = new ArrayList<>();
                int names = at.leb();
                for (int k = 0; k < names; k++) {
                    functions.add(at.declaredName());
                    at.byteAt();
                    at.leb();
                }
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
