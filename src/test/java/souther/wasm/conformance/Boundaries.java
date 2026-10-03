package souther.wasm.conformance;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.unit8.raoh.Err;
import net.unit8.raoh.Issue;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;
import net.unit8.raoh.ResourceBundleMessageResolver;
import net.unit8.raoh.Result;
import net.unit8.raoh.decode.Decoder;
import souther.compiler.Compiler;
import souther.compiler.jvm.ClassFileImage;
import souther.wasm.Compiled;
import souther.wasm.Running;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One model's boundary, read by each backend that reads one, each answer written the same way.
 *
 * <p>What a backend answers for a value of a type is either the value, as it writes it back, or the
 * issues it found, each as its path, its code, its message key and its metadata. Written alike so
 * that two backends are compared by comparing two documents, and a fixture says what both are held
 * to in the same words.
 */
final class Boundaries {

    /** Fractions kept as written, as the spec asks of a reader handing JSON to a decoder. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private Boundaries() {
    }

    /** A fixture's text, read with its fractions kept. */
    static JsonNode document(String text) {
        return JSON.readTree(text);
    }

    /** Raoh's own catalog, as the JVM resolves an issue's message from it. */
    private static final ResourceBundleMessageResolver CATALOG =
            new ResourceBundleMessageResolver("net.unit8.raoh.messages");

    /** The JVM's reading: the generated class's {@code jsonDecoder()}. */
    static JsonNode jvm(String model, String type, JsonNode input) {
        Map<String, ClassFileImage> classes = Compiler.compileModules(List.of(model));
        ClassLoader loader = new Defined(classes, Boundaries.class.getClassLoader());
        Result<?> result;
        try {
            Decoder<JsonNode, ?> decoder = (Decoder<JsonNode, ?>) loader.loadClass(type)
                    .getMethod("jsonDecoder").invoke(null);
            result = decoder.decode(input, Path.ROOT);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type + " has no JSON decoder the JVM wrote", e);
        }
        ObjectNode answer = JSON.createObjectNode();
        switch (result) {
            case Ok<?> ok -> answer.put("value", "decoded");
            case Err<?> err -> {
                // What a person reads, as the JVM's resolver writes it from Raoh's catalog, in each
                // language the catalog has: what the TypeScript side is held to as well.
                ObjectNode messages = answer.putObject("messages");
                for (Locale locale : List.of(Locale.ENGLISH, Locale.JAPANESE)) {
                    ArrayNode said = messages.putArray(locale.getLanguage());
                    for (Issue resolved : err.issues().resolve(CATALOG, locale).asList()) {
                        said.add(resolved.message());
                    }
                }
                ArrayNode issues = answer.putArray("issues");
                for (Issue issue : err.issues().asList()) {
                    ObjectNode written = issues.addObject();
                    written.put("path", issue.path().toJsonPointer());
                    written.put("code", issue.code());
                    written.put("messageKey", issue.messageKey());
                    written.set("meta", JSON.valueToTree(issue.meta()));
                    if (issue.customMessage()) {
                        written.put("message", issue.message());
                    }
                }
            }
        }
        return answer;
    }

    /** The wasm module's reading: {@code __souther_decode}, under the number the surface gives. */
    static JsonNode wasm(String model, String type, JsonNode input) {
        byte[] module = Compiled.module(Compiled.program(List.of(model)));
        Running running = Running.linked(module);
        JsonNode surface = JSON.readTree(Running.customSection(module, "souther:surface"));
        int number = -1;
        for (JsonNode declaration : surface.get("declarations")) {
            if ((declaration.get("module").asString() + "." + declaration.get("name").asString())
                    .equals(type) && declaration.has("decode")) {
                number = declaration.get("decode").asInt();
            }
        }
        if (number < 0) {
            throw new IllegalStateException(type + " is no type the module reads on its own");
        }
        String written = input.toString();
        int at = running.staged(written);
        long[] answer = running.callWith("__souther_decode", number, at,
                written.getBytes(StandardCharsets.UTF_8).length);
        JsonNode read = JSON.readTree(new String(
                running.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8));
        if (read.has("value")) {
            ObjectNode decoded = JSON.createObjectNode();
            decoded.put("value", "decoded");
            return decoded;
        }
        return read;
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
