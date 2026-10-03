// A binding is what a model offers, as TypeScript: a page written against it stops compiling when
// the model stops offering what the page reads, and a binding refuses a module it was not
// generated from.

import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import { bindingFor } from "../src/generate.ts";
import { fingerprintOf, surfaceOf } from "../src/index.ts";

const ROOT = join(import.meta.dirname, "..", "..", "..");
const RUNTIME = join(import.meta.dirname, "..", "src", "index.ts");
const TSC = join(import.meta.dirname, "..", "node_modules", ".bin", "tsc");

const MODEL = `module shop

data Sku = String
    invariant String.matches("[A-Z]{3}-[0-9]{4}", value)

data Line = { sku: Sku, quantity: Int, note: String? }

data Membership = Standard | Premium
data Standard
data Premium

data Priced = { total: Decimal }
data EmptyCart

behavior price : (lines: List<Line>, member: Membership) -> Priced | EmptyCart

let price (lines, member) = if List.length(lines) >= 1 then Priced { total = 1.00m } else EmptyCart

behavior rate : (pair: String) -> Decimal
`;

/** A page reading what the model offers. */
const PAGE = `import { load, type Line } from "./binding.ts";

export async function priced(bytes: Uint8Array): Promise<string> {
  const shop = await load(bytes, { "shop.rate": (pair: string) => (pair === "USD" ? 150 : 1) });
  const line: Line = { sku: "ABC-1234", quantity: 1 };
  const answer = shop.shop.price([line], "Premium");
  if (answer.issues !== undefined) {
    return answer.issues.map((issue) => issue.code).join();
  }
  if (answer.value.type === "Priced") {
    return String(answer.value.total);
  }
  const read = shop.decode.Sku("nope");
  return read.issues === undefined ? read.value : "not a code";
}
`;

/** The module compiled from `model`, and the binding generated from it, at a directory of their own. */
async function generated(model: string): Promise<{ at: string; bytes: Uint8Array }> {
  const at = mkdtempSync(join(tmpdir(), "binding-"));
  writeFileSync(join(at, "model.sou"), model);
  const jar = readdirSync(join(ROOT, "target")).find((name) => name.endsWith("-cli.jar"));
  assert.ok(jar, "the compiler is built: mvn package at the repository's root");
  execFileSync("java", ["-jar", join(ROOT, "target", jar), at, "-o", join(at, "model.wasm")]);
  const bytes = readFileSync(join(at, "model.wasm"));
  const [surface, held] = surfaceOf(await WebAssembly.compile(bytes));
  writeFileSync(join(at, "binding.ts"), bindingFor(surface, await fingerprintOf(held), RUNTIME));
  writeFileSync(join(at, "page.ts"), PAGE);
  return { at, bytes };
}

/** What `tsc` says of the page, strict, or nothing where it compiles. */
function checked(at: string): string {
  const run = spawnSync(TSC, ["--noEmit", "--strict", "--target", "es2024", "--module", "nodenext",
    "--moduleResolution", "nodenext", "--allowImportingTsExtensions", "--lib", "esnext,dom",
    "--types", "node", "--skipLibCheck", join(at, "page.ts")], { encoding: "utf-8" });
  return run.status === 0 ? "" : run.stdout + run.stderr;
}

describe("a binding", () => {
  it("compiles a page that reads what the model offers", async () => {
    const { at } = await generated(MODEL);
    assert.equal(checked(at), "");
  });

  it("stops a page compiling where a field it reads is renamed in the model", async () => {
    const { at } = await generated(MODEL.replace("{ total: Decimal }", "{ amount: Decimal }")
      .replace("Priced { total = 1.00m }", "Priced { amount = 1.00m }"));
    assert.match(checked(at), /total/);
  });

  it("stops a page compiling where a case it names is renamed in the model", async () => {
    const { at } = await generated(MODEL.replace(/Premium/g, "Gold"));
    assert.match(checked(at), /"Premium"/);
  });

  it("calls the module it was generated from, and answers what the model says", async () => {
    const { at, bytes } = await generated(MODEL);
    const page = await import(join(at, "page.ts"));
    assert.equal(await page.priced(bytes), "1");
  });

  it("refuses a module it was not generated from", async () => {
    const { at } = await generated(MODEL);
    const other = await generated(MODEL.replace("note: String?", "note: String?, gift: Bool"));
    const page = await import(join(at, "page.ts"));
    await assert.rejects(page.priced(other.bytes), /not the one the binding was generated from/);
  });
});
