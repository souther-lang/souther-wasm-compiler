// What the binding writes as an identifier is what TypeScript reads as one: the table the binding
// carries is held to the TypeScript this package is built with for every code point.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import ts from "typescript";
import { isIdentifierPart, isIdentifierStart, TYPESCRIPT } from "../src/ts-identifier-table.ts";

describe("the identifier table", () => {
  it("is read off the TypeScript the package is built with", () => {
    assert.equal(TYPESCRIPT, ts.version, "node scripts/generate-identifier-table.mjs, to read it again");
  });

  it("says of every code point what TypeScript says", () => {
    const wrong: string[] = [];
    for (let cp = 0; cp <= 0x10ffff; cp++) {
      if (isIdentifierStart(cp) !== ts.isIdentifierStart(cp, ts.ScriptTarget.ES2020)) {
        wrong.push(`start U+${cp.toString(16)}`);
      }
      if (isIdentifierPart(cp) !== ts.isIdentifierPart(cp, ts.ScriptTarget.ES2020)) {
        wrong.push(`part U+${cp.toString(16)}`);
      }
    }
    assert.deepEqual(wrong.slice(0, 10), []);
  });

  it("refuses U+088F, which Souther admits and TypeScript's table does not", () => {
    // Whether Node's own table holds it depends on the Node that runs this, which is why the binding
    // does not ask Node.
    assert.equal(isIdentifierStart(0x88f), false);
  });
});
