package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import souther.wasm.abi.RuntimeAbi;

/**
 * The glue reads the versions this build writes.
 *
 * <p>A version moves on the side that writes, and is held there to what it says. A reader that
 * did not move with it would refuse every module this build writes, or, refusing nothing, misread
 * them; so what the glue says it reads is held here to what this build writes, and moving one
 * without the other fails.
 */
class TheGlueReadsWhatThisBuildWritesTest {

    private static final Path GLUE = Path.of("packages/wasm/src/index.ts");

    @Test
    void readsTheSurfaceVersionAndTheAbiThisBuildWrites() throws IOException {
        Matcher reads = Pattern.compile("const READS = \\{ surface: (\\d+), abi: (\\d+) \\} as const;")
                .matcher(Files.readString(GLUE, StandardCharsets.UTF_8));

        assertThat(reads.find()).describedAs("the glue says which versions it reads").isTrue();
        assertThat(Integer.parseInt(reads.group(1))).isEqualTo(Surface.VERSION);
        assertThat(Integer.parseInt(reads.group(2))).isEqualTo(RuntimeAbi.VERSION);
    }
}
