// What the binding calls what the model names, held to the properties names.ts says it has. None of
// these starts a compiler: a name is a function of what the model declares, so it is read over every
// code point, over every short name made of what is awkward about names, and over every order a set of
// declarations can come in.

import assert from "node:assert/strict";
import { describe, it } from "node:test";
import ts from "typescript";
import { bindingFor } from "../src/generate.ts";
import { distinct, identifier, namesOf, parameterNames, RESERVED } from "../src/names.ts";
import type { Surface } from "../src/surface.ts";

const TARGET = ts.ScriptTarget.ES2020;

/** Whether `name` is one identifier to TypeScript's own scanner, which is not this package's table. */
function reads(name: string): boolean {
  const points = Array.from(name, (each) => each.codePointAt(0)!);
  return points.length > 0 && ts.isIdentifierStart(points[0]!, TARGET)
    && points.every((each) => ts.isIdentifierPart(each, TARGET));
}

/** What a name is, read back from the identifier written for it, by the grammar identifier says. */
function read(written: string): string {
  if (!written.startsWith("_")) {
    return written;
  }
  let name = "";
  for (let i = 1; i < written.length;) {
    if (written[i] === "_" && written[i + 1] === "_") {
      name += "_";
      i += 2;
    } else if (written[i] === "_" && written[i + 1] === "u") {
      const end = written.indexOf("_", i + 2);
      assert.ok(end > i + 2, `${written}: a code point that does not end`);
      name += String.fromCodePoint(parseInt(written.slice(i + 2, end), 16));
      i = end + 1;
    } else {
      assert.notEqual(written[i], "_", `${written}: a _ that is neither doubled nor beginning a code point`);
      const each = String.fromCodePoint(written.codePointAt(i)!);
      name += each;
      i += each.length;
    }
  }
  return name;
}

/** Whether `name` is one a binding writes as it is, by TypeScript's own scanner. */
const plain = (name: string): boolean => name[0] !== "_" && reads(name);

describe("an identifier", () => {
  it("is one TypeScript reads and can be read back, for every code point", () => {
    for (let cp = 0; cp <= 0x10ffff; cp++) {
      const name = String.fromCodePoint(cp);
      const written = identifier(name);
      assert.ok(reads(written), `U+${cp.toString(16)} is written ${written}`);
      assert.equal(read(written), name, `U+${cp.toString(16)} is written ${written}`);
      if (plain(name)) {
        assert.equal(written, name);
      }
    }
  });

  it("is one TypeScript reads, one name's and no other's, for every short name over what is awkward", () => {
    // `_` and `u` and the digits of a code point are what an escape is made of, `1` and `-` what an
    // identifier does not begin with or hold, U+088F what Souther admits and TypeScript does not,
    // and the rest what is kept: a name of any of these is the kind a model can make.
    const alphabet = ["_", "u", "0", "f", "a", "1", "-", " ", "$", "࢏", "౜", "保", "\u{10428}"];
    const written = new Map<string, string>();
    let names = [""];
    for (let length = 0; length <= 4; length++) {
      for (const name of names) {
        const identifierOf = identifier(name);
        assert.ok(reads(identifierOf), `${JSON.stringify(name)} is written ${identifierOf}`);
        assert.equal(read(identifierOf), name, `${JSON.stringify(name)} is written ${identifierOf}`);
        assert.equal(written.get(identifierOf), undefined,
          `${JSON.stringify(name)} and ${JSON.stringify(written.get(identifierOf))} are both written ${identifierOf}`);
        written.set(identifierOf, name);
        if (plain(name)) {
          assert.equal(identifierOf, name);
        }
      }
      names = names.flatMap((name) => alphabet.map((each) => name + each));
    }
    assert.ok(written.size > 20000);
  });

  it("does not write a name TypeScript refuses as another that it reads", () => {
    // Both are names Souther admits, and the second is what the first came to before this was one name each.
    assert.notEqual(identifier("a࢏"), identifier("a_u088f_"));
    assert.equal(identifier("a_u088f_"), "a_u088f_");
    assert.equal(identifier("a࢏"), "_a_u088f_");
    assert.equal(identifier("a_࢏"), "_a___u088f_");
  });

  it("keeps a name in the script it is written in", () => {
    for (const name of ["保険者番号", "位置", "αβ", "\u{10428}型", "Éa"]) {
      assert.equal(identifier(name), name);
    }
  });
});

