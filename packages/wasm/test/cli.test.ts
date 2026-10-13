// The command that writes a binding reads its whole line before saying what is wrong with it, and
// says each part that is: a line it will not run writes nothing, and one it runs writes where it
// was told and nowhere else.

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import { describe, it } from "node:test";
import { compiled } from "./compiled.ts";

const CLI = join(import.meta.dirname, "..", "src", "cli.ts");

const MODEL = `module counting

behavior next : (n: Int) -> Int

let next (n) = n + 1
`;

interface Ran {
  readonly status: number;
  readonly complained: string;
}

async function bindings(at: string, ...args: string[]): Promise<Ran> {
  try {
    await promisify(execFile)(process.execPath, [CLI, ...args], { cwd: at });
    return { status: 0, complained: "" };
  } catch (failed) {
    const { code, stderr } = failed as { code: number; stderr: string };
    return { status: code, complained: stderr };
  }
}

describe("the bindings command", { concurrency: true }, () => {
  it("writes the binding where it was told, the options in any order", async () => {
    const { bytes } = await compiled(MODEL);
    const at = mkdtempSync(join(tmpdir(), "souther-bindings-"));
    writeFileSync(join(at, "m.wasm"), bytes);
    assert.equal((await bindings(at, "--runtime", "./glue.ts", "-o", "b.ts", "m.wasm")).status, 0);
    assert.match(readFileSync(join(at, "b.ts"), "utf-8"), /import \* as souther from "\.\/glue\.ts";/);
  });

  it("says each part of the line that is wrong, and writes nothing", async () => {
    const at = mkdtempSync(join(tmpdir(), "souther-bindings-"));
    for (const [args, said] of [
      [["m.wasm"], ["-o is required"]],
      [["-o", "b.ts"], ["no module named"]],
      [[], ["no module named", "-o is required"]],
      [["m.wasm", "-o", "b.ts", "--runtime"], ["--runtime names no specifier"]],
      [["m.wasm", "-o", "b.ts", "--runtim", "x"], ["no such option: --runtim"]],
      [["m.wasm", "n.wasm", "-o", "b.ts"], ["a binding is of one module, and this names 2: m.wasm, n.wasm"]],
      [["m.wasm", "-o", "b.ts", "-o", "c.ts"], ["-o is given more than once"]],
      [["m.wasm", "-o", "--runtime", "x"], ["-o names no file"]],
      [["m.wasm", "-o", "b.ts", "--runtime", "-x"], ["--runtime names no specifier", "no such option: -x"]],
    ] as const) {
      const ran = await bindings(at, ...args);
      assert.equal(ran.status, 2, args.join(" "));
      for (const each of said) {
        assert.ok(ran.complained.includes(each), `${args.join(" ")}: ${ran.complained}`);
      }
      assert.ok(ran.complained.endsWith("[--runtime <specifier>]\n"), "the usage, after what was wrong");
    }
    for (const written of ["b.ts", "c.ts", "--runtime"]) {
      assert.equal(existsSync(join(at, written)), false, written);
    }
  });
});
