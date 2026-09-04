# A basket priced by a Souther model, from React

What a basket costs is written in [`model/src/cart.sou`](model/src/cart.sou) and nowhere else. This
page hands over what was typed and shows what came back. The shape of a product code, and that a
quantity is at least one, appear nowhere in the JavaScript.

    (cd ../.. && mvn package)
    npm install
    npm run dev

The first line builds the compiler, which is what turns the model into a module. `npm run dev`
compiles the model with it and then starts Vite, so it is what to run again after changing the
model.

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

[`src/souther.js`](src/souther.js) is the whole of the calling. It knows nothing about the model, so
it is the same file whatever program it loads:

* the arguments go over as one JSON array, written into the module's own memory
* a call is bracketed by `__ronto_alloc_mark` and `__ronto_alloc_reset`
* one JSON object comes back — either `{"value": ...}` or `{"issues": [...]}`

## Amounts

An amount is held to whatever precision it was written with. A JavaScript number is not, so an
amount put through one is rounded before the model ever sees it and rounded again coming back.

    import { amount } from "./souther.js";
    program.call("cart.price", [{ lines: [{ unitPrice: amount("12345678901234567890.12345") }] }]);

`amount` carries the digits. Coming back, a number is a number wherever one holds what the model
answered and the digits as a string wherever one does not — so reading a total means being ready
for either, and being handed a string is the model saying this is wider than you can hold.

An amount that crosses out carries no scale: the model answers `1.1` where it was handed `1.10`,
because how much it is and how it was written are two things and only the first is the amount. A
model that means a number of places to be shown answers a `String`, which is what `String.fromDecimal`
is for.

## Reaching out

A behavior the model declares and does not implement is one the page implements:

    const program = await load("/cart.wasm", {
      "rates.today": (pair) => rates[pair],
    });

`program.reachesOutFor` says which ones there are — the module carries the list, so a page can be
told what it owes before it is loaded rather than by a call failing.

Answering is a call and not a promise. There is no stopping wasm in the middle and picking it up
again, so what a model reaches out for has to be something the page already has: what it fetched
before this call, what it stored, what its clock says. Fetching belongs on the other side of the
call — React fetches, then hands the answer in.

## What the boundary says

Where it will not read what it was given, the model says which part it will not read.

```json
{"issues":[{"path":"/0/lines/0/sku","code":"invariant_violation",
            "meta":{"actual":"0","expected":"Sku"}}]}
```

`path` is a JSON Pointer into the arguments: `/0/lines/0/sku` is the first argument's first line's
product code. So the form does not work out which input a complaint belongs under. It arrives
knowing.

A rule about a whole line — at least one of something, at a price above nothing — arrives naming
the line, at `/0/lines/0`, because that is what the rule is about. Which of the line's rules it was
comes back as a number rather than as the name the model gave it (`atLeastOne`, `priced`), so the
form does not say which. Saying would be that rule written a second time, in a language nobody
reads when they change the first.

## An empty basket

`EmptyCart` is not a complaint. The answer is simply not a price, and the model says so with a type
of its own rather than with a price of zero — so the page tells the two apart by asking what it
was handed, not by comparing a number against nothing.

## After changing the model

The rows in [`model/src/cart.examples.sou`](model/src/cart.examples.sou) fail first. They are run
where the model is compiled, so whether a rule changed or something changed that was not meant to
is answered before the page is opened.
