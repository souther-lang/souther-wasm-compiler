// A Souther program, loaded and called from JavaScript.
//
// Everything the boundary is, is here: strings cross as a pointer and a length into the module's
// own memory, a caller brackets a call with a mark and a reset, and what comes back is one JSON
// object — either what the behavior answered or what the boundary would not read.
//
// Nothing in this file knows anything about a model. It is the same whatever program it loads;
// what a binding generated from a module knows is its own, and calls through this.

import { Decoder, type Issues, type Path, type Result, failed, parse, stringify } from "@raoh/core";
import { issuesIn, type Reading } from "./issues.ts";
import { type Behavior, type Shape, type Surface } from "./surface.ts";
import { AS_TYPED, type FormPolicy, Values } from "./values.ts";

export { messageOf, messagesFor } from "./issues.ts";
export type { Reading } from "./issues.ts";
export { Decimal } from "@raoh/core";
export type { Decoder, Issue, Issues } from "@raoh/core";
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
export const READS = { surface: 4, abi: 15 } as const;

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
  13: "a supplied function answered something other than its behavior's type",
};

/** The reason a call ends with where a supplied function answered outside its behavior's type. */
const INVALID_HOST_ANSWER = 13;

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
 * A call that ended because a function supplied for a behavior answered something that is not a
 * value of the type the behavior declares: a field missing, the wrong kind of JSON, or a value one
 * of the type's rules says nothing may be. The fault is the supplied function's and not the
 * caller's, so this is thrown rather than answered as the call's issues; the issues say where in
 * what it answered each thing is wrong.
 */
export class InvalidSuppliedAnswer extends Ended {
  /** The behavior whose function answered, by the name it was supplied under. */
  readonly behavior: string;
  /** What is wrong with the answer, at paths from its root. */
  readonly issues: Issues;

  constructor(behavior: string, issues: Issues, cause: unknown) {
    super(INVALID_HOST_ANSWER, cause);
    this.name = "InvalidSuppliedAnswer";
    this.message = `${behavior} answered something other than the type it declares: `
      + issues.list.map((issue) => `${issue.path.isRoot ? "(root)" : issue.path.toString()} ${issue.code}`).join(", ");
    this.behavior = behavior;
    this.issues = issues;
  }
}

/**
 * Loads a compiled Souther program.
 *
 * Everything a call needs is made sure of here, so that a program, once loaded, is one each of its
 * behaviors can be called on: refused where its surface or its runtime is of a version this does
 * not read, where `fingerprint` is given and its surface is not the one a binding was generated
 * from, where it imports anything but the one function this glue hands it, where a behavior it
 * reaches out for has no function supplied for it, and where it lacks something this glue calls.
 * What is supplied is checked before the module is instantiated, since instantiating it runs it.
 *
 * @param source where the module is
 * @param supplied what answers each behavior the program reaches out for, taken as it is now: a
 *   function put under a name afterwards is not the one the program calls
 * @param fingerprint what a binding was generated from, which the module's surface must be
 */
export async function load(
  source: Source,
  supplied: Supplied = {},
  fingerprint?: string,
): Promise<Program> {
  const module = await WebAssembly.compile(await asBytes(source));
  const [surface, held] = surfaceOf(module);
  if (fingerprint !== undefined) {
    const of = await fingerprintOf(held);
    if (of !== fingerprint) {
      throw new Error(`this module's surface is not the one the binding was generated from: `
        + `${of}, where the binding says ${fingerprint}`);
    }
  }
  const imported = WebAssembly.Module.imports(module)
    .filter((each) => !(each.module === HOST_CALL.module && each.name === HOST_CALL.name && each.kind === "function"))
    .map((each) => `${each.module}.${each.name} (${each.kind})`);
  if (imported.length > 0) {
    throw new Error(`this module imports what this glue, which calls ABI ${READS.abi}, does not hand it: `
      + imported.join(", "));
  }
  const crossings = answering(surface, supplied);
  const values = new Values(surface);
  // The module is handed what it reaches out through before there is a memory to read what it asks
  // from: one reaching out while it is instantiated is told so, and not answered from nothing.
  let reach: ((ordinal: number, at: number, length: number) => bigint) | undefined;
  const instance = await WebAssembly.instantiate(module, {
    [HOST_CALL.module]: {
      [HOST_CALL.name]: (ordinal: number, at: number, length: number) => {
        if (reach === undefined) {
          throw new Error("this program reached out while it was being instantiated, before anything could answer");
        }
        return reach(ordinal, at, length);
      },
    },
  });
  const abi = (instance.exports.__souther_abi_version as (() => number) | undefined)?.();
  if (abi !== READS.abi) {
    throw new Error(`this module's runtime is ABI ${abi}, and this glue calls ABI ${READS.abi}`);
  }
  const exports = exported(instance, surface);
  reach = (ordinal, at, length) => reachOut(crossings, values, exports, ordinal, at, length);
  return new Program(exports, surface, values, crossings);
}

