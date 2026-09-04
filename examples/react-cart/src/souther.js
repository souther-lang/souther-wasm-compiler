// A Souther program, loaded and called from JavaScript.
//
// Everything the boundary is, is here: strings cross as a pointer and a length into the module's
// own memory, a caller brackets a call with a mark and a reset, and what comes back is one JSON
// object — either what the behavior answered or what the boundary would not read.
//
// Nothing in this file knows anything about the model. It is the same file whatever program it
// loads, which is the point: what a cart costs is written in Souther and nowhere else.

const encoder = new TextEncoder();
const decoder = new TextDecoder();

/** Where an abort writes why the call ended, and how wide each field is. */
const FAILURE = { generation: 0, reason: 4, descriptor: 8, aux0: 12, aux1: 20 };

/** What each reason a call can end with is called, by the number it goes by. */
const REASONS = {
  1: "out of memory",
  2: "a mark the arena never gave out",
  3: "the arguments were not JSON",
  4: "a value the runtime could not read",
  5: "a whole number left the range",
  6: "division by zero",
  7: "no arm of a choice was taken",
  8: "something that must hold did not",
  9: "a position the program said gets no value",
  10: "a value outside the range its type reaches",
};

/** Where the module says what it reaches out for, and under which numbers. */
const CROSSINGS = "souther:crossings";

/**
 * An amount, as it was written.
 *
 * An amount is held to whatever precision it was written with and a JavaScript number is not, so
 * one written wider than a number holds would be rounded before it ever reached the model and
 * rounded again coming back. Handing this over instead carries the digits: what crosses is the
 * text, as the number it is.
 *
 * @param {string | number | bigint} written the amount, as digits
 */
export function amount(written) {
  return JSON.rawJSON(String(written));
}

/**
 * Loads a compiled Souther program.
 *
 * @param {string | URL | Response | ArrayBuffer} source where the module is
 * @param {Record<string, (...args: unknown[]) => unknown>} [supplied] what answers each behavior
 *     the program reaches out for, by the name the model declares it under
 * @returns a program whose behaviors can be called by name
 */
export async function load(source, supplied = {}) {
  const module = await WebAssembly.compile(await asBytes(source));
  const program = new Program(supplied, crossingsIn(module));
  program.ready(await WebAssembly.instantiate(module, {
    souther: { host_call: program.reachOut },
  }));
  return program;
}

async function asBytes(source) {
  if (source instanceof ArrayBuffer || ArrayBuffer.isView(source)) {
    return source;
  }
  const response = source instanceof Response ? source : await fetch(source);
  return response.arrayBuffer();
}

/**
 * What the module says it reaches out for.
 *
 * A call out carries a number and not a name, so the module says what the numbers are, in a
 * section of itself. In itself and not beside itself: a caller holding the module holds this, and
 * there is no second file to be handed the wrong one of.
 */
function crossingsIn(module) {
  const held = WebAssembly.Module.customSections(module, CROSSINGS);
  return held.length === 0 ? [] : JSON.parse(decoder.decode(held[0]));
}

class Program {
  #exports;
  #supplied;
  #crossings;

  constructor(supplied, crossings) {
    this.#supplied = supplied;
    this.#crossings = crossings;
    this.reachOut = this.reachOut.bind(this);
  }

  ready(instance) {
    this.#exports = instance.exports;
  }

