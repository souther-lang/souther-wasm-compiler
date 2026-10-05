# souther-wasm-compiler

Compiles a checked Souther program to a WebAssembly module.

A behavior becomes an export that takes JSON and answers JSON. The values in between live in
linear memory, in an arena the caller pops after every call, so the output runs wherever core wasm
runs rather than only where a garbage collector and a component runtime do.

## What stays compatible

Compatibility is kept at the boundaries this compiler offers the ones who use what it makes:

- the command line: its arguments, its exit status, and the files it writes (below, under
  "Running it");
- the JavaScript package `@souther/wasm` (`packages/wasm`);
- what a module it writes holds a host to: the host contract and its ABI version, the
  `souther:surface` section and its version, and the interfaces a component exports and imports
  (`souther:program/*`, `souther:decode/*`, `souther:reached/*`).

Each of these that has a version moves it when it changes, and its tests fail where it changes
without moving.

The Java classes here are how this compiler is written, and none of them is offered to a caller. A
`public` declaration is one another package of this compiler uses; it is not a Java API, and
changing or removing it breaks nothing anyone was offered.

## What it reads

`CheckedProgram` and what is reachable from it, and nothing else of the Souther compiler. Anything
this needs that the program API does not carry is a question for Souther rather than something to
reach around, so it is raised there.

## The two halves

The kernels a Souther program calls — string, map, set, list, temporal, decimal, rational, int —
are written in Rust under `runtime/` and compiled to wasm ahead of time. The compiled module is carried in
`src/main/resources` and every build links against that copy, so building this project needs no
Rust toolchain. Changing the runtime does: rebuild it with

    runtime/build.sh

which needs Docker. What the runtime compiles to depends on the machine as well as the source: a
dependency with a build script is hashed by Cargo with the host's triple, and the order the linker
lays functions out in follows the hashes. CI requires the carried module to be what the source
builds and builds it on x86-64 Linux, so the script builds there too, in the Rust image of the
toolchain `rust-toolchain.toml` names. `cd runtime && cargo build --release` still builds a runtime
that works, for trying a change, and is the one to replace before committing.

