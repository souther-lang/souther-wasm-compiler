package souther.wasm.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.lower.WasmCompiler;

/**
 * What a typed interface could not write, refused when the offering is made rather than found out
 * by the reader handed the text: a type that holds itself, and one name given to two things in one
 * interface.
 */
class ATypedInterfaceRefusesWhatWitCannotWriteTest {

    @Test
    void refusesATypeThatHoldsItselfWhereItCrosses() {
        var program = Compiled.program(List.of("""
                module trees exposing ( Tree, size )

                data Tree = { label: String, children: List<Tree> }

                behavior size : (tree: Tree) -> Int

                let size (tree) = List.length(tree.children)
                """));

        assertThatThrownBy(() -> WasmCompiler.typedOffering(program))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trees.tree -> trees.tree")
                .hasMessageContaining("no recursive type");
    }

    @Test
    void writesAProgramWhoseTypeHoldsItselfWhereNothingCrossesWithIt() {
        var program = Compiled.program(List.of("""
                module trees exposing ( count )

                data Tree = { label: String, children: List<Tree> }

                behavior count : (n: Int) -> Int

                let count (n) = List.length([Tree { label = "a", children = [] }]) + n
                """));

        assertThat(TypedWitText.of(WasmCompiler.typedOffering(program)))
                .contains("%count: func(%n: s64) -> result<s64, %refusal>;")
                .doesNotContain("record %tree ");
    }

    @Test
    void refusesABehaviorNamedAsATypeItsInterfaceUses() {
        var program = Compiled.program(List.of("""
                module shapes exposing ( LineItem, line_item )

                data LineItem = { x: Int }

                behavior line_item : (x: Int) -> LineItem

                let line_item (x) = LineItem { x = x }
                """));

        assertThatThrownBy(() -> WasmCompiler.typedOffering(program))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("souther:typed-program/shapes")
                .hasMessageContaining("line-item");
    }

    @Test
    void refusesATypeNamedAsWhatARefusedCallAnswers() {
        var program = Compiled.program(List.of("""
                module tracker exposing ( Issue, opened )

                data Issue = { title: String }

                behavior opened : (title: String) -> Issue

                let opened (title) = Issue { title = title }
                """));

        assertThatThrownBy(() -> WasmCompiler.typedOffering(program))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("souther:typed-program/tracker")
                .hasMessageContaining("issue");
    }
}
