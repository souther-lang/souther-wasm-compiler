#!/usr/bin/env node
// Writes the TypeScript binding of a compiled Souther module.
//
//     souther-wasm-bindings <module.wasm> -o <binding.ts> [--runtime <specifier>]
//
// What is written is read off the module and nothing else, so the binding is written again
// whenever the module is: a binding generated from one module refuses to load another.

import { readFileSync, writeFileSync } from "node:fs";
import { bindingFor } from "./generate.ts";
import { fingerprintOf, surfaceOf } from "./index.ts";

const args = process.argv.slice(2);
const output = args.indexOf("-o");
const runtime = args.indexOf("--runtime");
// What an option is given is not the module, whichever order they are written in.
const given = new Set([output, runtime].filter((at) => at >= 0).map((at) => at + 1));
const source = args.find((each, at) => !each.startsWith("-") && !given.has(at));
if (source === undefined || output < 0 || args[output + 1] === undefined) {
  process.stderr.write("usage: souther-wasm-bindings <module.wasm> -o <binding.ts> [--runtime <specifier>]\n");
  process.exit(2);
}
const module = await WebAssembly.compile(readFileSync(source));
const [surface, held] = surfaceOf(module);
writeFileSync(args[output + 1], bindingFor(surface, await fingerprintOf(held),
  runtime < 0 ? undefined : args[runtime + 1]));
process.stdout.write(`wrote ${args[output + 1]} from ${source}\n`);
