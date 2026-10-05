# A basket priced by a Souther model, from React

What a basket costs is written in [`model/src/cart.sou`](model/src/cart.sou) and nowhere else. This
page hands over what was typed and shows what came back. The shape of a product code, and that a
quantity is at least one, appear nowhere in the TypeScript.

    (cd ../.. && mvn package)
    (cd ../../packages/wasm && npm install && npm run build)
    npm install
    npm run dev

The first line builds the compiler, which is what turns the model into a module, and the second
the glue the page calls it through, which the page reads as a project installing it would:
`npm install` installs a copy of it (`.npmrc` says `install-links`), beside the page's own
`@raoh/core`, which the glue asks for as a peer. So the glue and the page share one Raoh, and a
decoder of the model's can be a part of one the page writes. A copy is a copy, so run
`npm install` here again after changing the glue.
`npm run dev`
compiles the model with it, writes the module's TypeScript binding into `src/cart.ts`, and then
starts Vite, so it is what to run again after changing the model. `npm run build` checks the page
against the binding with `tsc` before it bundles it: a field or a case renamed in the model is a
page that no longer compiles.

## How you work on one

Not through the browser. A model is a program the JVM runs, and
[`souther run`](https://github.com/souther-lang/souther) runs one behavior of it without compiling
anything to disk, so changing a rule and seeing what it answers is one command — and the module
this repository writes is a build output rather than a step in the loop.

    souther run --behavior price \
      --input '{"lines":[{"sku":"ABC-1234","quantity":2,"unitPrice":3000.00}],"member":"Premium"}' \
      model/src/cart.sou
    # => {"subtotal":6000,"discount":600,"shipping":0,"total":5400,"type":"Priced"}

The same command is where a rule is met from the other side. Nothing about the input below is a
special case anybody wrote:

    souther run --behavior price \
      --input '{"lines":[{"sku":"nope","quantity":1,"unitPrice":1}],"member":"Standard"}' \
      model/src/cart.sou
    # => input #1 could not be decoded — /lines/0/sku: invalid format

## Writing the examples is deciding the answers

`souther examples` reads a model and measures the rows against the model's own rules — not against
lines of anything. What it reports is which points the rules define and which of them no row stands
at:

    border   borders 3   obligations 2/3
      ! no row is at an IN point (comparison@32:35)
          · read as price/List.length(cart.lines): in 1 < List.length(cart.lines)
      · no OFF point is owed at quantity = 1 (invariant Line (atLeastOne)):
          excluded — the rules leave no value there

Two different things, and they are worth reading apart. The first is a point somebody has to decide
the answer at. The second is a point that cannot be written, because the rules leave no value
there — so it is not owed, and nothing is missing.

`--generate --boundaries` then writes the row for the first, with the answer left out:

    // example price
    //     | ( Cart { lines = [ Line { sku = Sku("AAA-0000"), quantity = 1, unitPrice = 1m },
    //                          Line { sku = Sku("AAA-0000"), quantity = 1, unitPrice = 1m } ],
    //                member = Standard } )
    //         -> <?>

The product code in it came from the format rule the model states. So what is left to do is fill in
`<?>`, which is the one part of it nothing but a person reading what the rules are meant to say can
answer. Working out what to ask is the compiler's half; deciding what the answer is is not.

## What a build stopping means

Three of them, and they mean three different things — all of them before the page is opened.

* **A row stopped holding.** Either a rule changed, or something changed that was not meant to. The
  rows run where the model is compiled, so this is the first thing that happens.
* **The language refused it.** What was written is not a model.
* **This backend refused it.** The language takes it and this cannot write it yet; the list is in
  [the compiler's README](../../README.md).

## What is going on

The compiler turns `cart.sou` into a WebAssembly module. Not a component — a plain core module,
which is what a browser reads, so nothing is transpiled and nothing is bundled between the compiler
and the page.

[`@souther/wasm`](../../packages/wasm) is the whole of the calling. It knows nothing about the
model, so it is the same package whatever program it loads: the arguments go over as one JSON array
written into the module's own memory, a call is bracketed by `__ronto_alloc_mark` and
`__ronto_alloc_reset`, and one JSON object comes back — either `{"value": ...}` or `{"issues": [...]}`.

What knows about the model is `src/cart.ts`, which `souther-wasm-bindings` writes from the module's
own surface each time the model is compiled. It types every value the page hands over and is handed
back, so the page reads `answer.value.type === "Priced"` against the cases the model declares, and
it refuses to load any module but the one it was written from.

## What the page hands over

The boxes of the form hold text, and the page hands that over as it is, read as a form gives a
basket:

    const read = cart.form.cart.Cart.decode({ lines, member });

Text that spells a number where the model has an `Int` or a `Decimal` is that number; any other text
goes over as it was typed, and the model says what is wrong with it, where it is. The page shows
each complaint beside the box it is about, by its path, `read.issues?.at(["lines", 0, "quantity"])`,
in the reader's language from Raoh's catalog. Nothing in the page says what a quantity or a product
code is.

## Amounts

An amount is held to whatever precision it was written with. A JavaScript number is not, so an
amount put through one is rounded before the model ever sees it and rounded again coming back. So
an `Int` crosses as a `bigint` and a `Decimal` as raoh-ts's `Decimal`, both ways:

    import { Decimal } from "@raoh/core";
    cart.modules.cart.price({ lines: [{ sku, quantity: 1n, unitPrice: Decimal.parse("12345678901234567890.12345")! }], member });

What comes back is the same types, so a total is a `Decimal` whatever its digits, `total.toString()`
writes them, and handed over again it is the number it is. The binding takes a `bigint` where the
model takes an `Int` and a `Decimal` where it takes a `Decimal`, and a page handing over a string or
a JavaScript number does not compile; a form's text is read by the form's decoder, above.

An amount that crosses out carries no scale: the model answers `1.1` where it was handed `1.10`,
because how much it is and how it was written are two things and only the first is the amount. A
model that means a number of places to be shown answers a `String`, which is what `String.fromDecimal`
is for.

## Reaching out

A behavior the model declares and does not implement is one the page implements:

    const rates = await load("/rates.wasm", {
      "rates.today": (pair) => today[pair],
    });

The binding types what has to be supplied (`Supplied`), so a page that leaves one out does not
compile, and `program.reachesOutFor` says the same at run time — the module carries the list.

Answering is a call and not a promise. There is no stopping wasm in the middle and picking it up
again, so what a model reaches out for has to be something the page already has: what it fetched
before this call, what it stored, what its clock says. Fetching belongs on the other side of the
call — React fetches, then hands the answer in.

## What the boundary says

Where it will not read what it was given, the model says which part it will not read.

```json
{"issues":[{"path":"/0/lines/0/sku","code":"invalid_format","messageKey":"invalid_format",
            "meta":{"pattern":"[A-Z]{3}-[0-9]{4}"}}]}
```

`path` is a JSON Pointer into the arguments: `/0/lines/0/sku` is the first argument's first line's
product code. So the form does not work out which input a complaint belongs under. It arrives
knowing.

The page hands over what was typed, which is not a basket until the model has read it as one: a
quantity typed as `two` is no `Int`. So it reads it first, with `bound.decode.cart.Cart.decode(typed)`, and
prices what that answers. A complaint about what was typed is then said of the basket, at
`/lines/0/sku`, and what is priced is a `Cart` the binding types.

An issue is Raoh's, as it is on the JVM: the code and the message key say which rule it was, and the
metadata what the rule says. A product code's pattern is reported as the format it is, with the
pattern; a rule about a whole line — at least one of something, at a price above nothing — arrives
naming the line, at `/0/lines/0`, as `invariant_violation` with the rule's name (`atLeastOne`,
`priced`).

What a person reads of an issue is not written here either. `messageOf(issue, locale)` writes it
from Raoh's catalogue, as the JVM does, in English or Japanese, so the page shows what the model's
rules say without saying any of them a second time.

## An empty basket

`EmptyCart` is not a complaint. The answer is simply not a price, and the model says so with a type
of its own rather than with a price of zero — so the page tells the two apart by asking what it
was handed, not by comparing a number against nothing.

## After changing the model

The rows in [`model/src/cart.examples.sou`](model/src/cart.examples.sou) fail first. They are run
where the model is compiled, so whether a rule changed or something changed that was not meant to
is answered before the page is opened.
