// What a program reaches out for is run once for each time it reaches out, whatever its answer is.
//
// A supplied function is the page's own code, and what it does besides answering — storing a row,
// handing out an id — happens as many times as it is called. So how long an answer is cannot be a
// reason to call it again (issue #62). And what it answers is held to the type its behavior declares:
// an answer that is not one ends the call as the supplied function's fault, saying where it is
// wrong, rather than become a value the program runs on (issue #63).

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { Ended, InvalidSuppliedAnswer, load } from "../src/index.ts";
import { compiled } from "./compiled.ts";

const PROBE = `module example.probe exposing (Id, Title, Row, storeRow, readRows, rowCount, titleOf)

data Id = Int
    invariant value >= 1

data Title = String
    invariant String.length(value) >= 1

data Row = { id: Id, title: Title }

behavior storeRow : (title: Title) -> Row

behavior readRows : () -> List<Row>

behavior rowCount : (rows: List<Row>) -> Int

let rowCount (rows) = List.length(rows)

behavior titleOf : (title: Title) -> Title depends on storeRow

let titleOf (title, storeRow) = storeRow(title).title
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

  // The call that ended still hands its arena back, so the next one starts where it started.
  it("answers the next call after one a supplied function threw out of", async () => {
    const { bytes } = await compiled(PROBE);
    const { rows, supplied } = store();
    let failing = true;
    const program = await load(bytes, {
      ...supplied,
      "example.probe.storeRow": (title: string) => {
        if (failing) {
          failing = false;
          throw new Error("the store is down");
        }
        return supplied["example.probe.storeRow"](title);
      },
    });
    const top = program.arenaTop();
    assert.throws(() => program.call("example.probe.storeRow", ["x".repeat(1000)]), /the store is down/);
    assert.equal(program.arenaTop(), top);
    assert.deepEqual(program.call("example.probe.storeRow", ["milk"]).value, { id: 1n, title: "milk" });
    assert.equal(rows.length, 1);
  });

  describe("an answer that is not of its behavior's type", () => {
    /** What a call to `behavior` ends with where every supplied function answers `answer`. */
    async function endedWith(behavior: string, args: unknown[], answer: unknown): Promise<unknown> {
      const { bytes } = await compiled(PROBE);
      const program = await load(bytes, {
        "example.probe.storeRow": () => answer,
        "example.probe.readRows": () => answer,
      });
      try {
        program.call(behavior, args);
      } catch (ended) {
        return ended;
      }
      assert.fail(`${behavior} answered rather than ending`);
    }

    /** The issues an ending says, each as its path and its code. */
    function said(ended: unknown): string[] {
      assert.ok(ended instanceof InvalidSuppliedAnswer, `ended with ${String(ended)}`);
      return ended.issues.list.map((issue) => `${issue.path.toString()} ${issue.code}`);
    }

    it("ends the call where an answer breaks a rule of its type, saying which", async () => {
      const ended = await endedWith("example.probe.storeRow", ["milk"], { id: 0n, title: "milk" });
      assert.ok(ended instanceof Ended);
      assert.ok(ended instanceof InvalidSuppliedAnswer);
      assert.equal(ended.reason, 13);
      assert.equal(ended.behavior, "example.probe.storeRow");
      assert.deepEqual(said(ended), ["/id out_of_range"]);
      assert.deepEqual(said(await endedWith("example.probe.storeRow", ["milk"], { id: 7n, title: "" })),
        ["/title too_short"]);
    });

    it("says where a missing field or the wrong kind of JSON is", async () => {
      assert.deepEqual(said(await endedWith("example.probe.storeRow", ["milk"], { id: 2n })), ["/title required"]);
      assert.deepEqual(said(await endedWith("example.probe.storeRow", ["milk"], "not a row")), [" type_mismatch"]);
    });

    it("refuses a list for one element that is wrong, at the element's place", async () => {
      const ended = await endedWith("example.probe.readRows", [],
        [{ id: 1n, title: "a" }, { id: 0n, title: "" }]);
      assert.equal((ended as InvalidSuppliedAnswer).behavior, "example.probe.readRows");
      assert.deepEqual(said(ended), ["/1/id out_of_range", "/1/title too_short"]);
    });

    it("ends a body that reached out for it, before it runs on anything", async () => {
      const ended = await endedWith("example.probe.titleOf", ["milk"], { id: 1n, title: "" });
      assert.equal((ended as InvalidSuppliedAnswer).behavior, "example.probe.storeRow");
      assert.deepEqual(said(ended), ["/title too_short"]);
    });

    // The inner call resets its arena, issues and all, before what it threw reaches the outer one.
    it("keeps what a call a supplied function made back into the program ended with", async () => {
      const { bytes } = await compiled(PROBE);
      const program = await load(bytes, {
        "example.probe.storeRow": () => {
          program.call("example.probe.readRows", []);
          return { id: 1n, title: "milk" };
        },
        "example.probe.readRows": () => [{ id: 0n, title: "a" }],
      });
      const top = program.arenaTop();
      let ended: unknown;
      try {
        program.call("example.probe.storeRow", ["milk"]);
      } catch (thrown) {
        ended = thrown;
      }
      assert.equal((ended as InvalidSuppliedAnswer).behavior, "example.probe.readRows");
      assert.deepEqual(said(ended), ["/0/id out_of_range"]);
      assert.equal(program.arenaTop(), top);
    });

    // A supplied function that catches what a call back in ended with and goes on: what it then
    // throws, or answers, is the waiting call's, and the record the inner call wrote is not.
    it("tells a waiting call what its own supplied function threw, after a call back in ended", async () => {
      const { bytes } = await compiled(PROBE);
      const inner: unknown[] = [];
      const program = await load(bytes, {
        "example.probe.storeRow": () => {
          try {
            program.call("example.probe.readRows", []);
          } catch (ended) {
            inner.push(ended);
          }
          throw new Error("storage failed");
        },
        "example.probe.readRows": () => [{ id: 0n, title: "a" }],
      });
      const top = program.arenaTop();
      assert.throws(() => program.call("example.probe.storeRow", ["milk"]),
        (thrown) => thrown instanceof Error && !(thrown instanceof Ended) && thrown.message === "storage failed");
      assert.deepEqual(said(inner[0]), ["/0/id out_of_range"]);
      assert.equal(program.arenaTop(), top);
    });

    it("tells a waiting call of its own wrong answer, after a call back in ended", async () => {
      const { bytes } = await compiled(PROBE);
      const program = await load(bytes, {
        "example.probe.storeRow": () => {
          assert.throws(() => program.call("example.probe.readRows", []), InvalidSuppliedAnswer);
          return { id: 1n, title: "" };
        },
        "example.probe.readRows": () => [{ id: 0n, title: "a" }],
      });
      let ended: unknown;
      try {
        program.call("example.probe.storeRow", ["milk"]);
      } catch (thrown) {
        ended = thrown;
      }
      assert.equal((ended as InvalidSuppliedAnswer).behavior, "example.probe.storeRow");
      assert.deepEqual(said(ended), ["/title too_short"]);
    });

    it("answers the next call from where the one that ended started", async () => {
      const { bytes } = await compiled(PROBE);
      let answer: unknown = { id: 0n, title: "" };
      let calls = 0;
      const program = await load(bytes, {
        "example.probe.storeRow": () => {
          calls += 1;
          return answer;
        },
        "example.probe.readRows": () => [],
      });
      const top = program.arenaTop();
      assert.throws(() => program.call("example.probe.storeRow", ["milk"]), InvalidSuppliedAnswer);
      assert.equal(program.arenaTop(), top);
      assert.equal(calls, 1);
      answer = { id: 1n, title: "milk" };
      assert.deepEqual(program.call("example.probe.storeRow", ["milk"]), { value: { id: 1n, title: "milk" } });
      assert.deepEqual(program.call("example.probe.titleOf", ["milk"]), { value: "milk" });
    });
  });
});
