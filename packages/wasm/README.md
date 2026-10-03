# @souther/wasm

Loads a Souther program compiled to WebAssembly by this repository, calls it, and writes the
TypeScript binding of what it offers.

## A binding is read off the module

A compiled module says what it offers in `souther:surface`: its behaviors, what each takes and
answers, what it reaches out for, and what a value of each type looks like. The binding is written
from that and nothing else:

    souther-wasm-bindings cart.wasm -o src/cart.ts

```ts
import { load } from "./cart.ts";

const cart = await load("/cart.wasm");
const answer = cart.cart.price({ lines, member: "Premium" });
if (answer.issues === undefined && answer.value.type === "Priced") {
  show(answer.value.total);
}
```

So a case or a field renamed in the model is a type renamed in the binding, and a page still
reading the old one stops compiling. A product is an object type, a newtype the type it is written
as, a sum the union of its cases discriminated by `type`, an enumeration the union of its names, an
`Int` or a `Decimal` a `Numeric` — a number where a JavaScript number holds it and an `Amount` where
one does not — and a temporal its text.

An `Amount` is the digits crossing as the number they are (`JSON.rawJSON`): `amount("1.10")` makes
one to hand over, an answer too wide for a number comes back as one, and either is handed over
again as a number. `numeral(held)` is its digits, for showing. A string is never a number here, so
a page handing one where the model takes an `Int` does not compile.

A binding knows which module it was generated from: the SHA-256 of that module's surface. Loading
any other module through it is refused, rather than calling it with the first one's numbers and
shapes; generate the binding again whenever the module is compiled again.

## What is checked when a module is loaded

Three things, and they are different questions:

- the surface's `version`, which says how what the module says is to be read;
- the runtime's ABI, which says how a call into the module is made and how it ends;
- the binding's fingerprint, which says the module is the one the binding was written from.

A module failing any of them is refused when it is loaded, not when it is first called.

## What the boundary would not read

A behavior's answer and a value read on its own are each a `Reading<T>`: the value, or the issues
the boundary found. An issue is Raoh's — `path`, `code`, `messageKey`, `meta`, and `message` where
the decoder said it in its own words — and `messageOf(issue, locale)` writes the sentence a person
reads from Raoh's catalog, the way the JVM's resolver writes it. So a form says what the JVM would
have said, in English or Japanese, and the rule it is about is written in the model and nowhere
else.

What each value comes to, and what is read of it, is held for the JVM, the wasm module and this
package alike to the fixtures in the repository's `conformance/issues`.

## Developing

    npm install
    npm run typecheck
    npm test        # needs the compiler built: mvn package at the repository's root

`src/catalog.ts` is generated from raoh-specification by `scripts/catalog.py`.
