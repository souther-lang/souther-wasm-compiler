// A Souther program, loaded and called from JavaScript.
//
// Everything the boundary is, is here: strings cross as a pointer and a length into the module's
// own memory, a caller brackets a call with a mark and a reset, and what comes back is one JSON
// object — either what the behavior answered or what the boundary would not read.
//
// Nothing in this file knows anything about a model. It is the same whatever program it loads;
// what a binding generated from a module knows is its own, and calls through this.

import type { Issue, Reading } from "./issues.ts";
import type { Surface } from "./surface.ts";

export { messageOf } from "./issues.ts";
export type { Issue, Reading } from "./issues.ts";
export type { Surface } from "./surface.ts";

const encoder = new TextEncoder();
const decoder = new TextDecoder();

/**
 * Which version of the surface this reads, and which ABI of the runtime it calls.
 *
 * A module of another is refused when it is loaded rather than read as this one: a surface of
 * another version says some things differently or not at all, and read as this one it would be
 * misread without a word. Held to what the compiler writes by
 * `TheGlueReadsWhatThisBuildWritesTest`.
 */
export const READS = { surface: 4, abi: 9 } as const;

/** Where the module says what it offers a caller, and what it reaches out for under which numbers. */
const SURFACE = "souther:surface";

/** Where an abort writes why the call ended, and how wide each field is. */
const FAILURE = { generation: 0, reason: 4, descriptor: 8, aux0: 12, aux1: 20 };

/** What each reason a call can end with is called, by the number it goes by. */
const REASONS: Readonly<Record<number, string>> = {
  1: "out of memory",
  2: "a mark the arena never gave out",
  3: "the arguments were not JSON",
  4: "a value the runtime could not read",
  5: "the answer had no place its type could hold",
  6: "division by zero",
  7: "this backend reached a state the checker settled it never would",
  8: "something that must hold did not",
  9: "a position the program said gets no value",
  10: "bounds that did not name what they were asked to name",
  11: "what a behavior answered did not keep what it promised",
  12: "a number the module gives no type under",
};

declare const AMOUNT: unique symbol;

/**
 * A number as it was written, crossing as the number it is: what `JSON.rawJSON` makes, which
 * `JSON.stringify` writes back as its digits and not as a string. Made only by `amount` and by
 * reading an answer, so an object that merely has a `rawJSON` field is not one.
 */
export interface Amount {
  readonly rawJSON: string;
  readonly [AMOUNT]: true;
}

/** `JSON.rawJSON`, which the standard library's types do not name yet. */
const raw = (JSON as unknown as { rawJSON(text: string): Amount }).rawJSON;

/**
 * Whether this engine reads a number's text as well as its value and writes a number from its
 * text: without both, a wide amount would be rounded on the way in or out and nothing would say so,
 * so a program is not loaded at all.
 */
const READS_NUMBERS_AS_WRITTEN = typeof raw === "function"
  && JSON.parse("1.0", (_key, _value, context?: { source?: string }) => context?.source) === "1.0";

/**
 * A number as the model holds one, handed over or handed back: a JavaScript number where one holds
 * it, and an `Amount` where one does not. Never a string — an answer handed back as one would be
 * handed over again as a string, and a string is not a number to the model.
 */
export type Numeric = number | Amount;

/**
 * An amount, as it was written.
 *
 * An amount is held to whatever precision it was written with and a JavaScript number is not, so
 * one written wider than a number holds would be rounded before it ever reached the model and
 * rounded again coming back. Handing this over instead carries the digits.
 *
 * @throws RangeError where `written` is not a number as JSON writes one
 */
export function amount(written: string | number | bigint): Amount {
  const text = String(written);
  if (partsOf(text) === undefined) {
    throw new RangeError(`${JSON.stringify(text)} is not a number as JSON writes one`);
  }
  return raw(text);
}

/** The digits a number is written as: what a page shows, whatever a JavaScript number would round. */
export function numeral(held: Numeric): string {
  return typeof held === "number" ? String(held) : held.rawJSON;
}

