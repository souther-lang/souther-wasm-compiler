package souther.wasm.abi;

import java.util.Optional;

/**
 * What a parsed JSON cell is, before anything decides which Souther type reads it.
 *
 * <p>A number is the digits that were written rather than a number this reader chose to parse them
 * into. {@code 1} and {@code 1.0} are one amount written two ways, and which was written is what a
 * {@code Decimal} reads back with, so the text is carried until something that knows the declared
 * type asks for it.
 */
public enum JsonTag {

    /** {@code null}. */
    NULL(0),
    /** {@code false}. */
    FALSE(1),
    /** {@code true}. */
    TRUE(2),
    /** A number, whose bytes are the digits as they were written. */
    NUMBER(3),
    /** A string, whose bytes are its unescaped UTF-8. */
    STRING(4),
    /** An array, whose length is how many elements it has. */
    ARRAY(5),
    /** An object, whose length is how many entries it has. */
    OBJECT(6);

    private final int tag;

    JsonTag(int tag) {
        this.tag = tag;
    }

    /** The number the runtime writes for this kind. */
    public int tag() {
        return tag;
    }

    /** The kind a number names, where this reader knows it. */
    public static Optional<JsonTag> of(int tag) {
        for (JsonTag kind : values()) {
            if (kind.tag == tag) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
