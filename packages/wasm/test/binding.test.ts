// A binding is what a model offers, as TypeScript: a page written against it stops compiling when
// the model stops offering what the page reads, and a binding refuses a module it was not
// generated from.

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import { bindingFor } from "../src/generate.ts";
import { fingerprintOf, surfaceOf } from "../src/index.ts";
import { compiled } from "./compiled.ts";

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

const run = promisify(execFile);

/**
 * The module compiled from `model`, with the binding generated from it and the page beside it: once
 * for every test that asks for the same model, since what is asked of them only reads them.
 */
const generatedFor = new Map<string, Promise<{ at: string; bytes: Uint8Array<ArrayBuffer> }>>();

function generated(model: string, page = PAGE): Promise<{ at: string; bytes: Uint8Array<ArrayBuffer> }> {
  const asked = JSON.stringify([model, page]);
  let held = generatedFor.get(asked);
  if (held === undefined) {
    held = generating(model, page);
    generatedFor.set(asked, held);
  }
  return held;
}

async function generating(model: string, page: string): Promise<{ at: string; bytes: Uint8Array<ArrayBuffer> }> {
  const { bytes } = await compiled(model);
  // A directory of its own for each page, since two pages may be written against one model.
  const at = mkdtempSync(join(tmpdir(), "souther-binding-"));
  const [surface, held] = surfaceOf(await WebAssembly.compile(bytes));
  writeFileSync(join(at, "binding.ts"), bindingFor(surface, await fingerprintOf(held), RUNTIME));
  writeFileSync(join(at, "page.ts"), page);
  return { at, bytes };
}

/** What `tsc` says of the page, strict, or nothing where it compiles. */
async function checked(at: string): Promise<string> {
  try {
    await run(TSC, ["--noEmit", "--strict", "--target", "es2024", "--module", "nodenext",
      "--moduleResolution", "nodenext", "--allowImportingTsExtensions", "--lib", "esnext,dom",
      "--types", "node", "--skipLibCheck", join(at, "page.ts")]);
    return "";
  } catch (refused) {
    const { stdout, stderr } = refused as { stdout: string; stderr: string };
    return stdout + stderr;
  }
}

// Each test asks of a module of its own or only reads a shared one, so they run side by side: what
// they cost is a compiler started and a page checked, mostly waiting.
describe("a binding", { concurrency: true }, () => {
  it("compiles a page that reads what the model offers", async () => {
    const { at } = await generated(MODEL);
    assert.equal(await checked(at), "");
  });

  it("stops a page compiling where a field it reads is renamed in the model", async () => {
    const { at } = await generated(MODEL.replace("{ total: Decimal }", "{ amount: Decimal }")
      .replace("Priced { total = 1.00m }", "Priced { amount = 1.00m }"));
    assert.match(await checked(at), /total/);
  });

  it("stops a page compiling where a case it names is renamed in the model", async () => {
    const { at } = await generated(MODEL.replace(/Premium/g, "Gold"));
    assert.match(await checked(at), /"Premium"/);
  });

  it("calls the module it was generated from, and answers what the model says", async () => {
    const { at, bytes } = await generated(MODEL);
    const page = await import(join(at, "page.ts"));
    assert.equal(await page.priced(bytes), "1");
  });

  // What a module keeps is not the caller's to call, whoever answers it; one kept and answered
  // outside is still the host's to supply, since the program cannot run without it.
  const KEEPING = `module rates exposing ( spread )

behavior today : (pair: String) -> Int

behavior lowered : (n: Int) -> Int

let lowered (n) = n - 1

behavior spread : (pair: String) -> Int
    depends on today

let spread (pair, today) = lowered(today(pair))
`;

  it("offers what a module publishes and asks for what it keeps and reaches out for", async () => {
    const { at, bytes } = await generated(KEEPING, `import { load } from "./binding.ts";

export async function spread(bytes: Uint8Array): Promise<unknown> {
  const rates = await load(bytes, { "rates.today": (pair: string) => (pair === "USDJPY" ? 150 : 0) });
  return rates.rates.spread("USDJPY").value;
}
`);
    assert.equal(await checked(at), "");
    const page = await import(join(at, "page.ts"));
    assert.equal(await page.spread(bytes), 149);
  });

  it("stops a page compiling where it calls what a module keeps", async () => {
    const { at } = await generated(KEEPING, `import { load } from "./binding.ts";

export async function lowered(bytes: Uint8Array): Promise<unknown> {
  const rates = await load(bytes, { "rates.today": () => 0 });
  return rates.rates.lowered(1);
}
`);
    assert.match(await checked(at), /lowered/);
  });

  it("refuses a module it was not generated from", async () => {
    const { at } = await generated(MODEL);
    const other = await generated(MODEL.replace("note: String?", "note: String?, gift: Bool"));
    const page = await import(join(at, "page.ts"));
    await assert.rejects(page.priced(other.bytes), /not the one the binding was generated from/);
  });
});
