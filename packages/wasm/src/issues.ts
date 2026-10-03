// What the boundary would not read, and the sentence a person reads about it.
//
// An issue is Raoh's (spec §decoder-error): where it is, its code, the message key that says
// which of the code's constraints it was, and the metadata that constraint carries. The sentence
// is not carried with it. It is written here, against the message key, from Raoh's own catalog,
// the way the JVM's resolver writes it — so a form says what the JVM would have said, in the
// reader's language, and the rule it is about is written in the model and nowhere else.

import { CATALOG } from "./catalog.ts";

/** One thing the boundary would not read. */
export interface Issue {
  /** Where, as a JSON Pointer into what was handed over. */
  readonly path: string;
  /** What kind of thing was wrong. */
  readonly code: string;
  /** Which of the code's constraints it was. */
  readonly messageKey: string;
  /** What the constraint says, and what was there. */
  readonly meta: Readonly<Record<string, unknown>>;
  /**
   * What the decoder says in its own words, where it says more than the catalog's template would:
   * the form a temporal is written in. A template does not replace it.
   */
  readonly message?: string;
}

/** What reading a value came to: the value, or what was wrong with it. */
export type Reading<T> =
  | { readonly value: T; readonly issues?: undefined }
  | { readonly issues: readonly Issue[]; readonly value?: undefined };

const PLACEHOLDER = /\{([A-Za-z_][A-Za-z0-9_.-]*)\}/g;

/**
 * The sentence for `issue`, in `locale`.
 *
 * Raoh's rule, as its resolver applies it: each layer of the catalog from the locale asked for to
 * the base, and in each the template for the message key before the one for the code, the first
 * whose every placeholder the metadata fills. What the decoder said in its own words is said as it
 * is. A template it could fill only partly is passed over,
 * since a half-filled one names a bound that is not there. Where none fits, what is said is what
 * the decoder would have said itself.
 *
 * @param locale a language tag, `ja` or `ja-JP`; the base catalog is English
 */
export function messageOf(issue: Issue, locale = "en"): string {
  if (issue.message !== undefined) {
    return issue.message;
  }
  for (const layer of layers(locale)) {
    for (const key of [issue.messageKey, issue.code]) {
      const template = layer[`raoh.${key}`];
      if (template !== undefined) {
        const filled = filledFully(template, issue.meta);
        if (filled !== undefined) {
          return filled;
        }
      }
    }
  }
  return ownMessage(issue);
}

/** The layers of the catalog asked in turn: the locale's language, then the base. */
function layers(locale: string): Readonly<Record<string, string>>[] {
  const language = locale.split(/[-_]/)[0].toLowerCase();
  const held = [];
  if (language !== "en" && CATALOG[language] !== undefined) {
    held.push(CATALOG[language]);
  }
  held.push(CATALOG.en);
  return held;
}

function filledFully(template: string, meta: Readonly<Record<string, unknown>>): string | undefined {
  for (const [, name] of template.matchAll(PLACEHOLDER)) {
    if (!(name in meta)) {
      return undefined;
    }
  }
  return template.replace(PLACEHOLDER, (_, name: string) => shown(meta[name]));
}

/** A value as a template shows it: what Java's `String.valueOf` writes, which the JVM's does. */
function shown(value: unknown): string {
  if (Array.isArray(value)) {
    return `[${value.map(shown).join(", ")}]`;
  }
  if (value !== null && typeof value === "object") {
    return JSON.stringify(value);
  }
  return String(value);
}

/**
 * What the decoder says of an issue no template fits, which for a rule of the model's own is the
 * rule it was: Raoh's catalog has nothing for one, and the JVM says this.
 */
function ownMessage(issue: Issue): string {
  if (issue.code === "invariant_violation") {
    const { module, type, clause } = issue.meta as { module?: string; type?: string; clause?: string };
    return clause === undefined
      ? `invariant violated on ${module}.${type}`
      : `invariant violated on ${module}.${type}: ${clause}`;
  }
  return issue.code;
}
