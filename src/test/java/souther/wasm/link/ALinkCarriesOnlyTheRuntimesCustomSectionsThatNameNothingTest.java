package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;
import souther.wasm.emit.WasmWriter;

/**
 * A link carries into the module only those of the runtime's custom sections it knows name no
 * function, type, segment or address.
 *
 * <p>The link leaves out and renumbers functions and leaves out data, so a custom section naming
 * any would say of the module something no longer so. One the link does not know may name any, and
 * one the runtime's linker wrote relocations for names what they relocate; what the linker said of
 * the runtime ({@code linking}, {@code reloc.*}) speaks of the runtime alone.
 */
class ALinkCarriesOnlyTheRuntimesCustomSectionsThatNameNothingTest {

    @Test
    void carriesWhatBuiltTheRuntime() {
        byte[] runtime = withCustom(Running.runtimeModule(), "producers", "\0");

        assertThat(carried(runtime)).contains("producers");
    }

    @Test
    void leavesOutWhatItDoesNotKnowAndWhatTheRuntimesLinkerSaid() {
        byte[] runtime = withCustom(Running.runtimeModule(), "souther:note", "kept");

        assertThat(carried(runtime)).doesNotContain("souther:note")
                .noneMatch(name -> name.equals("linking") || name.startsWith("reloc."));
    }

    @Test
    void leavesOutWhatTheRuntimesLinkerRelocated() {
        byte[] runtime = withCustom(withCustom(Running.runtimeModule(), "producers", "\0"),
                "reloc.producers", "\0\0");

        assertThat(carried(runtime)).doesNotContain("producers");
    }

    private static List<String> carried(byte[] runtime) {
        return customSections(Linker.link(new WasmFragment(LinkPlan.reading(runtime))));
    }

    private static byte[] withCustom(byte[] module, String name, String content) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        byte[] named = name.getBytes(StandardCharsets.UTF_8);
        new WasmWriter(payload).writeUnsignedLeb128(named.length).write(named)
                .write(content.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(module);
        new WasmWriter(out).write((byte) 0).writeUnsignedLeb128(payload.size()).write(payload.toByteArray());
        return out.toByteArray();
    }

    private static List<String> customSections(byte[] module) {
        List<String> names = new ArrayList<>();
        int[] at = {8};
        while (at[0] < module.length) {
            int id = module[at[0]++] & 0xff;
            int length = unsigned(module, at);
            int end = at[0] + length;
            if (id == 0) {
                int named = unsigned(module, at);
                names.add(new String(module, at[0], named, StandardCharsets.UTF_8));
            }
            at[0] = end;
        }
        return names;
    }

    private static int unsigned(byte[] bytes, int[] at) {
        int result = 0;
        int shift = 0;
        int b;
        do {
            b = bytes[at[0]++] & 0xff;
            result |= (b & 0x7f) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }
}
