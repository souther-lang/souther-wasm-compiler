// What the package's tests compile, compiled once for every test that asks for the same model, and
// the one way they compile: `compiling.test.ts` holds every other test file to asking here.
//
// The compiler is the repository's own, run as a command, so what compiling costs is a JVM started.
// A model asked for again is answered from what was started for it the first time, and models
// asked for together are compiled side by side.

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";

/** The repository's root, where the compiler is built. */
export const ROOT = join(import.meta.dirname, "..", "..", "..");

/** A compiled model: the directory it was compiled in, and the module. */
export interface Compiled {
  readonly at: string;
  readonly bytes: Uint8Array<ArrayBuffer>;
}

const run = promisify(execFile);
const held = new Map<string, Promise<Compiled>>();

/** The module compiled from `model`, a source or one per module, once for every test that asks for it. */
export function compiled(model: string | readonly string[]): Promise<Compiled> {
  const sources = typeof model === "string" ? [model] : model;
  const asked = JSON.stringify(sources);
  let answer = held.get(asked);
  if (answer === undefined) {
    answer = compiling(sources);
    held.set(asked, answer);
  }
  return answer;
}

async function compiling(sources: readonly string[]): Promise<Compiled> {
  const at = mkdtempSync(join(tmpdir(), "souther-wasm-"));
  sources.forEach((source, n) => writeFileSync(join(at, `model${n}.sou`), source));
  const jar = readdirSync(join(ROOT, "target")).find((name) => name.endsWith("-cli.jar"));
  assert.ok(jar, "the compiler is built: mvn package at the repository's root");
  await run("java", ["-jar", join(ROOT, "target", jar), at, "-o", join(at, "model.wasm")]);
  return { at, bytes: new Uint8Array(readFileSync(join(at, "model.wasm"))) };
}
