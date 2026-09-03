package souther.wasm.abi;

/**
 * What a linked Souther module answers with, and what it asks of whoever runs it.
 *
 * <p>The other side of {@code runtime/src/lib.rs}. Every name here is exported or imported by that
 * crate, and the two are checked against each other by a test that reads the compiled module
 * rather than by anyone remembering to keep them in step.
 *
 * <h2>Calling an export</h2>
 *
 * <p>A behavior takes JSON and answers JSON, as UTF-8 bytes in this module's memory addressed by
 * a pointer and a length. Reclaiming them is the caller's, on rontolisp's recipe:
 *
 * <ol>
 *   <li>read {@link #FAILURE_GENERATION} and keep it,
 *   <li>call {@link #ALLOC_MARK},
 *   <li>call {@link #ALLOC} for the input and write it,
 *   <li>call the export,
 *   <li>copy the answer's bytes out of memory,
 *   <li>call {@link #ALLOC_RESET} with the mark.
 * </ol>
 *
 * <p>The answer is a live pointer into the arena, so the reset has to come after the read. An
 * instance serves one call at a time: the arena is one bump region with one top, so a second call
 * entered before the first has reset would allocate over what the first is about to return.
 *
 * <h2>When a call traps</h2>
 *
 * <p>A trap leaves the instance usable — the caller still resets with the mark it took. Which
 * trap it was is read from the failure record: call {@link #FAILURE_GENERATION} again, and only
 * when it differs from the snapshot does the record describe the trap. An unchanged generation
 * means the wasm faulted on its own account, and the record still holds an older failure.
 */
public final class RuntimeAbi {

    private RuntimeAbi() {
    }

    /** The version {@link #ABI_VERSION} answers for a runtime this compiler emits against. */
    public static final int VERSION = 1;

    /** The module a linked output imports from. */
    public static final String IMPORT_MODULE = "souther";

    /** Reaches an injected behavior. See {@link #HOST_CALL} for what the two sides own. */
    public static final String IMPORT_HOST_CALL = "host_call";

    /** The linear memory a caller stages arguments in and reads answers out of. */
    public static final String MEMORY = "memory";

    /** {@code (i32 size) -> i32}: zeroed bytes from the arena. */
    public static final String ALLOC = "__ronto_alloc";

    /** {@code () -> i32}: the arena's top, to pop back to. */
    public static final String ALLOC_MARK = "__ronto_alloc_mark";

    /** {@code (i32 mark) -> ()}: pops the arena back to a mark it issued. */
    public static final String ALLOC_RESET = "__ronto_alloc_reset";

    /** {@code (i32 static_end) -> ()}: places the arena. Called by the generated start thunk. */
    public static final String RUNTIME_INIT = "__souther_runtime_init";

    /** {@code () -> i32}: the failure record's address. */
    public static final String FAILURE_ADDR = "__souther_failure_addr";

    /** {@code () -> i32}: the generation the failure record carries. */
    public static final String FAILURE_GENERATION = "__souther_failure_generation";

    /** {@code () -> i32}: the ABI this runtime answers as, against {@link #VERSION}. */
    public static final String ABI_VERSION = "__souther_abi_version";

    /**
     * {@code (i32 reason, i32 descriptor, i64 aux0, i64 aux1) -> !}: writes the failure record and
     * traps. Every abort a generated body raises goes through this one.
     */
    public static final String ABORT = "__souther_abort";

    /**
     * {@code (i32 behavior_id, i32 in_ptr, i32 in_len) -> i64}: reaches an injected behavior and
     * answers the arena pointer and length of what came back, packed low and high.
     *
     * <p>Wraps {@link #IMPORT_HOST_CALL} so that the buffer the host writes into is allocated
     * here. A host is handed a pointer and a capacity and answers the length the value wants; a
     * length past the capacity means it wrote nothing, and the call is made again against a
     * buffer that fits. Nothing outside this module moves the bump pointer.
     */
    public static final String HOST_CALL = "__souther_host_call";

    /**
     * The table a closure's slot and a descriptor's invariant slot live in.
     *
     * <p>The runtime declares it and calls through it, so a generated body reaches another through
     * a slot rather than through whatever table a recompilation of the runtime happened to make.
     * The link appends element segments to this one and raises its minimum to fit them.
     */
    public static final String TABLE = "__indirect_function_table";

    /** {@code (i32 slot, i32 argument) -> i32}: reaches the function in a slot of {@link #TABLE}. */
    public static final String CALL_SLOT = "__souther_call_slot";

    /** {@code i32}: where the linker's own data may begin, read out of the global section. */
    public static final String HEAP_BASE = "__heap_base";

    /** {@code i32}: where the runtime's own data ends. */
    public static final String DATA_END = "__data_end";

    /** The name a generated start thunk is given. It takes and answers nothing, as a start must. */
    public static final String START_THUNK = "__souther_start";

    /** The failure record's width in bytes. */
    public static final int FAILURE_BYTES = 28;

    /** Offset of the generation counter in the failure record. */
    public static final int FAILURE_OFF_GENERATION = 0;

    /** Offset of the reason code in the failure record. */
    public static final int FAILURE_OFF_REASON = 4;

    /** Offset of the descriptor id the reason is about. */
    public static final int FAILURE_OFF_DESCRIPTOR = 8;

    /** Offset of the first bounded word the reason carries. */
    public static final int FAILURE_OFF_AUX0 = 12;

    /** Offset of the second bounded word the reason carries. */
    public static final int FAILURE_OFF_AUX1 = 20;
}
