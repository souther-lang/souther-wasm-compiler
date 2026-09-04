package souther.wasm.lower;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
    /** Where a word begins or ends, which stands between characters and reads none. */
    private static final int BOUNDARY = 6;

    private final WasmFragment fragment;
    private final String pattern;
    private int at;

    private Patterns(WasmFragment fragment, String pattern, boolean eitherCase) {
        this.fragment = fragment;
        this.pattern = pattern;
        this.eitherCase = eitherCase;
    }

    /** Whether a character stands for itself or for itself and the other case of itself. */
    private final boolean eitherCase;

    /**
     * Places the machine that recognises a pattern and answers where it is.
     *
     * @param fragment where the machine goes, which is static memory
     * @param pattern the pattern as it was written
     */
    static int place(WasmFragment fragment, String pattern) {
        String written = wholeOf(pattern);
        boolean eitherCase = written.startsWith(EITHER_CASE);
        Patterns held = new Patterns(fragment,
                eitherCase ? written.substring(EITHER_CASE.length()) : written, eitherCase);
        List<int[]> steps = held.choice();
        if (held.at != held.pattern.length()) {
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

    /**
     * The pattern without the marks saying it is about the whole of a string.
     *
     * <p>What this recognises is the whole of a string, so a pattern that says so as well says
     * nothing more. Somebody writing a format writes them out of habit and means what is meant
     * without them, and refusing that is refusing a pattern for agreeing.
     *
     * <p>Only where they are the marks. A dollar at the end of a pattern that escaped it is the
     * character, and how many backslashes run up to it is what says which.
     */
    private static String wholeOf(String pattern) {
        String held = pattern.startsWith("^") ? pattern.substring(1) : pattern;
        if (!held.endsWith("$")) {
            return held;
        }
        int slashes = 0;
        while (slashes + 1 < held.length() && held.charAt(held.length() - 2 - slashes) == '\\') {
            slashes++;
        }
        return slashes % 2 == 0 ? held.substring(0, held.length() - 1) : held;
    }

    /**
     * What says a pattern is about a character whichever case it is written in.
     *
     * <p>Taken only at the front, where it is about the whole pattern. Written in the middle it is
     * about what follows it and not about what precedes it, and what refuses that is not this: it
     * is that a group opening with a question mark and no colon is a group this does not read. So
     * one written in the middle is refused whether or not this notices it.
     */
    private static final String EITHER_CASE = "(?i)";

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
                return List.of(oneOf(held));
            }
        }
    }

    /**
     * One character, or — where the pattern said either case — a set of the two it is written as.
     *
     * <p>Worked out where the pattern is read and not where it is walked, so that what runs is the
     * same walk over the same steps whichever the pattern said.
     */
    private int[] oneOf(int point) {
        if (!eitherCase) {
            return new int[] {MATCH_ONE, point, 0};
        }
        int lower = Character.toLowerCase(point);
        int upper = Character.toUpperCase(point);
        if (lower == upper) {
            return new int[] {MATCH_ONE, point, 0};
        }
        return new int[] {MATCH_CLASS,
                placeClass(settled(List.of(new int[] {lower, lower}, new int[] {upper, upper}))), 0};
    }

    /** A backslash and what follows it: a set of characters, or one written out of the way. */
    private int[] escape() {
        at++;
        if (at >= pattern.length()) {
            throw notRead();
        }
        char held = pattern.charAt(at++);
        return switch (held) {
            case 'd', 'D', 'w', 'W', 's', 'S' -> new int[] {MATCH_CLASS, placeClass(rangesOf(held)), 0};
            case 'b' -> new int[] {BOUNDARY, 0, 0};
            case 'n' -> new int[] {MATCH_ONE, '\n', 0};
            case 'r' -> new int[] {MATCH_ONE, '\r', 0};
            case 't' -> new int[] {MATCH_ONE, '\t', 0};
            case '\\', '.', '[', ']', '(', ')', '{', '}', '|', '*', '+', '?', '^', '$', '-', '/' ->
                    new int[] {MATCH_ONE, held, 0};
            // A class named rather than written out. What it names is not this backend's to say —
            // the pattern is read in the flavour the language declares it in — so what it stands
            // for is asked of the reader that owns the flavour, one character at a time.
            case 'p', 'P' -> new int[] {MATCH_CLASS, placeClass(asked("\\" + held + braced())), 0};
            default -> throw notRead();
        };
    }

    /** The last character there is, which is where a set's complement ends. */
    private static final int LAST = 0x10ffff;

    /** What follows a named class, which is the name in braces. */
    private String braced() {
        if (at >= pattern.length() || pattern.charAt(at) != '{') {
            // The one-letter form. A property named by one letter is as much a name as any other.
            if (at >= pattern.length()) {
                throw notRead();
            }
            return String.valueOf(pattern.charAt(at++));
        }
        int close = pattern.indexOf('}', at);
        if (close < 0) {
            throw notRead();
        }
        String held = pattern.substring(at, close + 1);
        at = close + 1;
        return held;
    }

    /**
     * The characters a written-out class stands for, asked of the reader whose flavour it is.
     *
     * <p>What {@code \p{IsHiragana}} names is a fact about a version of Unicode and not about this
     * compiler, and the language says which reader settles it. So this asks that reader, character
     * by character, rather than keeping a table of its own to disagree with it — the table would
     * be right on the day it was written and wrong on the day the reader was updated.
     *
     * <p>Once per class written, which is once per pattern that names one: what comes out is placed
     * in the module and nothing asks again.
     */
    private static List<int[]> asked(String written) {
        return NAMED.computeIfAbsent(written, held -> {
            Pattern reader;
            try {
                reader = Pattern.compile(held);
            } catch (PatternSyntaxException e) {
                // Not a pattern a program can arrive with: the language reads the pattern before
                // this does and refuses what its own reader refuses. What this catches is this
                // handing over something it built wrong out of what was written.
                throw new NotLowered("a class this backend put together and the reader refused: "
                        + held);
            }
            List<int[]> runs = new ArrayList<>();
            int from = -1;
            for (int c = 0; c <= LAST; c++) {
                if (reader.matcher(new String(Character.toChars(c))).matches()) {
                    from = from < 0 ? c : from;
                } else if (from >= 0) {
                    runs.add(new int[] {from, c - 1});
                    from = -1;
                }
            }
            if (from >= 0) {
                runs.add(new int[] {from, LAST});
            }
            return runs;
        });
    }

    /** What each class written out came to, so a pattern naming one twice asks once. */
    private static final Map<String, List<int[]>> NAMED = new HashMap<>();

    /** The characters one of the sets a backslash names stands for, as runs. */
    private static List<int[]> rangesOf(char held) {
        List<int[]> runs = switch (Character.toLowerCase(held)) {
            case 'd' -> List.of(new int[] {'0', '9'});
            case 'w' -> List.of(new int[] {'a', 'z'}, new int[] {'A', 'Z'},
                    new int[] {'0', '9'}, new int[] {'_', '_'});
            default -> List.of(new int[] {' ', ' '}, new int[] {'\t', '\t'},
                    new int[] {'\n', '\n'}, new int[] {0x0b, 0x0b},
                    new int[] {'\f', '\f'}, new int[] {'\r', '\r'});
        };
        return Character.isUpperCase(held) ? without(runs) : settled(runs);
    }

    /** The runs a set names, in order and with none touching the next. */
    private static List<int[]> settled(List<int[]> runs) {
        List<int[]> held = new ArrayList<>(runs);
        held.sort(Comparator.comparingInt(run -> run[0]));
        List<int[]> out = new ArrayList<>();
        for (int[] run : held) {
            if (run[1] < run[0]) {
                continue;
            }
            int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && run[0] <= last[1] + 1) {
                last[1] = Math.max(last[1], run[1]);
            } else {
                out.add(new int[] {run[0], run[1]});
            }
        }
        return out;
    }

    /** Every character the runs do not name. */
    private static List<int[]> without(List<int[]> runs) {
        List<int[]> out = new ArrayList<>();
        int from = 0;
        for (int[] run : settled(runs)) {
            if (run[0] > from) {
                out.add(new int[] {from, run[0] - 1});
            }
            from = run[1] + 1;
        }
        if (from <= LAST) {
            out.add(new int[] {from, LAST});
        }
        return out;
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
                char what = pattern.charAt(at + 1);
                if (what == 'p' || what == 'P') {
                    // A class named inside a class, which is the characters it stands for thrown
                    // in with the rest — the same as one a single letter names.
                    at += 2;
                    runs.addAll(asked("\\" + what + braced()));
                    continue;
                }
                if ("dDwWsS".indexOf(what) >= 0) {
                    // A set named inside a class is the characters it stands for, thrown in with
                    // the rest. Where it is the negated one it is every character it does not name
                    // — worked out here, so that what is placed is runs either way and nothing has
                    // to hold a set inside a set.
                    at += 2;
                    runs.addAll(rangesOf(what));
                    continue;
                }
                held = escape()[1];
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
        // Folded before it is negated and not after: what a class leaves out, where the pattern
        // said either case, is what neither case of it is.
        List<int[]> held = eitherCase ? bothCasesOf(runs) : runs;
        return placeClass(away ? without(held) : settled(held));
    }

    /**
     * The runs, and the runs of the other case of every character in them.
     *
     * <p>Walked a character at a time because a run is a run of code points and the other case of
     * one is somewhere else entirely — `a` to `z` has its other case a fixed distance away and the
     * next alphabet does not, so moving the ends of a run is an answer about one alphabet.
     */
    private static List<int[]> bothCasesOf(List<int[]> runs) {
        List<int[]> held = new ArrayList<>(runs);
        for (int[] run : runs) {
            for (int c = run[0]; c <= run[1]; c++) {
                int lower = Character.toLowerCase(c);
                int upper = Character.toUpperCase(c);
                if (lower != c) {
                    held.add(new int[] {lower, lower});
                }
                if (upper != c) {
                    held.add(new int[] {upper, upper});
                }
            }
        }
        return held;
    }

    /**
     * A set of characters in static memory: how many runs it names, and the runs.
     *
     * <p>The runs and nothing else. What is negated is worked out where it is written, because a
     * class can name a set inside it and a negated set inside a negated class is not the union
     * anybody reads it as — so the negating happens once, over runs, rather than being carried
     * here and undone by whatever reads it.
     */
    private int placeClass(List<int[]> runs) {
        ByteArrayOutputStream table = new ByteArrayOutputStream();
        WasmWriter out = new WasmWriter(table);
        out.writeLittleEndian4(0).writeLittleEndian4(runs.size());
        for (int[] run : runs) {
            out.writeLittleEndian4(run[0]).writeLittleEndian4(run[1]);
        }
        return fragment.place(table.toByteArray());
    }

    private NotLowered notRead() {
        return new NotLowered("a pattern this backend does not read: " + pattern);
    }
}
