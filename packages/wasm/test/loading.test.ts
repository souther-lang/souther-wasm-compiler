// A program, once loaded, is one each of its behaviors can be called on: whatever a call needs, from
// the caller or from the module, is made sure of when it loads, and a module missing any of it is
// refused then, naming what it was, and not on the first call that happens to reach it.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import * as glue from "../src/index.ts";
import { load } from "../src/index.ts";
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

const SUPPLIED = { "clock.now": () => 100n, "clock.today": () => 0n };

/**
 * The module with one name it imports or exports written as another of the same length: the name
 * as the module's import or export section holds it, its length before it, and nowhere else, so
 * what the surface says of it is left as it was.
 */
function renamed(bytes: Uint8Array, from: string, to: string): Uint8Array {
  assert.equal(to.length, from.length);
  const held = new Uint8Array([from.length, ...new TextEncoder().encode(from)]);
  const at: number[] = [];
  for (let i = 0; i + held.length <= bytes.length; i++) {
    if (held.every((byte, k) => bytes[i + k] === byte)) {
      at.push(i);
    }
  }
  assert.equal(at.length, 1, `${from} is named once by its length`);
  const copy = new Uint8Array(bytes);
  copy.set(new TextEncoder().encode(to), at[0]! + 1);
  return copy;
}

// One test at a time: one of them watches what is instantiated, which every other one does too.
describe("loading", () => {
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

  // Instantiating a module runs it, so what it needs of the caller is made sure of before.
  it("refuses what was not supplied before the module is instantiated", async (t) => {
    const { bytes } = await compiled(CLOCK);
    const instantiate = t.mock.method(WebAssembly, "instantiate");
    await assert.rejects(load(bytes, { "clock.today": () => 0n }), /no function was supplied for: clock\.now$/);
    assert.equal(instantiate.mock.callCount(), 0);
    await load(bytes, SUPPLIED);
    assert.equal(instantiate.mock.callCount(), 1);
  });

  it("makes a program by loading one and in no other way", () => {
    assert.equal("Program" in glue, false);
  });

  it("calls what it was loaded with, whatever is put under the name afterwards", async () => {
    const { bytes } = await compiled(CLOCK);
    const supplied: Record<string, () => bigint> = { ...SUPPLIED };
    const program = await load(bytes, supplied);
    supplied["clock.now"] = () => 200n;
    assert.equal(program.call("clock.later", [1n]).value, 101n);
    delete supplied["clock.now"];
    assert.equal(program.call("clock.later", [2n]).value, 102n);
  });

  it("calls what answers as a function, and not as a method of anything", async () => {
    const { bytes } = await compiled(CLOCK);
    const seen: unknown[] = [];
    const program = await load(bytes, {
      "clock.now": function (this: unknown) {
        seen.push(this);
        return 1n;
      },
      "clock.today": () => 0n,
    });
    assert.equal(program.call("clock.later", [1n]).value, 2n);
    assert.deepEqual(seen, [undefined]);
  });

  it("refuses a module importing anything but what this glue hands it, naming it", async () => {
    const { bytes } = await compiled(CLOCK);
    await assert.rejects(load(renamed(bytes, "host_call", "host_ring"), SUPPLIED),
      /does not hand it: souther\.host_ring \(function\)$/);
  });

  it("refuses a module lacking what the runtime exports, naming it", async () => {
    const { bytes } = await compiled(CLOCK);
    await assert.rejects(load(renamed(bytes, "__souther_failure_addr", "__souther_failure_adds"), SUPPLIED),
      /does not export what this glue calls: __souther_failure_addr \(function\)$/);
    await assert.rejects(load(renamed(bytes, "memory", "memorx"), SUPPLIED),
      /does not export what this glue calls: memory \(memory\)$/);
  });

  it("refuses a module lacking a behavior its surface says it exports, naming it", async () => {
    const { bytes } = await compiled(CLOCK);
    await assert.rejects(load(renamed(bytes, "clock.later", "clock.latex"), SUPPLIED),
      /does not export what this glue calls: clock\.later \(function\)$/);
  });
});
