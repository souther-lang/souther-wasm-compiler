// What the boundary would not read, as Raoh's issues, and the sentence a person reads about it.
//
// An issue is Raoh's (spec §decoder-error): where it is, its code, the message key that says which
// of the code's constraints it was, and the metadata that constraint carries. The module writes
// each as JSON, and here it becomes a raoh-ts `Issue`, so a page handles it as it handles an issue
// of any decoder of its own, and its path can be extended where a module's type is read as part of
// a larger value. The sentence is written by Raoh's catalogue, against the message key, in the
// reader's language; Souther adds only the sentence for the issues of its own, which no catalogue
// of Raoh's has.

import { Decimal, Issue, Issues, JsonNumber, type MessageResolver, Messages, Path, type Result }
  from "@raoh/core";

/** What reading a value came to: the value, or the issues that kept it from being one. */
export type Reading<T> = Result<T>;

/**
 * The issues a module wrote, from its answer, `{"issues": [...]}`, as Raoh's `parse` reads it.
 *
 * Read as Raoh reads JSON, nothing in it is rounded: a metadata number written as an integer is a
 * `bigint`, one written with a fraction a `Decimal` at the scale it is written with, as the bound it
 * states was. A `message` an issue carries is what the decoder said in its own words, where it says
 * more than a template would — the form a temporal is written in — and stays the issue's sentence
 * in every language.
 */
export function issuesIn(answer: unknown): Issues {
  const issues = answer instanceof Map ? answer.get("issues") : undefined;
  if (!Array.isArray(issues) || issues.length === 0) {
    throw new Error("the module's answer holds no issues where it says it does");
  }
  return new Issues(issues.map(issueOf));
}

function issueOf(written: unknown): Issue {
  if (!(written instanceof Map)) {
    throw new Error("an issue the module wrote is not an object");
  }
  const text = (name: string): string => {
    const value = written.get(name);
    if (typeof value !== "string") {
      throw new Error(`an issue the module wrote has no ${name}`);
    }
    return value;
  };
  const message = written.get("message");
  const meta = written.get("meta");
  return new Issue(text("code"), {
    messageKey: text("messageKey"),
    path: Path.parse(text("path")),
    meta: meta instanceof Map ? metaOf(meta) as Record<string, unknown> : {},
    message: typeof message === "string" ? message : undefined,
  });
}

/** A metadata value as the module wrote it, every number read as the amount it is written as. */
function metaOf(value: unknown): unknown {
  if (value instanceof JsonNumber) {
    return /^-?[0-9]+$/.test(value.lexeme) ? BigInt(value.lexeme) : Decimal.parse(value.lexeme);
  }
  if (value instanceof Map) {
    return Object.fromEntries([...value].map(([name, each]) => [name, metaOf(each)]));
  }
  if (Array.isArray(value)) {
    return value.map(metaOf);
  }
  return value;
}

/**
 * The sentence for an issue of Souther's own, which no Raoh catalogue has a template for: a rule of
 * the model that is not a standard constraint, named by its module, type and clause, as the JVM
 * says it.
 */
function southerSentence(issue: Issue): string {
  if (issue.code === "invariant_violation") {
    const { module, type, clause } = issue.meta as { module?: string; type?: string; clause?: string };
    return clause === undefined
      ? `invariant violated on ${module}.${type}`
      : `invariant violated on ${module}.${type}: ${clause}`;
  }
  return `validation failed: ${issue.code}`;
}

const ENGLISH = Messages.english.withFallback(southerSentence);
const JAPANESE = Messages.japanese.withFallback(southerSentence);

/**
 * The catalogue a person reading `locale` is written to: Raoh's Japanese one for Japanese, its
 * English one for any other language, each saying of Souther's own issues what the JVM says.
 *
 * @param locale a language tag, `ja` or `ja-JP`
 */
export function messagesFor(locale = "en"): MessageResolver {
  return locale.split(/[-_]/)[0]?.toLowerCase() === "ja" ? JAPANESE : ENGLISH;
}

/** The sentence for `issue`, in `locale`, as {@link messagesFor} writes it. */
export function messageOf(issue: Issue, locale = "en"): string {
  return issue.message(messagesFor(locale));
}
