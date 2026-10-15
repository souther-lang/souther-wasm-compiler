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

const USAGE = "usage: souther-wasm-bindings <module.wasm> -o <binding.ts> [--runtime <specifier>]\n";

/** Each option that names something, and what it names. */
const NAMING = { "-o": "file", "--runtime": "specifier" } as const;

// Every part of the line is read before anything is said of it, and each part that is wrong is said
// on its own, so a reader is not left to compare the line with the usage to find which part it was.
const args = process.argv.slice(2);
const modules: string[] = [];
const named = new Map<string, string>();
const asked = new Set<string>();
const complaints: string[] = [];
for (let i = 0; i < args.length; i++) {
  const held = args[i]!;
  if (held === "-h" || held === "--help") {
    process.stdout.write(USAGE);
    process.exit(0);
  }
  if (Object.hasOwn(NAMING, held)) {
    const option = held as keyof typeof NAMING;
    asked.add(option);
    // What follows an option that names something is what it names, unless it is another option: a
    // line that left it out does not write to a file named after the option that came next.
    const next = args[i + 1];
    if (next === undefined || next.startsWith("-")) {
      complaints.push(`${option} names no ${NAMING[option]}`);
    } else if (named.has(option)) {
      i++;
      complaints.push(`${option} is given more than once, and names one ${NAMING[option]}`);
    } else {
      named.set(option, next);
      i++;
    }
  } else if (held.startsWith("-")) {
    complaints.push(`no such option: ${held}`);
  } else {
    modules.push(held);
  }
}
if (modules.length === 0) {
  complaints.push("no module named to write the binding of");
} else if (modules.length > 1) {
  complaints.push(`a binding is of one module, and this names ${modules.length}: ${modules.join(", ")}`);
}
if (!asked.has("-o")) {
  complaints.push("-o is required: it names where to write the binding");
}
if (complaints.length > 0) {
  process.stderr.write(complaints.map((each) => `${each}\n`).join("") + USAGE);
  process.exit(2);
}

const [source] = modules as [string];
const output = named.get("-o")!;
const module = await WebAssembly.compile(readFileSync(source));
const [surface, held] = surfaceOf(module);
writeFileSync(output, bindingFor(surface, await fingerprintOf(held), named.get("--runtime")));
process.stdout.write(`wrote ${output} from ${source}\n`);
