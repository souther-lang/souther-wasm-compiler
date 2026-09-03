package souther.wasm.lower;

/**
 * What this compiler was handed and cannot yet write as wasm.
 *
 * <p>Separate from a malformed input: the program is a checked one, and what it holds is something
 * the JVM backend emits today and this one does not. So it names what it met rather than saying
 * the program is wrong, and a caller can tell a gap in this backend from a gap in the program.
 */
public final class NotLowered extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NotLowered(String what) {
        super(what);
    }
}