/** Where a module is: its bytes, or where to fetch them from. */
export type Source = string | URL | Response | ArrayBuffer | ArrayBufferView;

/** What answers each behavior a program reaches out for, by the name the model declares it under. */
export type Supplied = Readonly<Record<string, (...args: never[]) => unknown>>;

/** Why a call ended without a value, where the model said so. */
export class Ended extends Error {
  readonly reason: number;

  constructor(reason: number, cause: unknown) {
    super(`the call ended: ${REASONS[reason] ?? `reason ${reason}`}`, { cause });
    this.reason = reason;
  }
}

/**
 * Loads a compiled Souther program.
 *
 * Refused where its surface or its runtime is of a version this does not read, and, where
 * `fingerprint` is given, where its surface is not the one a binding was generated from.
 *
 * @param source where the module is
 * @param supplied what answers each behavior the program reaches out for
 * @param fingerprint what a binding was generated from, which the module's surface must be
 */
export async function load(
  source: Source,
  supplied: Supplied = {},
  fingerprint?: string,
): Promise<Program> {
  if (!READS_NUMBERS_AS_WRITTEN) {
    throw new Error("this engine cannot read a number as it was written (JSON.parse source text "
      + "access and JSON.rawJSON), so an amount wider than a JavaScript number would be rounded "
      + "without a word");
  }
  const module = await WebAssembly.compile(await asBytes(source));
  const [surface, held] = surfaceOf(module);
  if (fingerprint !== undefined) {
    const of = await fingerprintOf(held);
    if (of !== fingerprint) {
      throw new Error(`this module's surface is not the one the binding was generated from: `
        + `${of}, where the binding says ${fingerprint}`);
    }
  }
  const program = new Program(supplied, surface);
  const instance = await WebAssembly.instantiate(module, {
    souther: { host_call: program.reachOut },
  });
  const abi = (instance.exports.__souther_abi_version as (() => number) | undefined)?.();
  if (abi !== READS.abi) {
    throw new Error(`this module's runtime is ABI ${abi}, and this glue calls ABI ${READS.abi}`);
  }
  program.ready(instance);
  return program;
}

/**
 * What identifies a surface: the SHA-256 of what the module carries, as hex.
 *
 * Two modules whose surfaces differ by anything a binding was written from have different ones,
 * and a binding generated from one refuses the other rather than calling it with the numbers and
 * the shapes of the first.
 */
export async function fingerprintOf(surface: Uint8Array<ArrayBuffer>): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", surface));
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function asBytes(source: Source): Promise<BufferSource> {
  if (source instanceof ArrayBuffer || ArrayBuffer.isView(source)) {
    return source as BufferSource;
  }
  const response = source instanceof Response ? source : await fetch(source);
  return response.arrayBuffer();
}

/**
 * What the module offers a caller, and the bytes it was carried as.
 *
 * In the module and not beside it: a caller holding the module holds this, and there is no second
 * file to be handed the wrong one of.
 */
export function surfaceOf(module: WebAssembly.Module): [Surface, Uint8Array<ArrayBuffer>] {
  const held = WebAssembly.Module.customSections(module, SURFACE);
  if (held.length !== 1) {
    throw new Error(`this module carries ${held.length} ${SURFACE} sections, and the glue reads one`);
  }
  const bytes = new Uint8Array(held[0]);
  const surface = JSON.parse(decoder.decode(bytes)) as Surface;
  if (surface.version !== READS.surface) {
    throw new Error(`this module's surface is version ${surface.version}, and this glue reads `
      + `version ${READS.surface}`);
  }
  return [surface, bytes];
}

interface Exports {
  readonly memory: WebAssembly.Memory;
  readonly __ronto_alloc: (length: number) => number;
  readonly __ronto_alloc_mark: () => number;
  readonly __ronto_alloc_reset: (mark: number) => void;
  readonly __souther_failure_addr: () => number;
  readonly __souther_failure_generation: () => number;
  readonly __souther_decode: (number: number, at: number, length: number) => [number, number];
  readonly [behavior: string]: unknown;
}

