package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import souther.wasm.Compiled;
import souther.wasm.Running;

/**
 * Which arm a value takes is the arm its leaf is in, however many leaves there are and however many
 * arms they are in, and however the leaf is told (issue #77): by comparing, by a lookup and the arms
 * asked in turn, by a lookup and one jump, or by whichever the rule says costs less.
 *
 * <p>Each way is asked for outright, so that none is held to the answer only where the rule happens
 * to choose it: a test of a way that depended on where the rule changes its mind would stop testing
 * it, with nothing failing, the day the rule moved. What the rule chooses is pinned apart, below.
 */
class AMatchTakesTheArmOfItsLeafHoweverItIsToldTest {

    @Test
    void everyWayOfTellingGivesEveryLeafOfRandomlyCutSumsTheArmItIsIn() {
        Random random = new Random(2024);
        int[] sizes = {2, 3, 9, 35, 90};
        for (int leaves : sizes) {
            for (int round = 0; round < 2; round++) {
                int arms = 2 + random.nextInt(Math.min(leaves, 40) - 1);
                List<Integer> order = new ArrayList<>(IntStream.range(0, leaves).boxed().toList());
                Collections.shuffle(order, random);
                // Each leaf to an arm, every arm with at least one.
                int[] armOf = new int[leaves];
                for (int i = 0; i < leaves; i++) {
                    armOf[order.get(i)] = i < arms ? i : random.nextInt(arms);
                }
                String source = cut(leaves, arms, armOf);
                for (LeafTelling telling : LeafTelling.values()) {
                    for (int leaf = 0; leaf < leaves; leaf++) {
                        assertThat(answer(module(source, telling), "cut.which", "[\"L" + leaf + "\"]"))
                                .as("%s, %d leaves in %d arms, leaf %d", telling, leaves, arms, leaf)
                                .isEqualTo("{\"value\":" + (armOf[leaf] + 1) + "}");
                    }
                }
            }
        }
    }

    /**
     * More arms than the rule compares: every one of them, the first, the middle, the last but one,
     * and the last, which is no arm tested but what no other takes. By a jump, that is where the
     * lookup finds nothing and the jump goes to its default.
     */
    @Test
    void aMatchOverManyArmsTakesEachOfThem() {
        int leaves = 130;
        int[] armOf = IntStream.range(0, leaves).toArray();
        String source = cut(leaves, leaves, armOf);

        for (LeafTelling telling : List.of(LeafTelling.BY_COST, LeafTelling.JUMPING)) {
            Running module = module(source, telling);
            for (int leaf = 0; leaf < leaves; leaf++) {
                assertThat(answer(module, "cut.which", "[\"L" + leaf + "\"]"))
                        .as("%s, leaf %d", telling, leaf).isEqualTo("{\"value\":" + (leaf + 1) + "}");
            }
        }
    }

    /** The last arm over several leaves: each is in no tested arm, so each is where the lookup says nothing. */
    @Test
    void theLastArmOfAManyArmedMatchTakesWhatNoOtherDoes() {
        int leaves = 130;
        int[] armOf = new int[leaves];
        for (int i = 0; i < leaves; i++) {
            armOf[i] = Math.min(i, 127);
        }
        String source = cut(leaves, 128, armOf);

        for (LeafTelling telling : LeafTelling.values()) {
            Running module = module(source, telling);
            for (int leaf : new int[] {0, 1, 63, 126, 127, 128, 129}) {
                assertThat(answer(module, "cut.which", "[\"L" + leaf + "\"]"))
                        .as("%s, leaf %d", telling, leaf)
                        .isEqualTo("{\"value\":" + (armOf[leaf] + 1) + "}");
            }
        }
    }