describe("a set of names", () => {
  it("gives a name that needs nothing to it before one that was told apart from another", () => {
    // `class` is reserved and comes to `class_2`, which is another declaration's own name.
    assert.deepEqual(distinct(["class", "class_2"], RESERVED), ["class_3", "class_2"]);
    assert.deepEqual(distinct(["class_2", "class"], RESERVED), ["class_2", "class_3"]);
  });

  it("tells apart what a signature calls its parameters", () => {
    assert.deepEqual(parameterNames(["位置", null, "argument1", "class"]), ["位置", "argument1", "argument1_2", "class_2"]);
  });
});

/** Every order `items` can be in. */
function* orders<T>(items: readonly T[]): Generator<T[]> {
  if (items.length <= 1) {
    yield [...items];
    return;
  }
  for (let i = 0; i < items.length; i++) {
    for (const rest of orders([...items.slice(0, i), ...items.slice(i + 1)])) {
      yield [items[i]!, ...rest];
    }
  }
}

describe("what a declaration is called", () => {
  const OWN = ["Bound", "souther"];
  const DECLARED = [
    { module: "a", name: "X" },
    { module: "b", name: "X" },
    { module: "c", name: "AX" },
    { module: "a", name: "class" },
    { module: "a", name: "class_2" },
    { module: "a", name: "Bound" },
    { module: "p", name: "a࢏" },
    { module: "p", name: "a_u088f_" },
  ];

  it("is the same whichever order the declarations are in", () => {
    const entries = (list: readonly { module: string; name: string }[]) =>
      JSON.stringify([...namesOf(list, OWN)].sort(([a], [b]) => a < b ? -1 : 1));
    const first = entries(DECLARED);
    let seen = 0;
    for (const order of orders(DECLARED)) {
      assert.equal(entries(order), first, order.map((each) => `${each.module}.${each.name}`).join(" "));
      seen++;
    }
    assert.equal(seen, 40320);
  });

  it("is the same whichever order two declarations that come to one name are in", () => {
    // `a.b.C` and `a.BC` are each declared by another module as well, so each is named by its module,
    // and both are `ABC`: one of them is told apart by a number, and which one does not depend on the order.
    const colliding = [
      { module: "a.b", name: "C" }, { module: "z", name: "C" },
      { module: "a", name: "BC" }, { module: "y", name: "BC" },
    ];
    const results = new Set<string>();
    for (const order of orders(colliding)) {
      results.add(JSON.stringify(Object.fromEntries([...namesOf(order, OWN)].sort(([a], [b]) => a < b ? -1 : 1))));
    }
    assert.deepEqual([...results].map((each) => JSON.parse(each)), [
      { "a.BC": "ABC", "a.b.C": "ABC_2", "y.BC": "YBC", "z.C": "ZC" },
    ]);
  });

  it("gives each declaration the name it asks for where it can, and tells apart only what must be", () => {
    const named = namesOf(DECLARED, OWN);
    assert.deepEqual(Object.fromEntries(named), {
      "a.X": "AX_2", "b.X": "BX", "c.AX": "AX",
      "a.class": "class_3", "a.class_2": "class_2", "a.Bound": "Bound_2",
      "p.a࢏": "_a_u088f_", "p.a_u088f_": "a_u088f_",
    });
  });

  it("is a different name for each declaration", () => {
    assert.equal(new Set(namesOf(DECLARED, OWN).values()).size, DECLARED.length);
  });
});

describe("a binding", () => {
  it("does not let a name in the module it is generated from end the comment it is named in", () => {
    // A module is read as it is, and one written by hand can name a declaration whatever it likes.
    const surface = {
      version: 4,
      modules: [],
      declarations: [{ module: "m", name: "X */ export const injected = 1; /*", by: "module", published: true, is: "unit" }],
    } satisfies Surface;
    const source = ts.createSourceFile("binding.ts", bindingFor(surface, "fingerprint"), TARGET, true);
    const declared = source.statements.flatMap((each) => ts.isVariableStatement(each)
      ? each.declarationList.declarations.map((it) => it.name.getText(source)) : []);
    assert.deepEqual(declared, ["FINGERPRINT"]);
  });
});
