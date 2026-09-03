package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import souther.wasm.emit.WasmWriter;
import souther.wasm.link.WasmFragment;

/**
 * A pattern read once, where it is written, and placed as the machine that recognises it.
 *
 * <p>The language asks for the pattern to be a literal, so nothing is read at run time: what is
 * placed is a list of steps, and what runs is a walk over them. That is also why a construct this
 * does not know is refused here rather than answered wrongly later — the pattern is in front of
 * this compiler, and a program that would have been recognised differently is one it can decline
 * to write.
 *
 * <p>What is admitted is what a format asks for: characters, any character, a class, a group, a
 * choice, and the counts. What is not is what looks back or ahead — a reference to a group already
 * matched, a lookaround, a lazy or possessive count — none of which a format needs and all of which
 * mean the walk is not a walk.
 *
 * <p>Each piece is read into a run of steps that points only within itself, so putting two together
 * is moving the second one's targets along by the length of the first. Nothing is patched in place
 * afterwards, which is what keeps a choice inside a group the same shape as one at the top.
 */
final class Patterns {

    /** One character, given as its code point. */
    private static final int MATCH_ONE = 0;
    /** Any character but the ones that end a line, which is what a dot is. */
    private static final int MATCH_ANY = 1;
    /** One character of a set, whose description is at the address. */
    private static final int MATCH_CLASS = 2;
    /** Go on at either of two steps. */
    private static final int FORK = 3;
    /** Go on at one step. */
    private static final int GO = 4;
    /** The whole of it has been recognised. */
    private static final int DONE = 5;

    private final WasmFragment fragment;
    private final String pattern;
    private int at;

    private Patterns(WasmFragment fragment, String pattern) {
        this.fragment = fragment;
        this.pattern = pattern;
    }

    /**
     * Places the machine that recognises a pattern and answers where it is.
     *
     * @param fragment where the machine goes, which is static memory
     * @param pattern the pattern as it was written
     */
    static int place(WasmFragment fragment, String pattern) {
        Patterns held = new Patterns(fragment, pattern);
        List<int[]> steps = held.choice();
        if (held.at != pattern.length()) {
            throw held.notRead();
        }
        steps.add(new int[] {DONE, 0, 0});

        ByteArrayOutputStream table = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(table);
        out.writeLittleEndian4(steps.size());
        for (int[] step : steps) {
            out.writeLittleEndian4(step[0])
                    .writeLittleEndian4(step[1])
                    .writeLittleEndian4(step[2]);
        }
        return fragment.place(table.toByteArray());
    }

