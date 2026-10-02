package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.unit8.notation199x.pattern.PatternImage;
import net.unit8.notation199x.pattern.PatternMachine;
import souther.compiler.core.Core;
import souther.wasm.emit.WasmWriter;
import souther.wasm.link.WasmFragment;

/**
 * A pattern the checker settled, placed once as the image of the machine that recognises it.
 *
 * <p>The checker hands over what the pattern means, not the text it was written as, so nothing
 * here reads a pattern. The machine is built by 199x-notation, the library the JVM backend builds
 * its machine with, and written out as an image in one of the formats that library defines. The
 * runtime reads the image back with the Rust implementation of the same library, so both backends
 * run the machine the checker's reading means, and neither has an engine of its own.
 *
 * <p>An image is ASCII and is laid out as its length and its bytes:
 *
 * <pre>
 * +0  u32 how many bytes
 * +4  the image
 * </pre>
 */
final class Patterns {

    private Patterns() {}

    /**
     * Places the image of the machine that recognises a settled pattern and answers where it is.
     *
     * @param fragment where the image goes, which is static memory
     * @param settled  what the checker settled the pattern as
     */
    static int place(WasmFragment fragment, Core.KernelFact.StringMatches settled) {
        return switch (PatternMachine.of(settled.meaning()).image()) {
            case PatternImage.Written written -> {
                byte[] image = String.join("", written.strings()).getBytes(StandardCharsets.US_ASCII);
                ByteArrayOutputStream table = new ByteArrayOutputStream();
                new WasmWriter(table).writeLittleEndian4(image.length);
                table.writeBytes(image);
                yield fragment.place(table.toByteArray());
            }
            case PatternImage.MoreCharacters more -> throw new NotLowered("the machine of the pattern "
                    + settled.written() + " is written in more than " + more.most()
                    + " characters, which is more than one pattern's image holds");
        };
    }
}
