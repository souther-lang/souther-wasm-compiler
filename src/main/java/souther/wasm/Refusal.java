package souther.wasm;

import java.util.List;
import java.util.Locale;
import souther.compiler.CompilationSources;
import souther.compiler.diag.CompileException;
import souther.compiler.diag.DiagnosticRenderer;
import souther.compiler.diag.HumanRenderer;

/**
 * Why the language refused a program, as the language's human report: each diagnostic with the
 * source it points into named, the line quoted and a caret under the place.
 *
 * <p>Quoted from the sources the program was checked from, which say which text each diagnostic is
 * about and what a reader calls it; nothing here works that out again. An error that carries no
 * diagnostic is a site not yet reporting through the catalog, and its message is the whole of what
 * there is to say about it.
 *
 * <p>No colour and English: what reads this is as often a log or a pipe as a terminal, and every
 * other sentence the command writes is English.
 */
final class Refusal {

    private Refusal() {
    }

    /** The lines of the report of {@code refused}, about the program checked from {@code sources}. */
    static List<String> lines(CompileException refused, CompilationSources sources) {
        if (refused.diagnostic() == null) {
            return List.of(refused.getMessage());
        }
        return DiagnosticRenderer.renderAll(refused.locatedDiagnostics(), sources.contexts(),
                new HumanRenderer(false), Locale.ENGLISH);
    }
}
