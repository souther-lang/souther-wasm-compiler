package souther.wasm.link;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where a typed value lies in memory and what core values it is flattened to, as the Canonical ABI
 * says (CanonicalABI.md, "Despecialization", "Alignment", "Element Size", "Flattening").
 *
 * <p>Said once, here, and nowhere else: the core function a typed one is lifted from or lowered to
 * has the signature this flattens it to, and what writes a value into memory or reads one out of
 * it is handed the places this computes rather than computing them again. Two accounts of where a
 * field lies are one more than there should be, and the one that disagrees reads another field.
 *
 * <p>Read by its despecialization: a {@code tuple} is a {@code record} of what it holds, and an
 * {@code enum}, an {@code option} and a {@code result} are each a {@code variant}. So everything
 * below asks two questions of a type — what it is made of in order, and which cases it has and
 * what each carries — and answers the rest from those.
 */
public final class CanonicalAbi {

    /** A core value type, as a flattened value is made of them. */
    public enum Flat {
        I32, I64, F32, F64;

        @Override
        public String toString() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** At most this many core parameters are passed as themselves, and more pass a pointer. */
    public static final int MAX_FLAT_PARAMS = 16;

    /** At most this many core results are answered as themselves, and more answer through memory. */
    public static final int MAX_FLAT_RESULTS = 1;

    /**
     * The core function a typed one is.
     *
     * @param params its core parameters
     * @param results its core results
     * @param argumentsInMemory whether its arguments lie in memory, at the one pointer it takes,
     *     laid out as a record of them, rather than being its parameters
     * @param answerInMemory whether its answer lies in memory: at the pointer a lifted function
     *     answers, or at the one a lowered function is handed as its last parameter
     */
    public record CoreSignature(List<Flat> params, List<Flat> results, boolean argumentsInMemory,
            boolean answerInMemory) {

        public CoreSignature {
            params = List.copyOf(params);
            results = List.copyOf(results);
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder();
            if (!params.isEmpty()) {
                out.append("(param ").append(joined(params)).append(")");
            }
            if (!results.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append("(result ").append(joined(results))
                        .append(")");
            }
            return out.toString();
        }

        private static String joined(List<Flat> flats) {
            return String.join(" ", flats.stream().map(Flat::toString).toList());
        }
    }

    private final TypedOffering offering;
    private final Map<WitType, Integer> sizes = new HashMap<>();
    private final Map<WitType, Integer> alignments = new HashMap<>();
    private final Map<WitType, List<Flat>> flats = new HashMap<>();

    private CanonicalAbi(TypedOffering offering) {
        this.offering = offering;
    }

    /** The layout of everything an offering's functions name. */
    public static CanonicalAbi of(TypedOffering offering) {
        return new CanonicalAbi(offering);
    }

    /** The core function a typed one a component exports is lifted from. */
    public CoreSignature lifted(List<WitType> parameters, WitType answers) {
        return signature(parameters, answers, true);
    }

    /** The core function a typed one a component imports is lowered to. */
    public CoreSignature lowered(List<WitType> parameters, WitType answers) {
        return signature(parameters, answers, false);
    }

    private CoreSignature signature(List<WitType> parameters, WitType answers, boolean lift) {
        List<Flat> params = new ArrayList<>();
        parameters.forEach(each -> params.addAll(flatten(each)));
        List<Flat> results = new ArrayList<>(flatten(answers));
        boolean argumentsInMemory = params.size() > MAX_FLAT_PARAMS;
        if (argumentsInMemory) {
            params.clear();
            params.add(Flat.I32);
        }
        boolean answerInMemory = results.size() > MAX_FLAT_RESULTS;
        if (answerInMemory) {
            results.clear();
            if (lift) {
                results.add(Flat.I32);
            } else {
                params.add(Flat.I32);
            }
        }
        return new CoreSignature(params, results, argumentsInMemory, answerInMemory);
    }

