package souther.wasm;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import souther.compiler.CompilationSources;
import souther.compiler.CompilationSources.SourceFile;
import souther.compiler.diag.CompileException;
import souther.compiler.meta.ModulePath;
import souther.compiler.program.CheckedProgram;
import souther.wasm.link.WitText;
import souther.wasm.lower.NotLowered;
import souther.wasm.lower.WasmCompiler;

/**
 * Compiles Souther sources named on a command line.
 *
 * <p>The command line is what {@code souther compile --target wasm} hands over after the target's
 * name, verbatim: the CLI finds this jar by its {@code META-INF/souther/backend.properties} and runs
 * it as a process of its own. Run with {@code java -jar}, it reads the same arguments.
 *
 * <p>What it writes is a core module, or a component where one is asked for. Everything it can say
 * about why it did not goes to the error stream and nothing goes to the output file, so a build
 * that stopped leaves no half-written module for the next step to pick up.
 */
public final class Main {

    private static final String USAGE = """
            souther compile --target wasm — compiles a Souther program to WebAssembly

              souther compile --target wasm <source or directory>... -o <file> [--component]
                  [--wit <file>]

              -o <file>     where to write. Required.
              --component   write a component, one interface per Souther module, rather than a
                            core module. A behavior crosses as func(arguments: string) -> string.
              --wit <file>  also write what the program offers, as a reader of interfaces reads
                            it. The same whether a component is written or not.

            A directory is read for the .sou files under it, in the order their paths sort; a
            link under it is refused rather than followed. A lone file with no module header is
            named after the file.
            """;

    private Main() {
    }

    /**
     * @param arguments the command line
     */
    public static void main(String[] arguments) {
        System.exit(run(arguments, System.out, System.err));
    }

    /**
     * Runs one command line and answers what the process should exit with.
     *
     * <p>Taken apart from {@link #main} so that a test can run it: what a compile says about a
     * program it will not write is most of what this is for, and a test that could not read that
     * would be checking only the half that works.
     *
     * @param arguments the command line
     * @param out where to say what was written
     * @param problems where to say why nothing was
     * @return nothing wrong, a program refused, or a command line that named no compile
     */
    public static int run(String[] arguments, PrintStream out, PrintStream problems) {
        List<Path> sources = new ArrayList<>();
        Path into = null;
        Path offering = null;
        boolean component = false;
        for (int i = 0; i < arguments.length; i++) {
            String held = arguments[i];
            switch (held) {
                case "-o" -> {
                    if (i + 1 == arguments.length) {
                        problems.println("-o names no file");
                        return WRONG_COMMAND;
                    }
                    into = Path.of(arguments[++i]);
                }
                case "--component" -> component = true;
                case "--wit" -> {
                    if (i + 1 == arguments.length) {
                        problems.println("--wit names no file");
                        return WRONG_COMMAND;
                    }
                    offering = Path.of(arguments[++i]);
                }
                case "-h", "--help" -> {
                    out.print(USAGE);
                    return NOTHING_WRONG;
                }
                default -> {
                    if (held.startsWith("-")) {
                        problems.println("no such option: " + held);
                        problems.print(USAGE);
                        return WRONG_COMMAND;
                    }
                    sources.add(Path.of(held));
                }
            }
        }
        // Each part left out is said on its own, so a reader is not left to compare the line with
        // the usage to find which it was.
        if (sources.isEmpty()) {
            problems.println("nothing named to compile");
        }
        if (into == null) {
            problems.println("-o is required: it names where to write the module");
        }
        if (sources.isEmpty() || into == null) {
            problems.print(USAGE);
            return WRONG_COMMAND;
        }

        CompilationSources read;
        try {
            read = read(sources);
        } catch (IOException | UncheckedIOException e) {
            problems.println(e.getMessage());
            return WRONG_COMMAND;
        }
        int count = read.texts().size();
        if (count == 0) {
            problems.println("nothing to compile: no .sou file among what was named");
            return WRONG_COMMAND;
        }

        byte[] written;
        String offers = null;
        try {
            CheckedProgram program = CheckedProgram.of(read, ModulePath.EMPTY);
            written = component
                    ? WasmCompiler.compileAsComponent(program)
                    : WasmCompiler.compile(program);
            if (offering != null) {
                offers = WitText.written(WasmCompiler.offering(program));
            }
        } catch (CompileException e) {
            Refusal.lines(e, read).forEach(problems::println);
            return REFUSED;
        } catch (IllegalArgumentException e) {
            // A program that compiles but offers two behaviors one name, which is a refusal of
            // the program rather than something this backend has not got round to.
            problems.println(e.getMessage());
            return REFUSED;
        } catch (NotLowered e) {
            // What this backend does not write yet, which is a different thing from a program the
            // language refuses — so it says which it was rather than one word for both.
            problems.println("this backend does not write that yet: " + e.getMessage());
            return REFUSED;
        }

        try {
            if (into.getParent() != null) {
                Files.createDirectories(into.getParent());
            }
            Files.write(into, written);
            if (offering != null) {
                if (offering.getParent() != null) {
                    Files.createDirectories(offering.getParent());
                }
                Files.writeString(offering, offers, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            problems.println(e.getMessage());
            return WRONG_COMMAND;
        }
        out.println("wrote " + into + " from " + count + (count == 1 ? " source" : " sources"));
        return NOTHING_WRONG;
    }

    /** Nothing wrong: a module was written. */
    private static final int NOTHING_WRONG = 0;
    /** A program the language or this backend would not write. */
    private static final int REFUSED = 1;
    /** A command line naming no compile this could run. */
    private static final int WRONG_COMMAND = 2;

    /**
     * The sources a command line named, read: a file as itself, and a directory as every
     * {@code .sou} under it, read the way Souther reads a tree of sources.
     *
     * <p>Held as one value from here on, because what the program is checked from, what a lone file
     * with no header is called, and what a refusal is quoted from are one list of texts in one
     * order, and the compile is the one that says which id each gets. A link found under a named
     * directory is refused rather than followed, as {@code souther compile} refuses it.
     */
    private static CompilationSources read(List<Path> named) throws IOException {
        List<SourceFile> files = new ArrayList<>();
        for (Path each : named) {
            if (Files.isDirectory(each)) {
                files.addAll(CompilationSources.readTree(each));
            } else if (Files.isRegularFile(each)) {
                files.add(new SourceFile(each.toString(),
                        Files.readString(each, StandardCharsets.UTF_8)));
            } else {
                throw new IOException("no such file: " + each);
            }
        }
        return CompilationSources.files(files);
    }
}
