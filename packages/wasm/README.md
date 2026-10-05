# @souther/wasm

Loads a Souther program compiled to WebAssembly by `souther compile --target wasm`, calls it, and
writes the TypeScript binding of what it offers.

## A binding is read off the module

A compiled module says what it offers in `souther:surface`: its behaviors, what each takes and
answers, what it reaches out for, and what a value of each type looks like. The binding is written
from that and nothing else, so a project compiles the module and then writes the binding from it:

    souther compile --target wasm src/main/souther -o cart.wasm
    souther-wasm-bindings cart.wasm -o src/cart.ts

```ts
import { load } from "./cart.ts";

const cart = await load("/cart.wasm");
const answer = cart.modules.cart.price({ lines, member: "Premium" });
if (answer.issues === undefined && answer.value.type === "Priced") {
  show(answer.value.total);
}
```

What the model names stays under the name it gives: a behavior is `bound.modules.<module>.<behavior>`,
a type a value can be read as on its own is the decoder `bound.decode.<module>.<Type>`, and the
decoder of the same type as a form gives it `bound.form.<module>.<Type>`. Only behaviors the module
publishes are there to call. What the host supplies is every behavior the program reaches
out for, published or kept, by `"<module>.<behavior>"`. Each declaration is a type of the same name,
with its module's name before it where two modules declare one by that name; a name that TypeScript
reserves, or that the binding declares itself (`Bound`, `Supplied`, `load`, `FINGERPRINT`), is
given a number after it.

So a case or a field renamed in the model is a type renamed in the binding, and a page still
reading the old one stops compiling. A product is an object type, a newtype the type it is written
as, a sum the union of its cases discriminated by `type`, an enumeration the union of its names, an
`Int` a `bigint` and a `Decimal` raoh-ts's `Decimal`, as Raoh's `long()` and `decimal()` give them,
and a temporal its text. An optional field may be left out or written as `null`, since the boundary
reads either as nothing; a module writing one leaves it out.

What crosses is read as the surface says each value is, so no count or price is rounded however
many digits it has: a `bigint` and a `Decimal` are handed over as the numbers they are and come back
as the same types, and what came back is handed over again as it is. A string is never a number
here, so a page handing one where the model takes an `Int` does not compile; `Decimal.parse("1.10")`
and `Decimal.of(3)` make a `Decimal` to hand over, and `decimal.toString()` writes its digits.

## A form hands over what was typed

Every box of a form holds text. `bound.form.<module>.<Type>` reads a value as a form gives it: text
that spells a JSON number where the type has an `Int` or a `Decimal` is that number, and `"true"`
and `"false"` where it has a `Bool` are those. What the text spells decides it, and nothing else:
any other text is handed over as it was typed, and the model says what is wrong with it, at its
path, as it says of anything else. Nothing is trimmed, and nothing about what a quantity is or what
a code looks like is decided in the page.

What an empty box means is not something its text says. An optional `String` holds the empty string
as well as nothing, so an empty box is the empty string unless the page says otherwise:
`bound.form.<module>.<Type>.emptyAsNothing()` reads an empty box as nothing wherever the type has an
optional value. An empty box where a value is required is handed over as it is, either way.

```ts
const read = cart.form.cart.Cart.decode({ lines: [{ sku: "ABC-1234", quantity: "two", unitPrice: "1500.00" }], member });
read.issues?.at(["lines", 0, "quantity"]).map((issue) => messageOf(issue, locale)); // ["expected long"]
```

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

A behavior's answer and a value read on its own are each a `Reading<T>`, which is
[raoh-ts](https://github.com/raoh-project/raoh-ts)'s `Result<T>`: the value, or the `Issues` the
boundary found. Each is a raoh-ts `Issue` — a `Path`, a `code`, a `messageKey`, `meta`, and the
sentence the decoder gave where it said one in its own words — read from what the module wrote as
Raoh reads JSON, so a bound in its metadata is the amount it was: a `bigint` where it is written as
an integer, a `Decimal` at its scale where it is not. `messageOf(issue, locale)` writes the sentence
a person reads from Raoh's catalogue, and `messagesFor(locale)` is the catalogue itself, saying of
Souther's own issues (`invariant_violation`) what the JVM says. So a form says what the JVM would
have said, in English or Japanese, and the rule it is about is written in the model and nowhere
else.

`issues.at(path)` gives the issues at one path, which is what a form shows beside the field it names.

A type a value can be read as on its own is a raoh-ts `Decoder`. A value the model knows whole is
read by the model's own decoder; where a page reads something the model has no part in, such as a
box a person ticks to agree to terms, it reads it with Raoh, the model's types as its parts:

```ts
import { field, int, object } from "@raoh/core";

const order = object(field("sku", cart.decode.cart.Sku), field("count", int()));
order.decode({ sku: "nope", count: 1 }).issues?.list.map((issue) => issue.path.toString()); // ["/sku"]
```

The module reads the part, its rules included, and what it says is wrong is said at the part's
path. A member that is not there is read by the module as it reads one that is not there.

That takes the page and this package to use one `@raoh/core`: a `Path` the page's `field` hands
the model's decoder, and the `Issues` it hands back, are values of one copy of it. So this package
asks for `@raoh/core` as a peer dependency, which the project depends on itself:

    npm install @souther/wasm @raoh/core@^0.9.0

Where a program comes to hold two copies anyway, a value of one met by the other is refused with an
error saying so, rather than read as something else.

What each value comes to, and what is read of it, is held for the JVM, the wasm module and this
package alike to the fixtures in the repository's `conformance/issues`.

## Where it runs

What is installed is JavaScript and its declarations, built from `src` into `dist`; Node runs no
TypeScript under `node_modules`. It needs Node 22 or later, or a browser with ES2024: what crosses is
read and written by raoh-ts, number by number as the surface says each is, and nothing of the
engine's own reading of a number is asked.

## Developing

    npm install
    npm run typecheck
    npm run build   # what a project installing this, and the example beside it, reads
    npm test        # needs the compiler built: mvn package at the repository's root

Developing it takes Node 22.18.0 or later, which runs the tests as the TypeScript they are written
in; `package.json` says that in `devEngines`, apart from the Node a project runs the package on in
`engines`.

The tests read `src` directly, except `test/installed.test.ts`, which packs the package as it would
be published, installs it into a project of its own, and writes, compiles and runs a page there. CI
also hands what it built there, the archives, the module and the compiled page, to that test run on
the oldest Node `engines` names, which installs and runs them with nothing of the repository's tools.

Raoh's catalogue is raoh-ts's, which ships the text of raoh-specification's. `@raoh/core` is asked
for as a peer in the range of its 0.9 releases, and developed and tested against 0.9.0 exactly;
`test/installed.test.ts` installs it from what this directory installed, so the install reaches
nothing outside the machine.
