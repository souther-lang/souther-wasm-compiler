package souther.wasm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every test compiles through {@link Compiled}, and those that cannot say why.
 *
 * <p>A test that checks and compiles a program for itself does it again for every case that asks
 * for the same program, and that is how the suite came to spend most of its time doing so twice
 * over — twice, because a cache was put in and the next tests written went round it. What a test
 * may do is held here rather than remembered: a call to the compiler outside {@link Compiled} fails
 * this unless the file is one of those below, each with what keeps it from asking {@link Compiled};
 * and a file listed that no longer calls the compiler fails it too, so the list says only what is
 * so.
 */
class EveryTestCompilesThroughCompiledTest {

    private static final Path TESTS = Path.of("src/test/java");

    private static final Pattern COMPILING = Pattern.compile(
            "CheckedProgram\\.of\\(|WasmCompiler\\.compile(AsComponent)?\\(|Compiler\\.compile(Modules)?\\(");

    /** What compiles for itself, and why it cannot ask {@link Compiled}. */
    private static final Map<String, String> ON_THEIR_OWN = Map.of(
            "souther/wasm/Compiled.java",
            "it is the one way, and asks the compiler for everyone else",
            "souther/wasm/lower/WhatACallCostsGrowsWithWhatItWasGivenTest.java",
            "what a call costs is measured on a module that nothing else has been run against",
            "souther/wasm/lower/TheSurfaceChangesOnlyWithItsVersionTest.java",
            "its program reads a module off a path, which is an input Compiled does not key on",
            "souther/wasm/link/ALinkRefusesARuntimeOfAnotherAbiTest.java",
            "it links against a runtime of its own, which is an input Compiled does not key on");

    @Test
    void noTestButThoseListedCallsTheCompilerItself() {
        TreeSet<String> compiling = new TreeSet<>();
        try (Stream<Path> files = Files.walk(TESTS)) {
            files.filter(each -> each.toString().endsWith(".java")).forEach(file -> {
                if (COMPILING.matcher(code(file)).find()) {
                    compiling.add(TESTS.relativize(file).toString().replace('\\', '/'));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(compiling).describedAs("what calls the compiler itself")
                .isEqualTo(new TreeSet<>(ON_THEIR_OWN.keySet()));
    }

    /** A file's code, without its comments, which may name a call without making it. */
    private static String code(Path file) {
        try {
            return Files.readString(file)
                    .replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("//[^\\n]*", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
