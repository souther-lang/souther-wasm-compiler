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

    /** {@code (i32 ptr, i32 len) -> i32}: parses a document into the cell it is. */
    public static final String JSON_PARSE = "__souther_json_parse";

    /** {@code (i32 cell) -> i32}: what kind of value a cell is, from {@link JsonTag}. */
    public static final String JSON_TAG = "__souther_json_tag";

    /** {@code (i32 cell) -> i32}: bytes for a string or a number, entries for a container. */
    public static final String JSON_LENGTH = "__souther_json_length";

    /** {@code (i32 cell) -> i32}: where a string's or a number's bytes start. */
    public static final String JSON_BYTES = "__souther_json_bytes";

    /** {@code (i32 cell, i32 index) -> i32}: an array's element. */
    public static final String JSON_ELEMENT = "__souther_json_element";

    /** {@code (i32 cell, i32 index) -> i32}: an object entry's key, which is a string cell. */
    public static final String JSON_KEY = "__souther_json_key";

    /** {@code (i32 cell, i32 index) -> i32}: an object entry's value. */
    public static final String JSON_VALUE = "__souther_json_value";

    /** {@code (i64 value) -> i64}: a whole number as text, answered packed. */
    public static final String JSON_WRITE_INT = "__souther_json_write_int";

    /** {@code (i32 value) -> i64}: a boolean as text, answered packed. */
    public static final String JSON_WRITE_BOOL = "__souther_json_write_bool";

    /** {@code (i32 ptr, i32 len) -> i64}: a string quoted and escaped, answered packed. */
    public static final String JSON_WRITE_STRING = "__souther_json_write_string";

    /** {@code (i32 document, i32 expected) -> ()}: checks that a call was handed its arguments. */
    public static final String CHECK_ARGUMENTS = "__souther_check_arguments";

    /** {@code (i32 document, i32 index) -> i32}: one argument of a call. */
    public static final String ARGUMENT = "__souther_argument";

    /**
     * {@code (i32 json, i32 descriptor, i32 path, i32 pathLength) -> i32}: a JSON value read as a
     * value of the type a descriptor describes, or nothing where it could not be.
     */
    public static final String READ = "__souther_read";

    /** {@code (i32 descriptor) -> i32}: a cell of a declared shape, its fields not yet filled. */
    public static final String RECORD = "__souther_record";

    /** {@code (i32 cell, i32 index, i32 value) -> ()}: puts a value in one of a record's fields. */
    public static final String RECORD_SET = "__souther_record_set";

    /** {@code (i32 cell, i32 index) -> i32}: the value in one of a record's fields. */
    public static final String RECORD_GET = "__souther_record_get";

    /** {@code (i32 descriptor) -> i32}: the one value of a type that has one. */
    public static final String UNIT = "__souther_unit";

    /** {@code (i32 descriptor, i32 length) -> i32}: a list of that many, its places empty. */
    public static final String LIST = "__souther_list";

    /** {@code (i32 cell, i32 index, i32 value) -> ()}: puts a value at a position of a list. */
    public static final String LIST_SET = "__souther_list_set";

    /** {@code (i32 cell) -> i32}: how many elements a list holds. */
    public static final String LIST_LENGTH = "__souther_list_length";

    /** {@code (i32 cell, i32 index) -> i32}: the value at a position of a list. */
    public static final String LIST_GET = "__souther_list_get";

    /** {@code (i32 value) -> i32}: an option holding that. */
    public static final String SOME = "__souther_some";

    /** {@code () -> i32}: an option holding nothing. */
    public static final String NONE = "__souther_none";

    /** {@code () -> ()}: forgets what an earlier call's decode found. */
    public static final String ISSUES_BEGIN = "__souther_issues_begin";

    /** {@code () -> i32}: how many issues this call's decode found. */
    public static final String ISSUES_COUNT = "__souther_issues_count";

    /** {@code () -> i64}: the issues as the answer a caller reads, packed. */
    public static final String ISSUES_WRITTEN = "__souther_issues_written";

    /** {@code (i64 value) -> i32}: a cell holding an {@code Int}. */
    public static final String INT = "__souther_int";

    /** {@code (i32 value) -> i32}: a cell holding a {@code Bool}. */
    public static final String BOOL = "__souther_bool";

    /** {@code (i32 ptr, i32 len) -> i32}: a cell holding a {@code String}. */
    public static final String STRING = "__souther_string";

    /**
     * {@code (i32 cell, i32 descriptor) -> i64}: a value written as the answer a caller reads,
     * answered packed.
     *
     * <p>Against the declared type and not against the value alone: the tag saying which case a
     * value is belongs where a sum was declared and nowhere else, and what a cell holds cannot say
     * whether the place it fills was declared as the sum or as the case.
     */
    public static final String WRITE = "__souther_write";

    /** The name a generated start thunk is given. It takes and answers nothing, as a start must. */
    public static final String START_THUNK = "__souther_start";

    /** The failure record's width in bytes. */
    public static final int FAILURE_BYTES = 28;

    /** The pointer half of a packed {@code (pointer, length)} answer. */
    public static int pointerOf(long packed) {
        return (int) packed;
    }

    /** The length half of a packed {@code (pointer, length)} answer. */
    public static int lengthOf(long packed) {
        return (int) (packed >>> 32);
    }

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
