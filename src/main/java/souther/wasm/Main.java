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
import java.util.Locale;
import java.util.stream.Stream;
import souther.compiler.cst.SourceLayout;
import souther.compiler.diag.CompileException;
import souther.compiler.diag.DiagnosticRenderer;
import souther.compiler.diag.HumanRenderer;
import souther.compiler.diag.SourceContext;
import souther.compiler.diag.SourceContextResolver;
import souther.compiler.diag.SourceNames;
import souther.compiler.program.CheckedProgram;
import souther.compiler.query.Compilation;
import souther.wasm.link.WitText;
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
              --wit <file>  also write what the program offers, as a reader of interfaces reads
                            it. The same whether a component is written or not.

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
        String offers = null;
        List<String> read = new ArrayList<>(files.size());
        try {
            for (Path file : files) {
                read.add(Files.readString(file, StandardCharsets.UTF_8));
            }
            CheckedProgram program = CheckedProgram.of(read);
            written = component
                    ? WasmCompiler.compileAsComponent(program)
                    : WasmCompiler.compile(program);
            if (offering != null) {
                offers = WitText.written(
                        WasmCompiler.offered(program), WasmCompiler.reachedOutFor(program));
            }
        } catch (CompileException e) {
            say(e, files, read, problems);
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
        } catch (IOException e) {
            problems.println(e.getMessage());
            return WRONG_COMMAND;
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
     * Says why the language refused a program, quoting the source each diagnostic points into.
     *
     * <p>Where a report points is a place in a text; a line and a column are what that text is laid
     * out as, so they belong to whoever holds the text rather than to an exception on its way up a
     * stack. This command holds the texts it compiled, which is why the numbers are worked out here.
     * An error that carries no diagnostic is a site not yet reporting through the catalog, and what
     * it says is the whole of what there is to say about it.
     *
     * <p>No colour and English: this stream is as often a log or a pipe as a terminal, and every
     * other sentence this command writes is English.
     */
    private static void say(CompileException e, List<Path> files, List<String> texts,
                            PrintStream problems) {
        if (e.diagnostic() == null) {
            problems.println(e.getMessage());
            return;
        }
        for (String line : DiagnosticRenderer.renderAll(e.locatedDiagnostics(),
                quoting(files, texts), new HumanRenderer(false), Locale.ENGLISH)) {
            problems.println(line);
        }
    }

    /**
     * What to quote for each source a report names: the text this command read, under the name a
     * reader is shown it by.
     *
     * <p>A report names a source by the position it was handed over in, which is the one thing this
     * knows its own list by. A name that is none of them is about a source this compile did not hand
     * over, and answering it with a file that happens to be here would draw a caret in a text the
     * report says nothing about.
     *
     * <p>The layout is told which source its text is, so that the same thing is refused a second
     * time where it is a type error rather than a missed comparison: a layout that knows its source
     * will not resolve a place from another one. A text handed over with no name is a text nobody
     * can tell apart, and this one is told apart by the id it was compiled under.
     *
     * <p>The names are {@link SourceNames}, so a reader is shown {@code model.sou} where that is
     * unambiguous and enough of the path to tell two apart where it is not — the naming every other
     * report of this language is read under.
     */
    private static SourceContextResolver quoting(List<Path> files, List<String> texts) {
        List<String> names = SourceNames.of(files.stream().map(Path::toString).toList());
        return SourceContextResolver.memoized(id -> {
            for (int i = 0; i < texts.size(); i++) {
                if (Compilation.idOfSourceIndex(i).equals(id)) {
                    return new SourceContext(names.get(i), texts.get(i),
                            SourceLayout.of(texts.get(i), id));
                }
            }
            return null;
        });
    }

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
