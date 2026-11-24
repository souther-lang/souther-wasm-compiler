package souther.wasm.bench;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import souther.wasm.Compiled;
import souther.wasm.Running;
import souther.wasm.abi.RuntimeAbi;

/**
 * What a call costs as a function of how many leaves a sum has and where in it the leaf is, for the
 * work that tells a leaf apart: reading a document, writing one, sorting, and a {@code match}.
 *
 * <p>Run by {@code scripts/jfr-leaf-position.sh}, which starts one JVM under JFR for each cell and
 * reads the CPU samples the recording took, so that what is compared is CPU time and not the clock.
 * Each run calls one export in a loop for a fixed time and prints how many calls it made.
 *
 * <pre>
 * LeafPositionBench read|write|build|sort|match leaves position seconds
 * LeafPositionBench product fields position seconds     (position is unused)
 * </pre>
 */
public final class LeafPositionBench {

    private static final int VALUES = 100;

    private LeafPositionBench() {
    }

    public static void main(String[] arguments) {
        String work = arguments[0];
        int size = Integer.parseInt(arguments[1]);
        int position = Integer.parseInt(arguments[2]);
        long seconds = Long.parseLong(arguments[3]);

        String source;
        String export;
        String input;
        switch (work) {
            case "product" -> {
                String fields = IntStream.range(0, size).mapToObj(i -> "f" + i + ": Int")
                        .collect(Collectors.joining(", "));
                source = "module bench\n\ndata P = { " + fields + " }\n\n"
                        + "behavior count : (xs: List<P>) -> Int\n\nlet count (xs) = List.length(xs)\n";
                export = "bench.count";
                String one = "{" + IntStream.range(0, size).mapToObj(i -> "\"f" + i + "\":1")
                        .collect(Collectors.joining(",")) + "}";
                input = "[[" + String.join(",", java.util.Collections.nCopies(VALUES, one)) + "]]";
            }
            default -> {
                String leaves = IntStream.range(0, size).mapToObj(i -> "L" + i).collect(Collectors.joining(" | "));
                String declared = IntStream.range(0, size).mapToObj(i -> "data L" + i).collect(Collectors.joining("\n"));
                String head = "module bench\n\n" + declared + "\n\ndata W = " + leaves + "\n\n";
                String at = "\"L" + position + "\"";
                String beside = "\"L" + ((position + 1) % size) + "\"";
                switch (work) {
                    case "read" -> {
                        source = head + "behavior count : (xs: List<W>) -> Int\n\nlet count (xs) = List.length(xs)\n";
                        export = "bench.count";
                        input = "[[" + String.join(",", java.util.Collections.nCopies(VALUES, at)) + "]]";
                    }
                    case "write" -> {
                        String made = String.join(", ", java.util.Collections.nCopies(VALUES, "L" + position));
                        source = head + "behavior make : (n: Int) -> List<W>\n\nlet make (n) = [" + made + "]\n";
                        export = "bench.make";
                        input = "[1]";
                    }
                    case "build" -> {
                        String made = String.join(", ", java.util.Collections.nCopies(VALUES, "L" + position));
                        source = head + "behavior built : (n: Int) -> Int\n\nlet built (n) = List.length([" + made + "])\n";
                        export = "bench.built";
                        input = "[1]";
                    }
                    case "sort" -> {
                        source = head + "behavior sorted : (xs: List<W>) -> List<W>\n\nlet sorted (xs) = List.sort(xs)\n";
                        export = "bench.sorted";
                        input = "[[" + IntStream.range(0, VALUES).mapToObj(i -> i % 2 == 0 ? at : beside)
                                .collect(Collectors.joining(",")) + "]]";
                    }
                    case "match" -> {
                        String arms = IntStream.range(0, size).mapToObj(i -> "    | L" + i + " -> " + (i + 1))
                                .collect(Collectors.joining("\n"));
                        String values = String.join(", ", java.util.Collections.nCopies(2 * VALUES, "L" + position));
                        source = head + "behavior weight : (x: W) -> Int\n\nlet weight (x) = match x with\n" + arms
                                + "\n\nbehavior total : (n: Int) -> Int\n\nlet total (n) = List.fold((acc, x) -> acc + weight(x), 0, ["
                                + values + "])\n";
                        export = "bench.total";
                        input = "[1]";
                    }
                    default -> throw new IllegalArgumentException(work);
                }
            }
        }

        Running module = Running.linked(Compiled.module(Compiled.program(List.of(source))));
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 100; i++) {
            call(module, export, input, bytes);
        }
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        int calls = 0;
        while (System.nanoTime() < end) {
            call(module, export, input, bytes);
            calls++;
        }
        System.out.println("CALLS " + calls);
    }

    private static void call(Running module, String export, String input, byte[] bytes) {
        int mark = module.call(RuntimeAbi.ALLOC_MARK);
        int at = module.staged(input);
        module.callWithString(export, at, bytes.length);
        module.call(RuntimeAbi.ALLOC_RESET, mark);
    }
}