What a string means is not written here. Its order, its length, its case, its canonical form,
which characters are white space, which text is a day or a moment, and which strings a pattern
accepts are rules Souther shares with Raoh, and
[199x-notation](https://github.com/raoh-project/199x-notation) implements them once per language.
The runtime takes the Rust crate, pinned to a commit in `runtime/Cargo.toml`, and Souther's own
runtime takes the Java artifact, so the two backends answer from one account. A pattern is the one
rule that crosses between them: the checker settles what it means, the Java half writes the machine
that meaning is run as as an image with the Java artifact, and the runtime reads the image back with
the crate.

What an amount or an exact quotient is is not written here either. What a `Decimal` is and what
each operation on one answers, including the widest whole number one holds, and what a `Rational`
is — its one form, the four operations, the order and the rounding, none of which builds the power
of ten a `Decimal`'s scale names — are `souther-exact` in
[souther-runtime-rs](https://github.com/souther-lang/souther-runtime-rs), which the native backend's
runtime reads too. The runtime takes it pinned to a commit beside 199x-notation, and what is here is
the cell each is kept in, how text is read into a `Decimal`, and what a failure ends the call as.

The crates allocate, and this runtime has no allocator of its own beyond the arena, so the arena is
what they allocate from. Nothing is given back one allocation at a time; what a call made goes back
with the arena.

The Java half emits the program's own functions and links them onto that module. Placing them reads
no runtime code body — only the section framing, the exports, and the constants a global or a
segment offset is written with. Once they are placed, the link leaves out every function nothing
reaches: the runtime carries every kernel and a program calls a few. That walk reads which function
each call in a body names and copies everything else as it is, so what the Rust toolchain emits
inside a function is still not something this project has to model beyond how an instruction is
encoded. What is left is mostly the data the text rules read, which a fold over a list links to in
about 150 KB.

## The host contract

Taken from [rontolisp](https://github.com/making/rontolisp), so a host that already drives a
rontolisp module drives this one the same way: strings cross as a pointer and a length into the
exported memory, buffers come from `__ronto_alloc`, and a caller brackets a call with
`__ronto_alloc_mark` and `__ronto_alloc_reset`. An export that answers a string answers a live
pointer into the arena, so the reset comes after the bytes have been read out. Of the runtime, a
linked module exports what a host calls and nothing else; the rest is the link's to call.

A behavior this program holds no implementation for is reached the same way whichever of the two
reasons it is — the caller supplies it, or another build already did — because to a caller reaching
in they are the same call, and only one of them has an artifact to be found somewhere. A call out
carries a number rather than a name, and which behavior each number is, is in the module — on the
surface below, as the behavior's `reachOut`, beside its `implementation`, which says which of the two
reasons it is. In the module rather than beside it, so a caller holding one cannot be handed the
wrong other.

What a caller writes code against is in the module too. `souther:surface` is one JSON object: each
module's behaviors, with the export each is called through, who answers it (`here`, `injected`,
`unwritten` or `elsewhere`), the number a call out carries for one the program reaches out for, the
names and types of what it takes and the type it answers — an
answer nobody named as both its `members`, the union as it was written, and its `crossing`, the
leaves those descend to and the form they travel in, because the leaves alone are a union nobody
wrote; and every
declaration those name, with every one the program's modules declare whether or not a behavior names
it. A product says its fields, a newtype the type it is written as, a sum its cases and the form they
travel in (a bare tag, or the tag under one key and a wrapped case under another), and the first two
the rules a value is held to, by name and in the order a failure is decided in. What a rule says is
not there: a caller is told a value broke one, and checking it again in the caller's language would
be the rule written twice. An `option` is where absence is written, and where it stands says how — a
field leaves its key out, and an element or a map's value writes `null`. The object carries a
`version`, which moves when what it says is read differently, and a reader refuses a version it
does not read rather than reading it as one it does: the JavaScript glue refuses a module whose
surface or runtime ABI is not the one it was written for, and a link refuses a runtime of another
ABI.

A value of a type can also be read on its own, outside any behavior — what a form checks one field
against before there is a whole call to make. `__souther_decode(number, pointer, length)` reads the
JSON at the pointer as the type the number names, its rules included, and answers what a behavior's
export answers: `{"value": ...}` or `{"issues": [...]}`, the paths starting at the root. Which types
it reads, and under which number, is a declaration's `decode` on the surface, and only a type a
module of the program declares and publishes has one; one a module keeps is on the surface and is not
offered. The numbers are the module's own. A caller looks a type up by its module and name when it
loads the module and does not carry the number to another, so a module built later that numbers its
types differently is never read under an old one. A number the module gives no type ends the call,
as `NO_SUCH_TYPE`, because it is the caller misusing the module and not a document written wrong.

What the boundary will not read is answered with Raoh's issues, as the language says it is (spec
§decoder-error): each a `path`, a `code`, the `messageKey` that says which of the code's
constraints it was, and the `meta` that constraint carries. A newtype's clause that is a standard
constraint is reported as that constraint — `String.matches` as `invalid_format` with its
`pattern`, a bound as `out_of_range` under `out_of_range.minimum` — and any other clause as
`invariant_violation` naming its `module`, its `type` and, where it has one, its `clause`. Which
issue a value comes to is the language's and not a backend's, so it is written down once, in
[`conformance/issues`](conformance/issues), and this backend and the JVM's are each held to it.

What Souther adds is a way to say why a call ended without a value. An abort writes a fixed-width
record outside the arena and traps; the record carries a generation, so a caller that snapshots it
before the call can tell a Souther abort from an ordinary wasm fault.

`souther.wasm.abi.RuntimeAbi` writes all of this down, and the tests beside it run the module
rather than trusting the writing.

## As a component

`--component` wraps the same core module as a WebAssembly component. A Souther
module becomes an interface and a behavior a function of it:

    world root {
      export souther:program/counting;
    }
    package souther:program {
      interface counting {
        record ended { reason: u32 }
        doubled: func(arguments: string) -> result<string, ended>;
      }
    }

The string is the envelope the core export answers with. What the component adds is who owns the
memory it crosses in: the host lowers the argument through `cabi_realloc` and reads the answer out
of an area in this memory, and the post-return says when the whole of it goes back. That is the
bracket a core caller keeps, moved to where the format states it.

A call the runtime ended answers `err`, with the reason a core module's abort record holds: 3 for
arguments that are not JSON, 6 for a division by zero, and the rest as `RuntimeAbi` numbers them.
A core module traps there and its host reads the record afterwards, which a component cannot
offer, because an instance that has trapped cannot be asked anything again. So when the core module
is linked for a component, the one runtime function every ended call leaves through is replaced by
a throw that carries nothing, and each lifted function catches it and answers the reason the record
holds. The outcome is written to a static area rather than the arena, so a call that ended because
memory ran out is answered too. The instance answers the next call as it would have. A trap the
runtime gave no reason for is not an exception, and no catch stops it, so it stays a trap.

An argument too large for memory is the one failure that is not answered as `err`. The host lowers
it through `cabi_realloc` before the lifted function runs, and the component model lets a realloc
answer a place or fail the call, nothing else. So that call never begins, and the host is told it
failed as it would be of a trap.

A type a module publishes is offered too, to be read on its own as the core module's
`__souther_decode` reads it: one function per type, under a package of its own.

    world root {
      export souther:program/cart;
      export souther:decode/cart;
    }
    package souther:decode {
      interface cart {
        record ended { reason: u32 }
        sku: func(value: string) -> result<string, ended>;
        line-item: func(value: string) -> result<string, ended>;
      }
    }

It takes one value of the type as JSON and answers `{"value": ...}` or `{"issues": [...]}`, or
ends as a behavior does. The
types are not functions of the interface the behaviors are in, because a type and a behavior may
come to one name there (`LineItem` and `line_item`).

A behavior's name is not the same string on both sides — Souther writes one convention and an
interface another — so where two behaviors of a module would come to one interface name, this
refuses rather than exporting one of them twice. It refuses one that comes to `ended` too, which
the interface already gives the record.

A behavior the program does not implement is asked for rather than offered, under a package of its
own:

    world root {
      import souther:reached/rates;
      export souther:program/rates;
    }

One interface says what the program answers and the other what it has to be given, so a name says
which of the two it is. What stands between them is two small core modules. A program reaches out
through a call in its own memory — a behavior's number, where the arguments are, and a buffer of
its own — and a component's call carries a string and answers one, so something has to change one
into the other, and what it needs is the program's memory. That memory does not exist until the
program has been instantiated, which cannot happen until something answers what it reaches out for.
So the first module is instantiated before the program and answers by calling through a table it
exports, which is empty; the program is instantiated against it; the component's own calls are
lowered against the memory that now exists; and the second module, instantiated last, writes those
lowerings into the table as it starts. The table is full before anything outside has been handed
anything that could reach a call through it.

The tests read the component back out of what was written, which says the sections hold what they
were meant to hold and nothing about whether an index in one section names the thing in another —
and a component that reaches out is almost entirely indices between sections. That is the format's
question, so the build asks the format: CI validates a component this writes, and one that reaches
out, with `wasm-tools`.

Validating says each index names something of the right type, and not that a function lifts the
right one: every function takes a string and answers the same result, so a lift naming another
behavior's core function, or another type's reading, is still valid. So CI also calls each
function of one component with `wasmtime` and compares what it answers. The JVM tests run no
component.
The runtime functions a component's canonical calls go through are asked directly instead,
which is where the one question the writing cannot answer — where a result may begin — can be put.
The two modules a program reaches out through are run the same way, against lowerings that write
what a lowering writes, because what could be got wrong about them is which behavior a numbered
call reaches and what it does with a buffer too short to hold the answer.

## What is not written yet

- A behavior whose body nobody has written yet: one that says what it depends on and has no `let`.
  The language takes it as a model on its way to being written, and this backend has nothing to
  write for it, so the program is refused.

## Running it

    souther compile --target wasm src/ -o program.wasm
    souther compile --target wasm src/ -o program.wasm --component
    souther compile --target wasm src/ -o program.wasm --wit program.wit

Everything after `wasm` is this backend's: the `souther` command line reads none of it, finds the
backend's jar and runs it as a process of its own. It finds it in `$SOUTHER_HOME/backends`, or where
a package manager installed it, by `META-INF/souther/backend.properties`, which names the target,
`wasm`, and the Souther the jar was built against. Only a jar built against the Souther that runs it
is chosen; under another, `souther compile --target wasm` is refused before anything is compiled.

The jar `mvn package` writes is a backend like any other, and a clone installs it by putting it
there. One in `$SOUTHER_HOME/backends` stands in for one a package manager installed:

    mvn package
    mkdir -p "$SOUTHER_HOME/backends"
    cp target/souther-wasm-compiler-*-cli.jar "$SOUTHER_HOME/backends/"

`java -jar target/souther-wasm-compiler-*-cli.jar` takes the same arguments, for working on this
repository. Nothing then checks which Souther it was built against.

`--wit` writes what the program offers and what it has to be given, as a reader of interfaces reads
them. The same either way: a component carries it and a core module does not, but what a program
offers is the program's.

A directory is read for the `.sou` files under it, in the order their paths sort, so one command
line is one program every time. Nothing is written where the module would go unless the whole
program compiled: what stopped goes to the error stream, and it says which kind of stop it was —
a program the language refuses answers differently from one this backend does not write yet, and
both differently from a command line that named no compile.

## What a call costs

Reading, sorting and settling grow with what they were given and not with the square of it. That
is asked of the build rather than written down here, because a number written down here would be
this machine's on the day it was measured: `WhatACallCostsGrowsWithWhatItWasGivenTest` hands each
of them four times as much and requires it to cost well under sixteen times as much.

A loose bound on purpose. It does not say a call is fast — it says that reading an object, sorting
a list and settling a set have not gone back to asking every part about every other part, which
three of them were doing while every other test passed. A test that hands over three of something
cannot tell the two shapes apart.

Changing a collection one member at a time is held to a bound of its own, however the body holds
it, and on the room a call takes rather than its time: what copying costs is room, and room is
counted the same on every run and every machine. `ATreeStaysOrderedAndBalancedTest` walks the trees
a set and a map are held as, node by node, and holds each to its order, its sizes and its balance.
A list, a set and a map changed are new ones and the one each was made from stays what it was, as on
the JVM, whose collections are persistent. Here a list joined on writes into its own array where
nothing was made from it yet, and a set or a map put into or taken out of is a tree that shares all
but a path with the one before (`runtime/src/tree.rs`). Copying instead, `List.drop`,
`List.distinct`, `List.partition` and a set grown in a fold took the square of their length, and
`List.drop` over sixty-four thousand elements ran out of memory.

## Calling one from JavaScript

A core module is what a browser reads, so a program compiled here is loaded with
`WebAssembly.instantiateStreaming` and called with no toolchain in between.
[`examples/react-cart`](examples/react-cart) is a domain model priced from a React form: the rules
are in the Souther source and nowhere else, and where the boundary will not read what it was given
it says which part it will not read, as a path into the arguments. It also shows the two things a
caller outside the JVM has to get right: an amount crosses as its digits rather than through a
JavaScript number, and a behavior the model reaches out for is supplied by name, from the list the
module carries.

## What it has been run against

The projects in [souther-lang/examples](https://github.com/souther-lang/examples) are the programs
this is meant to compile, and they are not written against it. Every defect the tests here had no
fixture for came from running them.

The build does not run them. They follow the compiler's own releases, so a build that compiled
whatever they held would fail whenever the two were a release apart, for a reason that is in neither
this repository's change nor the compiler's. Compile them by hand where that is the question:

```sh
souther compile --target wasm <project>/src/main/souther -o /tmp/<project>.wasm
```

`invoicing` is written against a module `sharedmoney` publishes, so it takes both directories.

## Building

    mvn test

The compiler this reads a program through is a published artifact, so a build resolves it the way
it resolves anything else and there is nothing to install first.

## Releasing

The backend is released at its own version, which the jar's manifest records as
`Implementation-Version`; which Souther it works with is the descriptor's to say, so a fix here
releases no Souther. A release is built against a released Souther and nothing else: `souther.version`
in `pom.xml` names one on Maven Central, and `@souther/wasm` asks for a released `@raoh/core`.

Nothing is published from CI. From a clean clone at the release's commit, the jar `mvn package`
writes is attached to the GitHub release, and `npm publish` in `packages/wasm` publishes the package.

## Licence

Eclipse Public License 2.0, except for `src/main/java/souther/wasm/emit`, which is a copy of
[rontolisp](https://github.com/making/rontolisp)'s wasm assembler under the Apache License 2.0 —
the package name was rewritten and nothing else, which each file says at its head. `NOTICE` has the
attribution, and also lists what the runnable jar carries inside it, since distributing that jar
distributes those too.