    /** How many bytes a value of the type takes in memory. */
    public int size(WitType type) {
        Integer known = sizes.get(type);
        if (known != null) {
            return known;
        }
        Object resolved = resolved(type);
        int size = switch (resolved) {
            case WitType.Primitive primitive -> switch (primitive.name()) {
                case "bool" -> 1;
                case "s64" -> 8;
                case "u32" -> 4;
                case "string" -> 8;
                default -> throw unknown(primitive);
            };
            case WitType.ListOf ignored -> 8;
            default -> {
                List<WitType> members = membersOf(resolved);
                if (members != null) {
                    int at = 0;
                    for (WitType member : members) {
                        at = alignTo(at, alignment(member));
                        at += size(member);
                    }
                    yield alignTo(at, alignment(type));
                }
                List<@Nullable WitType> cases = casesOf(resolved);
                int at = discriminantSize(cases.size());
                at = alignTo(at, maxCaseAlignment(cases));
                int largest = 0;
                for (WitType carried : cases) {
                    if (carried != null) {
                        largest = Math.max(largest, size(carried));
                    }
                }
                yield alignTo(at + largest, alignment(type));
            }
        };
        sizes.put(type, size);
        return size;
    }

    /** What a value of the type is aligned to in memory. */
    public int alignment(WitType type) {
        Integer known = alignments.get(type);
        if (known != null) {
            return known;
        }
        Object resolved = resolved(type);
        int alignment = switch (resolved) {
            case WitType.Primitive primitive -> switch (primitive.name()) {
                case "bool" -> 1;
                case "s64" -> 8;
                case "u32", "string" -> 4;
                default -> throw unknown(primitive);
            };
            case WitType.ListOf ignored -> 4;
            default -> {
                List<WitType> members = membersOf(resolved);
                if (members != null) {
                    int most = 1;
                    for (WitType member : members) {
                        most = Math.max(most, alignment(member));
                    }
                    yield most;
                }
                List<@Nullable WitType> cases = casesOf(resolved);
                yield Math.max(discriminantSize(cases.size()), maxCaseAlignment(cases));
            }
        };
        alignments.put(type, alignment);
        return alignment;
    }

    /** Where each member of a record or a tuple lies, from where the value does. */
    public List<Integer> offsets(WitType type) {
        List<WitType> members = membersOf(resolved(type));
        if (members == null) {
            throw new IllegalArgumentException(type + " has no members");
        }
        List<Integer> offsets = new ArrayList<>();
        int at = 0;
        for (WitType member : members) {
            at = alignTo(at, alignment(member));
            offsets.add(at);
            at += size(member);
        }
        return offsets;
    }

    /** Where what a case of a variant carries lies, from where the value does. */
    public int payloadOffset(WitType type) {
        List<@Nullable WitType> cases = casesOf(resolved(type));
        return alignTo(discriminantSize(cases.size()), maxCaseAlignment(cases));
    }

    /** How many cases a variant, an enum, an option or a result has. */
    public int caseCount(WitType type) {
        return casesOf(resolved(type)).size();
    }

    /**
     * Which number a case of a variant, an enum, an option or a result is: its place among the
     * cases, an option's {@code none} and {@code some} and a result's {@code ok} and {@code err}
     * being the cases they despecialize to.
     */
    public int caseNumber(WitType type, String name) {
        Object resolved = resolved(type);
        List<String> names = switch (resolved) {
            case WitType.Variant variant -> variant.cases().stream().map(WitType.Case::name).toList();
            case WitType.Enumeration enumeration -> enumeration.cases();
            case WitType.OptionOf ignored -> List.of("none", "some");
            case WitType.ResultOf ignored -> List.of("ok", "err");
            default -> throw new IllegalArgumentException(type + " is not a variant");
        };
        int number = names.indexOf(name);
        if (number < 0) {
            throw new IllegalArgumentException(type + " has no case " + name);
        }
        return number;
    }

    /** How many bytes say which case a value of a variant, an enum, an option or a result is. */
    public int discriminantSize(WitType type) {
        return discriminantSize(caseCount(type));
    }

    /** How many bytes say which case of a variant of this many cases a value is. */
    public static int discriminantSize(int cases) {
        if (cases <= 1 << 8) {
            return 1;
        }
        if (cases <= 1 << 16) {
            return 2;
        }
        return 4;
    }

