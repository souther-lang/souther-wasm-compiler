package souther.wasm.link;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.emit.ComponentWriter;
import souther.wasm.emit.WasmWriter;

/**
 * A linked core module wrapped as a component, exporting one interface per Souther module.
 *
 * <p>A behavior crosses as {@code func(arguments: string) -> string} — the same envelope the core
 * export answers with, and the same one the other backend's boundary writes. What the component
 * adds is who owns the memory it crosses in: the host lowers the argument through
 * {@code cabi_realloc} and reads the answer out of an area in this memory, and the post-return
 * says when the whole of it goes back. That is the bracket the core module left to a caller to
 * keep, moved to where the format states it.
 *
 * <p>A name crosses as the interface names it. A Souther behavior is written in one convention and
 * a component export in another, so the two are not the same string — and where two behaviors of a
 * module would come to one name, this refuses rather than exporting one of them twice.
 */
public final class Component {

    /** The namespace and package a Souther program's interfaces are named under. */
    private static final String UNDER = "souther:program/";

    /** What a behavior crosses as, taking the call's arguments and answering the envelope. */
    private static final String TAKES = "arguments";

    private Component() {
    }

    /**
     * Wraps a linked core module as a component.
     *
     * @param core the linked core module
     * @param behaviors every behavior's core export name, by the module that declares it, in the
     *     order the program declares them
     * @return the component
     */
    public static byte[] around(byte[] core, Map<String, Map<String, String>> behaviors) {
        Aliases aliases = new Aliases(PROGRAM);
        List<byte[]> lifts = new ArrayList<>();
        List<byte[]> exports = new ArrayList<>();
        List<byte[]> instances = new ArrayList<>();

        int memory = aliases.coreMemory(RuntimeAbi.MEMORY);
        int realloc = aliases.coreFunc(RuntimeAbi.CANONICAL_REALLOC);
        int afterwards = aliases.coreFunc(Lifted.POST_RETURN);

        int lifted = 0;
        int made = 0;
        for (Map.Entry<String, Map<String, String>> module : behaviors.entrySet()) {
            List<Map.Entry<String, Integer>> inside = new ArrayList<>();
            Map<String, String> named = namesIn(module.getKey(), module.getValue());
            for (Map.Entry<String, String> behavior : module.getValue().entrySet()) {
                String crossing = named.get(behavior.getKey());
                int core32 = aliases.coreFunc(Lifted.wrapping(behavior.getValue()));
                lifts.add(ComponentWriter.canonLiftMemoryReallocUtf8PostReturn(
                        core32, 0, memory, realloc, afterwards));
                inside.add(Map.entry(crossing, lifted++));
            }
            instances.add(ComponentWriter.componentInstanceFromFuncs(inside));
            exports.add(ComponentWriter.exportInstance(UNDER + module.getKey(), made++));
        }

        ComponentWriter out = new ComponentWriter();
        out.rawSection(ComponentWriter.SEC_CORE_MODULE, core);
        out.rawSection(ComponentWriter.SEC_CORE_MODULE, unreachedHost());
        out.rawSection(ComponentWriter.SEC_CORE_INSTANCE, ComponentWriter.vec(List.of(
                ComponentWriter.coreInstanceInstantiate(1, List.of(), List.of()),
                ComponentWriter.coreInstanceInstantiate(
                        0, List.of(RuntimeAbi.IMPORT_MODULE), List.of(0)))));
        out.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases.written()));
        out.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(
                ComponentWriter.funcTypeScalars(List.of(TAKES),
                        List.of(ComponentWriter.VT_STRING), ComponentWriter.VT_STRING))));
        out.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lifts));
        out.rawSection(ComponentWriter.SEC_INSTANCE, ComponentWriter.vec(instances));
        out.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(exports));
        return out.toByteArray();
    }

    /**
     * A module standing where the crossing out of the program would be.
     *
     * <p>The runtime declares that crossing whether or not a program has anything to send across
     * it, so a component has to supply something. What a behavior sends across it is a call in
     * this module's own memory, which is not a thing the component model can carry — so a program
     * with an injected behavior is refused before it gets here, and what stands here is reached by
     * nothing.
     */
    private static byte[] unreachedHost() {
        return ComponentWriter.enc(w -> {
            w.write(new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00});
            section(w, 1, ComponentWriter.enc(t -> t.writeUnsignedLeb128(1)
                    .write(0x60)
                    .writeUnsignedLeb128(ACROSS)
                    .write(new byte[] {0x7f, 0x7f, 0x7f, 0x7f, 0x7f})
                    .writeUnsignedLeb128(1)
                    .write(0x7f)));
            section(w, 3, ComponentWriter.enc(f -> f.writeUnsignedLeb128(1).writeUnsignedLeb128(0)));
            section(w, 7, ComponentWriter.enc(e -> {
                e.writeUnsignedLeb128(1);
                ComponentWriter.plainName(e, RuntimeAbi.IMPORT_HOST_CALL);
                e.write(0x00).writeUnsignedLeb128(0);
            }));
            section(w, 10, ComponentWriter.enc(c -> c.writeUnsignedLeb128(1)
                    .writeUnsignedLeb128(3)
                    .writeUnsignedLeb128(0)
                    .write(0x00)
                    .write(0x0b)));
        });
    }

    /** How many values the crossing out of the program takes. */
    private static final int ACROSS = 5;

    /** Which core instance the program is: the one after what stands in for the crossing. */
    private static final int PROGRAM = 1;

    private static void section(WasmWriter out, int id, byte[] payload) {
        out.write(id).writeUnsignedLeb128(payload.length).write(payload);
    }

    /**
     * What an interface calls each behavior of a module, or why it can call none of them that.
     *
     * <p>Asked here rather than where a component is built, because what a program offers is the
     * same whether it is written as a component or only written down — and a name an interface
     * cannot carry is not something to find out at the second of those.
     *
     * @param module the module the behaviors are declared in, for saying which one
     * @param behaviors the behaviors of it, by the name Souther wrote
     * @return each behavior's name, by the name Souther wrote
     */
    public static Map<String, String> namesIn(String module, Map<String, String> behaviors) {
        Map<String, String> named = new LinkedHashMap<>();
        Map<String, String> already = new LinkedHashMap<>();
        for (String behavior : behaviors.keySet()) {
            String crossing = interfaceName(behavior);
            if (!WRITABLE.matcher(crossing).matches()) {
                throw new IllegalArgumentException(behavior + " comes to " + crossing
                        + ", which is not a name an interface writes");
            }
            String taken = already.put(crossing, behavior);
            if (taken != null) {
                throw new IllegalArgumentException(module + " declares " + taken + " and "
                        + behavior + ", which an interface would name " + crossing + " both times");
            }
            named.put(behavior, crossing);
        }
        return named;
    }

    /**
     * What an interface calls a behavior.
     *
     * <p>Souther writes a name as words run together with each but the first capitalised, or with
     * an underscore between them; an interface writes one as words with a dash between them. A
     * digit begins no word, so it stays with the one it was written in.
     *
     * <p>Two names can come to one here — Souther admits {@code Doubled} beside {@code doubled},
     * and {@code with_vat} beside {@code withVat}, and an interface has one name for each pair. So
     * this says what a name comes to and the caller says what to do where two agree.
     */
    public static String interfaceName(String behavior) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < behavior.length(); i++) {
            char held = behavior.charAt(i);
            if (held == '_') {
                out.append('-');
            } else if (Character.isUpperCase(held)) {
                if (i > 0) {
                    out.append('-');
                }
                out.append(Character.toLowerCase(held));
            } else {
                out.append(held);
            }
        }
        return out.toString();
    }

    /** The shape a name has to have for an interface to be able to write it down. */
    private static final Pattern WRITABLE = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    /** The names the generated core functions a component lifts are exported under. */
    public static final class Lifted {

        /** What every lifted function's post-return is, because all any of them owes is the arena. */
        public static final String POST_RETURN = "souther#post-return";

        private Lifted() {
        }

        /** The name the function that lifts a behavior's core export is exported under. */
        public static String wrapping(String export) {
            return export + "#lifted";
        }
    }

    /** The aliases a component takes out of the core instance, in the order it takes them. */
    private static final class Aliases {

        private final List<byte[]> written = new ArrayList<>();
        private final int instance;
        private int taken;

        Aliases(int instance) {
            this.instance = instance;
        }

        int coreMemory(String name) {
            written.add(ComponentWriter.aliasCoreMemory(instance, name));
            // A memory and a function are counted apart, so the first alias of each is nothing.
            return 0;
        }

        int coreFunc(String name) {
            written.add(ComponentWriter.aliasCoreFunc(instance, name));
            return taken++;
        }

        List<byte[]> written() {
            return written;
        }
    }
}
