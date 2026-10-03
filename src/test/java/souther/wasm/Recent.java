package souther.wasm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The last few answers to a question, kept so that asking it again soon costs nothing.
 *
 * <p>The few and not all of them. What the tests ask again is what the test beside them just
 * asked — the same program run against another input — and a parsed module is over a megabyte, so
 * keeping every one ever asked for keeps the suite's whole history alive to save nothing: a suite
 * that kept them all ran out of a heap develop's fits in.
 */
public final class Recent<K, V> {

    private final Map<K, V> held;

    /** @param size how many answers to keep, the most recently asked */
    public Recent(int size) {
        this.held = new LinkedHashMap<>(size * 2, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > size;
            }
        };
    }

    /**
     * The answer for {@code key}, worked out by {@code answer} where it is not kept.
     *
     * @param kept what to keep it under, for a key whose own form must not be held — bytes the
     *     caller may write into afterwards
     */
    public synchronized V of(K key, Function<K, K> kept, Function<K, V> answer) {
        V already = held.get(key);
        if (already != null) {
            return already;
        }
        V answered = answer.apply(key);
        held.put(kept.apply(key), answered);
        return answered;
    }
}
