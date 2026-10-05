package souther.wasm.link;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.emit.WasmTreeShaker;
import souther.wasm.emit.WasmWriter;
import souther.wasm.link.LayoutReader.RawSection;
import souther.wasm.link.WasmFragment.Segment;

/**
 * Puts a fragment onto the runtime.
 *
 * <p>The runtime's code bodies cross unread and unchanged. What is rewritten is the framing around
 * them: a section holding a counted vector gets the fragment's entries after the runtime's own and
 * a new count in front, the memory's minimum is raised to cover what was placed above it, and a
 * start section is written naming a thunk the link generates.
 *
 * <p>The thunk exists because a start function takes and answers nothing, while the runtime has to
 * be told where its arena begins — a number that is not settled until the last segment is placed.
 * So the thunk pushes that number and calls {@link RuntimeAbi#RUNTIME_INIT}. Where the runtime
 * claimed the start slot itself, the thunk calls that first: a module has one start, and what the
 * runtime meant to run before anything else still has to run before anything else.
 *
 * <p>One body of the runtime's is replaced, and only for a module a component wraps: the one every
 * call the runtime ends is left through, which traps, becomes a throw its lifted functions catch
 * ({@link WasmFragment#endsCallsByThrowing}). It is replaced whole, found by the name the runtime
 * exports it under, so no code the runtime wrote is read.
 */
public final class Linker {

    private static final int SEC_TYPE = 1;
    private static final int SEC_FUNCTION = 3;
    private static final int SEC_TABLE = 4;
    private static final int SEC_MEMORY = 5;
    private static final int SEC_EXPORT = 7;
    private static final int SEC_START = 8;
    private static final int SEC_ELEMENT = 9;
    private static final int SEC_DATA_COUNT = 12;
    private static final int SEC_CODE = 10;
    private static final int SEC_DATA = 11;
    private static final int SEC_TAG = 13;

    private static final int EXTERNAL_KIND_FUNCTION = 0x00;

    private static final int OPCODE_I32_CONST = 0x41;
    private static final int OPCODE_CALL = 0x10;
    private static final int OPCODE_END = 0x0b;
    private static final int OPCODE_THROW = 0x08;

    /** The one kind of tag there is: an exception. */
    private static final int TAG_EXCEPTION = 0x00;

    private static final int PAGE_BYTES = 65536;

    /**
     * The order the binary format puts sections in. A section this linker adds has to go where the
     * format says, not after whatever the runtime happened to write last. Tags go between memory
     * and globals.
     */
    private static final int[] BINARY_ORDER = {1, 2, 3, 4, 5, 13, 6, 7, 8, 9, 12, 10, 11};

    private Linker() {
    }

    /**
     * Links what a fragment holds onto the runtime it was written against.
     *
     * @param fragment the generated definitions, already numbered for the output
     * @return the linked module
     */
    public static byte[] link(WasmFragment fragment) {
        // What no export, no start and no table reaches is left out: the runtime carries every
        // kernel, and a program calls a few of them. Equal bodies are not folded: a linked module
        // has about one pair of them, seven bytes, and looking cost as much as the rest of the link.
        return WasmTreeShaker.withoutWhatNothingReaches(unshaken(fragment));
    }

