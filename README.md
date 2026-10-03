# souther-wasm-compiler

Compiles a checked Souther program to a WebAssembly module.

A behavior becomes an export that takes JSON and answers JSON. The values in between live in
linear memory, in an arena the caller pops after every call, so the output runs wherever core wasm
runs rather than only where a garbage collector and a component runtime do.

## What it reads

`CheckedProgram` and what is reachable from it, and nothing else of the Souther compiler. Anything
this needs that the program API does not carry is a question for Souther rather than something to
reach around, so it is raised there.

## The two halves

The kernels a Souther program calls — string, map, set, list, temporal, decimal, int — are written
in Rust under `runtime/` and compiled to wasm ahead of time. The compiled module is carried in
`src/main/resources` and every build links against that copy, so building this project needs no
Rust toolchain. Changing the runtime does: rebuild it with

    cd runtime && cargo build --release
    cp target/wasm32-unknown-unknown/release/souther_wasm_runtime.wasm \
       ../src/main/resources/souther/wasm/runtime.wasm

What a string means is not written here. Its order, its length, its case, its canonical form,
which characters are white space, which text is a day or a moment, and which strings a pattern
accepts are rules Souther shares with Raoh, and
[199x-notation](https://github.com/raoh-project/199x-notation) implements them once per language.
The runtime takes the Rust crate, pinned to a commit in `runtime/Cargo.toml`, and Souther's own
runtime takes the Java artifact, so the two backends answer from one account. A pattern is the one
rule that crosses between them: the checker settles what it means, the Java half writes the machine
that meaning is run as as an image with the Java artifact, and the runtime reads the image back with
the crate.

The crate allocates, and this runtime has no allocator of its own beyond the arena, so the arena is
what it allocates from. Nothing is given back one allocation at a time; what a call made goes back
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
in they are the same call, and only one of them has an artifact to be found somewhere. Which it is
is in the module: `souther:crossings` says what each number a call out carries is the name of, and
which of them another build implements. In the module rather than beside it, so a caller holding
one cannot be handed the wrong other.

What a caller writes code against is in the module too. `souther:surface` is one JSON object: each
module's behaviors, with the export each is called through, who answers it (`here`, `injected`,
`unwritten` or `elsewhere`), the names and types of what it takes and the type it answers; and every
declaration those name, with every one the program's modules declare whether or not a behavior names
it. A product says its fields, a newtype the type it is written as, a sum its cases and the form they
travel in (a bare tag, or the tag under one key and a wrapped case under another), and the first two
the rules a value is held to, by name and in the order a failure is decided in. What a rule says is
not there: a caller is told a value broke one, and checking it again in the caller's language would
be the rule written twice. An `option` is where absence is written, and where it stands says how — a
field leaves its key out, and an element or a map's value writes `null`. The object carries a
`version`, which moves when what it says is read differently.

What Souther adds is a way to say why a call ended without a value. An abort writes a fixed-width
record outside the arena and traps; the record carries a generation, so a caller that snapshots it
before the call can tell a Souther abort from an ordinary wasm fault.

`souther.wasm.abi.RuntimeAbi` writes all of this down, and the tests beside it run the module
rather than trusting the writing.

## As a component

`WasmCompiler.compileAsComponent` wraps the same core module as a WebAssembly component. A Souther
module becomes an interface and a behavior a function of it:

    world root {
      export souther:program/counting;
    }
    package souther:program {
      interface counting {
        doubled: func(arguments: string) -> string;
      }
    }

The envelope is the one the core export answers with. What the component adds is who owns the
memory it crosses in: the host lowers the argument through `cabi_realloc` and reads the answer out
of an area in this memory, and the post-return says when the whole of it goes back. That is the
bracket a core caller keeps, moved to where the format states it.

A behavior's name is not the same string on both sides — Souther writes one convention and an
interface another — so where two behaviors of a module would come to one interface name, this
refuses rather than exporting one of them twice.

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

They do not run one: nothing here can.
The three runtime functions a component's canonical calls go through are asked directly instead,
which is where the one question the writing cannot answer — where a result may begin — can be put.
The two modules a program reaches out through are run the same way, against lowerings that write
what a lowering writes, because what could be got wrong about them is which behavior a numbered
call reaches and what it does with a buffer too short to hold the answer.

## What is not written yet

- `Rational`. A quotient of two `Int`s is one, and a program that divides two of them is refused
  as one this backend does not write yet.

## Running it

    mvn package
    java -jar target/souther-wasm-compiler-*-cli.jar src/ -o program.wasm
    java -jar target/souther-wasm-compiler-*-cli.jar src/ -o program.wasm --component
    java -jar target/souther-wasm-compiler-*-cli.jar src/ -o program.wasm --wit program.wit

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
java -jar target/*-cli.jar <project>/src/main/souther -o /tmp/<project>.wasm
```

`invoicing` is written against a module `sharedmoney` publishes, so it takes both directories.

## Building

    mvn test

The compiler this reads a program through is a published artifact, so a build resolves it the way
it resolves anything else and there is nothing to install first.

## Licence

Eclipse Public License 2.0, except for `src/main/java/souther/wasm/emit`, which is a copy of
[rontolisp](https://github.com/making/rontolisp)'s wasm assembler under the Apache License 2.0 —
the package name was rewritten and nothing else, which each file says at its head. `NOTICE` has the
attribution, and also lists what the runnable jar carries inside it, since distributing that jar
distributes those too.
