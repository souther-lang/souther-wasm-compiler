package souther.wasm;

import java.util.List;
import java.util.Map;
import souther.compiler.Compiler;
import souther.compiler.jvm.ClassFileImage;
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
 *
 * <p>The one way the tests compile, which {@code EveryTestCompilesThroughCompiledTest} holds them
 * to: a test that compiles for itself compiles again for every case that asks, and that is how the
 * suite came to spend most of its time doing so twice over.
 */
public final class Compiled {

    /** As many as a test class asks for in turn; what was asked longer ago is asked again rarely
     *  enough that keeping it would cost more than checking it again. */
    private static final int KEPT = 8;

    private static final Recent<List<String>, CheckedProgram> PROGRAMS = new Recent<>(KEPT);
    private static final Recent<CheckedProgram, byte[]> MODULES = new Recent<>(KEPT);
    private static final Recent<CheckedProgram, byte[]> COMPONENTS = new Recent<>(KEPT);

    private Compiled() {
    }

    private static final Recent<List<String>, ClassLoader> JVM = new Recent<>(KEPT);

    /**
     * The classes the JVM backend writes for {@code sources}, defined in a loader of their own,
     * written once for every test that asks: what a generated class is asked holds nothing one
     * asking leaves for the next.
     */
    public static ClassLoader jvm(List<String> sources) {
        return JVM.of(List.copyOf(sources), kept -> kept, written -> new Defined(
                Compiler.compileModules(written), Compiled.class.getClassLoader()));
    }

    /** {@code CheckedProgram.of(sources)}, checked once. A program refused is refused every time. */
    public static CheckedProgram program(List<String> sources) {
        return PROGRAMS.of(List.copyOf(sources), kept -> kept, CheckedProgram::of);
    }

    /** {@code WasmCompiler.compile(program)}, written once for each program. */
    public static byte[] module(CheckedProgram program) {
        return MODULES.of(program, kept -> kept, WasmCompiler::compile).clone();
    }

    /** {@code WasmCompiler.compileAsComponent(program)}, written once for each program. */
    public static byte[] component(CheckedProgram program) {
        return COMPONENTS.of(program, kept -> kept, WasmCompiler::compileAsComponent).clone();
    }

    /** Classes the JVM wrote, defined as they are asked for. */
    private static final class Defined extends ClassLoader {

        private final Map<String, ClassFileImage> classes;

        Defined(Map<String, ClassFileImage> classes, ClassLoader parent) {
            super(parent);
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            ClassFileImage image = classes.get(name);
            if (image == null) {
                image = classes.get(name.replace('.', '/'));
            }
            if (image == null) {
                throw new ClassNotFoundException(name);
            }
            byte[] bytes = image.bytes();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
