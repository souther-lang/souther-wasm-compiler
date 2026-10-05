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
 * A link carries the runtime's custom sections into the module, all but what the runtime's linker
 * said of it.
 *
 * <p>The linker's sections ({@code linking}, {@code reloc.*}) are read once, for which of the
 * runtime's data each function reads, and speak of the runtime alone, at offsets a link moves. Any
 * other custom section is the runtime's to say — what built it, what it needs of an engine — and
 * a link has no reason to leave it out.
 */
class ALinkCarriesTheRuntimesCustomSectionsButItsLinkersTest {

    @Test
    void carriesWhatTheRuntimeSaysAndLeavesOutWhatItsLinkerSaid() {
        byte[] runtime = withCustom(Running.runtimeModule(), "souther:note", "kept");

        List<String> carried = customSections(Linker.link(new WasmFragment(LinkPlan.reading(runtime))));

        assertThat(carried).contains("souther:note");
        assertThat(carried).noneMatch(name -> name.equals("linking") || name.startsWith("reloc."));
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
