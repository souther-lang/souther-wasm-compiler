// The glue and the binding generated from the cart's module, run against the module.
//
// Building the page says it compiles. This says the calling works, which is a different claim and
// the only one nothing else in the repository makes: everything else reaches this boundary from
// the JVM, and what a caller outside it has to agree about — where a failure record's fields are,
// which way round a pointer and a length come back, what number each reason goes by — is agreed
// about here or nowhere.

import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Decimal, field, object, string } from "@raoh/core";
import { load as loadModule, messageOf, READS } from "@souther/wasm";
import { yen } from "./src/amount.ts";
import { load, type Cart } from "./src/cart.ts";

const bound = await load(readFileSync("public/cart.wasm"));
const program = bound.program;
let wrong = 0;

/** A value as it is compared: a bigint as its digits and a type of its own, and a Decimal as its digits. */
function shown(value: unknown): string {
  return JSON.stringify(value, (_key, held) => (typeof held === "bigint" ? `${held}n` : held));
}

function same(what: string, held: unknown, wanted: unknown): void {
  const written = shown(held);
  if (written !== shown(wanted)) {
    console.error(`${what}\n  answered ${written}\n  and not  ${shown(wanted)}`);
    wrong += 1;
  }
}

/** What a basket's subtotal comes to, through the binding. */
function subtotal(cart: Cart): unknown {
  const answer = bound.modules.cart.price(cart);
  return answer.value?.type === "Priced" ? answer.value.subtotal : answer;
}

same("what it offers", program.behaviors, ["cart.price"]);

same("what the module says it offers",
  program.surface.modules.map((module) => module.behaviors.map((it) => it.export)),
  [["cart.price"]]);

same("what a product code is written as",
  (({ decode, ...rest }) => rest)(program.surface.declarations.find((it) => it.name === "Sku")!),
  {
    module: "cart", name: "Sku", by: "module", published: true, is: "newtype",
    wraps: { is: "scalar", scalar: "string" }, rules: [{ name: "written" }],
  });

same("a product code read on its own", bound.decode.cart.Sku.decode("ABC-1234"),
  { value: "ABC-1234" });

same("a product code that is not one, read on its own",
  bound.decode.cart.Sku.decode("nope").issues?.list.map((it) => [it.path.toString(), it.code]),
  [["", "invalid_format"]]);

// The model's type as a part of a form the page reads with Raoh: the page's @raoh/core is the one
// the glue uses, so what the module says of the part is said where the part is.
same("a product code that is not one, read as a part of a form",
  object(field("code", bound.decode.cart.Sku), field("note", string())).decode({ code: "nope", note: "x" })
    .issues?.list.map((it) => [it.path.toString(), it.code]),
  [["/code", "invalid_format"]]);

same("a basket with something in it",
  bound.modules.cart.price({
    lines: [{ sku: "ABC-1234", quantity: 2n, unitPrice: Decimal.of(1500) }], member: "Standard",
  }),
  { value: { type: "Priced", subtotal: Decimal.of(3000), discount: Decimal.of(0), shipping: Decimal.of(500),
    total: Decimal.of(3500) } });

// What a form gives is text in every box. Read as a form gives it, the text of a number is the
// number, and text that is no number is the model's to say so of, where it is.
same("a basket typed into a form",
  bound.form.cart.Cart.decode({
    lines: [{ sku: "ABC-1234", quantity: "2", unitPrice: "1500.00" }], member: "Standard",
  }),
  { value: { lines: [{ sku: "ABC-1234", quantity: 2n, unitPrice: Decimal.of(1500) }], member: "Standard" } });
{
  const typed = bound.form.cart.Cart.decode({
    lines: [{ sku: "nope", quantity: "two", unitPrice: "1500.00" }], member: "Standard",
  });
  same("what is wrong with a line typed into a form, where it is",
    [typed.issues?.at(["lines", 0, "sku"]).map((it) => it.code),
      typed.issues?.at(["lines", 0, "quantity"]).map((it) => messageOf(it, "en")),
      typed.issues?.at(["lines", 0, "unitPrice"])],
    [["invalid_format"], ["expected long"], []]);
}

same("a member's tenth, and what is left shipping free",
  program.call("cart.price", [{
    lines: [{ sku: "ABC-1234", quantity: 2, unitPrice: 3000 }], member: "Premium",
  }]),
  { value: { type: "Priced", subtotal: Decimal.of(6000), discount: Decimal.of(600), shipping: Decimal.of(0),
    total: Decimal.of(5400) } });

same("a basket with nothing in it",
  program.call("cart.price", [{ lines: [], member: "Standard" }]),
  { value: { type: "EmptyCart" } });

same("a product code that is not one",
  program.call("cart.price", [{
    lines: [{ sku: "nope", quantity: 1, unitPrice: 1 }], member: "Standard",
  }]),
  { issues: [{ path: "/0/lines/0/sku", code: "invalid_format", messageKey: "invalid_format",
    message: "invalid format", meta: { pattern: "[A-Z]{3}-[0-9]{4}" } }] });

// What a person reads of it is written from Raoh's catalog, in their language, and nowhere here.
{
  const answer = bound.modules.cart.price({
    lines: [{ sku: "ABC-1234", quantity: 0n, unitPrice: Decimal.of(1) }], member: "Standard",
  });
  same("what is read of a line of none, in English",
    answer.issues?.list.map((issue) => messageOf(issue, "en")),
    ["invariant violated on cart.Line: atLeastOne"]);
  const code = bound.decode.cart.Sku.decode("nope");
  same("what is read of a product code that is not one, in Japanese",
    code.issues?.list.map((issue) => messageOf(issue, "ja")), ["形式が不正です"]);
}

