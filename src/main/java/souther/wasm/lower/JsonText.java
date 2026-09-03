package souther.wasm.lower;

/**
 * A Souther value as the text a caller reads.
 *
 * <p>Written here rather than by a JSON library so that what crosses the boundary is decided by
 * Souther's own account of a value's external representation and not by a library's defaults for
 * a Java object that happens to be carrying it.
 */
final class JsonText {

    private JsonText() {
    }

    /** A string, quoted and escaped as JSON has it. */
    static String of(String value) {
        StringBuilder text = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\n' -> text.append("\\n");
                case '\r' -> text.append("\\r");
                case '\t' -> text.append("\\t");
                case '\b' -> text.append("\\b");
                case '\f' -> text.append("\\f");
                default -> {
                    if (c < 0x20) {
                        text.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        text.append(c);
                    }
                }
            }
        }
        return text.append('"').toString();
    }

    /** An {@code Int}, which is sixty-four bits and written as the whole number it is. */
    static String of(long value) {
        return Long.toString(value);
    }

    /** A {@code Bool}. */
    static String of(boolean value) {
        return Boolean.toString(value);
    }
}