/** What the module imports: the one function it reaches out through, under the name it imports it by. */
const HOST_CALL = { module: "souther", name: "host_call" } as const;

/**
 * What answers each behavior a program reaches out for, by the number it reaches out under.
 *
 * Taken from `supplied` once, and refused where something other than a function is under a name,
 * naming every one: a program that would find out on a call is not made. A call out carries a number
 * and not a name, and the surface says which behavior each is: by its module and its name, since one
 * its module keeps is reached out for all the same and is exported as nothing.
 */
function answering(surface: Surface, supplied: Supplied): ReadonlyMap<number, Crossing> {
  const crossings = new Map<number, Crossing>();
  const unanswered: string[] = [];
  for (const { named, behavior } of reachedFor(surface)) {
    const answer: unknown = supplied[named];
    if (typeof answer === "function") {
      crossings.set(behavior.reachOut as number, { named, behavior, answer: answer as Crossing["answer"] });
    } else {
      unanswered.push(named);
    }
  }
  if (unanswered.length > 0) {
    throw new Error(`this program reaches out for what no function was supplied for: ${unanswered.join(", ")}`);
  }
  return crossings;
}

/** Each behavior a program reaches out for, and the name it is supplied under. */
function reachedFor(surface: Surface): { readonly named: string; readonly behavior: Behavior }[] {
  return surface.modules.flatMap((module) => module.behaviors
    .filter((behavior) => behavior.reachOut !== undefined)
    .map((behavior) => ({ named: `${module.name}.${behavior.name}`, behavior })));
}

/**
 * What the instance exports, refused where it lacks something this glue calls: what the runtime
 * gives every module, and each behavior the surface says is exported. Each is named, as what it
 * should have been, so a module missing one is refused when it is loaded and not on the call that
 * first reaches for it.
 */
function exported(instance: WebAssembly.Instance, surface: Surface): Exports {
  const held = instance.exports;
  const behaviors = surface.modules.flatMap((module) => module.behaviors)
    .filter((behavior) => behavior.export !== undefined)
    .map((behavior) => [behavior.export as string, "function"] as const);
  const lacking = [...Object.entries(RUNTIME), ...behaviors]
    .filter(([name, kind]) => !isA(held[name], kind))
    .map(([name, kind]) => `${name} (${kind})`);
  if (lacking.length > 0) {
    throw new Error(`this module does not export what this glue calls: ${lacking.join(", ")}`);
  }
  return held as unknown as Exports;
}

/** What an export of the module is, as far as this glue calls it: a function, or the memory. */
type Kind = "function" | "memory";

