package souther.wasm.link;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import souther.wasm.link.RuntimeLayout.Export;
import souther.wasm.link.RuntimeLayout.ExportKind;

/**
 * Walks a module's sections far enough to answer {@link RuntimeLayout}, and no further.
 *
 * <p>Every section is framed by an id and a byte length, so the walk skips what it does not read
 * by its length rather than by understanding it. The code section is skipped that way, which is
 * what lets the runtime be compiled by a toolchain this repository does not model.
 */
final class LayoutReader {

    private static final int SEC_TYPE = 1;
    private static final int SEC_IMPORT = 2;
    private static final int SEC_FUNCTION = 3;
    private static final int SEC_TABLE = 4;
    private static final int SEC_MEMORY = 5;
    private static final int SEC_GLOBAL = 6;
    private static final int SEC_EXPORT = 7;
    private static final int SEC_START = 8;
    private static final int SEC_DATA_COUNT = 12;
    private static final int SEC_DATA = 11;

    private static final int KIND_FUNCTION = 0x00;
    private static final int KIND_TABLE = 0x01;
    private static final int KIND_MEMORY = 0x02;
    private static final int KIND_GLOBAL = 0x03;

    private static final int OPCODE_I32_CONST = 0x41;
    private static final int OPCODE_END = 0x0b;

    private final byte[] module;

    LayoutReader(byte[] module) {
        this.module = module;
    }

    RuntimeLayout read() {
        int typeCount = 0;
        int importedFunctions = 0;
        int importedGlobals = 0;
        int importedTables = 0;
        int definedFunctions = 0;
        int definedGlobals = 0;
        int definedTables = 0;
        int memoryMinimum = 0;
        int tableMinimum = 0;
        OptionalInt memoryMaximum = OptionalInt.empty();
        int dataSegments = 0;
        boolean declaresDataCount = false;
        OptionalInt start = OptionalInt.empty();
        Map<String, Export> exports = new LinkedHashMap<>();

        for (Section section : sections()) {
            Cursor at = new Cursor(section.start());
            switch (section.id()) {
                case SEC_TYPE -> typeCount = at.readUnsigned();
                case SEC_IMPORT -> {
                    int count = at.readUnsigned();
                    for (int i = 0; i < count; i++) {
                        at.skipName();
                        at.skipName();
                        switch (at.readByte()) {
                            case KIND_FUNCTION -> {
                                importedFunctions++;
                                at.readUnsigned();
                            }
                            case KIND_TABLE -> {
                                importedTables++;
                                at.readByte();
                                at.skipLimits();
                            }
                            case KIND_MEMORY -> at.skipLimits();
                            case KIND_GLOBAL -> {
                                importedGlobals++;
                                at.readByte();
                                at.readByte();
                            }
                            default -> throw malformed("an import of a kind no module declares");
                        }
                    }
                }
                case SEC_FUNCTION -> definedFunctions = at.readUnsigned();
                case SEC_TABLE -> {
                    definedTables = at.readUnsigned();
                    for (int i = 0; i < definedTables; i++) {
                        at.readByte(); // what the table holds
                        boolean bounded = at.readByte() != 0;
                        tableMinimum = at.readUnsigned();
                        if (bounded) {
                            at.readUnsigned();
                        }
                    }
                }
                case SEC_MEMORY -> {
                    int count = at.readUnsigned();
                    for (int i = 0; i < count; i++) {
                        boolean bounded = at.readByte() != 0;
                        memoryMinimum = at.readUnsigned();
                        memoryMaximum = bounded ? OptionalInt.of(at.readUnsigned()) : OptionalInt.empty();
                    }
                }
                case SEC_GLOBAL -> definedGlobals = at.readUnsigned();
                case SEC_EXPORT -> {
                    int count = at.readUnsigned();
                    for (int i = 0; i < count; i++) {
                        String name = at.readName();
                        ExportKind kind = switch (at.readByte()) {
                            case KIND_FUNCTION -> ExportKind.FUNCTION;
                            case KIND_TABLE -> ExportKind.TABLE;
                            case KIND_MEMORY -> ExportKind.MEMORY;
                            case KIND_GLOBAL -> ExportKind.GLOBAL;
                            default -> throw malformed("an export of a kind no module declares");
                        };
                        exports.put(name, new Export(kind, at.readUnsigned()));
                    }
                }
                case SEC_START -> start = OptionalInt.of(at.readUnsigned());
                case SEC_DATA_COUNT -> {
                    declaresDataCount = true;
                    dataSegments = at.readUnsigned();
                }
                case SEC_DATA -> {
                    if (!declaresDataCount) {
                        dataSegments = at.readUnsigned();
                    }
                }
                default -> {
                    // Framed by its length, and nothing here reads it — the code section above all.
                }
            }
        }

        return new RuntimeLayout(
                typeCount,
                importedFunctions,
                definedFunctions,
                importedGlobals + definedGlobals,
                importedTables + definedTables,
                memoryMinimum,
                memoryMaximum,
                tableMinimum,
                dataSegments,
                declaresDataCount,
                start,
                Map.copyOf(exports));
    }

