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
            default -> {
                // A leaf reads nothing and binds nothing. What is not named here is not something a
                // body of this backend's writes, and meeting one is refused where it is written.
            }
        }
    }
}
