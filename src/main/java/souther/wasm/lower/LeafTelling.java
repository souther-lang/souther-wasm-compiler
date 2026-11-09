package souther.wasm.lower;

/**
 * How a compile tells a value's leaf from the others, where a {@code match} or a composition asks
 * which of some leaves it is.
 *
 * <p>The answer is the same whichever it is: the leaf a value was made as, and the arm that leaf is
 * in. What differs is what it costs, and which is cheaper depends on how many leaves there are and
 * on the engine the module runs in. So which to use is decided by a rule ({@link #BY_COST}), and each
 * way can be asked for outright, so that each is held to the answer on its own and no test of one
 * depends on where the rule changes its mind.
 */
public enum LeafTelling {

    /** Whichever the rule says costs less: what a compile does unless it is asked otherwise. */
    BY_COST,

    /** By comparing what the value was made as with each leaf, and an arm's chain in turn. */
    COMPARING,

    /** By one lookup of the value's leaf in a table, then the arms asked in turn for the number it gave. */
    LOOKING_UP,

    /** By one lookup of the value's leaf in a table, then one jump to the arm it gave. */
    JUMPING
}