  /** What this program offers, which is one name per behavior the model declares. */
  get behaviors() {
    return Object.keys(this.#exports).filter((name) => name.includes("."));
  }

  /** What this program reaches out for, which is what has to be supplied to load it. */
  get reachesOutFor() {
    return this.#crossings.map((crossing) => crossing.behavior);
  }

  /**
   * The first byte of this memory the module has not handed out.
   *
   * What a caller does with it is see that a call gave back what it took: everything a call made
   * lives until the answer has been read and nothing lives past that, so this is where it was
   * before any call and where it is after every one of them.
   */
  arenaTop() {
    return this.#exports.__ronto_alloc_mark();
  }

  /**
   * Calls a behavior.
   *
   * @param {string} behavior the name the model declares it under, module and all
   * @param {unknown[]} args the call's arguments, in the order the behavior declares them
   * @returns `{ value }` where the behavior answered, `{ issues }` where the boundary would not
   *     read what it was given
   */
  call(behavior, args) {
    const reach = this.#exports[behavior];
    if (reach === undefined) {
      throw new Error(`${behavior} is not a behavior this program offers`);
    }
    const generation = this.#exports.__souther_failure_generation();
    const mark = this.#exports.__ronto_alloc_mark();
    try {
      const written = encoder.encode(write(args));
      const at = this.#exports.__ronto_alloc(written.length);
      // After the allocation and not before: growing the memory replaces the buffer, and a view
      // made over the old one writes where nothing will read.
      this.#bytes().set(written, at);
      const [pointer, length] = reach(at, written.length);
      return read(decoder.decode(this.#bytes().subarray(pointer, pointer + length)));
    } catch (trapped) {
      throw this.#whyItEnded(generation, trapped);
    } finally {
      this.#exports.__ronto_alloc_reset(mark);
    }
  }

  /**
   * What the model reaches out for, answered by whoever loaded it.
   *
   * The buffer is the module's and the answer is written into it, so what comes back is how long
   * the answer wants to be — longer than the buffer asks to be called again against a longer one,
   * and nothing here has to know how long an answer is before it has one.
   *
   * Answering is a call and not a promise. There is no stopping wasm in the middle and picking it
   * up again, so what a model reaches out for has to be something the page already has: what it
   * fetched before this call, what it stored, what its clock says. Fetching belongs on the other
   * side of the call.
   */
  reachOut(ordinal, at, length, into, room) {
    const crossing = this.#crossings.find((held) => held.ordinal === ordinal);
    const supplied = crossing === undefined ? undefined : this.#supplied[crossing.behavior];
    if (supplied === undefined) {
      throw new Error(crossing === undefined
        ? `this program reached out under a number it does not name: ${ordinal}`
        : `${crossing.behavior} is reached out for and nothing was supplied for it`);
    }
    const asked = read(decoder.decode(this.#bytes().subarray(at, at + length)));
    const written = encoder.encode(write(supplied(...asked)));
    if (written.length <= room) {
      this.#bytes().set(written, into);
    }
    return written.length;
  }

  #bytes() {
    return new Uint8Array(this.#exports.memory.buffer);
  }

  /**
   * Why a call ended without a value.
   *
   * A Souther abort writes a record outside the arena and traps. The record carries a generation,
   * so a caller that read it before the call can tell a Souther abort from an ordinary wasm fault
   * — an unreachable instruction says the same thing either way, and only one of the two is the
   * model saying something.
   */
  #whyItEnded(generation, trapped) {
    const at = this.#exports.__souther_failure_addr();
    const words = new DataView(this.#exports.memory.buffer);
    if (words.getUint32(at + FAILURE.generation, true) === generation) {
      return trapped;
    }
    const reason = words.getUint32(at + FAILURE.reason, true);
    const said = REASONS[reason] ?? `reason ${reason}`;
    const held = new Error(`the call ended: ${said}`);
    held.reason = reason;
    held.cause = trapped;
    return held;
  }
}

/**
 * A document, read with every number kept as it was written where a number cannot hold it.
 *
 * The ordinary reading answers the nearest number a JavaScript number holds, and for an amount
 * that is a different amount. What comes back instead is the digits, as a string, wherever the two
 * are not the same — so a caller reading a total is reading what the model worked out rather than
 * what survived being read.
 */
function read(text) {
  return JSON.parse(text, function (key, value, context) {
    if (typeof value !== "number" || context?.source === undefined) {
      return value;
    }
    // What the number is written back as against what it was written as. Where they are the same
    // amount the number says everything the digits did, and where they are not the digits are what
    // the model answered — so a caller working in amounts wide enough for this to happen reads a
    // string, which is the only thing that can carry them.
    return sameAmount(String(value), context.source) ? value : context.source;
  });
}

/** Whether two texts written as JSON numbers are the same amount, however each is written. */
function sameAmount(left, right) {
  const a = digitsOf(left);
  const b = digitsOf(right);
  if (a === undefined || b === undefined) {
    return left === right;
  }
  const under = a.scale < b.scale ? b.scale : a.scale;
  return a.digits * 10n ** BigInt(under - a.scale) === b.digits * 10n ** BigInt(under - b.scale);
}

/** A number as it was written, taken apart into whole digits and how far the point moved. */
function digitsOf(written) {
  const held = /^(-?)(\d+)(?:\.(\d+))?(?:[eE]([-+]?\d+))?$/.exec(written);
  if (held === null) {
    return undefined;
  }
  const [, sign, whole, fraction = "", power = "0"] = held;
  return {
    digits: BigInt(sign + whole + fraction),
    scale: fraction.length - Number(power),
  };
}

/** A document, with an amount written as its digits rather than as the nearest number to them. */
function write(value) {
  return JSON.stringify(value);
}
