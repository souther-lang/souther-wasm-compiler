// The package as a project installs it: packed as it is published, installed into a project of its
// own, and used from there the way the README says — the command writing a binding, a page written
// against the binding type-checked against what the package declares, and the page run.
//
// Everything else here reads the package's sources. Node runs no TypeScript under node_modules, and
// a file the package does not carry is not there once it is installed, so what works in this
// directory says nothing about what works where the package is installed. This is what does.

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { it } from "node:test";
import { promisify } from "node:util";
import { compiled } from "./compiled.ts";

const PACKAGE = join(import.meta.dirname, "..");
const TSC = join(PACKAGE, "node_modules", ".bin", "tsc");
const run = promisify(execFile);

const MODEL = `module shop

data Line = { sku: String, quantity: Int, note: String? }

behavior counted : (lines: List<Line>) -> Int

let counted (lines) = List.length(lines)
`;

const PAGE = `import { amount, messageOf } from "@souther/wasm";
import { load } from "./binding.ts";

export async function counted(bytes: Uint8Array): Promise<unknown[]> {
  const shop = await load(bytes);
  const read = shop.decode.shop.Line({ sku: "A", quantity: "two" });
  return [
    shop.modules.shop.counted([{ sku: "A", quantity: amount("1"), note: null }]).value,
    read.issues?.map((issue) => messageOf(issue, "en")),
  ];
}
`;

it("is used from where it is installed, as the README says", async () => {
  const project = mkdtempSync(join(tmpdir(), "souther-installed-"));
  // Packing builds it, as publishing does: what is installed is what would be published.
  const { stdout } = await run("npm", ["pack", "--silent", "--pack-destination", project],
    { cwd: PACKAGE });
  writeFileSync(join(project, "package.json"), JSON.stringify({ type: "module", private: true }));
  await run("npm", ["install", "--silent", "--offline", "--no-audit", "--no-fund",
    join(project, stdout.trim())], { cwd: project });

  const { bytes } = await compiled(MODEL);
  writeFileSync(join(project, "model.wasm"), bytes);
  await run(join(project, "node_modules", ".bin", "souther-wasm-bindings"),
    ["model.wasm", "-o", "binding.ts"], { cwd: project });
  assert.match(readFileSync(join(project, "binding.ts"), "utf-8"),
    /import \* as souther from "@souther\/wasm";/);
  writeFileSync(join(project, "page.ts"), PAGE);
  // Every entry the package says it has is one a project can import from where it is installed.
  const entries = Object.keys(JSON.parse(readFileSync(join(PACKAGE, "package.json"), "utf-8")).exports)
    .map((entry) => entry.replace(/^\./, "@souther/wasm"));
  writeFileSync(join(project, "entries.ts"), entries
    .map((entry, at) => `export * as entry${at} from ${JSON.stringify(entry)};`).join("\n") + "\n");

  try {
    await run(TSC, ["--noEmit", "--strict", "--target", "es2024", "--module", "nodenext",
      "--moduleResolution", "nodenext", "--allowImportingTsExtensions", "--lib", "esnext,dom",
      "--skipLibCheck", "page.ts", "entries.ts"], { cwd: project });
  } catch (refused) {
    const { stdout: said } = refused as { stdout: string };
    assert.fail(`the page does not type-check against what was installed:\n${said}`);
  }
  const imported = await import(join(project, "entries.ts"));
  for (const [at, entry] of entries.entries()) {
    assert.ok(Object.keys(imported[`entry${at}`]).length > 0, `${entry} exports nothing`);
  }

  const page = await import(join(project, "page.ts"));
  assert.deepEqual(await page.counted(bytes),
    [1, ["expected long"]]);
});
