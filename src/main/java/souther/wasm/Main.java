package souther.wasm;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import souther.compiler.diag.CompileException;
import souther.compiler.program.CheckedProgram;
import souther.wasm.lower.NotLowered;
import souther.wasm.lower.WasmCompiler;

/**
 * Compiles Souther sources named on a command line.
 *
 * <p>What it writes is a core module, or a component where one is asked for. Everything it can say
 * about why it did not goes to the error stream and nothing goes to the output file, so a build
 * that stopped leaves no half-written module for the next step to pick up.
 */
public final class Main {

    private static final String USAGE = """
            souther-wasm — compiles a Souther program to WebAssembly

              souther-wasm <source or directory>... -o <file> [--component]

              -o <file>     where to write. Required.
              --component   write a component, one interface per Souther module, rather than a
                            core module. A behavior crosses as func(arguments: string) -> string.

            A directory is read for the .sou files under it, in the order their paths sort.
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
        if (sources.isEmpty() || into == null) {
            problems.print(USAGE);
            return WRONG_COMMAND;
        }

        List<Path> files;
        try {
            files = under(sources);
        } catch (IOException | UncheckedIOException e) {
            problems.println(e.getMessage());
            return WRONG_COMMAND;
        }
        if (files.isEmpty()) {
            problems.println("nothing to compile: no .sou file among what was named");
            return WRONG_COMMAND;
        }

        byte[] written;
        try {
            List<String> read = new ArrayList<>(files.size());
            for (Path file : files) {
                read.add(Files.readString(file, StandardCharsets.UTF_8));
            }
            CheckedProgram program = CheckedProgram.of(read);
            written = component
                    ? WasmCompiler.compileAsComponent(program)
                    : WasmCompiler.compile(program);
        } catch (CompileException e) {
            problems.println(e.getMessage());
            return REFUSED;
        } catch (NotLowered e) {
            // What this backend does not write yet, which is a different thing from a program the
            // language refuses — so it says which it was rather than one word for both.
            problems.println("this backend does not write that yet: " + e.getMessage());
            return REFUSED;
        } catch (IOException e) {
            problems.println(e.getMessage());
            return WRONG_COMMAND;
        }

        try {
            if (into.getParent() != null) {
                Files.createDirectories(into.getParent());
            }
            Files.write(into, written);
        } catch (IOException e) {
            problems.println(e.getMessage());
            return WRONG_COMMAND;
        }
        out.println("wrote " + into + " from " + files.size()
                + (files.size() == 1 ? " source" : " sources"));
        return NOTHING_WRONG;
    }

    /** Nothing wrong: a module was written. */
    private static final int NOTHING_WRONG = 0;
    /** A program the language or this backend would not write. */
    private static final int REFUSED = 1;
    /** A command line naming no compile this could run. */
    private static final int WRONG_COMMAND = 2;

    /**
     * The sources a command line named: a file as itself, a directory as the {@code .sou} files
     * under it, in the order their paths sort so that one command line is one program every time.
     */
    private static List<Path> under(List<Path> named) throws IOException {
        List<Path> found = new ArrayList<>();
        for (Path each : named) {
            if (Files.isDirectory(each)) {
                try (Stream<Path> walked = Files.walk(each)) {
                    walked.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".sou"))
                            .sorted(Comparator.comparing(Path::toString))
                            .forEach(found::add);
                }
            } else if (Files.isRegularFile(each)) {
                found.add(each);
            } else {
                throw new IOException("no such file: " + each);
            }
        }
        return found;
    }
}
