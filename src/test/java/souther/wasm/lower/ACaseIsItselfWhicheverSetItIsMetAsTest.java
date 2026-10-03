package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * A case is told apart from every other by what it is, whichever set of alternatives it is met as.
 *
 * <p>A collection made where its members were a union of a few cases holds that union's
 * descriptor, and keeps it when the collection is later held as the whole sum. A case the union
 * does not list then meets the collection: put in, looked for, taken out. Found by its place in
 * the union's descriptor, it had none, and was taken for the union's first case — found where it
 * was not, a key it was not, or the end of the call on a name it could not read. Each one here is
 * a case the collection's own set does not list, beside one it does.
 */
class ACaseIsItselfWhicheverSetItIsMetAsTest {

    private static final String SOURCE = """
            module cases

            data Level = Zeta | Eta | Delta | Gamma | Beta | Alpha

            behavior inSet : (r: Level) -> List<Bool>

            let inSet (r) = {
                let few = Set.fromList([Alpha, Beta, Gamma, Delta, Eta])
                [Set.contains(r, few),
                 Set.size(Set.insert(r, few)) == 5,
                 Set.size(Set.remove(r, few)) == 4]
            }

            behavior inList : (r: Level) -> Bool

            let inList (r) = {
                let few = [Alpha, Beta, Gamma, Delta, Eta]
                List.contains(r, few)
            }

            behavior inMap : (r: Level) -> List<Int>

            let inMap (r) = {
                let few = Map.fromList([(Alpha, 1), (Beta, 2), (Gamma, 3), (Delta, 4), (Eta, 5)])
                [Map.size(Map.insert(r, 0, few)),
                 Option.withDefault(-1, Map.get(r, Map.insert(r, 9, few))),
                 if Map.containsKey(r, few) then 1 else 0,
                 Map.size(Map.remove(r, few))]
            }

            behavior grown : (r: Level) -> List<Int>

            let grown (r) = {
                let few = List.fold((m, k) -> Map.insert(k, 1, m), Map.empty,
                    [Alpha, Beta, Gamma, Delta, Eta])
                [Map.size(Map.insert(r, 0, few)), if Map.containsKey(r, few) then 1 else 0]
            }

            behavior one : (r: Level) -> List<Int>

            let one (r) = {
                let only = Set.fromList([Alpha])
                let keyed = Map.fromList([(Alpha, 1)])
                [if Set.contains(r, only) then 1 else 0,
                 Set.size(Set.insert(r, only)),
                 Map.size(Map.insert(r, 0, keyed))]
            }

            data Note = { text: String }

            data Mark = Tick | Cross | Note

            behavior carried : (m: Mark) -> List<Bool>

            let carried (m) = {
                let few = Set.fromList([Tick, Note { text = "a" }])
                [Set.contains(m, few), Set.size(Set.insert(m, few)) == 2]
            }

            behavior noted : (m: Mark) -> List<Int>

            let noted (m) = {
                let only = Set.fromList([Note { text = "a" }])
                [if Set.contains(m, only) then 1 else 0, Set.size(Set.insert(m, only))]
            }
            """;

    @Test
    void aCaseTheCollectionsOwnSetDoesNotListIsNotTakenForOneItDoes() {
        Running module = compiled();

        // Alpha is the first case the union lists, and Zeta the one it does not.
        assertThat(answerOf(module, "cases.inSet", "[\"Alpha\"]")).isEqualTo(values(true, true, true));
        assertThat(answerOf(module, "cases.inSet", "[\"Zeta\"]"))
                .isEqualTo(values(false, false, false));
        assertThat(answerOf(module, "cases.inList", "[\"Alpha\"]")).isEqualTo("{\"value\":true}");
        assertThat(answerOf(module, "cases.inList", "[\"Zeta\"]")).isEqualTo("{\"value\":false}");
        assertThat(answerOf(module, "cases.inMap", "[\"Alpha\"]"))
                .isEqualTo("{\"value\":[5,9,1,4]}");
        assertThat(answerOf(module, "cases.inMap", "[\"Zeta\"]"))
                .isEqualTo("{\"value\":[6,9,0,5]}");
    }

    /**
     * A collection made of one case holds that case's own descriptor, which describes no other: a
     * unit's says every value of it is one, and a shape's says where its fields are. Another case
     * read by it was the one already there, or a cell read for fields it does not have.
     */
    @Test
    void aCollectionOfOneCaseIsNotTakenToHoldEveryOther() {
        Running module = compiled();

        assertThat(answerOf(module, "cases.one", "[\"Alpha\"]")).isEqualTo("{\"value\":[1,1,1]}");
        assertThat(answerOf(module, "cases.one", "[\"Eta\"]")).isEqualTo("{\"value\":[0,2,2]}");
        assertThat(answerOf(module, "cases.noted", "[{\"type\":\"Note\",\"text\":\"a\"}]"))
                .isEqualTo("{\"value\":[1,1]}");
        assertThat(answerOf(module, "cases.noted", "[{\"type\":\"Note\",\"text\":\"b\"}]"))
                .isEqualTo("{\"value\":[0,2]}");
        assertThat(answerOf(module, "cases.noted", "[{\"type\":\"Tick\"}]"))
                .isEqualTo("{\"value\":[0,2]}");
    }

    /** A map grown one entry at a time is grown by its keys' hashes, which are asked of the keys. */
    @Test
    void aMapGrownByAWalkHashesACaseAsItself() {
        Running module = compiled();

        assertThat(answerOf(module, "cases.grown", "[\"Alpha\"]")).isEqualTo("{\"value\":[5,1]}");
        assertThat(answerOf(module, "cases.grown", "[\"Zeta\"]")).isEqualTo("{\"value\":[6,0]}");
    }

    /** A case that carries something is read by its own descriptor, not by the set's first case. */
    @Test
    void aSumWhoseCasesCarrySomethingTellsThemApartTheSameWay() {
        Running module = compiled();

        assertThat(answerOf(module, "cases.carried", "[{\"type\":\"Tick\"}]"))
                .isEqualTo(values(true, true));
        assertThat(answerOf(module, "cases.carried", "[{\"type\":\"Cross\"}]"))
                .isEqualTo(values(false, false));
    }

    private static String values(boolean... each) {
        StringBuilder written = new StringBuilder("{\"value\":[");
        for (int i = 0; i < each.length; i++) {
            written.append(i == 0 ? "" : ",").append(each[i]);
        }
        return written.append("]}").toString();
    }

    private static Running compiled() {
        return Running.linked(Compiled.module(Compiled.program(List.of(SOURCE))));
    }

    private static String answerOf(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(
                export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