    /** The core values a value of the type is flattened to, in order. */
    public List<Flat> flatten(WitType type) {
        List<Flat> known = flats.get(type);
        if (known != null) {
            return known;
        }
        Object resolved = resolved(type);
        List<Flat> flat = switch (resolved) {
            case WitType.Primitive primitive -> switch (primitive.name()) {
                case "bool", "u32" -> List.of(Flat.I32);
                case "s64" -> List.of(Flat.I64);
                case "string" -> List.of(Flat.I32, Flat.I32);
                default -> throw unknown(primitive);
            };
            case WitType.ListOf ignored -> List.of(Flat.I32, Flat.I32);
            default -> {
                List<WitType> members = membersOf(resolved);
                if (members != null) {
                    List<Flat> joined = new ArrayList<>();
                    members.forEach(member -> joined.addAll(flatten(member)));
                    yield joined;
                }
                List<Flat> joined = new ArrayList<>();
                for (WitType carried : casesOf(resolved)) {
                    if (carried == null) {
                        continue;
                    }
                    List<Flat> each = flatten(carried);
                    for (int i = 0; i < each.size(); i++) {
                        if (i < joined.size()) {
                            joined.set(i, join(joined.get(i), each.get(i)));
                        } else {
                            joined.add(each.get(i));
                        }
                    }
                }
                List<Flat> withCase = new ArrayList<>();
                withCase.add(Flat.I32);
                withCase.addAll(joined);
                yield withCase;
            }
        };
        flat = Collections.unmodifiableList(flat);
        flats.put(type, flat);
        return flat;
    }

    /** Two core types one place of a variant's flattening holds, as the place it is. */
    private static Flat join(Flat a, Flat b) {
        if (a == b) {
            return a;
        }
        if ((a == Flat.I32 && b == Flat.F32) || (a == Flat.F32 && b == Flat.I32)) {
            return Flat.I32;
        }
        return Flat.I64;
    }

    /**
     * What a type is made of in order, where it is a record or a tuple; null where it is not.
     *
     * @param type a type {@link #resolved} has been asked of
     */
    private static @Nullable List<WitType> membersOf(Object type) {
        return switch (type) {
            case WitType.Record record ->
                    record.fields().stream().map(WitType.Field::type).toList();
            case WitType.TupleOf tuple -> tuple.members();
            default -> null;
        };
    }

    /**
     * Which cases a type has and what each carries, where it is one of the types a variant
     * despecializes from.
     */
    private static List<@Nullable WitType> casesOf(Object type) {
        return switch (type) {
            case WitType.Variant variant -> {
                List<@Nullable WitType> carried = new ArrayList<>();
                variant.cases().forEach(each -> carried.add(each.carries()));
                yield carried;
            }
            case WitType.Enumeration enumeration ->
                    Arrays.asList(new WitType[enumeration.cases().size()]);
            case WitType.OptionOf option -> Arrays.asList(null, option.present());
            case WitType.ResultOf result -> Arrays.asList(result.ok(), result.err());
            default -> throw new IllegalArgumentException(type + " is not a variant");
        };
    }

    private int maxCaseAlignment(List<@Nullable WitType> cases) {
        int most = 1;
        for (WitType carried : cases) {
            if (carried != null) {
                most = Math.max(most, alignment(carried));
            }
        }
        return most;
    }

    /**
     * What a type is, a name looked up to what it names: a declaration of a module's types
     * interface, or one of {@link TypedOffering#PROTOCOL}. A {@code type} alias is what it names.
     * Answered as the {@link WitType} for a type written in place and as the declaration for a
     * named one, which is then a record, a variant or an enum.
     */
    private Object resolved(WitType type) {
        WitType at = type;
        while (at instanceof WitType.Named name) {
            WitType.Declaration declaration = offering.declarationOf(name);
            if (declaration instanceof WitType.Alias alias) {
                at = alias.of();
            } else {
                return declaration;
            }
        }
        return at;
    }

    private static int alignTo(int at, int alignment) {
        return (at + alignment - 1) / alignment * alignment;
    }

    private static IllegalArgumentException unknown(WitType.Primitive primitive) {
        return new IllegalArgumentException("no layout for " + primitive.name());
    }
}
