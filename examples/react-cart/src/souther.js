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

/**
 * Loads a compiled Souther program.
 *
 * @param {string | URL | Response | ArrayBuffer} source where the module is
 * @returns a program whose behaviors can be called by name
 */
export async function load(source) {
  const { instance } = await instantiate(source);
  return new Program(instance);
}

async function instantiate(source) {
  const imports = {
    // The one crossing out of a Souther module: a behavior the model declares and the host
    // implements. A program with none never reaches this, and one with some is not something this
    // example writes, so it says so rather than answering.
    souther: {
      host_call() {
        throw new Error("this program reaches out for a behavior the host has not implemented");
      },
    },
  };
  if (source instanceof ArrayBuffer || ArrayBuffer.isView(source)) {
    return WebAssembly.instantiate(source, imports);
  }
  const response = source instanceof Response ? source : fetch(source);
  return WebAssembly.instantiateStreaming(response, imports);
}

class Program {
  #exports;

  constructor(instance) {
    this.#exports = instance.exports;
  }

  /** What this program offers, which is one name per behavior the model declares. */
  get behaviors() {
    return Object.keys(this.#exports).filter((name) => name.includes("."));
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
    const written = encoder.encode(JSON.stringify(args));
    const generation = this.#exports.__souther_failure_generation();
    const mark = this.#exports.__ronto_alloc_mark();
    try {
      const at = this.#exports.__ronto_alloc(written.length);
      // After the allocation and not before: growing the memory replaces the buffer, and a view
      // made over the old one writes where nothing will read.
      this.#bytes().set(written, at);
      const [pointer, length] = reach(at, written.length);
      return JSON.parse(decoder.decode(this.#bytes().subarray(pointer, pointer + length)));
    } catch (trapped) {
      throw this.#whyItEnded(generation, trapped);
    } finally {
      this.#exports.__ronto_alloc_reset(mark);
    }
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
