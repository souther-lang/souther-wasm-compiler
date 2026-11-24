// What the binding writes as an identifier is what TypeScript reads as one: the table the binding
// carries is held to the TypeScript this package is built with for every code point.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import ts from "typescript";
import { isIdentifierPart, isIdentifierStart, OLDEST_TYPESCRIPT } from "../src/ts-identifier-table.ts";

describe("the identifier table", () => {
  it("holds only what the TypeScript the package is built with reads, for every code point", () => {
    const wrong: string[] = [];
    for (let cp = 0; cp <= 0x10ffff; cp++) {
      if (isIdentifierStart(cp) && !ts.isIdentifierStart(cp, ts.ScriptTarget.ES2020)) {
        wrong.push(`start U+${cp.toString(16)}`);
      }
      if (isIdentifierPart(cp) && !ts.isIdentifierPart(cp, ts.ScriptTarget.ES2020)) {
        wrong.push(`part U+${cp.toString(16)}`);
      }
    }
    assert.deepEqual(wrong.slice(0, 10), [], "node scripts/generate-identifier-table.mjs, to write it again");
  });

  it("holds what a model is written in, and not what TypeScript does not read", () => {
    // Not empty, nor short of what was there before it was read off TypeScript: the check above only
    // says what the table holds is read, not that it holds anything.
    for (const each of ["a", "Z", "_", "$", "保", "ä", "α", "\u{10428}"]) {
      assert.ok(isIdentifierStart(each.codePointAt(0)!), each);
    }
    for (const each of ["1", "-", " ", "\u088f"]) {
      assert.equal(isIdentifierStart(each.codePointAt(0)!), false, each);
    }
    assert.ok(isIdentifierPart("1".codePointAt(0)!));
    assert.ok(isIdentifierPart(0x200c));
    assert.equal(isIdentifierPart("-".codePointAt(0)!), false);
    // U+088F is a Souther name's start and is not in TypeScript's table. Whether Node's own table holds
    // it depends on the Node that runs this, which is why the binding does not ask Node.
  });

  it("is for a TypeScript the README names", () => {
    assert.match(readFileSync(join(import.meta.dirname, "..", "README.md"), "utf8"),
      new RegExp(`TypeScript ${OLDEST_TYPESCRIPT.split(".").slice(0, 2).join("\\.")} or later`));
  });
});
