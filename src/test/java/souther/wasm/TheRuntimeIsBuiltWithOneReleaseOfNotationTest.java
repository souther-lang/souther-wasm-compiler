package souther.wasm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The runtime is built with one release of notation-199x's crate.
 *
 * <p>A pattern's image is written on the JVM by the library's Java implementation, which this
 * build takes from souther-compiler, and read in the runtime by its Rust implementation, which
 * {@code runtime/Cargo.toml} names. Each implementation of the library is released on its own and
 * numbered on its own, so the two numbers are not held to each other: a Java release that adds to
 * its API has no crate of its number. That the runtime reads what the Java half writes is held by
 * running it ({@code APatternIsRecognisedWhereTheJvmRecognisesItTest}). What is held here is that
 * the runtime answers from one account of the rules, and one anybody can get: a single crate in
 * {@code runtime/Cargo.lock}, a release, from crates.io.
 */
class TheRuntimeIsBuiltWithOneReleaseOfNotationTest {

    private static final String CRATE = "notation199x";

    private static final String CRATES_IO = "registry+https://github.com/rust-lang/crates.io-index";

    @Test
    void theCrateIsOneReleaseFromCratesIo() throws IOException {
        List<Package> crates = packages(Path.of("runtime/Cargo.lock")).stream()
                .filter(each -> each.name().equals(CRATE)).toList();

        assertThat(crates).as("the " + CRATE + " the runtime is built with").hasSize(1);
        assertThat(crates.get(0).source()).as("where the crate comes from").isEqualTo(CRATES_IO);
        assertThat(crates.get(0).version()).as("a release, which a development version is not")
                .matches("[0-9]+\\.[0-9]+\\.[0-9]+");
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
