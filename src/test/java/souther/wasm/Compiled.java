package souther.wasm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import souther.compiler.program.CheckedProgram;
import souther.wasm.lower.WasmCompiler;

/**
 * What the tests compile, compiled once for every test that compiles the same thing.
 *
 * <p>Many tests here run one program against many inputs, and each used to check and compile it
 * again; the check is most of what a test costs. A checked program is a snapshot nothing can
 * change, so one checked once is the same program for every test that asks, and the module
 * written from it is the same bytes.
 *
 * <p>Shaped as the calls it stands for, argument for argument — {@link #program} for
 * {@code CheckedProgram.of}, {@link #module} for {@code WasmCompiler.compile} — so a test reads as
 * it did. Each answer is held under everything that decides it: a program under its sources, and a
 * module or a component under the program it was written from, which is one instance per sources.
 * A program read off a module path, a module linked against another runtime, and a test about what
 * compiling costs or whether it answers alike twice ask the compiler themselves.
 *
 * <p>Bytes are handed out as a copy: a test that wrote into what it was handed would otherwise be
 * writing into every other test's module.
 */
public final class Compiled {

    private static final Map<List<String>, CheckedProgram> PROGRAMS = new ConcurrentHashMap<>();
    private static final Map<CheckedProgram, byte[]> MODULES = new ConcurrentHashMap<>();
    private static final Map<CheckedProgram, byte[]> COMPONENTS = new ConcurrentHashMap<>();

    private Compiled() {
    }

    /** {@code CheckedProgram.of(sources)}, checked once. A program refused is refused every time. */
    public static CheckedProgram program(List<String> sources) {
        return PROGRAMS.computeIfAbsent(List.copyOf(sources), CheckedProgram::of);
    }

    /** {@code WasmCompiler.compile(program)}, written once for each program. */
    public static byte[] module(CheckedProgram program) {
        return MODULES.computeIfAbsent(program, WasmCompiler::compile).clone();
    }

    /** {@code WasmCompiler.compileAsComponent(program)}, written once for each program. */
    public static byte[] component(CheckedProgram program) {
        return COMPONENTS.computeIfAbsent(program, WasmCompiler::compileAsComponent).clone();
    }
}