    /**
     * What {@link #link} answers before what nothing reaches is left out: every function of the
     * runtime at the index the runtime gave it, the fragment's after them. For a measurement that
     * asks what keeps each runtime function, which the shake's renumbering would hide.
     *
     * @param fragment the generated definitions, already numbered for the output
     * @return the linked module, with all of the runtime in it
     */
    public static byte[] unshaken(WasmFragment fragment) {
        LinkPlan plan = fragment.plan();
        byte[] runtime = plan.runtime();
        RuntimeLayout layout = plan.layout();

        int thunkType = fragment.functionType(List.of(), List.of());
        int thunk = fragment.define(thunkType, startThunkBody(plan, layout, fragment.staticEnd()));

        Map<Integer, byte[]> sections = new LinkedHashMap<>();
        for (RawSection section : new LayoutReader(runtime).rawSections()) {
            if (sections.putIfAbsent(section.id(), section.payload()) != null) {
                throw new IllegalArgumentException(
                        "the runtime writes section " + section.id() + " twice, so what it holds is not one vector");
            }
        }
        if (sections.containsKey(SEC_TAG)) {
            throw new IllegalArgumentException(
                    "the runtime declares tags, which this linker neither numbers after nor keeps");
        }
        if (fragment.throwsEndings()) {
            throwingEndings(sections, plan);
        }

        sections.put(SEC_TYPE, appendEntries(sections.get(SEC_TYPE), fragment.typeEntries()));
        sections.put(SEC_FUNCTION, appendEntries(sections.get(SEC_FUNCTION), functionEntries(fragment)));
        sections.put(SEC_EXPORT, appendEntries(
                shownToTheHost(sections.get(SEC_EXPORT)), exportEntries(fragment)));
        sections.put(SEC_CODE, appendEntries(sections.get(SEC_CODE), codeEntries(fragment)));
        sections.put(SEC_DATA, appendEntries(sections.get(SEC_DATA), dataEntries(fragment)));
        sections.put(SEC_MEMORY, memoryHolding(sections.get(SEC_MEMORY), fragment.staticEnd()));
        if (!fragment.tableEntries().isEmpty()) {
            sections.put(SEC_TABLE, tableHolding(sections.get(SEC_TABLE),
                    fragment.firstSlot() + fragment.tableEntries().size()));
            sections.put(SEC_ELEMENT,
                    appendEntries(sections.get(SEC_ELEMENT), List.of(elementEntry(fragment))));
        }
        sections.put(SEC_START, startSection(thunk));
        if (layout.declaresDataCount()) {
            sections.put(SEC_DATA_COUNT, unsignedLeb(
                    layout.dataSegmentCount() + fragment.dataSegments().size()));
        }

        return assemble(sections, surface(fragment));
    }

    /**
     * Has a call the runtime ends leave by a throw rather than a trap.
     *
     * <p>The runtime leaves every call it ends through one function, {@link RuntimeAbi#END_CALL},
     * whose body traps. That one body is replaced by a throw under a tag this adds as {@link
     * WasmFragment#ENDED_TAG}; nothing else the runtime wrote is read or changed. The tag's type is
     * the function's own, which takes nothing and answers nothing: why the call ended is the
     * failure record's to say, and the throw only leaves the call.
     */
    private static void throwingEndings(Map<Integer, byte[]> sections, LinkPlan plan) {
        int defined = plan.functionIndexOf(RuntimeAbi.END_CALL) - plan.layout().importedFunctionCount();
        if (defined < 0) {
            throw new IllegalArgumentException(RuntimeAbi.END_CALL
                    + " is imported, so the runtime does not say how a call it ends is left");
        }
        Reading types = new Reading(sections.get(SEC_FUNCTION));
        types.unsigned();
        for (int i = 0; i < defined; i++) {
            types.unsigned();
        }
        int type = types.unsigned();

        ByteArrayOutputStream tags = new ByteArrayOutputStream();
        new WasmWriter(tags).writeUnsignedLeb128(1).write((byte) TAG_EXCEPTION).writeUnsignedLeb128(type);
        sections.put(SEC_TAG, tags.toByteArray());

        ByteArrayOutputStream thrown = new ByteArrayOutputStream();
        new WasmWriter(thrown)
                .writeUnsignedLeb128(0) // no locals
                .write((byte) OPCODE_THROW).writeUnsignedLeb128(WasmFragment.ENDED_TAG)
                .write((byte) OPCODE_END);
        sections.put(SEC_CODE, replacing(sections.get(SEC_CODE), defined, thrown.toByteArray()));
    }

    /** A code section with one entry's body replaced and every other entry copied across. */
    private static byte[] replacing(byte[] section, int entry, byte[] body) {
        Reading reading = new Reading(section);
        int count = reading.unsigned();
        if (entry >= count) {
            throw new IllegalArgumentException("the runtime's code holds no body " + entry);
        }
        int start = reading.position;
        for (int i = 0; i < entry; i++) {
            reading.bytes(reading.unsigned());
        }
        int before = reading.position;
        reading.bytes(reading.unsigned());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new WasmWriter(out)
                .writeUnsignedLeb128(count)
                .write(Arrays.copyOfRange(section, start, before))
                .writeUnsignedLeb128(body.length)
                .write(body)
                .write(reading.remaining());
        return out.toByteArray();
    }