same("a quantity that is not a number",
  program.call("cart.price", [{
    lines: [{ sku: "ABC-1234", quantity: "two", unitPrice: 1 }], member: "Standard",
  }]),
  { issues: [{ path: "/0/lines/0/quantity", code: "type_mismatch", messageKey: "type_mismatch",
    message: "expected long", meta: { actual: "string", expected: "long" } }] });

// Everything a call made goes back at the reset, so the tenth call is the first.
for (let i = 1; i <= 10; i++) {
  same(`call ${i}`,
    subtotal({
      lines: [{ sku: "ABC-1234", quantity: BigInt(i), unitPrice: Decimal.of(1000) }], member: "Standard",
    }),
    Decimal.of(i * 1000));
}

// What a call with the wrong number of arguments comes to, which is an answer and not an ending:
// a caller writing one is bad input like any other.
same("a call given nothing",
  program.call("cart.price", []),
  { issues: [{ path: "", code: "invalid_size", messageKey: "invalid_size",
    message: "must have exactly 1 elements", meta: { actual: 0, expected: 1 } }] });

// A Decimal is held to whatever precision it was written with, and a JavaScript number is not. So
// one is handed over as its digits and comes back as its digits, however many there are — which is
// the only way a caller reads what the model worked out rather than what survived being read.
const wide = "12345678901234567890.12345";
const widely = subtotal({
  lines: [{ sku: "ABC-1234", quantity: 1n, unitPrice: Decimal.parse(wide)! }], member: "Standard",
});
same("a Decimal wider than a number holds", widely, Decimal.parse(wide));
// What came back is handed over again as the number it is: an answer is something a caller passes on.
same("a Decimal handed back, handed over again",
  subtotal({
    lines: [{ sku: "ABC-1234", quantity: 2n, unitPrice: widely as Decimal }], member: "Standard",
  }),
  Decimal.parse("24691357802469135780.2469"));
same("a Decimal written with a power of ten",
  subtotal({
    lines: [{ sku: "ABC-1234", quantity: 1n, unitPrice: Decimal.parse("1.5e3")! }], member: "Standard",
  }),
  Decimal.of(1500));

// What the page shows of an amount is every digit the Decimal holds, its whole part grouped: past
// the places and the powers of ten a number formatter takes, nothing is rounded or dropped.
same("an amount, shown",
  ["1234567.89", "1.1234567890123456789012345", "1E+1000", "1E-1000", "-0.001", "1000", "999"]
    .map((written) => yen(Decimal.parse(written)!)),
  ["¥1,234,567.89", "¥1.1234567890123456789012345", "¥1E+1000", "¥1E-1000", "¥-0.001", "¥1,000", "¥999"]);
same("the amount the model answered, shown",
  yen(subtotal({ lines: [{ sku: "ABC-1234", quantity: 1n, unitPrice: Decimal.parse(wide)! }], member: "Standard" }) as Decimal),
  "¥12,345,678,901,234,567,890.12345");

// Everything a call made goes back when it is over. What is left standing after many calls is what
// says the reset ran — a caller that never gave the arena back would leave it climbing.
const before = program.arenaTop();
for (let i = 0; i < 200; i++) {
  program.call("cart.price", [{ lines: [{ sku: "ABC-1234", quantity: 1, unitPrice: 1 }],
    member: "Standard" }]);
}
same("what a call gave back", program.arenaTop(), before);

// What a model reaches out for is answered by name, and the call out carries a number: the surface
// is what says which name each number is. The cart reaches out for nothing, so a model that does is
// compiled here by the same compiler the page's model is.
{
  const at = mkdtempSync(join(tmpdir(), "reaching-"));
  writeFileSync(join(at, "rates.sou"), `module rates

behavior today : (pair: String) -> Int

behavior yesterday : (pair: String) -> Int

behavior spread : (pair: String) -> Int
    depends on today, yesterday

let spread (pair, today, yesterday) = today(pair) - yesterday(pair)
`);
  execFileSync("souther", ["compile", "--target", "wasm", at, "-o", join(at, "rates.wasm")]);
  const reaching = await loadModule(readFileSync(join(at, "rates.wasm")), {
    "rates.today": (pair: string) => (pair === "USDJPY" ? 150n : 0n),
    "rates.yesterday": (pair: string) => (pair === "USDJPY" ? 147n : 0n),
  });
  same("what it reaches out for", reaching.reachesOutFor, ["rates.today", "rates.yesterday"]);
  same("what it answers from what was supplied", reaching.call("rates.spread", ["USDJPY"]),
    { value: 3n });
}

// A module whose surface is of another version is refused when it is loaded, rather than read as
// this one: what a surface of another version says, it says differently or not at all. The versions
// are the glue's own (`READS`), and the other one is written in as many bytes as this one, so the
// module is still one a browser compiles and only what it says it is has changed.
{
  const reads = READS.surface;
  const other = String(reads - 1).length === String(reads).length ? reads - 1 : reads + 1;
  const bytes = readFileSync("public/cart.wasm");
  const text = bytes.toString("latin1");
  const written = `{"version":${reads},`;
  const at = text.indexOf(written);
  same("where the surface says its version", at >= 0 && text.indexOf(written, at + 1) < 0, true);
  const another = Buffer.from(bytes);
  another.write(`{"version":${other},`, at, "latin1");
  let refused: string | null = null;
  try {
    await loadModule(another);
  } catch (said) {
    refused = said instanceof Error ? said.message : String(said);
  }
  same("what loading a surface of another version says", refused,
    `this module's surface is version ${other}, and this glue reads version ${reads}`);
}

console.log(wrong === 0 ? "the glue calls a compiled program" : `${wrong} did not hold`);
process.exit(wrong === 0 ? 0 : 1);
