package souther.wasm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The runtime reads a pattern's image with the version of notation-199x that wrote it.
 *
 * <p>The image is written on the JVM by the library's Java implementation, which this build takes
 * from souther-compiler rather than naming a version of its own, and read in the runtime by its
 * Rust implementation, which {@code runtime/Cargo.toml} names. Two files in two languages say one
 * thing, so this reads what each resolved — the Java artifact on the classpath and the crate in
 * {@code runtime/Cargo.lock} — and holds them to the same release of the same library. A Souther
 * that moves the library, or renames its artifact, fails here until the runtime moves with it.
 */
class TheRuntimeReadsWithTheNotationTheCompilerWritesWithTest {

    private static final String JAVA = "/META-INF/maven/net.unit8.199x/notation-199x/pom.properties";

    private static final String CRATE = "notation199x";

    private static final String CRATES_IO = "registry+https://github.com/rust-lang/crates.io-index";

    @Test
    void theCrateIsTheReleaseTheJavaArtifactIs() throws IOException {
        Properties java = new Properties();
        try (InputStream in = getClass().getResourceAsStream(JAVA)) {
            assertThat(in).as("notation-199x on the classpath, which souther-compiler takes").isNotNull();
            java.load(in);
        }

        List<Package> crates = packages(Path.of("runtime/Cargo.lock")).stream()
                .filter(each -> each.name().equals(CRATE)).toList();

        assertThat(crates).as("the " + CRATE + " the runtime is built with").hasSize(1);
        assertThat(crates.get(0).source()).as("where the crate comes from").isEqualTo(CRATES_IO);
        assertThat(crates.get(0).version()).isEqualTo(java.getProperty("version"));
    }

    private record Package(String name, String version, String source) {}

    /**
     * The packages a lockfile names, read by their keys rather than by where a line falls. What
     * comes before the first package, the lockfile's own format version among it, is no package's.
     */
    private static List<Package> packages(Path lockfile) throws IOException {
        List<Package> found = new ArrayList<>();
        boolean inPackage = false;
        String name = null;
        String version = null;
        String source = null;
        for (String line : Files.readAllLines(lockfile)) {
            if (line.startsWith("[")) {
                if (name != null) {
                    found.add(new Package(name, version, source));
                }
                inPackage = line.equals("[[package]]");
                name = null;
                version = null;
                source = null;
            } else if (!inPackage) {
                continue;
            } else if (line.startsWith("name = ")) {
                name = quoted(line);
            } else if (line.startsWith("version = ")) {
                version = quoted(line);
            } else if (line.startsWith("source = ")) {
                source = quoted(line);
            }
        }
        if (name != null) {
            found.add(new Package(name, version, source));
        }
        return found;
    }

    private static String quoted(String line) {
        return line.substring(line.indexOf('"') + 1, line.lastIndexOf('"'));
    }
}