    /**
     * The constant a defined global is initialised to.
     *
     * <p>Only an {@code i32.const} is read. A global whose initialiser is anything else is not
     * something this linker can place data against, and answering some other number for it would
     * put the arena where a value already lives.
     */
    int globalInitialiser(int globalIndex) {
        int imported = 0;
        for (Section section : sections()) {
            if (section.id() != SEC_IMPORT) {
                continue;
            }
            Cursor at = new Cursor(section.start());
            int count = at.readUnsigned();
            for (int i = 0; i < count; i++) {
                at.skipName();
                at.skipName();
                switch (at.readByte()) {
                    case KIND_FUNCTION -> at.readUnsigned();
                    case KIND_TABLE -> {
                        at.readByte();
                        at.skipLimits();
                    }
                    case KIND_MEMORY -> at.skipLimits();
                    case KIND_GLOBAL -> {
                        imported++;
                        at.readByte();
                        at.readByte();
                    }
                    default -> throw malformed("an import of a kind no module declares");
                }
            }
        }
        if (globalIndex < imported) {
            throw new IllegalArgumentException(
                    "global " + globalIndex + " is imported, so this module does not say what it holds");
        }

        for (Section section : sections()) {
            if (section.id() != SEC_GLOBAL) {
                continue;
            }
            Cursor at = new Cursor(section.start());
            int count = at.readUnsigned();
            for (int i = 0; i < count; i++) {
                at.readByte(); // value type
                at.readByte(); // mutability
                int value = at.readConstantI32();
                if (imported + i == globalIndex) {
                    return value;
                }
            }
        }
        throw new IllegalArgumentException("this module defines no global " + globalIndex);
    }

    /**
     * The first byte past every active data segment the runtime places.
     *
     * <p>Read from the segments themselves rather than from a symbol the toolchain exported, so
     * that a linker placing its own data after the runtime's has a bound it derived and not one it
     * was told. A passive segment is at no address and bounds nothing.
     */
    int activeDataEnd() {
        int end = 0;
        for (Section section : sections()) {
            if (section.id() != SEC_DATA) {
                continue;
            }
            Cursor at = new Cursor(section.start());
            int count = at.readUnsigned();
            for (int i = 0; i < count; i++) {
                int flags = at.readUnsigned();
                boolean active = flags == 0 || flags == 2;
                if (flags == 2) {
                    at.readUnsigned(); // the memory it is placed in
                }
                int offset = active ? at.readConstantI32() : 0;
                int length = at.readUnsigned();
                at.position += length;
                if (active) {
                    end = Math.max(end, offset + length);
                }
            }
        }
        return end;
    }

    /**
     * Every section, in the order the module wrote them, as an id and the bytes it framed.
     *
     * <p>The bytes are handed over unread. A linker rewrites the vector of a section it appends to
     * by putting its own entries after these, which needs the count at the front and nothing else
     * of what follows it — the code section above all, whose entries are the bodies this project
     * does not model.
     */
    List<RawSection> rawSections() {
        List<RawSection> raw = new ArrayList<>();
        for (Section section : sections()) {
            raw.add(new RawSection(
                    section.id(),
                    Arrays.copyOfRange(module, section.start(), section.end())));
        }
        return raw;
    }

    /** A section as it stands in a module: what it is, and the bytes it frames. */
    record RawSection(int id, byte[] payload) {
    }

    private record Section(int id, int start, int end) {
    }

    private List<Section> sections() {
        if (module.length < 8) {
            throw malformed("a module shorter than its own header");
        }
        List<Section> sections = new ArrayList<>();
        Cursor at = new Cursor(8);
        while (at.position < module.length) {
            int id = at.readByte();
            int length = at.readUnsigned();
            int start = at.position;
            sections.add(new Section(id, start, start + length));
            at.position = start + length;
            if (at.position > module.length) {
                throw malformed("a section running past the end of the module");
            }
        }
        return sections;
    }

    private static IllegalArgumentException malformed(String what) {
        return new IllegalArgumentException("this is not a wasm module a layout can be read from: " + what);
    }

    /** A position in the module, and the readings that move it. */
    private final class Cursor {

        private int position;

        Cursor(int position) {
            this.position = position;
        }

        int readByte() {
            return module[position++] & 0xff;
        }

        int readUnsigned() {
            int value = 0;
            int shift = 0;
            while (true) {
                int b = readByte();
                value |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
        }

        int readSigned() {
            int value = 0;
            int shift = 0;
            int b;
            do {
                b = readByte();
                value |= (b & 0x7f) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            if (shift < 32 && (b & 0x40) != 0) {
                value |= -(1 << shift);
            }
            return value;
        }

        String readName() {
            int length = readUnsigned();
            String name = new String(module, position, length, StandardCharsets.UTF_8);
            position += length;
            return name;
        }

        void skipName() {
            int length = readUnsigned();
            position += length;
        }

        void skipLimits() {
            boolean bounded = readByte() != 0;
            readUnsigned();
            if (bounded) {
                readUnsigned();
            }
        }

        int readConstantI32() {
            if (readByte() != OPCODE_I32_CONST) {
                throw new IllegalArgumentException(
                        "a global this linker places data against is initialised by something other "
                                + "than an i32 constant");
            }
            int value = readSigned();
            if (readByte() != OPCODE_END) {
                throw new IllegalArgumentException("a constant expression that does not end after its constant");
            }
            return value;
        }
    }
}
