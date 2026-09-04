package souther.wasm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.ValType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import souther.wasm.abi.FailureRecord;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.link.RuntimeLayout;

/**
 * A module of this project's, instantiated and callable.
 *
 * <p>Everything the runtime and the linker claim is claimed about a file, so a test that did not
 * run the file would be checking this project against itself. This is what runs it.
 */
public final class Running {

    private final Instance instance;

    private Running(Instance instance) {
        this.instance = instance;
    }

    /**
     * A linked module, which places its own arena from the start thunk the link generated.
     *
     * @param module the linked output
     */
    public static Running linked(byte[] module) {
        return new Running(instantiate(module));
    }

    /**
     * The runtime on its own, with the arena placed by hand.
     *
     * <p>Unlinked, so it has no start section and nothing has told it where its arena goes. What a
     * link would compute from the last segment it placed is here just the runtime's own heap base,
     * because nothing was appended.
     */
    public static Running bareRuntime() {
        byte[] module = runtimeModule();
        Running running = new Running(instantiate(module));
        running.call(RuntimeAbi.RUNTIME_INIT, RuntimeLayout.of(module).heapBase(module));
        return running;
    }

    /** The runtime module this build carries. */
    public static byte[] runtimeModule() {
        try (InputStream in = RuntimeAbi.class.getResourceAsStream("/souther/wasm/runtime.wasm")) {
            if (in == null) {
                throw new IllegalStateException("this build carries no runtime module");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Calls an export taking one number, answering its first result or zero where it has none. */
    public int call(String export, int argument) {
        long[] answer = instance.export(export).apply(argument);
        return answer == null || answer.length == 0 ? 0 : (int) answer[0];
    }

    /** Calls an export taking nothing and answering one number. */
    public int call(String export) {
        return (int) instance.export(export).apply()[0];
    }

    /** Calls an export taking a pointer and a length and answering a pointer and a length. */
    public long[] callWithString(String export, int pointer, int length) {
        return instance.export(export).apply(pointer, length);
    }

    /** Calls an export taking two numbers and answering one. */
    public int call(String export, int first, int second) {
        return (int) instance.export(export).apply(first, second)[0];
    }

    /** Calls an export answering nothing. */
    public void run(String export) {
        instance.export(export).apply();
    }

    /** Calls an export taking four numbers and answering one. */
    public int call(String export, int first, int second, int third, int fourth) {
        return (int) instance.export(export).apply(first, second, third, fourth)[0];
    }

    /** Calls an export answering a pointer and a length packed into one number. */
    public long callPacked(String export, long... arguments) {
        return instance.export(export).apply(arguments)[0];
    }

    /** Stages text in the arena and answers where it went. */
    public int staged(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int address = call(RuntimeAbi.ALLOC, utf8.length);
        write(address, utf8);
        return address;
    }

    /** The text a packed answer points at. */
    public String textOf(long packed) {
        return new String(
                read(RuntimeAbi.pointerOf(packed), RuntimeAbi.lengthOf(packed)),
                StandardCharsets.UTF_8);
    }

    /** Writes bytes into the module's memory. */
    public void write(int address, byte[] bytes) {
        instance.memory().write(address, bytes);
    }

    /** Reads bytes out of the module's memory. */
    public byte[] read(int address, int length) {
        return instance.memory().readBytes(address, length);
    }

    /** What the runtime wrote about the call it ended, wherever it put the record. */
    public FailureRecord failureRecord() {
        int address = call(RuntimeAbi.FAILURE_ADDR);
        ByteBuffer record = ByteBuffer
                .wrap(read(address, RuntimeAbi.FAILURE_BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        return FailureRecord.read(record, 0);
    }

    private static Instance instantiate(byte[] module) {
        HostFunction hostCall = new HostFunction(
                RuntimeAbi.IMPORT_MODULE,
                RuntimeAbi.IMPORT_HOST_CALL,
                List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                List.of(ValType.I32),
                (instance, arguments) -> new long[] {0});
        return Instance.builder(Parser.parse(module))
                .withImportValues(ImportValues.builder().addFunction(hostCall).build())
                .build();
    }
}