    /**
     * A match inside an arm of a match, each told every way: the labels a jump goes to are counted
     * from where it is, so what is opened around it, and what an arm opens within it, has to be.
     */
    @Test
    void aMatchInsideAnArmTakesItsOwnArm() {
        int leaves = 130;
        String declared = IntStream.range(0, leaves).mapToObj(i -> "data X" + i + "\ndata Y" + i)
                .collect(Collectors.joining("\n"));
        String xs = IntStream.range(0, leaves).mapToObj(i -> "X" + i).collect(Collectors.joining(" | "));
        String ys = IntStream.range(0, leaves).mapToObj(i -> "Y" + i).collect(Collectors.joining(" | "));
        StringBuilder outer = new StringBuilder();
        for (int x = 0; x < leaves; x++) {
            if (x == 0 || x == 64 || x == 128) {
                outer.append("    | X").append(x).append(" -> match y with\n");
                for (int y = 0; y < leaves; y++) {
                    outer.append("        | Y").append(y).append(" -> ").append(1000 * (x + 1) + y).append('\n');
                }
            } else {
                outer.append("    | X").append(x).append(" -> ").append(x).append('\n');
            }
        }
        String source = "module nest\n\n" + declared + "\n\ndata X = " + xs + "\n\ndata Y = " + ys + "\n\n"
                + "behavior which : (x: X, y: Y) -> Int\n\nlet which (x, y) = match x with\n" + outer;

        for (LeafTelling telling : LeafTelling.values()) {
            Running module = module(source, telling);
            for (int x : new int[] {0, 64, 128, 129, 5}) {
                for (int y : new int[] {0, 64, 129}) {
                    int expected = x == 0 || x == 64 || x == 128 ? 1000 * (x + 1) + y : x;
                    assertThat(answer(module, "nest.which", "[\"X" + x + "\",\"Y" + y + "\"]"))
                            .as("%s, x %d, y %d", telling, x, y).isEqualTo("{\"value\":" + expected + "}");
                }
            }
        }
    }

    /**
     * What the rule chooses, where this PR says it does: comparing a match of a few dozen arms, a
     * jump over a hundred and more. Read off the module the rule writes, which is the module of the
     * way it chose and not the module of the others: so a change to the rule that moves these points
     * fails here, and the tests above, which no longer depend on them, go on testing every way.
     */
    @Test
    void theRuleComparesAFewDozenArmsAndJumpsOverAHundredAndMore() {
        String few = cut(34, 34, IntStream.range(0, 34).toArray());
        String many = cut(130, 130, IntStream.range(0, 130).toArray());

        assertThat(bytes(few, LeafTelling.BY_COST)).isEqualTo(bytes(few, LeafTelling.COMPARING));
        assertThat(bytes(few, LeafTelling.BY_COST)).isNotEqualTo(bytes(few, LeafTelling.JUMPING));
        assertThat(bytes(many, LeafTelling.BY_COST)).isEqualTo(bytes(many, LeafTelling.JUMPING));
        assertThat(bytes(many, LeafTelling.BY_COST)).isNotEqualTo(bytes(many, LeafTelling.COMPARING));
        assertThat(bytes(many, LeafTelling.JUMPING)).isNotEqualTo(bytes(many, LeafTelling.LOOKING_UP));
    }

    private static byte[] bytes(String source, LeafTelling telling) {
        return Compiled.module(Compiled.program(List.of(source)), telling);
    }

    private static Running module(String source, LeafTelling telling) {
        return Running.linked(bytes(source, telling));
    }

    /** A sum of {@code leaves} leaves matched by {@code arms} arms, each leaf in the arm {@code armOf} says. */
    private static String cut(int leaves, int arms, int[] armOf) {
        String declared = IntStream.range(0, leaves).mapToObj(i -> "data L" + i).collect(Collectors.joining("\n"));
        String sum = IntStream.range(0, leaves).mapToObj(i -> "L" + i).collect(Collectors.joining(" | "));
        StringBuilder match = new StringBuilder();
        for (int arm = 0; arm < arms; arm++) {
            int taken = arm;
            String covered = IntStream.range(0, leaves).filter(i -> armOf[i] == taken)
                    .mapToObj(i -> "L" + i).collect(Collectors.joining(" | "));
            match.append("    | ").append(covered).append(" -> ").append(arm + 1).append('\n');
        }
        return "module cut\n\n" + declared + "\n\ndata W = " + sum + "\n\n"
                + "behavior which : (x: W) -> Int\n\nlet which (x) = match x with\n" + match;
    }

    private static String answer(Running module, String export, String arguments) {
        int address = module.staged(arguments);
        long[] answer = module.callWithString(export, address, arguments.getBytes(StandardCharsets.UTF_8).length);
        return new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
    }
}
