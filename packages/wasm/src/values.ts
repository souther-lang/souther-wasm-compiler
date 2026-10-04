// What a value of a type the surface names is, on either side of the boundary.
//
// The module reads and writes JSON, and JSON has one kind of number. The surface says which type
// each value is, so what crosses is read as the type says: an `Int` a `bigint` and a `Decimal` a
// Raoh `Decimal`, as Raoh's `long()` and `decimal()` give them, so nothing a model counts or prices
// is rounded or loses the places it was written to. What a page hands over needs no such reading:
// Raoh writes a `bigint`, a `Decimal` and a JavaScript number as the numbers they are.
//
// A form is the one place a page holds text where the model holds something else: every box is a
// string. So a value can also be read as a form gives it, text standing where a number or a yes or
// no is read as the JSON value it spells, and an empty box where nothing may be as nothing. Text
// that spells no such value is left as it was typed, and the model says what is wrong with it,
// where it is, as it says of any other value.

import { Decimal, JsonNumber } from "@raoh/core";
import { type Declaration, type Form, keyOf, type Scalar, type Shape, type Surface, tagOf } from "./surface.ts";

/** A JSON number, as RFC 8259 writes one: what text in a box must be to be read as a number. */
const NUMBER = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/;

/** The types a program's values are, by the declarations its surface names. */
export class Values {
  readonly #declared: ReadonlyMap<string, Declaration>;

  constructor(surface: Surface) {
    this.#declared = new Map(surface.declarations.map((each) => [keyOf(each), each]));
  }

  /**
   * A value the module wrote, as Raoh's `parse` reads JSON, every object a `Map` and every number a
   * `JsonNumber`: as the value of `shape` a page holds, every object a plain one, an `Int` a
   * `bigint` and a `Decimal` a `Decimal` at the scale it was written to.
   *
   * @throws Error where what was written is not a value of `shape`, which a module never writes
   */
  read(shape: Shape, written: unknown): unknown {
    switch (shape.is) {
      case "scalar":
        if (written instanceof JsonNumber) {
          return this.#number(shape.scalar, written.lexeme);
        }
        return written;
      case "declared":
        return this.#readDeclared(this.#declaration(shape), written);
      case "list":
      case "set":
        return this.#elements(written).map((each) => this.read(shape.of, each));
      case "map":
        return Object.fromEntries([...this.#members(written)].map(([name, each]) => [name, this.read(shape.value, each)]));
      case "option":
        return written === null ? null : this.read(shape.of, written);
      case "union":
        return this.#readAlternative(shape.crossing.cases, shape.crossing.form, written);
    }
  }

  /**
   * A value as a form gives it, as the module reads a value of `shape`: a string where a number is
   * read as the number it spells, a string where a yes or no is read as `true` or `false` where it
   * is one of those, and an empty string where nothing may be as nothing. Everything else is left
   * as it was given, for the model to read and to say what is wrong with.
   */
  typed(shape: Shape, given: unknown): unknown {
    switch (shape.is) {
      case "scalar":
        if (typeof given === "string") {
          if ((shape.scalar === "int" || shape.scalar === "decimal") && NUMBER.test(given)) {
            return new JsonNumber(given);
          }
          if (shape.scalar === "bool" && (given === "true" || given === "false")) {
            return given === "true";
          }
        }
        return given;
      case "declared":
        return this.#typedDeclared(this.#declaration(shape), given);
      case "list":
      case "set":
        return Array.isArray(given) ? given.map((each) => this.typed(shape.of, each)) : given;
      case "map":
        return isRecord(given)
          ? Object.fromEntries(Object.entries(given).map(([name, each]) => [name, this.typed(shape.value, each)]))
          : given;
      case "option":
        return given === "" ? null : this.typed(shape.of, given);
      case "union":
        return this.#typedAlternative(shape.crossing.cases, shape.crossing.form, given);
    }
  }

  #number(scalar: Scalar, lexeme: string): unknown {
    switch (scalar) {
      case "int":
        return BigInt(lexeme);
      case "decimal":
        return Decimal.parse(lexeme);
      default:
        throw new Error(`the module wrote the number ${lexeme} where a ${scalar} is`);
    }
  }

  #declaration(shape: { readonly module: string; readonly name: string }): Declaration {
    const declaration = this.#declared.get(keyOf(shape));
    if (declaration === undefined) {
      throw new Error(`${keyOf(shape)} is a type this program's surface does not declare`);
    }
    return declaration;
  }

