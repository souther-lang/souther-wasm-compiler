package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import souther.compiler.core.BoundaryConstraint;
import souther.compiler.core.ValueShape;
import souther.compiler.types.TypeSymbol;
import souther.wasm.emit.WasmWriter;
import souther.wasm.link.WasmFragment;

/**
 * What a type's clauses are reported as at the boundary, written once in static memory for the
 * runtime to read when a value breaks one.
 *
 * <p>A value read from outside that breaks a clause is an issue, and which issue is the language's
 * to say (spec §decoder-error). A newtype's clause that is a standard constraint is reported as
 * that constraint — its code, its message key and its metadata — and every other clause as an
 * invariant violation naming the type's module, the type, and the clause where it has a name. What
 * a clause is as constraints is the checker's answer ({@link ValueShape.Invariant#projection()}),
 * written here as it was answered; the runtime renders it and decides nothing about it.
 *
 * <pre>
 * +0  u32 where the module's name is, u32 how long
 * +8  u32 how many clauses
 * +12 per clause: u32 where its name is, u32 how long (both nothing where it has none),
 *                 u32 where its constraints are, u32 how many, u32 whether they are the whole clause
 *
 * a constraint: u32 its rule, then four u32 its rule reads as it says
 * </pre>
 *
 * <p>A constraint is about the one value a newtype holds, so only a newtype's clauses carry any:
 * a product's are each the rule they are (spec §decoder-error).
 */
final class Clauses {

    /** {@code String.length(value) >= n}: {@code n}. */
    static final int RULE_MIN_LENGTH = 1;
    /** {@code String.length(value) <= n}: {@code n}. */
    static final int RULE_MAX_LENGTH = 2;
    /** {@code String.length(value) == n}: {@code n}. */
    static final int RULE_FIXED_LENGTH = 3;
    /** {@code String.matches(p, value)}: where {@code p} is written and how long, and where its
     *  machine's image is. */
    static final int RULE_PATTERN = 4;
    /** {@code value >= n} of an {@code Int}: {@code n}'s low word and its high one. */
    static final int RULE_MIN = 5;
    /** {@code value <= n} of an {@code Int}: {@code n}'s low word and its high one. */
    static final int RULE_MAX = 6;
    /** {@code value > 0} of an {@code Int}. */
    static final int RULE_POSITIVE = 7;
    /** {@code value >= 0} of an {@code Int}. */
    static final int RULE_NON_NEGATIVE = 8;
    /** {@code value >= n} of a {@code Decimal}: where {@code n} is written and how long. */
    static final int RULE_DECIMAL_MIN = 9;
    /** {@code value <= n} of a {@code Decimal}: where {@code n} is written and how long. */
    static final int RULE_DECIMAL_MAX = 10;
    /** {@code value > 0} of a {@code Decimal}. */
    static final int RULE_DECIMAL_POSITIVE = 11;
    /** {@code value >= 0} of a {@code Decimal}. */
    static final int RULE_DECIMAL_NON_NEGATIVE = 12;
    /** {@code List.length(value) >= 1}. */
    static final int RULE_NON_EMPTY = 13;
    /** {@code List.length(value) >= n}: {@code n}. */
    static final int RULE_MIN_SIZE = 14;
    /** {@code List.length(value) <= n}: {@code n}. */
    static final int RULE_MAX_SIZE = 15;
    /** {@code List.length(value) == n}: {@code n}. */
    static final int RULE_FIXED_SIZE = 16;
    /** {@code List.allDistinctBy(x -> x, value)}. */
    static final int RULE_UNIQUE = 17;
    /** {@code Map.size(value) >= 1}. */
    static final int RULE_MAP_NON_EMPTY = 18;
    /** {@code Map.size(value) >= n}: {@code n}. */
    static final int RULE_MAP_MIN_SIZE = 19;
    /** {@code Map.size(value) <= n}: {@code n}. */
    static final int RULE_MAP_MAX_SIZE = 20;

    private final WasmFragment fragment;
    private final Patterns patterns;

    Clauses(WasmFragment fragment, Patterns patterns) {
        this.fragment = fragment;
        this.patterns = patterns;
    }

