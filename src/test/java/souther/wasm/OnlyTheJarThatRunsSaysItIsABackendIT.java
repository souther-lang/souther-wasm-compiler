package souther.wasm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;

/**
 * The jars the build wrote, read as the souther CLI reads them.
 *
 * <p>The CLI takes a jar holding {@code META-INF/souther/backend.properties} for a backend and runs
 * it with {@code java -jar}, without asking whether it has a main class. So the descriptor says the
 * jar runs, and a jar that holds it and does not run is chosen and then fails; two that hold it are
 * refused as one backend installed twice. Every jar in the build directory is read here, rather
 * than the one meant to be the backend, because the jar nobody meant to carry it is the one that
 * goes wrong.
 */
class OnlyTheJarThatRunsSaysItIsABackendIT {

    private static final String DESCRIPTOR = "META-INF/souther/backend.properties";

    private static final Path BUILT = Path.of(System.getProperty("backend.built"));

    @Test
    void theDescriptorIsInTheOneJarThatRuns() throws IOException {
        List<Path> jars = jars();
        assertThat(jars).as("the jars the build wrote").hasSizeGreaterThan(1);

        List<Path> described = jars.stream().filter(OnlyTheJarThatRunsSaysItIsABackendIT::described)
                .toList();
        List<Path> running = jars.stream().filter(jar -> mainClass(jar) != null).toList();

        assertThat(described).as("jars holding the descriptor").hasSize(1);
        assertThat(described).as("jars holding the descriptor, against those that run")
                .isEqualTo(running);
        assertThat(described.get(0).getFileName().toString()).endsWith("-cli.jar");
    }

    @Test
    void theDescriptorNamesTheTargetAndTheSoutherAsWritten() throws IOException {
        Path backend = backend();

        // Compared as text, as the CLI compares it: a value with whitespace around it, or a key
        // more, is refused there and so is refused here.
        assertThat(entry(backend, DESCRIPTOR)).isEqualTo(
                "name=wasm\nsouther.version=" + System.getProperty("backend.souther") + "\n");
    }

    @Test
    void theManifestSaysTheBackendsOwnRelease() throws IOException {
        try (JarFile jar = new JarFile(backend().toFile())) {
            Manifest manifest = jar.getManifest();
            assertThat(manifest.getMainAttributes().getValue("Implementation-Version"))
                    .isEqualTo(System.getProperty("backend.release"));
        }
    }

    @Test
    void theJarRunsAsTheCliRunsIt() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", backend().toString(), "--help")
                .redirectErrorStream(true).start();
        String said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(said).isZero();
        assertThat(said).contains("souther compile --target wasm");
    }

    private static Path backend() throws IOException {
        return jars().stream().filter(OnlyTheJarThatRunsSaysItIsABackendIT::described)
                .findFirst().orElseThrow();
    }

    private static List<Path> jars() throws IOException {
        try (var listing = Files.list(BUILT)) {
            return listing.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static boolean described(Path jar) {
        try (JarFile archive = new JarFile(jar.toFile())) {
            return archive.getEntry(DESCRIPTOR) != null;
        } catch (IOException e) {
            throw new IllegalStateException(jar.toString(), e);
        }
    }

    private static String mainClass(Path jar) {
        try (JarFile archive = new JarFile(jar.toFile())) {
            Manifest manifest = archive.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            throw new IllegalStateException(jar.toString(), e);
        }
    }

    private static String entry(Path jar, String name) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            ZipEntry entry = archive.getEntry(name);
            try (InputStream in = archive.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
