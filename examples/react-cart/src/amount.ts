// How an amount the model answers is shown.

import type { Decimal } from "@souther/wasm";

/**
 * An amount of yen as a person reads it: the digits of its whole part in groups of three, and the
 * rest, a fraction or a power of ten, as the Decimal writes it. The digits are the Decimal's own and
 * are never made a JavaScript number on the way to the page: a number formatter rounds what a number
 * cannot hold, and a Decimal holds more places and a wider power of ten than any formatter takes.
 */
export function yen(held: Decimal): string {
  const [, sign, whole, rest] = /^(-?)([0-9]+)(.*)$/.exec(held.toString()) as RegExpExecArray;
  return `¥${sign}${(whole as string).replace(/\B(?=([0-9]{3})+$)/g, ",")}${rest}`;
}