    /**
     * The runtime's exports a host calls, and none of the rest.
     *
     * <p>Read entry by entry and kept by name, so an export the runtime adds is left out of a
     * linked module until {@link RuntimeAbi#HOST_EXPORTS} says a host calls it.
     */
    private static byte[] shownToTheHost(byte[] section) {
        Reading reading = new Reading(section);
        int count = reading.unsigned();
        List<byte[]> kept = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int start = reading.position;
            byte[] name = reading.bytes(reading.unsigned());
            reading.next();
            reading.unsigned();
            if (RuntimeAbi.HOST_EXPORTS.contains(new String(name, StandardCharsets.UTF_8))) {
                kept.add(Arrays.copyOfRange(section, start, reading.position));
            }
        }
        return appendEntries(new byte[] {0}, kept);
    }

    /**
     * The body of the thunk the start section names.
     *
     * <p>Nothing but what has to run before the first call: whatever the runtime already started
     * with, and then the arena's placement.
     */
    private static byte[] startThunkBody(LinkPlan plan, RuntimeLayout layout, int staticEnd) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(body);
        writer.writeUnsignedLeb128(0); // no locals
        layout.existingStart().ifPresent(existing -> writer
                .write((byte) OPCODE_CALL)
                .writeUnsignedLeb128(existing));
        writer.write((byte) OPCODE_I32_CONST)
                .writeSignedLeb128(staticEnd)
                .write((byte) OPCODE_CALL)
                .writeUnsignedLeb128(plan.functionIndexOf(RuntimeAbi.RUNTIME_INIT))
                .write((byte) OPCODE_END);
        return body.toByteArray();
    }

    private static List<byte[]> functionEntries(WasmFragment fragment) {
        List<byte[]> entries = new ArrayList<>();
        for (int typeIndex : fragment.functionTypeIndices()) {
            entries.add(unsignedLeb(typeIndex));
        }
        return entries;
    }

    private static List<byte[]> exportEntries(WasmFragment fragment) {
        List<byte[]> entries = new ArrayList<>();
        fragment.exportedFunctions().forEach((name, index) -> {
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
            new WasmWriter(entry)
                    .writeUnsignedLeb128(utf8.length)
                    .write(utf8)
                    .write((byte) EXTERNAL_KIND_FUNCTION)
                    .writeUnsignedLeb128(index);
            entries.add(entry.toByteArray());
        });
        return entries;
    }

    private static List<byte[]> codeEntries(WasmFragment fragment) {
        List<byte[]> entries = new ArrayList<>();
        for (byte[] body : fragment.functionBodies()) {
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            new WasmWriter(entry).writeUnsignedLeb128(body.length).write(body);
            entries.add(entry.toByteArray());
        }
        return entries;
    }

    private static List<byte[]> dataEntries(WasmFragment fragment) {
        List<byte[]> entries = new ArrayList<>();
        for (Segment segment : fragment.dataSegments()) {
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            new WasmWriter(entry)
                    .writeUnsignedLeb128(0) // active, in memory zero
                    .write((byte) OPCODE_I32_CONST)
                    .writeSignedLeb128(segment.address())
                    .write((byte) OPCODE_END)
                    .writeUnsignedLeb128(segment.bytes().length)
                    .write(segment.bytes());
            entries.add(entry.toByteArray());
        }
        return entries;
    }

    /**
     * The table, with room for the slots the link took after the ones the runtime already had.
     */
    private static byte[] tableHolding(byte[] section, int wanted) {
        if (section == null) {
            throw new IllegalArgumentException("the runtime declares no table for a link to fill");
        }
        Reading reading = new Reading(section);
        int count = reading.unsigned();
        if (count != 1) {
            throw new IllegalArgumentException("this linker fills one table, and the runtime has " + count);
        }
        int holds = reading.next();
        boolean bounded = reading.next() != 0;
        int minimum = reading.unsigned();
        // A toolchain writes the table with exactly the slots its own code needs, bound at that,
        // so the bound moves with the minimum. What the bound is for is stopping the table from
        // growing at run time, and nothing here grows one.
        int maximum = bounded ? reading.unsigned() : 0;
        int held = Math.max(minimum, wanted);

        ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(rewritten);
        writer.writeUnsignedLeb128(1)
                .write((byte) holds)
                .write((byte) (bounded ? 1 : 0))
                .writeUnsignedLeb128(held);
        if (bounded) {
            writer.writeUnsignedLeb128(Math.max(maximum, held));
        }
        return rewritten.toByteArray();
    }

    /** One active element segment, putting the link's functions at the slots it took. */
    private static byte[] elementEntry(WasmFragment fragment) {
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(entry);
        writer.writeUnsignedLeb128(0) // active, in table zero, holding functions
                .write((byte) OPCODE_I32_CONST)
                .writeSignedLeb128(fragment.firstSlot())
                .write((byte) OPCODE_END)
                .writeUnsignedLeb128(fragment.tableEntries().size());
        fragment.tableEntries().forEach(writer::writeUnsignedLeb128);
        return entry.toByteArray();
    }

    /**
     * The memory section, asking for enough pages to hold everything placed in it.
     *
     * <p>Growing later is not an answer here: what the link placed is written by the segments at
     * instantiation, and a segment past the initial size makes the instantiation fail rather than
     * reach any code that could have grown the memory.
     */
    private static byte[] memoryHolding(byte[] section, int staticEnd) {
        if (section == null) {
            throw new IllegalArgumentException("the runtime declares no memory for a link to place data in");
        }
        Reading reading = new Reading(section);
        int count = reading.unsigned();
        if (count != 1) {
            throw new IllegalArgumentException("this linker places data in one memory, and the runtime has " + count);
        }
        boolean bounded = reading.next() != 0;
        int minimum = reading.unsigned();
        int maximum = bounded ? reading.unsigned() : 0;

        int wanted = Math.max(minimum, (staticEnd + PAGE_BYTES - 1) / PAGE_BYTES);
        if (bounded && wanted > maximum) {
            throw new IllegalArgumentException(
                    "what the link placed needs more pages than the runtime's memory will grow to");
        }

        ByteArrayOutputStream rewritten = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(rewritten);
        writer.writeUnsignedLeb128(1).write((byte) (bounded ? 1 : 0)).writeUnsignedLeb128(wanted);
        if (bounded) {
            writer.writeUnsignedLeb128(maximum);
        }
        return rewritten.toByteArray();
    }

    private static byte[] startSection(int functionIndex) {
        return unsignedLeb(functionIndex);
    }

    /**
     * A counted vector with more entries after the ones it already held.
     *
     * <p>The count is read and written again; what follows it is copied across as bytes. That is
     * the whole of what this linker does to the code section, and the reason a runtime compiled by
     * a toolchain this project does not model can be linked at all.
     */
    private static byte[] appendEntries(byte[] section, List<byte[]> entries) {
        if (entries.isEmpty()) {
            return section;
        }
        int held = 0;
        byte[] rest = new byte[0];
        if (section != null) {
            Reading reading = new Reading(section);
            held = reading.unsigned();
            rest = reading.remaining();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(out);
        writer.writeUnsignedLeb128(held + entries.size()).write(rest);
        entries.forEach(writer::write);
        return out.toByteArray();
    }

    private static byte[] assemble(Map<Integer, byte[]> sections, byte[] surface) {
        ByteArrayOutputStream module = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(module);
        writer.write(new byte[] {0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00});
        for (int id : BINARY_ORDER) {
            byte[] payload = sections.get(id);
            if (payload == null) {
                continue;
            }
            writer.write((byte) id).writeUnsignedLeb128(payload.length).write(payload);
        }
        if (surface.length > 0) {
            writer.write((byte) SEC_CUSTOM).writeUnsignedLeb128(surface.length).write(surface);
        }
        return module.toByteArray();
    }

    /** A section carrying no code, whose meaning is its name. */
    private static final int SEC_CUSTOM = 0;

    /** What the program offers a caller, under the name a reader looks for it by. */
    private static final String SURFACE = "souther:surface";

    /** What the program offers a caller, written as a section of the module, where it said. */
    private static byte[] surface(WasmFragment fragment) {
        String held = fragment.surface();
        return held == null ? new byte[0] : custom(SURFACE, held);
    }

    /** A custom section: its name, then what it says. */
    private static byte[] custom(String named, String written) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(out);
        byte[] name = named.getBytes(StandardCharsets.UTF_8);
        writer.writeUnsignedLeb128(name.length).write(name)
                .write(written.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static byte[] unsignedLeb(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new WasmWriter(out).writeUnsignedLeb128(value);
        return out.toByteArray();
    }

    /** A position in a section's payload, for the little of it a linker reads. */
    private static final class Reading {

        private final byte[] payload;
        private int position;

        Reading(byte[] payload) {
            this.payload = payload;
        }

        int next() {
            return payload[position++] & 0xff;
        }

        int unsigned() {
            int value = 0;
            int shift = 0;
            while (true) {
                int b = next();
                value |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
        }

        byte[] bytes(int length) {
            byte[] held = Arrays.copyOfRange(payload, position, position + length);
            position += length;
            return held;
        }

        byte[] remaining() {
            return Arrays.copyOfRange(payload, position, payload.length);
        }
    }
}