    /**
     * Where the table of {@code name}'s clauses is, placing it.
     *
     * @param projected whether a clause may be reported as a constraint, which only a newtype's
     *     may
     */
    int of(TypeSymbol.AtModule name, List<ValueShape.Invariant> invariants, boolean projected) {
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(table);
        byte[] module = name.module().getBytes(StandardCharsets.UTF_8);
        out.writeLittleEndian4(fragment.intern(module))
                .writeLittleEndian4(module.length)
                .writeLittleEndian4(invariants.size());
        for (ValueShape.Invariant clause : invariants) {
            byte[] named = clause.name().map(each -> each.getBytes(StandardCharsets.UTF_8))
                    .orElse(new byte[0]);
            List<BoundaryConstraint> constraints = projected
                    ? clause.projection().constraints() : List.of();
            out.writeLittleEndian4(named.length == 0 ? 0 : fragment.intern(named))
                    .writeLittleEndian4(named.length)
                    .writeLittleEndian4(constraints.isEmpty() ? 0 : constraints(constraints))
                    .writeLittleEndian4(constraints.size())
                    .writeLittleEndian4(projected && clause.projection().complete() ? 1 : 0);
        }
        return fragment.place(table.toByteArray());
    }

    private int constraints(List<BoundaryConstraint> constraints) {
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(table);
        for (BoundaryConstraint constraint : constraints) {
            int[] words = switch (constraint) {
                case BoundaryConstraint.MinLength it -> new int[] {RULE_MIN_LENGTH, it.n(), 0, 0, 0};
                case BoundaryConstraint.MaxLength it -> new int[] {RULE_MAX_LENGTH, it.n(), 0, 0, 0};
                case BoundaryConstraint.FixedLength it ->
                        new int[] {RULE_FIXED_LENGTH, it.n(), 0, 0, 0};
                case BoundaryConstraint.Pattern it -> {
                    byte[] written = it.written().getBytes(StandardCharsets.UTF_8);
                    yield new int[] {RULE_PATTERN, fragment.intern(written), written.length,
                            patterns.of(it.meaning(), it.written()), 0};
                }
                case BoundaryConstraint.Min it -> wide(RULE_MIN, it.n());
                case BoundaryConstraint.Max it -> wide(RULE_MAX, it.n());
                case BoundaryConstraint.Positive it -> new int[] {RULE_POSITIVE, 0, 0, 0, 0};
                case BoundaryConstraint.NonNegative it ->
                        new int[] {RULE_NON_NEGATIVE, 0, 0, 0, 0};
                case BoundaryConstraint.DecimalMin it -> amount(RULE_DECIMAL_MIN, it.n());
                case BoundaryConstraint.DecimalMax it -> amount(RULE_DECIMAL_MAX, it.n());
                case BoundaryConstraint.DecimalPositive it ->
                        new int[] {RULE_DECIMAL_POSITIVE, 0, 0, 0, 0};
                case BoundaryConstraint.DecimalNonNegative it ->
                        new int[] {RULE_DECIMAL_NON_NEGATIVE, 0, 0, 0, 0};
                case BoundaryConstraint.NonEmpty it -> new int[] {RULE_NON_EMPTY, 0, 0, 0, 0};
                case BoundaryConstraint.MinSize it -> new int[] {RULE_MIN_SIZE, it.n(), 0, 0, 0};
                case BoundaryConstraint.MaxSize it -> new int[] {RULE_MAX_SIZE, it.n(), 0, 0, 0};
                case BoundaryConstraint.FixedSize it -> new int[] {RULE_FIXED_SIZE, it.n(), 0, 0, 0};
                case BoundaryConstraint.Unique it -> new int[] {RULE_UNIQUE, 0, 0, 0, 0};
                case BoundaryConstraint.MapNonEmpty it ->
                        new int[] {RULE_MAP_NON_EMPTY, 0, 0, 0, 0};
                case BoundaryConstraint.MapMinSize it ->
                        new int[] {RULE_MAP_MIN_SIZE, it.n(), 0, 0, 0};
                case BoundaryConstraint.MapMaxSize it ->
                        new int[] {RULE_MAP_MAX_SIZE, it.n(), 0, 0, 0};
            };
            for (int word : words) {
                out.writeLittleEndian4(word);
            }
        }
        return fragment.place(table.toByteArray());
    }

    private static int[] wide(int rule, long n) {
        return new int[] {rule, (int) n, (int) (n >>> 32), 0, 0};
    }

    private int[] amount(int rule, BigDecimal n) {
        byte[] written = n.toPlainString().getBytes(StandardCharsets.UTF_8);
        return new int[] {rule, fragment.intern(written), written.length, 0, 0};
    }
}