  #readDeclared(declaration: Declaration, written: unknown): unknown {
    switch (declaration.is) {
      case "product":
        return this.#readFields(declaration, written, {});
      case "newtype":
        return this.read(declaration.wraps, written);
      case "unit":
        return Object.fromEntries(this.#members(written));
      case "sum":
        return this.#readAlternative(declaration.cases, declaration.form, written);
    }
  }

  /** The fields of a product the module wrote, beside what `into` already holds: the tag of a case. */
  #readFields(declaration: Declaration & { is: "product" }, written: unknown, into: Record<string, unknown>):
    Record<string, unknown> {
    const members = this.#members(written);
    for (const field of declaration.fields) {
      if (members.has(field.name)) {
        into[field.name] = this.read(field.type, members.get(field.name));
      }
    }
    return into;
  }

  #readAlternative(cases: readonly Shape[], form: Form, written: unknown): unknown {
    if (form.is === "enumeration") {
      return written;
    }
    const members = this.#members(written);
    const tag = members.get(form.tag);
    const chosen = cases.find((each) => tagOf(each) === tag);
    if (chosen === undefined) {
      throw new Error(`the module wrote a case tagged ${String(tag)}, which is none of its type's`);
    }
    const into: Record<string, unknown> = { [form.tag]: tag };
    if (chosen.is === "declared") {
      const declaration = this.#declaration(chosen);
      if (declaration.is === "unit") {
        return into;
      }
      if (declaration.is === "product") {
        return this.#readFields(declaration, written, into);
      }
    }
    // A newtype or a primitive is what it is written as, under a member of its own beside the tag.
    into[form.contents] = this.read(chosen, members.get(form.contents));
    return into;
  }

  #typedDeclared(declaration: Declaration, given: unknown): unknown {
    switch (declaration.is) {
      case "product":
        return this.#typedFields(declaration, given);
      case "newtype":
        return this.typed(declaration.wraps, given);
      case "unit":
        return given;
      case "sum":
        return this.#typedAlternative(declaration.cases, declaration.form, given);
    }
  }

  /** A product a form gives, each field the form read of it read as the field's type, and any other
   *  member left as it is, for the model to refuse. */
  #typedFields(declaration: Declaration & { is: "product" }, given: unknown): unknown {
    if (!isRecord(given)) {
      return given;
    }
    const types = new Map(declaration.fields.map((field) => [field.name, field.type]));
    return Object.fromEntries(Object.entries(given).map(([name, each]) => {
      const type = types.get(name);
      return [name, type === undefined ? each : this.typed(type, each)];
    }));
  }

  #typedAlternative(cases: readonly Shape[], form: Form, given: unknown): unknown {
    if (form.is === "enumeration" || !isRecord(given)) {
      return given;
    }
    const chosen = cases.find((each) => tagOf(each) === given[form.tag]);
    if (chosen === undefined) {
      return given;
    }
    if (chosen.is === "declared") {
      const declaration = this.#declaration(chosen);
      if (declaration.is === "product") {
        return this.#typedFields(declaration, given);
      }
      if (declaration.is === "unit") {
        return given;
      }
    }
    return { ...given, [form.contents]: this.typed(chosen, given[form.contents]) };
  }

  #members(written: unknown): Map<string, unknown> {
    if (!(written instanceof Map)) {
      throw new Error("the module wrote something other than an object where its type has one");
    }
    return written as Map<string, unknown>;
  }

  #elements(written: unknown): unknown[] {
    if (!Array.isArray(written)) {
      throw new Error("the module wrote something other than an array where its type has one");
    }
    return written;
  }
}

/** Whether `value` is an object whose data are its own properties, as a page writes one. */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && Object.getPrototypeOf(value) === Object.prototype;
}
