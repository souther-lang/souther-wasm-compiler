package souther.wasm.lower;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * arms they are in: told by comparing where that is cheap, and by a lookup and one jump where it is
 * not (issue #77). Random sums are cut into random arms, and every leaf of each is asked for the arm
 * it takes, against what the cut says.
 */
class AMatchTakesTheArmOfItsLeafHoweverItIsToldTest {

    @Test
    void everyLeafOfRandomlyCutSumsTakesTheArmItIsIn() {
        Random random = new Random(2024);
        // Sizes either side of where comparing stops paying, and arms from two to many.
        int[] sizes = {2, 3, 5, 9, 20, 33, 34, 35, 48, 64, 90};
        for (int leaves : sizes) {
            for (int round = 0; round < 3; round++) {
                int arms = 2 + random.nextInt(Math.min(leaves, 40) - 1);
                List<Integer> order = new ArrayList<>(IntStream.range(0, leaves).boxed().toList());
                Collections.shuffle(order, random);
                // Each leaf to an arm, every arm with at least one.
                int[] armOf = new int[leaves];
                for (int i = 0; i < leaves; i++) {
                    armOf[order.get(i)] = i < arms ? i : random.nextInt(arms);
                }
                check(leaves, arms, armOf);
            }
        }
    }

    private static void check(int leaves, int arms, int[] armOf) {
        String declared = IntStream.range(0, leaves).mapToObj(i -> "data L" + i).collect(Collectors.joining("\n"));
        String sum = IntStream.range(0, leaves).mapToObj(i -> "L" + i).collect(Collectors.joining(" | "));
        StringBuilder match = new StringBuilder();
        for (int arm = 0; arm < arms; arm++) {
            int taken = arm;
            String covered = IntStream.range(0, leaves).filter(i -> armOf[i] == taken)
                    .mapToObj(i -> "L" + i).collect(Collectors.joining(" | "));
            match.append("        | ").append(covered).append(" -> ").append(arm + 1).append('\n');
        }
        String source = "module cut\n\n" + declared + "\n\ndata W = " + sum + "\n\n"
                + "behavior which : (x: W) -> Int\n\nlet which (x) = match x with\n" + match;
        Running module = Running.linked(Compiled.module(Compiled.program(List.of(source))));

        for (int leaf = 0; leaf < leaves; leaf++) {
            int address = module.staged("[\"L" + leaf + "\"]");
            long[] answer = module.callWithString("cut.which", address, ("[\"L" + leaf + "\"]").length());
            String written = new String(module.read((int) answer[0], (int) answer[1]), StandardCharsets.UTF_8);
            assertThat(written).as("%d leaves in %d arms, leaf %d", leaves, arms, leaf)
                    .isEqualTo("{\"value\":" + (armOf[leaf] + 1) + "}");
        }
    }
}
