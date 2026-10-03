// What a value read at the boundary comes to, held on the TypeScript side to the same fixtures the
// JVM and the wasm module are held to (conformance/issues), down to the sentence a person reads.
//
// The compiler is the repository's own, run as a command: a module compiled from each fixture's
// model is loaded through this package, each case is read as its type, and what comes back is held
// to the fixture's issues and what this package says of them to the messages the JVM's resolver
// wrote from Raoh's catalog.

import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import { amount, load, messageOf, type Issue, type Program } from "../src/index.ts";

const ROOT = join(import.meta.dirname, "..", "..", "..");
const FIXTURES = join(ROOT, "conformance", "issues");

interface Case {
  readonly about: string;
  readonly type: string;
  readonly input: unknown;
  readonly expect: "value" | readonly Issue[];
  readonly messages?: { readonly en: readonly string[]; readonly ja: readonly string[] };
}

/** A fixture, its numbers read as the digits they were written as, so none is rounded on the way. */
function fixture(file: string): { model: string; cases: Case[] } {
  return JSON.parse(readFileSync(file, "utf-8"), (_key, value, context?: { source?: string }) =>
    typeof value === "number" && context?.source !== undefined ? amount(context.source) : value);
}

/** The same fixture, read as plain JSON, for what is expected. */
function expected(file: string): { cases: Case[] } {
  return JSON.parse(readFileSync(file, "utf-8"));
}

async function compiled(model: string): Promise<Program> {
  const at = mkdtempSync(join(tmpdir(), "conformance-"));
  writeFileSync(join(at, "model.sou"), model);
  const jar = readdirSync(join(ROOT, "target")).find((name) => name.endsWith("-cli.jar"));
  assert.ok(jar, "the compiler is built: mvn package at the repository's root");
  execFileSync("java", ["-jar", join(ROOT, "target", jar), at, "-o", join(at, "model.wasm")]);
  return load(readFileSync(join(at, "model.wasm")));
}

for (const name of readdirSync(FIXTURES).filter((each) => each.endsWith(".json")).sort()) {
  const file = join(FIXTURES, name);
  const { model, cases } = fixture(file);
  const wanted = expected(file).cases;
  describe(name, async () => {
    const program = await compiled(model);
    cases.forEach((each, at) => {
      it(each.about, () => {
        const read = program.decode(each.type, each.input);
        const expect = wanted[at].expect;
        if (expect === "value") {
          assert.ok(read.issues === undefined, `${each.about}: ${JSON.stringify(read.issues)}`);
          return;
        }
        assert.deepEqual(read.issues, expect);
        const messages = wanted[at].messages;
        assert.ok(messages, "a case with issues says what is read of them");
        assert.deepEqual(read.issues?.map((issue) => messageOf(issue, "en")), messages.en);
        assert.deepEqual(read.issues?.map((issue) => messageOf(issue, "ja")), messages.ja);
      });
    });
  });
}
