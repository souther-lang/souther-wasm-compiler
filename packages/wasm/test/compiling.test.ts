// Every test compiles through compiled.ts.
//
// A test that starts the compiler for itself starts it again for every test that asks for the same
// model, which is how these tests came to start it three times for one model. What a test may do is
// held here rather than remembered.

import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { it } from "node:test";

it("starts the compiler only in compiled.ts", () => {
  const starting = readdirSync(import.meta.dirname)
    .filter((name) => name.endsWith(".ts"))
    .filter((name) => /\bexecFile(Sync)?\(\s*["']java["']|\brun\(\s*["']java["']/
      .test(readFileSync(join(import.meta.dirname, name), "utf-8")));
  assert.deepEqual(starting, ["compiled.ts"]);
});
