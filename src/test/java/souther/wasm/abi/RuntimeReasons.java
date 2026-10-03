package souther.wasm.abi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The reasons the runtime declares a call can end for, by name, read off its source. */
final class RuntimeReasons {

    private static final Pattern DECLARED =
            Pattern.compile("pub const REASON_([A-Z_]+): u32 = (\\d+);");

    private RuntimeReasons() {
    }

    /** Each reason's name and number, in the order the runtime declares them. */
    static Map<String, Integer> declared() {
        try {
            String source = Files.readString(
                    Path.of("runtime", "src", "lib.rs"), StandardCharsets.UTF_8);
            Map<String, Integer> held = new LinkedHashMap<>();
            Matcher found = DECLARED.matcher(source);
            while (found.find()) {
                held.put(found.group(1), Integer.parseInt(found.group(2)));
            }
            return held;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
