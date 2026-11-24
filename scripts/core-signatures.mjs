// The core function every import and export of a core module is, one line each, read from what
// `wasm-tools print` writes of it.
//
// What a component's typed functions come to as core functions is the Canonical ABI's to say, and
// `wasm-tools component embed --dummy` writes a core module that says it: one import or export per
// function, of the core type the ABI flattens it to. So CI writes this for the typed interfaces a
// test holds to their version, and requires it to be the file the test reads, which is the file
// the compiler's own flattening is held to.
//
//     wasm-tools print core.wasm | node scripts/core-signatures.mjs

import { readFileSync } from "node:fs";

const text = readFileSync(0, "utf8");
const types = new Map();
const funcs = new Map();
const lines = [];

for (const [, index, signature] of text.matchAll(/\(type \(;(\d+);\) \(func ?(.*?)\)\)\n/g)) {
  types.set(index, signature.trim());
}
for (const [, module, name, index, type] of text.matchAll(
    /\(import "([^"]*)" "([^"]*)" \(func \(;(\d+);\) \(type (\d+)\)\)\)/g)) {
  funcs.set(index, type);
  lines.push(`import "${module}" "${name}" ${types.get(type)}`);
}
for (const [, index, type] of text.matchAll(/\n\s*\(func \(;(\d+);\) \(type (\d+)\)/g)) {
  funcs.set(index, type);
}
for (const [, name, index] of text.matchAll(/\(export "([^"]*)" \(func (\d+)\)\)/g)) {
  lines.push(`export "${name}" ${types.get(funcs.get(index))}`);
}
process.stdout.write(lines.map((line) => line.trimEnd() + "\n").join(""));
