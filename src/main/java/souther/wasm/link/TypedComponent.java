package souther.wasm.link;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import souther.wasm.abi.RuntimeAbi;
import souther.wasm.emit.ComponentWriter;

/**
 * A linked core module wrapped as a component whose functions take and answer the types of the
 * model, as the typed interfaces say ({@link TypedWitText}).
 *
 * <p>Built from the {@link TypedOffering} the text is written from, so the component and the text
 * are two accounts of one offering. Each module's types are an interface the component imports,
 * which declares them once; every interface naming one of them, whatever a program answers, reads
 * on its own or reaches out for, refers to that declaration, as WIT's {@code use} does. A
 * component built to these interfaces in another language composes with this one by those names.
 *
 * <p>What a lifted function is, and what a function the program reaches out to is lowered to, are
 * the core functions {@link CanonicalAbi} flattens each typed one to; the core module was written
 * with those ({@code WasmCompiler.typedCore}).
 */
public final class TypedComponent {

    /** Which core instance answers what the program reaches out for. */
    private static final int HOST = Component.HOST;

    /** Which core instance the program is. */
    private static final int PROGRAM = Component.PROGRAM;

    private TypedComponent() {
    }

    /**
     * Wraps a linked core module as a typed component.
     *
     * @param core the core module {@code WasmCompiler.typedCore} links
     * @param offering what the program offers and has to be given, typed
     * @return the component
     */
    public static byte[] around(byte[] core, TypedOffering offering) {
        return new TypedComponent.Writing(offering).written(core);
    }

    /** The interface a package holds for one module, versioned. */
    static String interfaceOf(String held, String module) {
        return held + "/" + Component.writable(module) + "@" + TypedOffering.VERSION;
    }

    /** One component being written, and what each of its index spaces holds so far. */
    private static final class Writing {

        private final TypedOffering offering;
        private final CanonicalAbi abi;
        private final ComponentWriter out = new ComponentWriter();
        private final List<byte[]> exports = new ArrayList<>();
        private final Map<WitType.Named, Integer> declared = new HashMap<>();
        private int types;
        private int funcs;
        private int instances;
        private int components;
        private int coreFuncs;

        Writing(TypedOffering offering) {
            this.offering = offering;
            this.abi = CanonicalAbi.of(offering);
        }

        byte[] written(byte[] core) {
            List<CanonicalAbi.CoreSignature> lowered = new ArrayList<>();
            offering.reached().values().forEach(functions -> functions.values().forEach(each ->
                    lowered.add(abi.lowered(parameters(each), each.answers()))));
            int reaches = lowered.size();

            Component.instantiated(out, core, reaches,
                    reaches == 0 ? null : ReachingOut.fillingTheTableTyped(lowered));

            List<byte[]> taken = new ArrayList<>();
            taken.add(ComponentWriter.aliasCoreMemory(PROGRAM, RuntimeAbi.MEMORY));
            int memory = 0;
            int realloc = coreFunc(taken, RuntimeAbi.CANONICAL_REALLOC);
            int afterwards = coreFunc(taken, Component.Lifted.POST_RETURN);
            Map<String, Integer> lifted = new LinkedHashMap<>();
            offering.behaviors().forEach((module, functions) -> functions.keySet().forEach(name -> {
                String export = Component.Lifted.typed(module + "." + name);
                lifted.put(export, coreFunc(taken, export));
            }));
            offering.readable().forEach((module, functions) -> functions.keySet().forEach(name -> {
                String export = Component.Lifted.typedReading(module + "." + name);
                lifted.put(export, coreFunc(taken, export));
            }));
            if (reaches > 0) {
                taken.add(ComponentWriter.aliasCoreTable(HOST, ReachingOut.TABLE));
            }
            out.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(taken));

