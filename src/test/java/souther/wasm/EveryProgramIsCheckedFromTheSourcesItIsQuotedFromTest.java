package souther.wasm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.InvokeInstruction;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import souther.compiler.diag.CompileException;
import souther.compiler.program.CheckedProgram;

/**
 * What this compiler checks a program from is the sources it read, held as Souther holds them, and
 * a refusal of one is said from those sources.
 *
 * <p>A program checked from bare texts leaves whoever says why it was refused to work out which text
 * each diagnostic is about and what to call it, and a command that worked it out again refused a
 * lone file with no header that {@code souther compile} takes, and read a link under a directory
 * that {@code souther compile} refuses (#54). Kept here over the compiled classes, so that a new way
 * into the compiler is held to it whatever its source looks like.
 */
class EveryProgramIsCheckedFromTheSourcesItIsQuotedFromTest {

    private static final String CHECKED_PROGRAM = CheckedProgram.class.getName().replace('.', '/');
    private static final String COMPILE_EXCEPTION =
            CompileException.class.getName().replace('.', '/');
    private static final String REFUSAL = Refusal.class.getName().replace('.', '/');

    @Test
    void noProgramIsCheckedFromBareTexts() throws Exception {
        List<String> found = new ArrayList<>();
        for (Owned method : methods()) {
            for (InvokeInstruction call : calls(method.code())) {
                if (call.owner().asInternalName().equals(CHECKED_PROGRAM)
                        && call.name().equalsString("of")
                        && call.type().stringValue().startsWith("(Ljava/util/List;")) {
                    found.add(method.named());
                }
            }
        }

        assertThat(found).as("checked from texts with no names, in").isEmpty();
    }

    @Test
    void everyRefusalIsSaidThroughTheRenderer() throws Exception {
        List<String> catching = new ArrayList<>();
        List<String> found = new ArrayList<>();
        for (Owned method : methods()) {
            boolean catches = method.code().elementStream()
                    .anyMatch(each -> each instanceof ExceptionCatch handler
                            && handler.catchType().isPresent()
                            && handler.catchType().get().asInternalName().equals(COMPILE_EXCEPTION));
            if (!catches) {
                continue;
            }
            catching.add(method.named());
            boolean says = calls(method.code()).stream()
                    .anyMatch(call -> call.owner().asInternalName().equals(REFUSAL)
                            && call.name().equalsString("lines"));
            if (!says) {
                found.add(method.named());
            }
        }

        // And the walk saw the command's own catch, so an empty answer is not a walk that found
        // nothing to look at.
        assertThat(catching).anyMatch(each -> each.startsWith(Main.class.getName() + "."));
        assertThat(found).as("a refusal caught and not said through Refusal, in").isEmpty();
    }

    private record Owned(String named, CodeModel code) {
    }

    private static List<InvokeInstruction> calls(CodeModel code) {
        return code.elementStream().filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast).toList();
    }

    private static List<Owned> methods() throws IOException, URISyntaxException {
        Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Owned> methods = new ArrayList<>();
        try (Stream<Path> walked = Files.walk(classes)) {
            for (Path file : walked.filter(it -> it.toString().endsWith(".class")).toList()) {
                ClassModel model = ClassFile.of().parse(file);
                for (MethodModel method : model.methods()) {
                    method.code().ifPresent(code -> methods.add(new Owned(
                            model.thisClass().asInternalName().replace('/', '.') + "."
                                    + method.methodName().stringValue(), code)));
                }
            }
        }
        assertThat(methods).as("methods under " + classes).hasSizeGreaterThan(100);
        return methods;
    }
}
