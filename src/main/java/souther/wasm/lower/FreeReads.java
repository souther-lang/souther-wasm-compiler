package souther.wasm.lower;

import java.util.LinkedHashSet;
import java.util.Set;
import souther.compiler.core.Core;
import souther.compiler.types.BindingId;

/**
 * What a block reads that something around it bound.
 *
 * <p>A block written where a value goes leaves the body it was written in, so what it reads from
 * there has to travel with it. This is what has to: every read whose binding the block does not
 * make itself.
 */
final class FreeReads {

    private FreeReads() {
    }

    /** The bindings an expression reads and does not bind. */
    static Set<BindingId> of(Core expression) {
        Set<BindingId> read = new LinkedHashSet<>();
        Set<BindingId> bound = new LinkedHashSet<>();
        walk(expression, read, bound);
        read.removeAll(bound);
        return read;
    }

    private static void walk(Core expression, Set<BindingId> read, Set<BindingId> bound) {
        switch (expression) {
            case Core.Read each -> read.add(each.binding());
            case Core.LetIn each -> {
                bound.add(each.binder().binding());
                walk(each.value(), read, bound);
                walk(each.body(), read, bound);
            }
            case Core.Block each -> {
                each.params().forEach(binder -> bound.add(binder.binding()));
                walk(each.body(), read, bound);
            }
            case Core.Match each -> {
                walk(each.scrutinee(), read, bound);
                for (Core.Case arm : each.cases()) {
                    if (arm.binder() != null) {
                        bound.add(arm.binder().binding());
                    }
                    walk(arm.body(), read, bound);
                }
            }
            case Core.Call each -> each.args().forEach(argument -> walk(argument, read, bound));
            case Core.Apply each -> {
                walk(each.fn(), read, bound);
                each.args().forEach(argument -> walk(argument, read, bound));
            }
            case Core.Binary each -> {
                walk(each.left(), read, bound);
                walk(each.right(), read, bound);
            }
            case Core.If each -> {
                walk(each.cond(), read, bound);
                walk(each.then(), read, bound);
                walk(each.els(), read, bound);
            }
            case Core.Neg each -> walk(each.operand(), read, bound);
            case Core.FieldAccess each -> walk(each.target(), read, bound);
            case Core.Construct each ->
                    each.values().forEach(field -> walk(field.value(), read, bound));
            case Core.ListLit each -> each.elements().forEach(element -> walk(element, read, bound));
            case Core.OptionSome each -> walk(each.value(), read, bound);
            case Core.Tuple each -> each.elements().forEach(element -> walk(element, read, bound));
            case Core.TupleGet each -> walk(each.tuple(), read, bound);
            case Core.IfConstructed each -> {
                walk(each.construct(), read, bound);
                bound.add(each.binder().binding());
                walk(each.then(), read, bound);
                each.els().forEach(arm -> walk(arm.body(), read, bound));
            }
            // The leaves, named rather than left to a default. What a block reads has to travel
            // with it, and a walk that treats what it does not know as a leaf leaves a read behind
            // — the block is written, the read is not bound where it lands, and what says so is
            // the emitter meeting a binding nothing put anywhere. So the walk refuses instead, at
            // the one place that can still say which expression it was.
            case Core.Int ignored -> {
            }
            case Core.Bool ignored -> {
            }
            case Core.Str ignored -> {
            }
            case Core.Decimal ignored -> {
            }
            case Core.Temporal ignored -> {
            }
            case Core.OptionNone ignored -> {
            }
            case Core.UnitValue ignored -> {
            }
            case Core.Unreachable ignored -> {
            }
            default -> throw new NotLowered(expression.getClass().getSimpleName()
                    + " is written into a block, and what it reads is not walked for");
        }
    }
}
