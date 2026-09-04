// The glue, run against a compiled model.
//
// Building the page says it compiles. This says the calling works, which is a different claim and
// the only one nothing else in the repository makes: everything else reaches this boundary from
// the JVM, and what a caller outside it has to agree about — where a failure record's fields are,
// which way round a pointer and a length come back, what number each reason goes by — is agreed
// about here or nowhere.

import { readFileSync } from "node:fs";
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

console.log(wrong === 0 ? "the glue calls a compiled program" : `${wrong} did not hold`);
process.exit(wrong === 0 ? 0 : 1);
