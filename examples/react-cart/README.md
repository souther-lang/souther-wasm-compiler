# A basket priced by a Souther model, from React

What a basket costs is written in [`model/src/cart.sou`](model/src/cart.sou) and nowhere else. This
page hands over what was typed and shows what came back. The shape of a product code, and that a
quantity is at least one, appear nowhere in the JavaScript.

    npm install
    npm run dev

`npm run dev` compiles the model and then starts Vite. Run it again after changing the model.

## What is going on

The compiler turns `cart.sou` into a WebAssembly module. Not a component — a plain core module,
which is what a browser reads, so nothing is transpiled and nothing is bundled between the compiler
and the page.

[`src/souther.js`](src/souther.js) is the whole of the calling. It knows nothing about the model, so
it is the same file whatever program it loads:

* the arguments go over as one JSON array, written into the module's own memory
* a call is bracketed by `__ronto_alloc_mark` and `__ronto_alloc_reset`
* one JSON object comes back — either `{"value": ...}` or `{"issues": [...]}`

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
