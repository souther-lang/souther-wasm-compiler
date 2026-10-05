package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import souther.wasm.Running;
import souther.wasm.emit.WasmWriter;

/**
 * What the runtime's linker said of it is used to leave its data out, so it is used only where all
 * of it was read; where any of it is something {@link RuntimeData} does not read, nothing is
 * claimed and every segment is kept.
 *
 * <p>Each runtime here is the one this build carries with one thing its linker said changed into
 * something no linker this reads says: another version of the {@code linking} section, a
 * relocation of a kind Linking.md does not define, a relocation section missing. Read as if it
 * were understood, each would have data left out that something still reads.
 */
class WhatTheRuntimesLinkerSaidIsUsedOnlyWhereAllOfItIsReadTest {

    private static final byte[] RUNTIME = Running.runtimeModule();

    @Test
    void claimsWhatTheRuntimeThisBuildCarriesSays() {
        assertThat(RuntimeData.owners(RUNTIME)).isNotEmpty();
    }

    @Test
    void claimsNothingOfALinkingSectionOfAnotherVersion() {
        byte[] runtime = withCustom("linking", content -> {
            byte[] changed = content.clone();
            changed[0] = 3;
            return changed;
        });

        assertThat(RuntimeData.owners(runtime)).isEmpty();
        assertThat(RuntimeData.functionNames(runtime)).isEmpty();
    }

    @Test
    void claimsNothingOfARelocationOfAKindItDoesNotKnow() {
        byte[] runtime = withCustom("reloc.CODE", content -> {
            byte[] changed = content.clone();
            int[] at = {0};
            unsigned(changed, at); // the section they apply to
            unsigned(changed, at); // how many
            changed[at[0]] = 99;
            return changed;
        });

        assertThat(RuntimeData.owners(runtime)).isEmpty();
    }

    @Test
    void claimsNothingWhereWhatTheDataNamesIsNotSaid() {
        assertThat(RuntimeData.owners(withCustom("reloc.DATA", null))).isEmpty();
    }

    /**
     * The runtime with what the custom section {@code name} holds after its name changed, or the
     * section left out where there is no change.
     */
    private static byte[] withCustom(String name, @Nullable UnaryOperator<byte[]> change) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(RUNTIME, 0, 8);
        int[] at = {8};
        boolean found = false;
        while (at[0] < RUNTIME.length) {
            int id = RUNTIME[at[0]++] & 0xff;
            int length = unsigned(RUNTIME, at);
            int start = at[0];
            byte[] payload = java.util.Arrays.copyOfRange(RUNTIME, start, start + length);
            at[0] = start + length;
            if (id == 0) {
                int[] p = {0};
                int named = unsigned(payload, p);
                String held = new String(payload, p[0], named, StandardCharsets.UTF_8);
                if (held.equals(name)) {
                    found = true;
                    if (change == null) {
                        continue;
                    }
                    byte[] changed = change.apply(
                            java.util.Arrays.copyOfRange(payload, p[0] + named, payload.length));
                    ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
                    new WasmWriter(rewritten).writeUnsignedLeb128(named)
                            .write(name.getBytes(StandardCharsets.UTF_8)).write(changed);
                    payload = rewritten.toByteArray();
                }
            }
            new WasmWriter(out).write((byte) id).writeUnsignedLeb128(payload.length).write(payload);
        }
        assertThat(found).describedAs("the runtime carries %s", name).isTrue();
        return out.toByteArray();
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
