package souther.wasm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line, which is how this is reached from outside a build.
 *
 * <p>What it has to get right is not only the compile. A build that stopped must leave nothing
 * where the module would have been, and must say which kind of stop it was: a program the language
 * refuses is not the same as a program this backend does not write yet, and neither is a command
 * line that named no compile at all.
 */
class ACommandLineWritesAModuleOrSaysWhyNotTest {

    private static final String COUNTING = """
            module counting

            behavior doubled : (n: Int) -> Int

            let doubled (n) = n + n
            """;

    @Test
    void writesAModuleForTheSourcesItWasNamed(@TempDir Path room) throws IOException {
        Path source = Files.writeString(room.resolve("counting.sou"), COUNTING);
        Path into = room.resolve("out.wasm");

        Ran ran = run(source.toString(), "-o", into.toString());

        assertThat(ran.status()).isZero();
        assertThat(ran.said()).contains("wrote");
        assertThat(Files.readAllBytes(into)).startsWith(
                (byte) 0x00, (byte) 'a', (byte) 's', (byte) 'm');
    }

    @Test
    void readsADirectoryForWhatIsUnderIt(@TempDir Path room) throws IOException {
        Files.createDirectories(room.resolve("deep"));
        Files.writeString(room.resolve("deep/counting.sou"), COUNTING);
        Files.writeString(room.resolve("deep/greeting.sou"), """
                module greeting

                behavior hello : (name: String) -> String

                let hello (name) = String.append("hello ", name)
                """);
        Files.writeString(room.resolve("deep/notes.txt"), "not a source");
        Path into = room.resolve("out.wasm");

        Ran ran = run(room.toString(), "-o", into.toString());

        assertThat(ran.status()).isZero();
        assertThat(ran.said()).contains("2 sources");
    }

    @Test
    void writesAComponentWhereOneIsAskedFor(@TempDir Path room) throws IOException {
        Path source = Files.writeString(room.resolve("counting.sou"), COUNTING);
        Path core = room.resolve("core.wasm");
        Path component = room.resolve("component.wasm");

        run(source.toString(), "-o", core.toString());
        run(source.toString(), "-o", component.toString(), "--component");

        // A component says which layer it is where a core module says nothing.
        assertThat(Files.readAllBytes(core)[6]).isEqualTo((byte) 0x00);
        assertThat(Files.readAllBytes(component)[6]).isEqualTo((byte) 0x01);
    }

    @Test
    void leavesNothingWhereTheModuleWouldHaveGoneWhenTheLanguageRefuses(@TempDir Path room)
            throws IOException {
        Path source = Files.writeString(room.resolve("bad.sou"), """
                module bad

                behavior x : (n: Int) -> Int

                let x (n) = n +
                """);
        Path into = room.resolve("out.wasm");

        Ran ran = run(source.toString(), "-o", into.toString());

        assertThat(ran.status()).isEqualTo(1);
        assertThat(ran.complained()).contains("E2302");
        assertThat(into).doesNotExist();
    }

    @Test
    void saysWhichKindOfStopItWasWhenThisBackendIsTheOneRefusing(@TempDir Path room)
            throws IOException {
        Path source = Files.writeString(room.resolve("wrapped.sou"), """
                module wrapped

                data ProductId = String

                behavior kept : (x: ProductId) -> ProductId

                let kept (x) = x
                """);
        Path into = room.resolve("out.wasm");

        Ran ran = run(source.toString(), "-o", into.toString());

        assertThat(ran.status()).isEqualTo(1);
        assertThat(ran.complained()).contains("this backend does not write that yet");
        assertThat(into).doesNotExist();
    }

    @Test
    void saysWhatToTypeWhenTheCommandLineNamesNoCompile(@TempDir Path room) throws IOException {
        Path source = Files.writeString(room.resolve("counting.sou"), COUNTING);

        // Each of these is a different mistake, and none of them is a program being refused, so
        // none of them answers with what a refused program answers with.
        for (String[] arguments : new String[][] {
            {source.toString()},
            {"-o", room.resolve("out.wasm").toString()},
            {source.toString(), "-o"},
            {source.toString(), "-o", room.resolve("out.wasm").toString(), "--fast"},
            {room.resolve("nothing-here.sou").toString(), "-o",
                    room.resolve("out.wasm").toString()},
        }) {
            Ran ran = run(arguments);
            assertThat(ran.status()).describedAs(String.join(" ", arguments)).isEqualTo(2);
        }
    }

    @Test
    void saysSoRatherThanWritingAnEmptyModuleWhenNothingNamedIsASource(@TempDir Path room)
            throws IOException {
        Files.writeString(room.resolve("notes.txt"), "not a source");
        Path into = room.resolve("out.wasm");

        Ran ran = run(room.toString(), "-o", into.toString());

        assertThat(ran.status()).isEqualTo(2);
        assertThat(ran.complained()).contains("nothing to compile");
        assertThat(into).doesNotExist();
    }

    /** What one run said, complained and answered with. */
    private record Ran(int status, String said, String complained) {
    }

    private static Ran run(String... arguments) {
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        ByteArrayOutputStream complained = new ByteArrayOutputStream();
        int status = Main.run(arguments,
                new PrintStream(said, true, StandardCharsets.UTF_8),
                new PrintStream(complained, true, StandardCharsets.UTF_8));
        return new Ran(status, said.toString(StandardCharsets.UTF_8),
                complained.toString(StandardCharsets.UTF_8));
    }
}
