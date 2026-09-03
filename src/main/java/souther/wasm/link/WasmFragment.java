package souther.wasm.link;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import souther.wasm.emit.Type;
import souther.wasm.emit.WasmWriter;

/**
 * What a link adds to the runtime.
 *
 * <p>Not a module. A module's indices start at zero, and a generated definition's do not — they
 * start where the runtime's leave off — so what is collected here is already written in the
 * numbering the linked output will have. A body added to this may call a function added before it
 * by the index {@link #define} answered, and may load a constant from an address {@link #place}
 * answered, both of which are final.
 *
 * <p>Static data is placed before any body that reads it is written, because a body reads it by
 * address. That is why placing and defining are separate: a caller places everything a behavior
 * spells, and only then writes the bodies that point at it.
 */
public final class WasmFragment {

    private final LinkPlan plan;
    private final List<byte[]> types = new ArrayList<>();
    private final Map<String, Integer> typeIndices = new LinkedHashMap<>();
    private final List<Integer> functionTypes = new ArrayList<>();
    private final List<byte[]> bodies = new ArrayList<>();
    private final Map<String, Integer> exports = new LinkedHashMap<>();
    private final List<Segment> data = new ArrayList<>();
    private final Set<Integer> reserved = new LinkedHashSet<>();
    private int staticTop;

    /** A run of bytes the link places in static memory, at an address it has settled. */
    record Segment(int address, byte[] bytes) {
    }

    /**
     * Starts a fragment against a runtime.
     *
     * @param plan what the runtime occupies, which is what everything here is numbered after
     */
    public WasmFragment(LinkPlan plan) {
        this.plan = plan;
        this.staticTop = plan.staticBase();
    }

    /** The runtime this fragment is written against. */
    public LinkPlan plan() {
        return plan;
    }

    /**
     * The index of a function type, declaring it if the fragment has not already.
     *
     * <p>Two functions of one shape share an entry: a type is a shape rather than a name, and a
     * second entry for the same shape would be a second answer to one question.
     */
    public int functionType(List<Type> parameters, List<Type> results) {
        String shape = parameters + "->" + results;
        Integer existing = typeIndices.get(shape);
        if (existing != null) {
            return existing;
        }
        ByteArrayOutputStream entry = new ByteArrayOutputStream();
        WasmWriter writer = new WasmWriter(entry);
        writer.write((byte) 0x60).writeUnsignedLeb128(parameters.size());
        parameters.forEach(writer::write);
        writer.writeUnsignedLeb128(results.size());
        results.forEach(writer::write);
        int index = plan.firstGeneratedTypeIndex() + types.size();
        types.add(entry.toByteArray());
        typeIndices.put(shape, index);
        return index;
    }

    /**
     * Adds a function, and answers the index every call to it writes.
     *
     * @param typeIndex the shape, from {@link #functionType}
     * @param body the locals vector followed by the instructions and their {@code end}
     */
    public int define(int typeIndex, byte[] body) {
        int index = plan.firstGeneratedFunctionIndex() + bodies.size();
        functionTypes.add(typeIndex);
        bodies.add(body.clone());
        return index;
    }

    /**
     * Makes a function reachable from outside under a name.
     *
     * @param name the name a caller reaches it by
     * @param functionIndex what {@link #define} answered
     */
    public void export(String name, int functionIndex) {
        Integer already = exports.putIfAbsent(name, functionIndex);
        if (already != null) {
            throw new IllegalArgumentException("this fragment already exports " + name);
        }
    }

    /**
     * Places bytes in static memory and answers where they went.
     *
     * <p>Static, so they outlive the arena: what a caller reads after a call has returned is still
     * there when the arena is reset, and a constant a body loads is there before the first call.
     */
    public int place(byte[] bytes) {
        int address = staticTop;
        data.add(new Segment(address, bytes.clone()));
        staticTop = align(address + bytes.length);
        return address;
    }

    /**
     * Takes an address for bytes that are not settled yet, and answers where they will go.
     *
     * <p>For a value that has to know its own address before it can be written — a descriptor of a
     * type holding a value of itself is one. Nothing reads what is there until {@link #fill} puts
     * it there, and a link refuses to finish while anything reserved is still empty.
     */
    public int reserve(int length) {
        int address = staticTop;
        data.add(new Segment(address, new byte[length]));
        reserved.add(address);
        staticTop = align(address + length);
        return address;
    }

    /**
     * Puts the bytes of something reserved where they were promised.
     *
     * @param address what {@link #reserve} answered
     * @param bytes exactly as many as were reserved
     */
    public void fill(int address, byte[] bytes) {
        for (int i = 0; i < data.size(); i++) {
            Segment segment = data.get(i);
            if (segment.address() == address) {
                if (segment.bytes().length != bytes.length) {
                    throw new IllegalArgumentException(
                            "what was reserved at " + address + " is not as long as what was written for it");
                }
                data.set(i, new Segment(address, bytes.clone()));
                reserved.remove(address);
                return;
            }
        }
        throw new IllegalArgumentException("nothing was reserved at " + address);
    }

    /** The first byte no generated data occupies, which is where the arena will start. */
    public int staticEnd() {
        return staticTop;
    }

    List<byte[]> typeEntries() {
        return List.copyOf(types);
    }

    List<Integer> functionTypeIndices() {
        return List.copyOf(functionTypes);
    }

    List<byte[]> functionBodies() {
        return List.copyOf(bodies);
    }

    Map<String, Integer> exportedFunctions() {
        return Map.copyOf(exports);
    }

    List<Segment> dataSegments() {
        if (!reserved.isEmpty()) {
            throw new IllegalStateException(
                    "a link would place empty bytes where something was reserved: " + reserved);
        }
        return List.copyOf(data);
    }

    private static int align(int address) {
        return (address + 7) & ~7;
    }
}
