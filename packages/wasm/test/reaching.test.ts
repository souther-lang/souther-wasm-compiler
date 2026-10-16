// What a program reaches out for is run once for each time it reaches out, whatever its answer is.
//
// A supplied function is the page's own code, and what it does besides answering — storing a row,
// handing out an id — happens as many times as it is called. So how long an answer is cannot be a
// reason to call it again (issue #62).

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { load } from "../src/index.ts";
import { compiled } from "./compiled.ts";

const PROBE = `module example.probe exposing (Id, Title, Row, storeRow, readRows, rowCount)

data Id = Int
    invariant value >= 1

data Title = String
    invariant String.length(value) >= 1

data Row = { id: Id, title: Title }

behavior storeRow : (title: Title) -> Row

behavior readRows : () -> List<Row>

behavior rowCount : (rows: List<Row>) -> Int

let rowCount (rows) = List.length(rows)
`;

/** A store the program reaches out to, which keeps every row it is handed and numbers each. */
function store() {
  const rows: { id: bigint; title: string }[] = [];
  return {
    rows,
    supplied: {
      "example.probe.storeRow": (title: string) => {
        const row = { id: BigInt(rows.length + 1), title };
        rows.push(row);
        return row;
      },
      "example.probe.readRows": () => rows,
    },
  };
}

describe("reaching out", () => {
  // 256 bytes was the buffer a module once offered ahead of the answer; 70 000 is past the memory a
  // module starts with, so putting the answer in grows it.
  it("runs a supplied function once whatever the length of its answer", async () => {
    const { bytes } = await compiled(PROBE);
    for (const length of [200, 255, 1000, 70_000]) {
      const { rows, supplied } = store();
      const program = await load(bytes, supplied);
      const reading = program.call("example.probe.storeRow", ["x".repeat(length)]);
      assert.equal(reading.issues, undefined);
      assert.equal(rows.length, 1, `rows stored for a title of ${length}`);
      assert.deepEqual(reading.value, { id: 1n, title: "x".repeat(length) });
    }
  });

  it("answers with what the one run answered, where each run answers something else", async () => {
    const { bytes } = await compiled(PROBE);
    const { rows, supplied } = store();
    const program = await load(bytes, supplied);
    const first = program.call("example.probe.storeRow", ["x".repeat(1000)]);
    const second = program.call("example.probe.storeRow", ["y".repeat(1000)]);
    assert.deepEqual(first.value, { id: 1n, title: "x".repeat(1000) });
    assert.deepEqual(second.value, { id: 2n, title: "y".repeat(1000) });
    assert.equal(rows.length, 2);
  });

  it("measures an answer in bytes, not in characters", async () => {
    const { bytes } = await compiled(PROBE);
    const { rows, supplied } = store();
    const program = await load(bytes, supplied);
    const title = "日本語🍣".repeat(30);
    assert.deepEqual(program.call("example.probe.storeRow", [title]).value, { id: 1n, title });
    assert.equal(rows.length, 1);
  });

  it("reads a list longer than one row once", async () => {
    const { bytes } = await compiled(PROBE);
    const { supplied } = store();
    let reads = 0;
    const program = await load(bytes, {
      ...supplied,
      "example.probe.readRows": () => {
        reads += 1;
        return supplied["example.probe.readRows"]();
      },
    });
    for (let n = 0; n < 20; n++) {
      program.call("example.probe.storeRow", ["a title of some length".repeat(4)]);
    }
    assert.equal((program.call("example.probe.readRows", []).value as unknown[]).length, 20);
    assert.equal(reads, 1);
  });

  it("ends the call where a supplied function throws, without running it again", async () => {
    const { bytes } = await compiled(PROBE);
    let calls = 0;
    const program = await load(bytes, {
      "example.probe.storeRow": () => {
        calls += 1;
        throw new Error("the store is down");
      },
      "example.probe.readRows": () => [],
    });
    assert.throws(() => program.call("example.probe.storeRow", ["milk"]), /the store is down/);
    assert.equal(calls, 1);
  });
});
