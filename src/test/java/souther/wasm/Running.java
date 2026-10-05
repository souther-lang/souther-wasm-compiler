package souther.wasm;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.MemoryLimits;
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
import souther.wasm.link.LinkPlan;
import souther.wasm.link.Linker;
import souther.wasm.link.RuntimeLayout;
import souther.wasm.link.WasmFragment;

/**
 * A module of this project's, instantiated and callable.
 *
 * <p>Everything the runtime and the linker claim is claimed about a file, so a test that did not
 * run the file would be checking this project against itself. This is what runs it.
 */
public final class Running {

    private final Instance instance;
    private final byte[] module;

    private Running(Instance instance, byte[] module) {
        this(instance, module, java.util.Map.of());
    }

    private Running(Instance instance, byte[] module, java.util.Map<String, Integer> slots) {
        this.instance = instance;
        this.module = module;
        this.slots = slots;
    }

    /** The slot each function {@link #runtimeWithSlots} was asked to put in the table went to. */
    private final java.util.Map<String, Integer> slots;

    /**
     * The runtime with the functions named put in its table and everything it exports shown, for
     * a test that writes a descriptor by hand and has to name what orders a value of it: a
     * descriptor names what reads, writes and orders one by the slot of each, and the runtime on its
     * own has no slot to name. Linked as a program would be, with nothing of a program in it.
     *
     * @param functions what the runtime exports under those names, each put in a slot
     */
    public static Running runtimeWithSlots(String... functions) {
        LinkPlan plan = LinkPlan.reading(runtimeModule());
        WasmFragment fragment = new WasmFragment(plan);
        java.util.Map<String, Integer> slots = new java.util.HashMap<>();
        for (String function : functions) {
            slots.put(function, fragment.slot(plan.functionIndexOf(function)));
        }
        plan.layout().exports().forEach((name, export) -> {
            if (export.kind() == RuntimeLayout.ExportKind.FUNCTION && !RuntimeAbi.HOST_EXPORTS.contains(name)) {
                fragment.export(name, export.index());
            }
        });
        byte[] module = Linker.unshaken(fragment);
        return new Running(instantiate(module), module, java.util.Map.copyOf(slots));
    }

    /** The slot a function {@link #runtimeWithSlots} put in the table went to. */
    public int slotOf(String function) {
        Integer slot = slots.get(function);
        if (slot == null) {
            throw new IllegalArgumentException(function + " was not put in a slot");
        }
        return slot;
    }

    /**
     * A linked module, which places its own arena from the start thunk the link generated.
     *
     * @param module the linked output
     */
    public static Running linked(byte[] module) {
        return new Running(instantiate(module), module);
    }

    /**
     * A linked module whose memory cannot grow more than {@code pages} past where it starts, so that
     * running out of it is something a test can reach.
     */
    public static Running linkedWithin(byte[] module, int pages) {
        return new Running(instantiateWithin(module, pages), module);
    }

    /** What the custom section {@code name} of this module says, as text. */
    public String customSection(String name) {
        return customSection(module, name);
    }

    /**
     * What the custom section {@code name} of {@code module} says, as text.
     *
     * @throws AssertionError where the module carries no such section
     */
    public static String customSection(byte[] module, String name) {
        int at = 8;
        while (at < module.length) {
            int id = module[at++] & 0xff;
            long[] size = leb(module, at);
            at = (int) size[1];
            int end = at + (int) size[0];
            if (id == 0) {
                long[] length = leb(module, at);
                int nameAt = (int) length[1];
                int from = nameAt + (int) length[0];
                if (new String(module, nameAt, (int) length[0], StandardCharsets.UTF_8).equals(name)) {
                    return new String(module, from, end - from, StandardCharsets.UTF_8);
                }
            }
            at = end;
        }
        throw new AssertionError("the module carries no " + name);
    }

    /** An unsigned LEB128 at {@code at}: the value, and where what follows it starts. */
    private static long[] leb(byte[] bytes, int at) {
        long value = 0;
        int shift = 0;
        int next = at;
        while (true) {
            int b = bytes[next++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return new long[] {value, next};
            }
            shift += 7;
        }
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
        return placed(new Running(instantiate(module), module));
    }

    /** The runtime on its own, as {@link #bareRuntime}, with memory that cannot grow at all. */
    public static Running bareRuntimeThatCannotGrow() {
        byte[] module = runtimeModule();
        return placed(new Running(instantiateWithin(module, 0), module));
    }

    private static Running placed(Running running) {
        running.instance.export(RuntimeAbi.RUNTIME_INIT)
                .apply(RuntimeLayout.of(running.module).heapBase(running.module), 0);
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

    /** Calls an export taking three numbers and answering nothing. */
    public void run(String export, int first, int second, int third) {
        instance.export(export).apply(first, second, third);
    }

    /** Calls an export taking four numbers and answering one. */
    public int call(String export, int first, int second, int third, int fourth) {
        return (int) instance.export(export).apply(first, second, third, fourth)[0];
    }

    /** Calls an export taking any numbers and answering nothing. */
    public void runWith(String export, long... arguments) {
        instance.export(export).apply(arguments);
    }

    /** Calls an export taking any numbers and answering what it answers. */
    public long[] callWith(String export, long... arguments) {
        return instance.export(export).apply(arguments);
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

    /** How many bytes of memory the instance has now. */
    public int memoryBytes() {
        return instance.memory().pages() * 65536;
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
        return builderFor(module).build();
    }

    /** An instance whose memory grows at most {@code pages} past what the module starts with. */
    private static Instance instantiateWithin(byte[] module, int pages) {
        int starts = parsed(module).memorySection().orElseThrow().getMemory(0).limits().initialPages();
        return builderFor(module).withMemoryLimits(new MemoryLimits(starts, starts + pages)).build();
    }

    private static Instance.Builder builderFor(byte[] module) {
        HostFunction hostCall = new HostFunction(
                RuntimeAbi.IMPORT_MODULE,
                RuntimeAbi.IMPORT_HOST_CALL,
                List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                List.of(ValType.I32),
                (instance, arguments) -> new long[] {0});
        return Instance.builder(parsed(module))
                .withImportValues(ImportValues.builder().addFunction(hostCall).build());
    }

    /**
     * {@code module}, parsed once for every instance made of the same bytes.
     *
     * <p>Reading a module is most of what making an instance of one costs, and it is a question of
     * the bytes alone: what an instance holds of its own — its memory, its globals, its table — is
     * made when it is built, so every test still runs on an instance nothing else has touched.
     * Only the last few are kept, which is where the same bytes come back: the next test of a class
     * running the program the last one did.
     */
    private static WasmModule parsed(byte[] module) {
        return PARSED.of(ByteBuffer.wrap(module),
                asked -> ByteBuffer.wrap(module.clone()), asked -> Parser.parse(module));
    }

    private static final Recent<ByteBuffer, WasmModule> PARSED = new Recent<>(8);
}
