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

- A `Map` keyed by a `Date`, a `Time`, a `DateTime`, an `Instant` or a declared enumeration. What a
  key of one of those is written as is a rule of its own, not something read off the key's type.
- `Instant`.
- A behavior supplied from outside, in a component. A core module reaches out for one.
- A behavior another build implements. This links one program.

## Building

    mvn test

## Licence

Eclipse Public License 2.0, except for `src/main/java/souther/wasm/emit`, which is a copy of
rontolisp's wasm assembler under the Apache License 2.0. See `NOTICE`.