/** A loaded program, whose behaviors are called by name. */
export class Program {
  #exports: Exports | undefined;
  readonly #supplied: Supplied;
  readonly #crossings: ReadonlyMap<number, string>;
  readonly #surface: Surface;
  readonly #decodable: ReadonlyMap<string, number>;

  constructor(supplied: Supplied, surface: Surface) {
    this.#supplied = supplied;
    // A call out carries a number and not a name, and the surface says which behavior each is: by
    // its module and its name, since one its module keeps is reached out for all the same and is
    // exported as nothing.
    this.#crossings = new Map(surface.modules
      .flatMap((module) => module.behaviors
        .filter((behavior) => behavior.reachOut !== undefined)
        .map((behavior) => [behavior.reachOut as number, `${module.name}.${behavior.name}`])));
    this.#surface = surface;
    // Resolved once, by name: the numbers are this module's, and only its own surface says which
    // type each one is.
    this.#decodable = new Map(surface.declarations
      .filter((declared) => declared.decode !== undefined)
      .map((declared) => [`${declared.module}.${declared.name}`, declared.decode as number]));
    this.reachOut = this.reachOut.bind(this);
  }

  ready(instance: WebAssembly.Instance): void {
    this.#exports = instance.exports as unknown as Exports;
  }

  /** What this program offers a caller, as the module says it. */
  get surface(): Surface {
    return this.#surface;
  }

  /** What this program offers, which is one name per behavior the model declares. */
  get behaviors(): string[] {
    return Object.keys(this.#held()).filter((name) => name.includes("."));
  }

  /** What this program reaches out for, which is what has to be supplied to load it. */
  get reachesOutFor(): string[] {
    return [...this.#crossings.values()];
  }

  /**
   * The first byte of this memory the module has not handed out: where it was before any call and
   * where it is after every one of them.
   */
  arenaTop(): number {
    return this.#held().__ronto_alloc_mark();
  }

  /**
   * Calls a behavior.
   *
   * @param behavior the name the model declares it under, module and all
   * @param args the call's arguments, in the order the behavior declares them
   */
  call<T = unknown>(behavior: string, args: readonly unknown[]): Reading<T> {
    const reach = this.#held()[behavior] as ((at: number, length: number) => [number, number])
      | undefined;
    if (reach === undefined) {
      throw new Error(`${behavior} is not a behavior this program offers`);
    }
    return this.#crossed(args, reach) as Reading<T>;
  }

  /**
   * Reads a value as a type the program publishes, on its own and not as a behavior's argument:
   * what a form checks one field against before there is a whole call to make. The type's rules
   * run, as they do for an argument.
   *
   * @param type the type, module and all: `cart.Sku`
   */
  decode<T = unknown>(type: string, value: unknown): Reading<T> {
    const number = this.#decodable.get(type);
    if (number === undefined) {
      throw new Error(`${type} is not a type this program offers to read`);
    }
    return this.#crossed(value, (at, length) => this.#held().__souther_decode(number, at, length)) as
      Reading<T>;
  }

  /** Hands `document` to `reach` as JSON in the module's memory, and reads what it answers. */
  #crossed(document: unknown, reach: (at: number, length: number) => [number, number]): unknown {
    const exports = this.#held();
    const generation = exports.__souther_failure_generation();
    const mark = exports.__ronto_alloc_mark();
    try {
      const written = encoder.encode(JSON.stringify(document));
      const at = exports.__ronto_alloc(written.length);
      // After the allocation and not before: growing the memory replaces the buffer, and a view
      // made over the old one writes where nothing will read.
      this.#bytes().set(written, at);
      const [pointer, length] = reach(at, written.length);
      return read(decoder.decode(this.#bytes().subarray(pointer, pointer + length)));
    } catch (trapped) {
      throw this.#whyItEnded(generation, trapped);
    } finally {
      exports.__ronto_alloc_reset(mark);
    }
  }

  /**
   * What the model reaches out for, answered by whoever loaded it.
   *
   * Answering is a call and not a promise. There is no stopping wasm in the middle and picking it
   * up again, so what a model reaches out for has to be something the page already has.
   */
  reachOut(ordinal: number, at: number, length: number, into: number, room: number): number {
    const crossing = this.#crossings.get(ordinal);
    const supplied = crossing === undefined ? undefined : this.#supplied[crossing];
    if (supplied === undefined) {
      throw new Error(crossing === undefined
        ? `this program reached out under a number it does not name: ${ordinal}`
        : `${crossing} is reached out for and nothing was supplied for it`);
    }
    const asked = read(decoder.decode(this.#bytes().subarray(at, at + length))) as never[];
    const written = encoder.encode(JSON.stringify(supplied(...asked)));
    if (written.length <= room) {
      this.#bytes().set(written, into);
    }
    return written.length;
  }

  #held(): Exports {
    if (this.#exports === undefined) {
      throw new Error("this program is not instantiated yet");
    }
    return this.#exports;
  }

  #bytes(): Uint8Array {
    return new Uint8Array(this.#held().memory.buffer);
  }

  /**
   * Why a call ended without a value. A Souther abort writes a record outside the arena and traps,
   * and the record carries a generation, so a trap with an unchanged one came from wasm itself.
   */
  #whyItEnded(generation: number, trapped: unknown): unknown {
    const exports = this.#held();
    const at = exports.__souther_failure_addr();
    const words = new DataView(exports.memory.buffer);
    if (words.getUint32(at + FAILURE.generation, true) === generation) {
      return trapped;
    }
    return new Ended(words.getUint32(at + FAILURE.reason, true), trapped);
  }
}

/**
 * Whether a document may hold a number no JavaScript number holds: one written with sixteen digits
 * or more, or with a power of ten. A number of fifteen significant digits or fewer and no power of
 * ten comes back from a JavaScript number as the amount it was, so a document with neither is read
 * without asking after each number — which is most of what reading one otherwise costs. Digits in
 * a string can only make this say yes where the answer is no, and that costs the slower read and
 * nothing else.
 */
const WIDE = /\d[\d.]{15}|\d[eE]/;

/**
 * A document, read with every number kept as it was written where a number cannot hold it: an
 * `Amount` of the digits wherever the nearest JavaScript number is a different amount, which is
 * handed over again as the number it is.
 */
function read(text: string): unknown {
  if (!WIDE.test(text)) {
    return JSON.parse(text);
  }
  return JSON.parse(text, function (_key, value, context?: { source?: string }) {
    // Most numbers are written as a JavaScript number writes them, and those it holds exactly.
    if (typeof value !== "number" || context?.source === undefined
      || context.source === String(value)) {
      return value;
    }
    return sameAmount(String(value), context.source) ? value : raw(context.source);
  });
}

/**
 * Whether two texts written as JSON numbers are the same amount, however each is written.
 *
 * Compared in the one form each amount has, and never by lining the two up: lining up `0` and
 * `1e-1000000000` would write out a number a billion digits long, for a text of fourteen bytes.
 */
function sameAmount(left: string, right: string): boolean {
  const a = partsOf(left);
  const b = partsOf(right);
  if (a === undefined || b === undefined) {
    return left === right;
  }
  return a.negative === b.negative && a.digits === b.digits && a.power === b.power;
}

/**
 * A number as JSON writes one, in the one form its amount has: whether it is below nothing, its
 * digits with no zero leading or trailing, and the power of ten they are multiplied by. Nothing is
 * no digits, whatever sign or power it was written with. What it costs is the text's length, so a
 * power however large is only read and never raised.
 */
function partsOf(written: string): { negative: boolean; digits: string; power: bigint } | undefined {
  const held = /^(-?)(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([-+]?\d+))?$/.exec(written);
  if (held === null) {
    return undefined;
  }
  const [, sign, whole, fraction = "", power = "0"] = held;
  const significant = (whole + fraction).replace(/^0+/, "");
  const digits = significant.replace(/0+$/, "");
  if (digits === "") {
    return { negative: false, digits: "", power: 0n };
  }
  return {
    negative: sign === "-",
    digits,
    power: BigInt(power) - BigInt(fraction.length) + BigInt(significant.length - digits.length),
  };
}

