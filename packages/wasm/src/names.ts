// What the binding calls what the model names: the TypeScript identifiers it declares.
//
// A name here is a function of what the model declares and nothing else, so what a name is does not
// depend on the order the declarations came in, nor on the Node that runs this, nor on what the
// model's other declarations were called before one was added. What a page imports from a binding is
// then what the model says, and a binding generated again from a module that changed in one place
// changes in that place. The properties are these, and test/names.test.ts holds each of them, for
// every code point where it can and for every short name over an alphabet that has the awkward ones
// where it cannot:
//
//   valid      an identifier is one TypeScript reads, in a binding read by the TypeScript the README names
//   kept       a name that is one, and does not begin with `_`, is written as it is, in whatever script
//   injective  two names are never written as one identifier
//   settled    what a declaration is called is the same whichever order the declarations are in
//   unmoved    a name written as it is is never given to another, however it was come to
//
// `identifier` has the first three and `distinct` the last two.

import { isIdentifierPart, isIdentifierStart } from "./ts-identifier-table.ts";

/**
 * What no name the binding declares at its top may be: what TypeScript reserves, what a type alias
 * cannot be called, and what the binding itself declares or reads there. A declaration a model
 * calls one of these is given another name, rather than the binding declaring two things under one
 * or a type of the model standing where the binding meant TypeScript's own.
 */
export const RESERVED: ReadonlySet<string> = new Set([
  // What JavaScript reserves, in strict code and in a module.
  "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do",
  "else", "enum", "export", "extends", "false", "finally", "for", "function", "if", "import", "in",
  "instanceof", "new", "null", "return", "super", "switch", "this", "throw", "true", "try",
  "typeof", "var", "void", "while", "with", "implements", "interface", "let", "package",
  "private", "protected", "public", "static", "yield", "await", "arguments", "eval",
  // What a type alias cannot be called.
  "any", "unknown", "never", "string", "number", "boolean", "symbol", "object", "bigint",
  "undefined", "type", "as", "satisfies",
]);

/**
 * `name` as an identifier TypeScript reads.
 *
 * A name every character of which TypeScript reads in an identifier, and the first as its start,
 * and which does not begin with `_`, is written as it is. Any other is written under a `_` of its
 * own, which no name written as it is begins with, so the two kinds cannot meet: a `_` in it is
 * written `__`, and a character TypeScript does not read there is written as its code point between
 * `_u` and `_`, as `_u088f_`. Read from the left, `__` is a `_` and `_u` is the beginning of a code
 * point, since a `_` of the name is never followed by `u` without being doubled first; so there is
 * one name to read back from what is written, which is what makes two names two identifiers.
 *
 * What TypeScript reads as an identifier is its own table (ts-identifier-table.ts), which is neither
 * Node's nor that of the language the model is written in, so a name that language admits may hold a
 * character TypeScript does not read.
 */
export function identifier(name: string): string {
  const characters = Array.from(name);
  const plain = characters.length > 0 && characters[0] !== "_"
    && isIdentifierStart(characters[0]!.codePointAt(0)!)
    && characters.every((each) => isIdentifierPart(each.codePointAt(0)!));
  if (plain) {
    return name;
  }
  return "_" + characters.map((each) => {
    const codePoint = each.codePointAt(0)!;
    if (each === "_") {
      return "__";
    }
    return isIdentifierPart(codePoint) ? each : `_u${codePoint.toString(16).padStart(4, "0")}_`;
  }).join("");
}

/**
 * Names for what `wanted` says, one for each, no two of them the same and none of them in `taken`.
 *
 * A name is written as `identifier` has it wherever that is free, for every one of them before any
 * is told apart from another; so a name that needs nothing is not given up to one that was come to
 * by being told apart, whichever of them was asked for first. Those that are not free are told apart
 * by a number, `x_2`, `x_3`…, in the order given, which is therefore the caller's to make not depend
 * on the order the model was written in.
 */
export function distinct(wanted: readonly string[], taken: Iterable<string>): string[] {
  const used = new Set(taken);
  const bases = wanted.map(identifier);
  const named: (string | undefined)[] = bases.map((base) => {
    if (used.has(base)) {
      return undefined;
    }
    used.add(base);
    return base;
  });
  return named.map((name, at) => {
    if (name !== undefined) {
      return name;
    }
    let n = 2;
    while (used.has(`${bases[at]}_${n}`)) {
      n++;
    }
    const told = `${bases[at]}_${n}`;
    used.add(told);
    return told;
  });
}

/** A declaration, as far as what it is called goes. */
export interface Named {
  readonly module: string;
  readonly name: string;
}

const keyOf = (each: Named): string => `${each.module}.${each.name}`;

/** Code units, so that what comes first does not depend on a locale. */
const before = (a: string, b: string): number => a < b ? -1 : a > b ? 1 : 0;

/**
 * What each declaration is called in the binding, by the key its module and name make: its own name
 * where no other module declares one by that name, and its module's before it where one does, since
 * a type is its module and its name.
 *
 * Those that need no module come first, so one written as `AX` in module `c` keeps its name where
 * `a.X` would be written the same way, and each goes in the order its key sorts in rather than the
 * order it was declared in: what a declaration is called is then a matter of what the model declares,
 * and a model whose declarations are reordered has the same binding.
 *
 * @param own what the binding declares itself, which no declaration of the model displaces
 */
export function namesOf(declarations: readonly Named[], own: Iterable<string>): Map<string, string> {
  const count = new Map<string, number>();
  for (const each of declarations) {
    count.set(each.name, (count.get(each.name) ?? 0) + 1);
  }
  const shared = (each: Named): boolean => (count.get(each.name) ?? 0) > 1;
  const ordered = [...declarations].sort((a, b) =>
    Number(shared(a)) - Number(shared(b)) || before(keyOf(a), keyOf(b)));
  const wanted = ordered.map((each) =>
    shared(each) ? each.module.split(".").map(capitalised).join("") + each.name : each.name);
  const named = distinct(wanted, [...RESERVED, ...own]);
  return new Map(ordered.map((each, at) => [keyOf(each), named[at]!]));
}

/**
 * What a signature calls its parameters, in order: the names the model gave them, and `argument0`,
 * `argument1`… where it gave none.
 */
export function parameterNames(declared: readonly (string | null)[]): string[] {
  return distinct(declared.map((each, at) => each ?? `argument${at}`), RESERVED);
}

function capitalised(word: string): string {
  const [first = "", ...rest] = Array.from(word);
  return first.toUpperCase() + rest.join("");
}