function isA(value: WebAssembly.ExportValue | undefined, kind: Kind): boolean {
  return kind === "function" ? typeof value === "function" : value instanceof WebAssembly.Memory;
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

/** What the runtime exports in every module, which this glue calls. */
interface Runtime {
  readonly memory: WebAssembly.Memory;
  readonly __ronto_alloc: (length: number) => number;
  readonly __ronto_alloc_mark: () => number;
  readonly __ronto_alloc_reset: (mark: number) => void;
  readonly __souther_abi_version: () => number;
  readonly __souther_failure_addr: () => number;
  readonly __souther_failure_generation: () => number;
  readonly __souther_decode: (number: number, at: number, length: number) => [number, number];
  readonly __souther_issues_written: () => bigint;
}

/** What each export of the runtime is, by its name: one for each the glue calls, and no other. */
const RUNTIME = {
  memory: "memory",
  __ronto_alloc: "function",
  __ronto_alloc_mark: "function",
  __ronto_alloc_reset: "function",
  __souther_abi_version: "function",
  __souther_failure_addr: "function",
  __souther_failure_generation: "function",
  __souther_decode: "function",
  __souther_issues_written: "function",
} as const satisfies { readonly [name in keyof Runtime]: Kind };

type Exports = Runtime & { readonly [behavior: string]: unknown };

/** A behavior a program reaches out for: what it is called, what it is, and what answers it. */
interface Crossing {
  readonly named: string;
  readonly behavior: Behavior;
  readonly answer: (...args: never[]) => unknown;
}

/**
 * A loaded program, whose behaviors are called by name.
 *
 * Made by {@link load} and nothing else, which is why the package exports it as a type: a program is
 * not made before everything a call needs has been made sure of.
 */
class Program {
  readonly #exports: Exports;
  readonly #surface: Surface;
  readonly #decodable: ReadonlyMap<string, number>;
  readonly #values: Values;
  /** What each behavior a caller calls answers, by what it is exported as. */
  readonly #answers: ReadonlyMap<string, Shape>;
  /** What each behavior the program reaches out for is supplied under, by the number it goes by. */
  readonly #crossings: ReadonlyMap<number, Crossing>;

  constructor(exports: Exports, surface: Surface, values: Values, crossings: ReadonlyMap<number, Crossing>) {
    this.#exports = exports;
    this.#crossings = crossings;
    this.#answers = new Map(surface.modules
      .flatMap((module) => module.behaviors)
      .filter((behavior) => behavior.export !== undefined)
      .map((behavior) => [behavior.export as string, behavior.answers]));
    this.#values = values;
    this.#surface = surface;
    // Resolved once, by name: the numbers are this module's, and only its own surface says which
    // type each one is.
    this.#decodable = new Map(surface.declarations
      .filter((declared) => declared.decode !== undefined)
      .map((declared) => [`${declared.module}.${declared.name}`, declared.decode as number]));
  }

  /** What this program offers a caller, as the module says it. */
  get surface(): Surface {
    return this.#surface;
  }

  /** What this program offers, which is one name per behavior the model declares. */
  get behaviors(): string[] {
    return Object.keys(this.#exports).filter((name) => name.includes("."));
  }

  /** What this program reaches out for, which is what has to be supplied to load it. */
  get reachesOutFor(): string[] {
    return reachedFor(this.#surface).map((each) => each.named);
  }

  /**
   * The first byte of this memory the module has not handed out: where it was before any call and
   * where it is after every one of them.
   */
  arenaTop(): number {
    return this.#exports.__ronto_alloc_mark();
  }

  /**
   * Calls a behavior.
   *
   * @param behavior the name the model declares it under, module and all
   * @param args the call's arguments, in the order the behavior declares them
   */
  call<T = unknown>(behavior: string, args: readonly unknown[]): Reading<T> {
    const reach = this.#exports[behavior] as ((at: number, length: number) => [number, number])
      | undefined;
    const answers = this.#answers.get(behavior);
    if (reach === undefined || answers === undefined) {
      throw new Error(`${behavior} is not a behavior this program offers`);
    }
    return this.#crossed(args, reach, answers) as Reading<T>;
  }

  /**
   * Reads a value as a type the program publishes, on its own and not as a behavior's argument:
   * what a form checks one field against before there is a whole call to make. The type's rules
   * run, as they do for an argument.
   *
   * @param type the type, module and all: `cart.Sku`
   */
  decode<T = unknown>(type: string, value: unknown): Reading<T> {
    return this.decoder<T>(type).decode(value);
  }

  /**
   * A type the program publishes, as a Raoh decoder: what a page combines with decoders of its own,
   * so that `object(field("sku", decoder))` reads the field as the model's type, its rules
   * included, and says what is wrong with it at the field's path. An absent value is read as
   * `null`, which a module reads as it reads a member that is not there.
   *
   * @param type the type, module and all: `cart.Sku`
   */
  decoder<T = unknown>(type: string): Decoder<T> {
    return this.#reading<T>(type, (input) => input);
  }

  /**
   * A type the program publishes, as a Raoh decoder of what a form gives: the same reading as
   * {@link decoder}, of a value whose numbers and yes-or-nos may be the text typed for them. Text
   * that spells a JSON number where the type has a number is read as that number, and `"true"` and
   * `"false"` where it has a yes or no as those: what the text spells decides it. Any other text is
   * handed over as it was typed, the empty string included, and the model says what is wrong with
   * it at its path. A page that means an empty box to be nothing says so with
   * {@link FormDecoder.emptyAsNothing}.
   *
   * @param type the type, module and all: `cart.Cart`
   */
  formDecoder<T = unknown>(type: string): FormDecoder<T> {
    return this.#form<T>(type, AS_TYPED);
  }

  #form<T>(type: string, policy: FormPolicy): FormDecoder<T> {
    const shape = this.#shapeOf(type);
    return new FormDecoder<T>(this.#read<T>(type, (input) => this.#values.typed(shape, input, policy)),
      (chosen) => this.#form<T>(type, chosen));
  }

  #reading<T>(type: string, given: (input: unknown) => unknown): Decoder<T> {
    return new ModuleDecoder<T>(this.#read<T>(type, given));
  }

  /** How a value of `type` is read: what `given` makes of it, handed to the module. */
  #read<T>(type: string, given: (input: unknown) => unknown): (input: unknown) => Reading<T> {
    const number = this.#decodable.get(type);
    if (number === undefined) {
      throw new Error(`${type} is not a type this program offers to read`);
    }
    const shape = this.#shapeOf(type);
    return (input) =>
      this.#crossed(input === undefined ? null : given(input),
        (at, length) => this.#exports.__souther_decode(number, at, length), shape) as Reading<T>;
  }

  /** The shape a type the program declares is written as: the type itself, by its module and name. */
  #shapeOf(type: string): Shape {
    const at = type.lastIndexOf(".");
    return { is: "declared", module: type.slice(0, at), name: type.slice(at + 1) };
  }

  /**
   * Hands `document` to `reach` as JSON in the module's memory, and reads what it answers.
   *
   * The document is written as Raoh writes the input model, so a `bigint`, a `Decimal` or a number
   * read with Raoh's `parse` crosses as its digits and an object's members keep their order. What
   * comes back is read as Raoh reads JSON, so none of it is rounded: the value, read as `answers`
   * says it is, or the issues.
   */
  #crossed(document: unknown, reach: (at: number, length: number) => [number, number], answers: Shape):
    Result<unknown> {
    const exports = this.#exports;
    const generation = exports.__souther_failure_generation();
    const mark = exports.__ronto_alloc_mark();
    try {
      const written = encoder.encode(stringify(document));
      const at = exports.__ronto_alloc(written.length);
      // After the allocation and not before: growing the memory replaces the buffer, and a view
      // made over the old one writes where nothing will read.
      this.#bytes().set(written, at);
      const [pointer, length] = reach(at, written.length);
      const answer = parse(decoder.decode(this.#bytes().subarray(pointer, pointer + length)));
      if (!(answer instanceof Map)) {
        throw new Error("the module answered something other than an object");
      }
      return answer.has("issues") ? failed(issuesIn(answer)) : { value: this.#values.read(answers, answer.get("value")) };
    } catch (trapped) {
      throw this.#whyItEnded(generation, trapped);
    } finally {
      exports.__ronto_alloc_reset(mark);
    }
  }

  #bytes(): Uint8Array {
    return new Uint8Array(this.#exports.memory.buffer);
  }

  /**
   * Why a call ended without a value. A Souther abort writes a record outside the arena and traps,
   * and the record carries a generation, so a trap with an unchanged one came from wasm itself.
   */
  #whyItEnded(generation: number, trapped: unknown): unknown {
    // A call a supplied function made back into this program ended, and said why as it did. The
    // record is that call's and the arena it wrote the issues in has been reset, so this call has
    // nothing to add, and reading the record again would name the inner ending as its own.
    if (trapped instanceof Ended) {
      return trapped;
    }
    const exports = this.#exports;
    const at = exports.__souther_failure_addr();
    const words = new DataView(exports.memory.buffer);
    if (words.getUint32(at + FAILURE.generation, true) === generation) {
      return trapped;
    }
    const reason = words.getUint32(at + FAILURE.reason, true);
    if (reason !== INVALID_HOST_ANSWER) {
      return new Ended(reason, trapped);
    }
    // Read from the record before the issues are written: writing them may grow the memory, and
    // the view taken over it before then is of a buffer that is gone.
    const ordinal = Number(words.getBigUint64(at + FAILURE.aux0, true));
    const behavior = this.#crossings.get(ordinal)?.named ?? `the behavior reached out for under ${ordinal}`;
    // The issues are in the arena of the call that ended, which the caller has not reset yet.
    const written = exports.__souther_issues_written();
    const pointer = Number(written & 0xffff_ffffn);
    const length = Number(written >> 32n);
    const answer = parse(decoder.decode(this.#bytes().subarray(pointer, pointer + length)));
    return new InvalidSuppliedAnswer(behavior, issuesIn(answer), trapped);
  }
}

export type { Program };

/**
 * What the model reaches out for, answered by whoever loaded it.
 *
 * Answering is a call and not a promise. There is no stopping wasm in the middle and picking it up
 * again, so what a model reaches out for has to be something the page already has. What answers is
 * called as a function and not as anything's method, so `this` in it is `undefined`.
 *
 * What answers is called once, and its answer put in the module's arena, where the call that reached
 * out reads it: answered with where it is in the low half and how long in the high half.
 */
function reachOut(crossings: ReadonlyMap<number, Crossing>, values: Values, exports: Runtime,
  ordinal: number, at: number, length: number): bigint {
  const crossing = crossings.get(ordinal);
  if (crossing === undefined) {
    throw new Error(`this program reached out under a number it does not name: ${ordinal}`);
  }
  // What is asked is read as the behavior's parameters are, as a value the module answers is.
  const written = parse(decoder.decode(new Uint8Array(exports.memory.buffer).subarray(at, at + length)));
  const parameters = crossing.behavior.parameters;
  if (!Array.isArray(written) || written.length !== parameters.length) {
    throw new Error(`${crossing.named} was asked something other than its ${parameters.length} arguments`);
  }
  const asked = written.map((each, place) => values.read(parameters[place]!.type, each)) as never[];
  const answer = encoder.encode(stringify(Reflect.apply(crossing.answer, undefined, asked)));
  const into = exports.__ronto_alloc(answer.length) >>> 0;
  // Read after allocating: growing the memory leaves the buffer read before it detached.
  new Uint8Array(exports.memory.buffer).set(answer, into);
  return (BigInt(answer.length) << 32n) | BigInt(into);
}

/** A type a module publishes, read by the module: the issues it gives below where it is read. */
class ModuleDecoder<T> extends Decoder<T> {
  readonly #read: (input: unknown) => Reading<T>;

  constructor(read: (input: unknown) => Reading<T>) {
    super();
    this.#read = read;
  }

  decodeAt(input: unknown, path: Path): Reading<T> {
    const read = this.#read(input);
    return read.issues === undefined ? read : failed(read.issues.under(path));
  }
}

/**
 * A type a module publishes, read as a form gives it, with what a page chooses of how its form is
 * read beyond what the text spells.
 */
export class FormDecoder<T> extends ModuleDecoder<T> {
  readonly #chosen: (policy: FormPolicy) => FormDecoder<T>;

  constructor(read: (input: unknown) => Reading<T>, chosen: (policy: FormPolicy) => FormDecoder<T>) {
    super(read);
    this.#chosen = chosen;
  }

  /**
   * The same reading, an empty string where the type has an optional value read as nothing: what
   * an empty box means to a page that chooses it, where an optional String could otherwise hold the
   * empty string. An empty box where a value is required is still handed over as it is.
   */
  emptyAsNothing(): FormDecoder<T> {
    return this.#chosen({ emptyAsNothing: true });
  }
}
