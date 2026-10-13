// What a program reaches out for is answered by what it was loaded with, and a program is not
// loaded without it: a caller missing one is told so when it loads, and not on the first call that
// happens to reach it.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { load, Program, surfaceOf } from "../src/index.ts";
import { compiled } from "./compiled.ts";

const CLOCK = `module clock

behavior now : () -> Int

behavior today : () -> Int

behavior later : (n: Int) -> Int
    depends on now

let later (n, now) = now() + n

behavior tomorrow : (n: Int) -> Int
    depends on today

let tomorrow (n, today) = today() + n
`;

describe("loading", { concurrency: true }, () => {
  it("refuses a program reaching out for what nothing was supplied for, naming each", async () => {
    const { bytes } = await compiled(CLOCK);
    await assert.rejects(load(bytes), /no function was supplied for: clock\.now, clock\.today$/);
    await assert.rejects(load(bytes, { "clock.now": () => 1n }), /no function was supplied for: clock\.today$/);
  });

  it("refuses something other than a function supplied for what the program reaches out for", async () => {
    const { bytes } = await compiled(CLOCK);
    const supplied = { "clock.now": 1n, "clock.today": () => 1n } as never;
    await assert.rejects(load(bytes, supplied), /no function was supplied for: clock\.now$/);
  });

  // Refused by the program as it is made, from the surface alone and before there is any instance
  // to call: no way of making one holds a program that would find out on a call.
  it("makes no program from a surface whose reaching out is not all answered", async () => {
    const { bytes } = await compiled(CLOCK);
    const [surface] = surfaceOf(await WebAssembly.compile(bytes));
    assert.throws(() => new Program({ "clock.today": () => 1n }, surface), /no function was supplied for: clock\.now$/);
  });

  it("calls what it was loaded with, whatever is put under the name afterwards", async () => {
    const { bytes } = await compiled(CLOCK);
    const supplied: Record<string, () => bigint> = { "clock.now": () => 100n, "clock.today": () => 0n };
    const program = await load(bytes, supplied);
    supplied["clock.now"] = () => 200n;
    assert.equal(program.call("clock.later", [1n]).value, 101n);
    delete supplied["clock.now"];
    assert.equal(program.call("clock.later", [2n]).value, 102n);
  });
});