    /** {@code a|b|c}: one of several, and what recognises it is a walk that is at all of them. */
    private List<int[]> choice() {
        List<List<int[]>> branches = new ArrayList<>();
        branches.add(sequence());
        while (at < pattern.length() && pattern.charAt(at) == '|') {
            at++;
            branches.add(sequence());
        }
        if (branches.size() == 1) {
            return branches.get(0);
        }
        // Every branch but the last starts with a fork past it and ends with a jump to the end, so
        // the walk is at the first step of each branch and back together after whichever ran.
        int total = 0;
        for (List<int[]> branch : branches) {
            total += branch.size();
        }
        int end = total + 2 * (branches.size() - 1);
        List<int[]> steps = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) {
            List<int[]> branch = branches.get(i);
            if (i == branches.size() - 1) {
                steps.addAll(moved(branch, steps.size()));
                break;
            }
            int fork = steps.size();
            steps.add(new int[] {FORK, fork + 1, fork + 1 + branch.size() + 1});
            steps.addAll(moved(branch, steps.size()));
            steps.add(new int[] {GO, end, 0});
        }
        return steps;
    }

    /** One thing after another. */
    private List<int[]> sequence() {
        List<int[]> steps = new ArrayList<>();
        while (at < pattern.length() && pattern.charAt(at) != '|' && pattern.charAt(at) != ')') {
            steps.addAll(moved(counted(), steps.size()));
        }
        return steps;
    }

    /** One thing, and however many of it the pattern asks for. */
    private List<int[]> counted() {
        List<int[]> held = one();
        if (at >= pattern.length()) {
            return held;
        }
        char how = pattern.charAt(at);
        if (how != '*' && how != '+' && how != '?' && how != '{') {
            return held;
        }
        if (how == '{') {
            return repeated(held);
        }
        at++;
        List<int[]> steps = new ArrayList<>();
        switch (how) {
            case '*' -> {
                steps.add(new int[] {FORK, 1, held.size() + 2});
                steps.addAll(moved(held, 1));
                steps.add(new int[] {GO, 0, 0});
            }
            case '+' -> {
                steps.addAll(held);
                steps.add(new int[] {FORK, 0, held.size() + 1});
            }
            default -> {
                steps.add(new int[] {FORK, 1, held.size() + 1});
                steps.addAll(moved(held, 1));
            }
        }
        return steps;
    }

    /** {@code {n}}, {@code {n,}} and {@code {n,m}}, written out as that many copies. */
    private List<int[]> repeated(List<int[]> held) {
        int close = pattern.indexOf('}', at);
        if (close < 0) {
            throw notRead();
        }
        String written = pattern.substring(at + 1, close);
        at = close + 1;
        int comma = written.indexOf(',');
        int least;
        int most;
        try {
            if (comma < 0) {
                least = Integer.parseInt(written);
                most = least;
            } else {
                least = Integer.parseInt(written.substring(0, comma));
                String upper = written.substring(comma + 1);
                most = upper.isEmpty() ? -1 : Integer.parseInt(upper);
            }
        } catch (NumberFormatException e) {
            throw notRead();
        }
        if (least < 0 || (most >= 0 && most < least) || least > 1000 || most > 1000) {
            throw new NotLowered(
                    "a pattern asking for more copies than this backend writes out: " + pattern);
        }
        List<int[]> steps = new ArrayList<>();
        for (int i = 0; i < least; i++) {
            steps.addAll(moved(held, steps.size()));
        }
        if (most < 0) {
            int fork = steps.size();
            steps.add(new int[] {FORK, fork + 1, fork + held.size() + 2});
            steps.addAll(moved(held, steps.size()));
            steps.add(new int[] {GO, fork, 0});
            return steps;
        }
        // A copy that may or may not run forks past everything still to come, so any number of them
        // between the least and the most is a way through.
        int over = steps.size() + (most - least) * (held.size() + 1);
        for (int i = least; i < most; i++) {
            steps.add(new int[] {FORK, steps.size() + 1, over});
            steps.addAll(moved(held, steps.size()));
        }
        return steps;
    }

    /** The same steps somewhere else, with the ones they point at moved along by as much. */
    private static List<int[]> moved(List<int[]> held, int by) {
        List<int[]> steps = new ArrayList<>(held.size());
        for (int[] step : held) {
            steps.add(new int[] {
                step[0],
                step[0] == FORK || step[0] == GO ? step[1] + by : step[1],
                step[0] == FORK ? step[2] + by : step[2],
            });
        }
        return steps;
    }

    /** One character, one class, or a group. */
    private List<int[]> one() {
        char held = pattern.charAt(at);
        switch (held) {
            case '(' -> {
                at++;
                if (pattern.startsWith("?:", at)) {
                    at += 2;
                } else if (at < pattern.length() && pattern.charAt(at) == '?') {
                    throw new NotLowered("a group this backend does not read: " + pattern);
                }
                List<int[]> inside = choice();
                if (at >= pattern.length() || pattern.charAt(at) != ')') {
                    throw notRead();
                }
                at++;
                return inside;
            }
            case '[' -> {
                return List.of(new int[] {MATCH_CLASS, characterClass(), 0});
            }
            case '.' -> {
                at++;
                return List.of(new int[] {MATCH_ANY, 0, 0});
            }
            case '\\' -> {
                return List.of(escape());
            }
            // A count with nothing in front of it, which is also where a lazy or possessive one
            // ends up: the count it belongs to has already been read, and what is left is this.
            case '^', '$', '*', '+', '?' -> throw notRead();
            default -> {
                at++;
                return List.of(new int[] {MATCH_ONE, held, 0});
            }
        }
    }

    /** A backslash and what follows it: a set of characters, or one written out of the way. */
    private int[] escape() {
        at++;
        if (at >= pattern.length()) {
            throw notRead();
        }
        char held = pattern.charAt(at++);
        return switch (held) {
            case 'd', 'D', 'w', 'W', 's', 'S' -> new int[] {MATCH_CLASS, named(held), 0};
            case 'n' -> new int[] {MATCH_ONE, '\n', 0};
            case 'r' -> new int[] {MATCH_ONE, '\r', 0};
            case 't' -> new int[] {MATCH_ONE, '\t', 0};
            case '\\', '.', '[', ']', '(', ')', '{', '}', '|', '*', '+', '?', '^', '$', '-', '/' ->
                    new int[] {MATCH_ONE, held, 0};
            default -> throw notRead();
        };
    }

    /** One of the sets a backslash names, placed as the runs of characters it stands for. */
    private int named(char held) {
        boolean away = Character.isUpperCase(held);
        List<int[]> runs = switch (Character.toLowerCase(held)) {
            case 'd' -> List.of(new int[] {'0', '9'});
            case 'w' -> List.of(new int[] {'a', 'z'}, new int[] {'A', 'Z'},
                    new int[] {'0', '9'}, new int[] {'_', '_'});
            default -> List.of(new int[] {' ', ' '}, new int[] {'\t', '\t'},
                    new int[] {'\n', '\n'}, new int[] {0x0b, 0x0b},
                    new int[] {'\f', '\f'}, new int[] {'\r', '\r'});
        };
        return placeClass(away, runs);
    }

    /** {@code [...]}: the characters it names, or everything but them. */
    private int characterClass() {
        at++;
        boolean away = at < pattern.length() && pattern.charAt(at) == '^';
        if (away) {
            at++;
        }
        List<int[]> runs = new ArrayList<>();
        while (at < pattern.length() && pattern.charAt(at) != ']') {
            int held;
            if (pattern.charAt(at) == '\\') {
                int[] escaped = escape();
                if (escaped[0] == MATCH_CLASS) {
                    // A set inside a set would be a union, and a negated one inside a negated one
                    // is not the union anybody reads it as, so neither is admitted.
                    throw new NotLowered(
                            "a class naming a set inside it, which this backend does not read: "
                                    + pattern);
                }
                held = escaped[1];
            } else {
                held = pattern.charAt(at++);
            }
            if (at + 1 < pattern.length()
                    && pattern.charAt(at) == '-'
                    && pattern.charAt(at + 1) != ']') {
                at++;
                int upper = pattern.charAt(at) == '\\' ? escape()[1] : pattern.charAt(at++);
                runs.add(new int[] {held, upper});
            } else {
                runs.add(new int[] {held, held});
            }
        }
        if (at >= pattern.length()) {
            throw notRead();
        }
        at++;
        return placeClass(away, runs);
    }

    /**
     * A set of characters in static memory: whether it is what is named or everything else, how
     * many runs it names, and the runs.
     */
    private int placeClass(boolean away, List<int[]> runs) {
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(table);
        out.writeLittleEndian4(away ? 1 : 0).writeLittleEndian4(runs.size());
        for (int[] run : runs) {
            out.writeLittleEndian4(run[0]).writeLittleEndian4(run[1]);
        }
        return fragment.place(table.toByteArray());
    }

    private NotLowered notRead() {
        return new NotLowered("a pattern this backend does not read: " + pattern);
    }
}
