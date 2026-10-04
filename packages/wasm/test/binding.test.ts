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
import { Decimal } from "@raoh/core";
import { fingerprintOf, surfaceOf } from "../src/index.ts";
import { compiled } from "./compiled.ts";

const RUNTIME = join(import.meta.dirname, "..", "src", "index.ts");
/** Raoh as the runtime imports it, so a page and the binding share one `Decoder`. */
const RAOH = join(import.meta.dirname, "..", "node_modules", "@raoh", "core", "dist", "index.js");
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

behavior doubled : (n: Decimal) -> Decimal

let doubled (n) = n + n

behavior converted : (n: Decimal) -> Decimal
    depends on rate

let converted (n, rate) = n * rate("USD")
`;

/** A page reading what the model offers. */
const PAGE = `import { Decimal } from ${JSON.stringify(RAOH)};
import { load, type Line } from "./binding.ts";

export async function priced(bytes: Uint8Array): Promise<string> {
  const shop = await load(bytes, { "shop.rate": (pair: string) => Decimal.of(pair === "USD" ? 150 : 1) });
  const line: Line = { sku: "ABC-1234", quantity: 1n };
  const answer = shop.modules.shop.price([line], "Premium");
  if (answer.issues !== undefined) {
    return answer.issues.list.map((issue) => issue.code).join();
  }
  if (answer.value.type === "Priced") {
    return String(answer.value.total);
  }
  const read = shop.decode.shop.Sku.decode("nope");
  return read.issues === undefined ? read.value : "not a code";
}
`;

const run = promisify(execFile);

/**
 * The module compiled from `model`, with the binding generated from it and the page beside it: once
 * for every test that asks for the same model, since what is asked of them only reads them.
 */
const generatedFor = new Map<string, Promise<{ at: string; bytes: Uint8Array<ArrayBuffer> }>>();

function generated(model: string | readonly string[], page = PAGE): Promise<{ at: string; bytes: Uint8Array<ArrayBuffer> }> {
  const asked = JSON.stringify([model, page]);
  let held = generatedFor.get(asked);
  if (held === undefined) {
    held = generating(model, page);
    generatedFor.set(asked, held);
  }
  return held;
}

async function generating(model: string | readonly string[], page: string): Promise<{ at: string; bytes: Uint8Array<ArrayBuffer> }> {
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
  const rates = await load(bytes, { "rates.today": (pair: string) => (pair === "USDJPY" ? 150n : 0n) });
  return rates.modules.rates.spread("USDJPY").value;
}
`);
    assert.equal(await checked(at), "");
    const page = await import(join(at, "page.ts"));
    assert.equal(await page.spread(bytes), 149n);
  });

  it("stops a page compiling where it calls what a module keeps", async () => {
    const { at } = await generated(KEEPING, `import { load } from "./binding.ts";

export async function lowered(bytes: Uint8Array): Promise<unknown> {
  const rates = await load(bytes, { "rates.today": () => 0n });
  return rates.modules.rates.lowered(1n);
}
`);
    assert.match(await checked(at), /lowered/);
  });

  // An Int crosses as a bigint and a Decimal as Raoh's Decimal, both ways: what the model answers
  // is handed over again as the number it is, however wide.
  it("hands a Decimal it was handed back over again as the number it is", async () => {
    const { at, bytes } = await generated(MODEL, `import { Decimal } from ${JSON.stringify(RAOH)};
import { load } from "./binding.ts";

export async function twice(bytes: Uint8Array): Promise<string[]> {
  const shop = await load(bytes, { "shop.rate": () => Decimal.parse("1.000000000000000000001")! });
  const once = shop.modules.shop.doubled(Decimal.parse("12345678901234567890.5")!);
  if (once.issues !== undefined) {
    return once.issues.list.map((issue) => issue.code);
  }
  const again = shop.modules.shop.doubled(once.value);
  const converted = shop.modules.shop.converted(Decimal.of(3));
  return [again, converted].map((read) =>
    read.issues === undefined ? read.value.toString() : read.issues.list.map((issue) => issue.code).join());
}
`);
    assert.equal(await checked(at), "");
    const page = await import(join(at, "page.ts"));
    assert.deepEqual(await page.twice(bytes),
      ["49382715604938271562", "3.000000000000000000003"]);
  });

  // A number written with a power of ten however large is read as written and never raised to:
  // what it costs to read is its text, and not a number of a billion digits.
  it("reads a Decimal written with a vast power of ten as the number it is", async () => {
    const { at, bytes } = await generated(MODEL, `import { Decimal } from ${JSON.stringify(RAOH)};
import { load } from "./binding.ts";

export async function tiny(bytes: Uint8Array): Promise<string> {
  const shop = await load(bytes, { "shop.rate": () => Decimal.of(1) });
  const read = shop.modules.shop.doubled(Decimal.parse("1e-1000000000")!);
  return read.issues === undefined ? read.value.toString() : read.issues.list.map((it) => it.code).join();
}
`);
    const page = await import(join(at, "page.ts"));
    const started = performance.now();
    assert.match(await page.tiny(bytes), /^2(\.0*)?E-1000000000$/);
    assert.ok(performance.now() - started < 1000, "read without raising ten to the power");
  });

  // What a form gives is text in every box. Read as a form gives it, text that spells the number,
  // the yes or no, or the nothing the type has there is read as that, and any other text is handed
  // over as typed, for the model to say what is wrong with it where it is.
  it("reads what a form gives as the type it is to be, and leaves the rest to the model", async () => {
    const { at, bytes } = await generated(MODEL, `import { load } from "./binding.ts";

export async function typed(bytes: Uint8Array): Promise<unknown[]> {
  const shop = await load(bytes, { "shop.rate": () => { throw new Error("not asked"); } });
  const form = shop.form.shop;
  return [
    form.Line.decode({ sku: "ABC-1234", quantity: "2", note: "" }).value,
    form.Line.decode({ sku: "ABC-1234", quantity: "2", note: "gift" }).value,
    form.Priced.decode({ total: "1.50" }).value?.total.toString(),
    form.Line.decode({ sku: "ABC-1234", quantity: 3n }).value,
    form.Line.decode({ sku: "bad", quantity: "two" }).issues?.list.map((issue) => [issue.path.toString(), issue.code]),
    form.Line.decode({ sku: "ABC-1234", quantity: " 2" }).issues?.list.map((issue) => [issue.path.toString(), issue.code]),
    form.Line.decode({ sku: "ABC-1234", quantity: "" }).issues?.list.map((issue) => [issue.path.toString(), issue.code]),
    shop.decode.shop.Line.decode({ sku: "ABC-1234", quantity: "2" }).issues?.list.map((issue) => issue.code),
  ];
}
`);
    assert.equal(await checked(at), "");
    const page = await import(join(at, "page.ts"));
    assert.deepEqual(await page.typed(bytes), [
      { sku: "ABC-1234", quantity: 2n },
      { sku: "ABC-1234", quantity: 2n, note: "gift" },
      // The model writes a Decimal in the fewest places that hold it.
      "1.5",
      { sku: "ABC-1234", quantity: 3n },
      [["/sku", "invalid_format"], ["/quantity", "type_mismatch"]],
      // Text is read as the number it spells, and nothing is trimmed for it.
      [["/quantity", "type_mismatch"]],
      // An empty box where a value is required is no number, and the model says so.
      [["/quantity", "type_mismatch"]],
      // Read as a value and not as a form gives it, text is not a number.
      ["type_mismatch"],
    ]);
  });

  // A type the model publishes is a Raoh decoder, so a page reads a form of its own with it as a
  // part: the model's rules run on the part, and what is wrong is said where the part is.
  it("reads a type the model publishes as a part of a value a page decodes", async () => {
    const { at, bytes } = await generated(MODEL, `import { Decimal, field, int, list, object } from ${JSON.stringify(RAOH)};
import { load } from "./binding.ts";

export async function ordered(bytes: Uint8Array): Promise<unknown> {
  const shop = await load(bytes, { "shop.rate": () => Decimal.of(1) });
  const order = object(
    field("sku", shop.decode.shop.Sku),
    field("lines", list(shop.decode.shop.Line)),
    field("count", int()),
  );
  const wrong = order.decode({ lines: [{ sku: "ABC-1234", quantity: 1 }, { sku: "bad", quantity: "two" }], count: "x" });
  const right = order.decode({ sku: "ABC-1234", lines: [{ sku: "ABC-1234", quantity: 1 }], count: 1 });
  return [wrong.issues?.list.map((issue) => [issue.path.toString(), issue.code]), right.value];
}
`);
    assert.equal(await checked(at), "");
    const page = await import(join(at, "page.ts"));
    assert.deepEqual(await page.ordered(bytes), [
      [
        // A member that is not there is read by the module as it reads one that is not there.
        ["/sku", "required"],
        ["/lines/1/sku", "invalid_format"],
        ["/lines/1/quantity", "type_mismatch"],
        ["/count", "type_mismatch"],
      ],
      ["ABC-1234", [{ sku: "ABC-1234", quantity: 1n }], 1],
    ]);
  });

  // What a page hands over may write an optional field as null, which every backend reads as
  // nothing; and what comes back leaves it out, which the same type says.
  it("takes an optional field written as null, as the boundary does", async () => {
    const { at, bytes } = await generated(MODEL, `import { Decimal } from ${JSON.stringify(RAOH)};
import { load, type Line } from "./binding.ts";

export async function priced(bytes: Uint8Array): Promise<unknown> {
  const shop = await load(bytes, { "shop.rate": () => Decimal.of(1) });
  const line: Line = { sku: "ABC-1234", quantity: 1n, note: null };
  const read = shop.decode.shop.Line.decode(line);
  return [shop.modules.shop.price([line], "Standard").value, read.value];
}
`);
    assert.equal(await checked(at), "");
    const page = await import(join(at, "page.ts"));
    assert.deepEqual(await page.priced(bytes),
      [{ type: "Priced", total: Decimal.of(1) }, { sku: "ABC-1234", quantity: 1n }]);
  });

  it("stops a page compiling where it hands over a number as anything but the model's type", async () => {
    const { at } = await generated(MODEL, `import { Decimal } from ${JSON.stringify(RAOH)};
import { load } from "./binding.ts";

export async function doubled(bytes: Uint8Array): Promise<unknown> {
  const shop = await load(bytes, { "shop.rate": () => Decimal.of(1) });
  return [shop.modules.shop.doubled("123"), shop.modules.shop.doubled(123), shop.modules.shop.doubled({ rawJSON: "123" })];
}
`);
    const said = await checked(at);
    assert.match(said, /page\.ts\(6,\d+\).*'string' is not assignable/);
    assert.match(said, /page\.ts\(6,\d+\).*'number' is not assignable/);
    assert.match(said, /page\.ts\(6,\d+\).*'rawJSON' does not exist in type 'Decimal'/);
  });

  // What the model names is the model's to name: a module called what the binding calls its own
  // parts, a type called what the runtime calls its types or what another type is written as once
  // its module is put before it, a parameter called what TypeScript reserves.
  it("writes whatever a model names without one name standing for another", async () => {
    const { at, bytes } = await generated([`module a exposing ( X, Reading, Numeric, Program, Bound, Record, Promise, Readonly, souther )

data X = { n: Int }
data Reading = { n: Int }
data Numeric = Int
data Program = String
data Bound = { b: Bool }
data Record = { r: Int }
data Promise = { p: Int }
data Readonly = { q: Int }
data souther = { s: Int }
`, `module b exposing ( X )

data X = { m: Int }
`, `module c exposing ( AX )

data AX = { k: Int }
`, `module program exposing ( decode, modules )

import a ( X, Reading, Record )

behavior decode : (program: Int, function: Int, new: Int) -> Int

let decode (program, function, new) = program + function + new

behavior modules : (x: X, reading: Reading, record: Record) -> Int

let modules (x, reading, record) = x.n + reading.n + record.r
`, `module decode exposing ( program, load )

behavior program : (souther: Int) -> Int

let program (souther) = souther + 1

behavior load : (argument1: Int, FINGERPRINT: Int) -> Int

let load (argument1, FINGERPRINT) = argument1 + FINGERPRINT
`], `import { load, type AX, type AX_2, type BX, type Bound, type Bound_2,
  type Promise as Promised, type Readonly as Held, type Record as Recorded } from "./binding.ts";

export async function all(bytes: Uint8Array): Promise<unknown[]> {
  const bound: Bound = await load(bytes);
  const x: AX_2 = { n: 1n };
  const flag: Bound_2 = { b: true };
  const other: BX = { m: 2n };
  const ax: AX = { k: 3n };
  // The binding names none of TypeScript's own types, so the model's keep their names there; the
  // page, which does name Promise, calls them otherwise.
  const kept: [Recorded, Promised, Held] = [{ r: 3n }, { p: 4n }, { q: 5n }];
  return [
    bound.modules.decode.program(1n).value,
    bound.modules.decode.load(2n, 3n).value,
    bound.modules.program.decode(1n, 2n, 3n).value,
    bound.modules.program.modules(x, { n: 2n }, { r: 3n }).value,
    bound.decode.a.Bound.decode(flag).value,
    bound.decode.b.X.decode(other).value,
    bound.decode.c.AX.decode(ax).value,
    bound.decode.a.souther.decode({ s: 1n }).value,
    bound.decode.a.Record.decode(kept[0]).value,
  ];
}
`);
    assert.equal(await checked(at), "");
    const page = await import(join(at, "page.ts"));
    assert.deepEqual(await page.all(bytes),
      [2n, 5n, 6n, 6n, { b: true }, { m: 2n }, { k: 3n }, { s: 1n }, { r: 3n }]);
  });

  it("refuses a module it was not generated from", async () => {
    const { at } = await generated(MODEL);
    const other = await generated(MODEL.replace("note: String?", "note: String?, gift: Bool"));
    const page = await import(join(at, "page.ts"));
    await assert.rejects(page.priced(other.bytes), /not the one the binding was generated from/);
  });
});
