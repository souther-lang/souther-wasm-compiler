// What a value read at the boundary comes to, held on the TypeScript side to the same fixtures the
// JVM and the wasm module are held to (conformance/issues), down to the sentence a person reads.
//
// The compiler is the repository's own, run as a command: a module compiled from each fixture's
// model is loaded through this package, each case is read as its type, and what comes back is held
// to the fixture's issues and what this package says of them to the messages the JVM's resolver
// wrote from Raoh's catalog.

import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { Decimal, type Issue, JsonNumber, parse } from "@raoh/core";
import { amount, load, messageOf, type Program } from "../src/index.ts";
import { compiled, ROOT } from "./compiled.ts";

const FIXTURES = join(ROOT, "conformance", "issues");

interface Case {
  readonly about: string;
  readonly type: string;
  readonly input: unknown;
  readonly expect: "value" | readonly unknown[];
  readonly messages?: { readonly en: readonly string[]; readonly ja: readonly string[] };
}

/** A fixture, its numbers read as the digits they were written as, so none is rounded on the way. */
function fixture(file: string): { model: string; cases: Case[] } {
  return JSON.parse(readFileSync(file, "utf-8"), (_key, value, context?: { source?: string }) =>
    typeof value === "number" && context?.source !== undefined ? amount(context.source) : value);
}

/**
 * The same fixture, read as Raoh reads JSON, for what is expected: every number the text of the
 * amount it is, as an issue's metadata states it.
 */
function expected(file: string): { cases: Case[] } {
  return plain(parse(readFileSync(file, "utf-8"))) as { cases: Case[] };
}

/** A value read by Raoh's `parse` as plain JSON values, each number the text of its amount. */
function plain(value: unknown): unknown {
  if (value instanceof JsonNumber) {
    return amountText(value.lexeme);
  }
  if (value instanceof Map) {
    return Object.fromEntries([...value].map(([name, each]) => [name, plain(each)]));
  }
  return Array.isArray(value) ? value.map(plain) : value;
}

function amountText(lexeme: string): string {
  return /^-?[0-9]+$/.test(lexeme) ? BigInt(lexeme).toString() : String(Decimal.parse(lexeme));
}

/** An issue as the module wrote it, each metadata number the text of the amount it was read as. */
function written(issue: Issue): unknown {
  const meta = (value: unknown): unknown => {
    if (typeof value === "bigint" || value instanceof Decimal) {
      return amountText(String(value));
    }
    if (Array.isArray(value)) {
      return value.map(meta);
    }
    return typeof value === "object" && value !== null
      ? Object.fromEntries(Object.entries(value).map(([name, each]) => [name, meta(each)]))
      : value;
  };
  return {
    path: issue.path.toString(),
    code: issue.code,
    messageKey: issue.messageKey,
    meta: meta(issue.meta),
    ...(issue.givenMessage === undefined ? {} : { message: issue.givenMessage }),
  };
}

async function loaded(model: string): Promise<Program> {
  return load((await compiled(model)).bytes);
}

// Every fixture's module is compiled at once, side by side, before any is read: what compiling costs
// is a compiler started, and starting three one after another is waiting three times.
const names = readdirSync(FIXTURES).filter((each) => each.endsWith(".json")).sort();
const programs = new Map(names.map((name) => [name, loaded(fixture(join(FIXTURES, name)).model)]));

for (const name of names) {
  const file = join(FIXTURES, name);
  const { cases } = fixture(file);
  const wanted = expected(file).cases;
  describe(name, async () => {
    const program = await programs.get(name)!;
    cases.forEach((each, at) => {
      it(each.about, () => {
        const read = program.decode(each.type, each.input);
        const expect = wanted[at].expect;
        if (expect === "value") {
          assert.ok(read.issues === undefined, `${each.about}: ${JSON.stringify(read.issues)}`);
          return;
        }
        const issues = read.issues?.list ?? [];
        assert.deepEqual(issues.map(written), expect);
        const messages = wanted[at].messages;
        assert.ok(messages, "a case with issues says what is read of them");
        assert.deepEqual(issues.map((issue) => messageOf(issue, "en")), messages.en);
        assert.deepEqual(issues.map((issue) => messageOf(issue, "ja")), messages.ja);
      });
    });
  });
}
