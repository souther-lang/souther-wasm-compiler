/**
 * A WebAssembly binary assembler and section reader, copied from rontolisp's {@code am.ik.wasm}.
 *
 * <p>Copied rather than depended on. What this compiler asks of a wasm writer changes as the
 * Rust runtime's output does — a section reader has to know the opcodes it walks past, and a
 * runtime compiled from Rust emits instructions a Lisp backend never had to write — so the cost
 * of a change here is a local edit rather than a release somewhere else and a wait.
 *
 * <p>Source: <a href="https://github.com/making/rontolisp">making/rontolisp</a>, Apache License
 * 2.0, at commit {@code e599ae10bd377e673263ec4e99e3e13d0639015a}. The package was renamed;
 * nothing else was changed on the way in, so a later upstream fix is a diff against that commit.
 * The attribution is in this repository's {@code NOTICE}.
 */
package souther.wasm.emit;
