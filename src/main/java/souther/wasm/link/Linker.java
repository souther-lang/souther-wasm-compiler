package souther.wasm.link;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import souther.wasm.abi.RuntimeAbi;
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

    private static final int EXTERNAL_KIND_FUNCTION = 0x00;

    private static final int OPCODE_I32_CONST = 0x41;
    private static final int OPCODE_CALL = 0x10;
    private static final int OPCODE_END = 0x0b;

    private static final int PAGE_BYTES = 65536;

    /**
     * The order the binary format puts sections in. A section this linker adds has to go where the
     * format says, not after whatever the runtime happened to write last.
     */
    private static final int[] BINARY_ORDER = {1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 10, 11};

    private Linker() {
    }

    /**
     * Links what a fragment holds onto the runtime it was written against.
     *
     * @param fragment the generated definitions, already numbered for the output
     * @return the linked module
     */
    public static byte[] link(WasmFragment fragment) {
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

        sections.put(SEC_TYPE, appendEntries(sections.get(SEC_TYPE), fragment.typeEntries()));
        sections.put(SEC_FUNCTION, appendEntries(sections.get(SEC_FUNCTION), functionEntries(fragment)));
        sections.put(SEC_EXPORT, appendEntries(sections.get(SEC_EXPORT), exportEntries(fragment)));
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

        return assemble(sections, crossings(fragment));
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

    private static byte[] assemble(Map<Integer, byte[]> sections, byte[] crossings) {
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
        if (crossings.length > 0) {
            writer.write((byte) SEC_CUSTOM).writeUnsignedLeb128(crossings.length).write(crossings);
        }
        return module.toByteArray();
    }

    /** A section carrying no code, whose meaning is its name. */
    private static final int SEC_CUSTOM = 0;

    /** What the module reaches out for, under the name a reader looks for it by. */
    private static final String CROSSINGS = "souther:crossings";

    /**
     * What the module reaches out for, written as a section of the module.
     *
     * <p>A number is what a call out carries, so what the numbers are has to be said somewhere, and
     * the module is the only place a reader cannot be given the wrong one of. Written as one JSON
     * array, because a caller reading it is reading JSON already — a call's arguments and its
     * answer are both JSON, and this is the same reader.
     */
    private static byte[] crossings(WasmFragment fragment) {
        List<WasmFragment.Crossing> held = fragment.crossings();
        if (held.isEmpty()) {
            return new byte[0];
        }
        StringBuilder written = new StringBuilder("[");
        for (WasmFragment.Crossing crossing : held) {
            written.append(written.length() > 1 ? "," : "")
                    .append("{\"ordinal\":").append(crossing.ordinal())
                    .append(",\"behavior\":\"").append(crossing.behavior())
                    .append("\",\"implementedElsewhere\":").append(crossing.elsewhere())
                    .append("}");
        }
        byte[] payload = written.append("]").toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(out);
        byte[] name = CROSSINGS.getBytes(StandardCharsets.UTF_8);
        writer.writeUnsignedLeb128(name.length).write(name).write(payload);
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

        byte[] remaining() {
            return Arrays.copyOfRange(payload, position, payload.length);
        }
    }
}