            for (String module : inDependencyOrder()) {
                importTypes(module);
            }
            if (reaches > 0) {
                reachOut(memory, realloc);
            }
            offering.behaviors().forEach((module, functions) -> offer(
                    TypedOffering.PROGRAM, module, functions, name -> lifted.get(
                            Component.Lifted.typed(module + "." + name)),
                    memory, realloc, afterwards));
            offering.readable().forEach((module, functions) -> offer(
                    TypedOffering.DECODE, module, functions, name -> lifted.get(
                            Component.Lifted.typedReading(module + "." + name)),
                    memory, realloc, afterwards));
            out.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(exports));
            return out.toByteArray();
        }

        private static List<WitType> parameters(TypedOffering.Function function) {
            return function.parameters().stream().map(WitType.Field::type).toList();
        }

        private int coreFunc(List<byte[]> taken, String name) {
            taken.add(ComponentWriter.aliasCoreFunc(PROGRAM, name));
            return coreFuncs++;
        }

        /**
         * The modules whose types the program's interfaces declare, each after every module whose
         * types its own name: an instance type aliases what it uses out of one imported before it.
         * Souther refuses a cycle of imports, so there is an order.
         */
        private List<String> inDependencyOrder() {
            List<String> ordered = new ArrayList<>();
            Set<String> visiting = new HashSet<>();
            for (String module : offering.types().keySet()) {
                placeAfterItsUses(module, ordered, visiting);
            }
            return ordered;
        }

        private void placeAfterItsUses(String module, List<String> ordered, Set<String> visiting) {
            if (ordered.contains(module) || !visiting.add(module)) {
                return;
            }
            for (String used : usedBy(module).keySet()) {
                placeAfterItsUses(used, ordered, visiting);
            }
            ordered.add(module);
        }

        private Map<String, Set<String>> usedBy(String module) {
            List<WitType> naming = new ArrayList<>();
            offering.declaredIn(module).forEach(each -> naming.addAll(TypedOffering.holds(each)));
            return TypedOffering.used(module, true, naming);
        }

        /**
         * Imports a module's types interface and takes each of its types out of it, so that
         * everything after names each type as the one the interface declares.
         */
        private void importTypes(String module) {
            Space.Inside inside = new Space.Inside();
            usedBy(module).forEach((from, names) -> names.forEach(name -> {
                WitType.Named used = new WitType.Named(from, name);
                inside.name(used, inside.exported(name, inside.aliasedOut(declared.get(used))));
            }));
            for (WitType.Declaration declaration : inModuleOrder(module)) {
                WitType.Named named = new WitType.Named(module, declaration.name());
                inside.name(named, inside.exported(declaration.name(),
                        definition(declaration, inside)));
            }
            out.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(
                    ComponentWriter.instanceTypeOf(inside.decls))));
            int type = types++;
            out.rawSection(ComponentWriter.SEC_IMPORT, ComponentWriter.vec(List.of(
                    ComponentWriter.importInstance(
                            interfaceOf(TypedOffering.TYPES, module), type))));
            int instance = instances++;
            List<byte[]> aliases = new ArrayList<>();
            for (WitType.Declaration declaration : offering.declaredIn(module)) {
                aliases.add(ComponentWriter.aliasInstanceType(instance, declaration.name()));
                declared.put(new WitType.Named(module, declaration.name()), types++);
            }
            out.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases));
        }

        /** A module's declarations, each after the ones of the module it names. */
        private List<WitType.Declaration> inModuleOrder(String module) {
            Map<String, WitType.Declaration> byName = new LinkedHashMap<>();
            offering.declaredIn(module).forEach(each -> byName.put(each.name(), each));
            List<WitType.Declaration> ordered = new ArrayList<>();
            Set<String> placed = new HashSet<>();
            for (WitType.Declaration each : byName.values()) {
                placeAfterWhatItHolds(module, each, byName, ordered, placed);
            }
            return ordered;
        }

        private void placeAfterWhatItHolds(String module, WitType.Declaration declaration,
                Map<String, WitType.Declaration> byName, List<WitType.Declaration> ordered,
                Set<String> placed) {
            if (!placed.add(declaration.name())) {
                return;
            }
            for (WitType held : TypedOffering.holds(declaration)) {
                for (WitType.Named name : namesIn(held)) {
                    if (module.equals(name.module()) && byName.containsKey(name.name())) {
                        placeAfterWhatItHolds(module, byName.get(name.name()), byName, ordered,
                                placed);
                    }
                }
            }
            ordered.add(declaration);
        }

        private static List<WitType.Named> namesIn(WitType type) {
            List<WitType.Named> found = new ArrayList<>();
            collect(type, found);
            return found;
        }

        private static void collect(WitType type, List<WitType.Named> found) {
            switch (type) {
                case WitType.Primitive ignored -> {
                }
                case WitType.ListOf list -> collect(list.element(), found);
                case WitType.OptionOf option -> collect(option.present(), found);
                case WitType.ResultOf result -> {
                    collect(result.ok(), found);
                    collect(result.err(), found);
                }
                case WitType.TupleOf tuple -> tuple.members().forEach(each -> collect(each, found));
                case WitType.Named name -> found.add(name);
            }
        }

        /**
         * Imports the interface each module's reached-out-for behaviors are asked for under, lowers
         * each function against the program's memory, and instantiates what fills the table with
         * the lowerings.
         */
        private void reachOut(int memory, int realloc) {
            List<Integer> asked = new ArrayList<>();
            for (Map.Entry<String, Map<String, TypedOffering.Function>> module
                    : offering.reached().entrySet()) {
                Space.Inside inside = new Space.Inside();
                List<WitType> naming = new ArrayList<>();
                module.getValue().values().forEach(each -> naming.addAll(TypedOffering.holds(each)));
                usedNames(inside, module.getKey(), naming);
                for (TypedOffering.Function function : module.getValue().values()) {
                    int type = inside.define(functionType(function, function.answers(), inside));
                    inside.decls.add(ComponentWriter.instanceDeclExportFunc(function.name(), type));
                }
                out.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(List.of(
                        ComponentWriter.instanceTypeOf(inside.decls))));
                int type = types++;
                out.rawSection(ComponentWriter.SEC_IMPORT, ComponentWriter.vec(List.of(
                        ComponentWriter.importInstance(
                                interfaceOf(TypedOffering.REACHED, module.getKey()), type))));
                int instance = instances++;
                List<byte[]> aliases = new ArrayList<>();
                for (TypedOffering.Function function : module.getValue().values()) {
                    aliases.add(ComponentWriter.aliasInstanceFunc(instance, function.name()));
                    asked.add(funcs++);
                }
                out.rawSection(ComponentWriter.SEC_ALIAS, ComponentWriter.vec(aliases));
            }
            List<byte[]> lowers = new ArrayList<>();
            List<Integer> lowered = new ArrayList<>();
            for (int func : asked) {
                lowers.add(ComponentWriter.canonLowerMemoryReallocUtf8(func, memory, realloc));
                lowered.add(coreFuncs++);
            }
            out.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lowers));
            Component.fillTheTable(out, memory, realloc, lowered);
        }

        /** Puts into an instance type each type of another interface what it names uses. */
        private void usedNames(Space.Inside inside, String module, List<WitType> naming) {
            TypedOffering.used(module, false, naming).forEach((from, names) -> names.forEach(name -> {
                WitType.Named used = new WitType.Named(from, name);
                inside.name(used, inside.exported(name, inside.aliasedOut(declared.get(used))));
            }));
        }

        /**
         * Offers one interface: a function lifted from each core function, and the instance of
         * them exported under the interface's name.
         *
         * <p>The instance is made by instantiating a small component of its own, as WIT's tools
         * make one. A type of the model the interface's functions name is handed to it from the
         * types interface the component imports, and it exports the type under its name: that
         * is what a reader of interfaces reads as the interface using the type, rather than
         * declaring one of its own. What a call that can refuse answers is the interface's own,
         * and is declared in it.
         */
        private void offer(String held, String module, Map<String, TypedOffering.Function> functions,
                java.util.function.Function<String, Integer> coreFunctionOf, int memory,
                int realloc, int afterwards) {
            Space.Outside outside = new Space.Outside(this);
            Map<WitType.Named, Integer> protocol = new HashMap<>();
            for (WitType.Declaration declaration : TypedOffering.PROTOCOL) {
                WitType.Named name = new WitType.Named(null, declaration.name());
                int type = definition(declaration, outside);
                outside.name(name, type);
                protocol.put(name, type);
            }
            Map<String, Integer> funcTypes = new LinkedHashMap<>();
            for (Map.Entry<String, TypedOffering.Function> function : functions.entrySet()) {
                TypedOffering.Function crossing = function.getValue();
                funcTypes.put(function.getKey(),
                        outside.define(functionType(crossing, crossing.refusing(), outside)));
            }
            out.rawSection(ComponentWriter.SEC_TYPE, ComponentWriter.vec(outside.defined));

            List<byte[]> lifts = new ArrayList<>();
            List<Integer> lifted = new ArrayList<>();
            for (Map.Entry<String, TypedOffering.Function> function : functions.entrySet()) {
                lifts.add(ComponentWriter.canonLiftMemoryReallocUtf8PostReturn(
                        coreFunctionOf.apply(function.getKey()), funcTypes.get(function.getKey()),
                        memory, realloc, afterwards));
                lifted.add(funcs++);
            }
            out.rawSection(ComponentWriter.SEC_CANON, ComponentWriter.vec(lifts));

            Shim shim = new Shim(this, module, List.copyOf(functions.values()), protocol);
            out.rawSection(ComponentWriter.SEC_COMPONENT, shim.written());
            int component = components++;
            out.rawSection(ComponentWriter.SEC_INSTANCE, ComponentWriter.vec(List.of(
                    shim.instantiated(component, lifted))));
            exports.add(ComponentWriter.exportInstance(interfaceOf(held, module), instances++));
        }

        /**
         * The component an offered interface is instantiated from: it imports each type of the
         * model its functions name, and each function lifted; it exports the types its functions
         * name directly, its own records of a refused call, and each function, typed by those.
         */
        private static final class Shim {

            private final Writing writing;
            private final String module;
            private final List<TypedOffering.Function> functions;
            private final Map<WitType.Named, Integer> protocol;
            private final ComponentWriter inner = new ComponentWriter();
            private final List<String> argumentNames = new ArrayList<>();
            private final List<byte[]> argumentSorts = new ArrayList<>();
            private int types;
            private int funcs;

            Shim(Writing writing, String module, List<TypedOffering.Function> functions,
                    Map<WitType.Named, Integer> protocol) {
                this.writing = writing;
                this.protocol = protocol;
                this.module = module;
                this.functions = functions;
            }

            byte[] written() {
                List<WitType> naming = new ArrayList<>();
                functions.forEach(each -> naming.addAll(TypedOffering.holds(each)));

                Space.Within imported = new Space.Within(this);
                for (WitType.Named name : closure(naming)) {
                    imported.name(name, importType(
                            writing.definition(writing.declarationOf(name), imported),
                            writing.declared.get(name)));
                }
                for (WitType.Declaration declaration : TypedOffering.PROTOCOL) {
                    WitType.Named name = new WitType.Named(null, declaration.name());
                    imported.name(name, importType(
                            writing.definition(declaration, imported), protocol.get(name)));
                }
                List<Integer> taken = new ArrayList<>();
                for (TypedOffering.Function function : functions) {
                    int type = imported.define(
                            writing.functionType(function, function.refusing(), imported));
                    String argument = "import-func-f" + taken.size();
                    inner.rawSection(ComponentWriter.SEC_IMPORT, ComponentWriter.vec(List.of(
                            ComponentWriter.importFunc(argument, type))));
                    taken.add(funcs++);
                    argumentNames.add(argument);
                    argumentSorts.add(null);
                }

                Space.Within exported = new Space.Within(this);
                TypedOffering.used(module, false, naming).forEach((from, names) -> names.forEach(name -> {
                    WitType.Named used = new WitType.Named(from, name);
                    exported.name(used, exportType(name, imported.indexOf(used)));
                }));
                for (WitType.Declaration declaration : TypedOffering.PROTOCOL) {
                    exported.name(new WitType.Named(null, declaration.name()),
                            exportType(declaration.name(), writing.definition(declaration, exported)));
                }
                for (int i = 0; i < functions.size(); i++) {
                    TypedOffering.Function function = functions.get(i);
                    int type = exported.define(
                            writing.functionType(function, function.refusing(), exported));
                    int func = taken.get(i);
                    inner.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(List.of(
                            ComponentWriter.enc(w -> {
                                ComponentWriter.declName(w, function.name());
                                w.write(0x01).writeUnsignedLeb128(func);
                                w.write(0x01).write(0x01).writeUnsignedLeb128(type);
                            }))));
                    funcs++;
                }
                return inner.toByteArray();
            }

            /**
             * Imports a type equal to one defined here, to be handed the component's own: a
             * function imported or exported names its types by a name, and an import is one.
             */
            private int importType(int bound, int outer) {
                String argument = "import-type-t" + argumentNames.size();
                inner.rawSection(ComponentWriter.SEC_IMPORT, ComponentWriter.vec(List.of(
                        ComponentWriter.enc(w -> {
                            ComponentWriter.declName(w, argument);
                            w.write(0x03).write(0x00).writeUnsignedLeb128(bound);
                        }))));
                argument(argument, 0x03, outer);
                return types++;
            }

            /** Exports a type under a name, which is a type of its own after. */
            private int exportType(String name, int index) {
                inner.rawSection(ComponentWriter.SEC_EXPORT, ComponentWriter.vec(List.of(
                        ComponentWriter.enc(w -> {
                            ComponentWriter.declName(w, name);
                            w.write(0x03).writeUnsignedLeb128(index);
                            w.write(0x00);
                        }))));
                return types++;
            }

            private void argument(String name, int sort, int index) {
                argumentNames.add(name);
                argumentSorts.add(ComponentWriter.enc(w -> w.write(sort).writeUnsignedLeb128(index)));
            }

            /** The instance of it, handed each type it imports and each lifted function. */
            byte[] instantiated(int component, List<Integer> lifted) {
                return ComponentWriter.enc(w -> {
                    w.write(0x00).writeUnsignedLeb128(component);
                    w.writeUnsignedLeb128(argumentNames.size());
                    int function = 0;
                    for (int i = 0; i < argumentNames.size(); i++) {
                        ComponentWriter.plainName(w, argumentNames.get(i));
                        byte[] sort = argumentSorts.get(i);
                        if (sort == null) {
                            w.write(0x01).writeUnsignedLeb128(lifted.get(function++));
                        } else {
                            w.write((Object) sort);
                        }
                    }
                });
            }

            /**
             * Every type of the model what the functions name holds, however far down, each
             * after what its own declaration names.
             */
            private List<WitType.Named> closure(List<WitType> naming) {
                List<WitType.Named> ordered = new ArrayList<>();
                for (WitType type : naming) {
                    for (WitType.Named name : Writing.namesIn(type)) {
                        reach(name, ordered);
                    }
                }
                return ordered;
            }

            private void reach(WitType.Named name, List<WitType.Named> ordered) {
                if (name.module() == null || ordered.contains(name)) {
                    return;
                }
                for (WitType held : TypedOffering.holds(writing.declarationOf(name))) {
                    for (WitType.Named inner : Writing.namesIn(held)) {
                        reach(inner, ordered);
                    }
                }
                ordered.add(name);
            }
        }

        private WitType.Declaration declarationOf(WitType.Named name) {
            for (WitType.Declaration each : offering.declaredIn(name.module())) {
                if (each.name().equals(name.name())) {
                    return each;
                }
            }
            throw new IllegalStateException(name + " is declared nowhere");
        }

        private byte[] functionType(TypedOffering.Function function, WitType answers, Space space) {
            List<String> names = new ArrayList<>();
            List<byte[]> held = new ArrayList<>();
            for (WitType.Field parameter : function.parameters()) {
                names.add(parameter.name());
                held.add(valType(parameter.type(), space));
            }
            return ComponentWriter.funcTypeOf(names, held, valType(answers, space));
        }

        /** Defines a declaration's type in a space, answering where it is. */
        private int definition(WitType.Declaration declaration, Space space) {
            return switch (declaration) {
                case WitType.Record record -> {
                    List<String> names = new ArrayList<>();
                    List<byte[]> held = new ArrayList<>();
                    for (WitType.Field field : record.fields()) {
                        names.add(field.name());
                        held.add(valType(field.type(), space));
                    }
                    yield space.define(ComponentWriter.definedRecordOf(names, held));
                }
                case WitType.Variant variant -> {
                    List<String> names = new ArrayList<>();
                    List<byte[]> carried = new ArrayList<>();
                    for (WitType.Case each : variant.cases()) {
                        names.add(each.name());
                        carried.add(each.carries() == null ? null : valType(each.carries(), space));
                    }
                    yield space.define(ComponentWriter.definedVariantOf(names, carried));
                }
                case WitType.Enumeration enumeration ->
                        space.define(ComponentWriter.definedEnumOf(enumeration.cases()));
                case WitType.Alias alias -> switch (alias.of()) {
                    case WitType.Named name -> space.indexOf(name);
                    case WitType.Primitive primitive ->
                            space.define(ComponentWriter.definedPrim(primitiveCode(primitive)));
                    default -> definedCompound(alias.of(), space);
                };
            };
        }

        /** What a value type is written as where it stands: a primitive's code, or an index. */
        private byte[] valType(WitType type, Space space) {
            return switch (type) {
                case WitType.Primitive primitive ->
                        ComponentWriter.valTypePrim(primitiveCode(primitive));
                case WitType.Named name -> ComponentWriter.valTypeIndex(space.indexOf(name));
                default -> ComponentWriter.valTypeIndex(definedCompound(type, space));
            };
        }

        private int definedCompound(WitType type, Space space) {
            return switch (type) {
                case WitType.ListOf list ->
                        space.define(ComponentWriter.definedListOf(valType(list.element(), space)));
                case WitType.OptionOf option ->
                        space.define(ComponentWriter.definedOptionOf(valType(option.present(), space)));
                case WitType.TupleOf tuple -> {
                    List<byte[]> members = new ArrayList<>();
                    tuple.members().forEach(each -> members.add(valType(each, space)));
                    yield space.define(ComponentWriter.definedTupleOf(members));
                }
                case WitType.ResultOf result -> space.define(ComponentWriter.definedResultOf(
                        valType(result.ok(), space), valType(result.err(), space)));
                default -> throw new IllegalArgumentException(type + " is defined where it is named");
            };
        }

        private static int primitiveCode(WitType.Primitive primitive) {
            return switch (primitive.name()) {
                case "bool" -> ComponentWriter.VT_BOOL;
                case "s64" -> ComponentWriter.VT_S64;
                case "u32" -> ComponentWriter.VT_U32;
                case "string" -> ComponentWriter.VT_STRING;
                default -> throw new IllegalArgumentException("no code for " + primitive.name());
            };
        }
    }

    /**
     * A type index space being written into: an instance type's own, or the component's.
     */
    private abstract static sealed class Space {

        private final Map<WitType.Named, Integer> names = new HashMap<>();

        /** Defines a type, answering where it is. */
        abstract int define(byte[] definition);

        void name(WitType.Named name, int index) {
            names.put(name, index);
        }

        int indexOf(WitType.Named name) {
            Integer index = names.get(name);
            if (index == null) {
                throw new IllegalStateException(name + " is named and not yet where it is named");
            }
            return index;
        }

        /** An instance type's declarations, and its own index space. */
        static final class Inside extends Space {

            final List<byte[]> decls = new ArrayList<>();
            private int next;


            @Override
            int define(byte[] definition) {
                decls.add(ComponentWriter.instanceDeclType(definition));
                return next++;
            }

            /** A type of the component the instance type is in, aliased into it. */
            int aliasedOut(int outer) {
                decls.add(ComponentWriter.instanceDeclAliasOuterType(1, outer));
                return next++;
            }

            /** A type exported by the instance under a name, which is a type of its own. */
            int exported(String name, int index) {
                decls.add(ComponentWriter.instanceDeclExportTypeEq(name, index));
                return next++;
            }
        }

        /** The type index space of a component an interface is instantiated from. */
        static final class Within extends Space {

            private final Writing.Shim shim;

            Within(Writing.Shim shim) {
                this.shim = shim;
            }

            @Override
            int define(byte[] definition) {
                shim.inner.rawSection(ComponentWriter.SEC_TYPE,
                        ComponentWriter.vec(List.of(definition)));
                return shim.types++;
            }
        }

        /** The component's own type index space, where its own types are defined. */
        static final class Outside extends Space {

            final List<byte[]> defined = new ArrayList<>();
            private final Writing writing;

            Outside(Writing writing) {
                this.writing = writing;
            }

            @Override
            int define(byte[] definition) {
                defined.add(definition);
                return writing.types++;
            }

            @Override
            int indexOf(WitType.Named name) {
                if (name.module() != null) {
                    Integer index = writing.declared.get(name);
                    if (index != null) {
                        return index;
                    }
                }
                return super.indexOf(name);
            }
        }
    }
}
