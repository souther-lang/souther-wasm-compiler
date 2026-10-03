// The glue, run against a compiled model.
//
// Building the page says it compiles. This says the calling works, which is a different claim and
// the only one nothing else in the repository makes: everything else reaches this boundary from
// the JVM, and what a caller outside it has to agree about — where a failure record's fields are,
// which way round a pointer and a length come back, what number each reason goes by — is agreed
// about here or nowhere.

import { execFileSync } from "node:child_process";
import { mkdtempSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { load, amount } from "./src/souther.js";

const program = await load(readFileSync("public/cart.wasm"));
let wrong = 0;

function same(what, held, wanted) {
  const written = JSON.stringify(held);
  if (written !== JSON.stringify(wanted)) {
    console.error(`${what}\n  answered ${written}\n  and not  ${JSON.stringify(wanted)}`);
    wrong += 1;
  }
}

same("what it offers", program.behaviors, ["cart.price"]);

same("what the module says it offers",
  program.surface.modules.map((module) => module.behaviors.map((it) => it.export)),
  [["cart.price"]]);

same("what a product code is written as",
  (({ decode, ...rest }) => rest)(program.surface.declarations.find((it) => it.name === "Sku")),
  {
    module: "cart", name: "Sku", by: "module", published: true, is: "newtype",
    wraps: { is: "scalar", scalar: "string" }, rules: [{ name: "written" }],
  });

same("a product code read on its own", program.decode("cart.Sku", "ABC-1234"),
  { value: "ABC-1234" });

same("a product code that is not one, read on its own",
  program.decode("cart.Sku", "nope").issues.map((it) => [it.path, it.code]),
  [["", "invariant_violation"]]);

same("a basket with something in it",
  program.call("cart.price", [{
    lines: [{ sku: "ABC-1234", quantity: 2, unitPrice: 1500 }], member: "Standard",
  }]),
  { value: { type: "Priced", subtotal: 3000, discount: 0, shipping: 500, total: 3500 } });

same("a member's tenth, and what is left shipping free",
  program.call("cart.price", [{
    lines: [{ sku: "ABC-1234", quantity: 2, unitPrice: 3000 }], member: "Premium",
  }]),
  { value: { type: "Priced", subtotal: 6000, discount: 600, shipping: 0, total: 5400 } });

same("a basket with nothing in it",
  program.call("cart.price", [{ lines: [], member: "Standard" }]),
  { value: { type: "EmptyCart" } });

same("a product code that is not one",
  program.call("cart.price", [{
    lines: [{ sku: "nope", quantity: 1, unitPrice: 1 }], member: "Standard",
  }]),
  { issues: [{ path: "/0/lines/0/sku", code: "invariant_violation",
    meta: { actual: "0", expected: "Sku" } }] });

same("a quantity that is not a number",
  program.call("cart.price", [{
    lines: [{ sku: "ABC-1234", quantity: "two", unitPrice: 1 }], member: "Standard",
  }]),
  { issues: [{ path: "/0/lines/0/quantity", code: "type_mismatch",
    meta: { actual: "string", expected: "Int" } }] });

// Everything a call made goes back at the reset, so the tenth call is the first.
for (let i = 1; i <= 10; i++) {
  same(`call ${i}`,
    program.call("cart.price", [{
      lines: [{ sku: "ABC-1234", quantity: i, unitPrice: 1000 }], member: "Standard",
    }]).value.subtotal,
    i * 1000);
}

// What a call with the wrong number of arguments comes to, which is an answer and not an ending:
// a caller writing one is bad input like any other.
same("a call given nothing",
  program.call("cart.price", []),
  { issues: [{ path: "", code: "invalid_size", meta: { actual: "0", expected: "1" } }] });

// An amount is held to whatever precision it was written with, and a JavaScript number is not. So
// one is handed over as its digits and comes back as its digits wherever a number could not have
// carried them — which is the only way a caller reads what the model worked out rather than what
// survived being read.
const wide = "12345678901234567890.12345";
same("an amount wider than a number holds",
  program.call("cart.price", [{
    lines: [{ sku: "ABC-1234", quantity: 1, unitPrice: amount(wide) }], member: "Standard",
  }]).value.subtotal,
  wide);
same("an amount a number does hold",
  program.call("cart.price", [{
    lines: [{ sku: "ABC-1234", quantity: 2, unitPrice: amount("1500.00") }], member: "Standard",
  }]).value.subtotal,
  3000);
// Written one way and written back another, and the same amount either way: what the two are
// compared by is how much each is and not which digits each is written with.
same("an amount written with a point where the answer has none",
  program.call("cart.price", [{
    lines: [{ sku: "ABC-1234", quantity: 1, unitPrice: amount("1500.000") }], member: "Standard",
  }]).value.subtotal,
  1500);
// An amount a number holds exactly and writes another way round: the model writes the digits out
// and a JavaScript number writes a power of ten, so the two texts differ and the two amounts do
// not. Comparing the digits without the point would call these two amounts.
same("an amount a number writes as a power of ten",
  program.call("cart.price", [{
    lines: [{ sku: "ABC-1234", quantity: 1, unitPrice: amount("1e21") }], member: "Standard",
  }]).value.subtotal,
  1e21);
same("an amount written as a power of ten",
  program.call("cart.price", [{
    lines: [{ sku: "ABC-1234", quantity: 1, unitPrice: amount("1.5e3") }], member: "Standard",
  }]).value.subtotal,
  1500);

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
  const jar = readdirSync("../../target").find((name) => name.endsWith("-cli.jar"));
  execFileSync("java", ["-jar", join("../../target", jar), at, "-o", join(at, "rates.wasm")]);
  const reaching = await load(readFileSync(join(at, "rates.wasm")), {
    "rates.today": (pair) => (pair === "USDJPY" ? 150 : 0),
    "rates.yesterday": (pair) => (pair === "USDJPY" ? 147 : 0),
  });
  same("what it reaches out for", reaching.reachesOutFor, ["rates.today", "rates.yesterday"]);
  same("what it answers from what was supplied", reaching.call("rates.spread", ["USDJPY"]),
    { value: 3 });
}

// A module whose surface is of another version is refused when it is loaded, rather than read as
// this one: a version 2 surface carries no numbers for what it reaches out for, and taken for a
// version 3 one its first call out would fail.
{
  const bytes = readFileSync("public/cart.wasm");
  const text = bytes.toString("latin1");
  const at = text.indexOf('{"version":3,');
  same("where the surface says its version", at >= 0 && text.indexOf('{"version":3,', at + 1) < 0,
    true);
  const older = Buffer.from(bytes);
  older.write('{"version":2,', at, "latin1");
  let refused = null;
  try {
    await load(older);
  } catch (said) {
    refused = said.message;
  }
  same("what loading a version 2 surface says", refused,
    "this module's surface is version 2, and this glue reads version 3");
}

console.log(wrong === 0 ? "the glue calls a compiled program" : `${wrong} did not hold`);
process.exit(wrong === 0 ? 0 : 1);
