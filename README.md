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

The Java half emits the program's own functions and links them onto that module. It never reads a
runtime code body — only the section framing, the exports, and the constants a global or a segment
offset is written with — so what the Rust toolchain emits inside a function is not something this
project has to model.

## The host contract

Taken from [rontolisp](https://github.com/making/rontolisp), so a host that already drives a
rontolisp module drives this one the same way: strings cross as a pointer and a length into the
exported memory, buffers come from `__ronto_alloc`, and a caller brackets a call with
`__ronto_alloc_mark` and `__ronto_alloc_reset`. An export that answers a string answers a live
pointer into the arena, so the reset comes after the bytes have been read out.

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
refuses rather than exporting one of them twice. A behavior supplied from outside is refused as
well: what it sends out is a call in this module's own memory, which is not a thing a component
carries, and it has no crossing of its own yet.

The tests read the component back out of what was written. They do not run one: nothing here can.
The three runtime functions a component's canonical calls go through are asked directly instead,
which is where the one question the writing cannot answer — where a result may begin — can be put.

## What is not written yet

- A type declared over another — `data ProductId = String` — which is written as what it is declared
  over rather than as an object. It reaches this backend as one field called `value`, which is also
  what `data Wrapper = { value: String }` reaches it as, and those two are written differently. Both
  are refused rather than one of them answered for by the other. Telling them apart is a question
  for `CheckedProgram`.
- A behavior supplied from outside, in a component. A core module reaches out for one.
- A behavior another build implements. This links one program.

## Running it

    mvn package
    java -jar target/souther-wasm-compiler-*-cli.jar src/ -o program.wasm
    java -jar target/souther-wasm-compiler-*-cli.jar src/ -o program.wasm --component
    java -jar target/souther-wasm-compiler-*-cli.jar src/ -o program.wasm --wit program.wit

`--wit` writes what the program offers, as a reader of interfaces reads it. The same either way: a
component carries it and a core module does not, but what a program offers is the program's.

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

## Building

    mvn test

## Licence

Eclipse Public License 2.0, except for `src/main/java/souther/wasm/emit`, which is a copy of
[rontolisp](https://github.com/making/rontolisp)'s wasm assembler under the Apache License 2.0 —
the package name was rewritten and nothing else, which each file says at its head. `NOTICE` has the
attribution, and also lists what the runnable jar carries inside it, since distributing that jar
distributes those too.
