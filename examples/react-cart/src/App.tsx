import { useEffect, useMemo, useState } from "react";
import { messageOf, type Decimal, type Issue, type Reading } from "@souther/wasm";
import { load, type Bound, type Membership, type Priced } from "./cart.ts";
import "./app.css";

/** A line as it is being typed: the text in each box, which is what the page hands over. */
interface Typed {
  readonly sku: string;
  readonly quantity: string;
  readonly unitPrice: string;
}

/**
 * What pricing a basket comes to, as the binding says it, or why the call ended. What was typed is
 * read as a basket first, so what the model would not read of it is said of the basket, by where
 * in the basket it is.
 */
type Answer =
  | Reading<{ readonly type: "EmptyCart" } | ({ readonly type: "Priced" } & Priced)>
  | { readonly ended: string };

const START: readonly Typed[] = [{ sku: "ABC-1234", quantity: "2", unitPrice: "1500.00" }];

const FIELDS = ["sku", "quantity", "unitPrice"] as const;

/** The language a complaint is written in: the reader's, where Raoh's catalog has it. */
const LOCALE = navigator.language;

/** An amount of yen, grouped, every digit the model worked out shown. */
const YEN = new Intl.NumberFormat("en", { maximumFractionDigits: 20 });

export default function App() {
  const [bound, setBound] = useState<Bound | Error | null>(null);
  const [lines, setLines] = useState<readonly Typed[]>(START);
  const [member, setMember] = useState<Membership>("Standard");

  useEffect(() => {
    load("/cart.wasm").then(setBound, (refused: unknown) =>
      setBound(refused instanceof Error ? refused : new Error(String(refused))));
  }, []);

  const answer = useMemo((): Answer | null => {
    if (bound === null || bound instanceof Error) {
      return null;
    }
    // What was typed goes over as it was typed, read as a form gives a basket. Nothing here decides
    // whether a code is well formed or a quantity is a quantity: the model decides that, and says
    // where. What it reads is a basket, typed as one, and that is what is priced.
    try {
      const cart = bound.form.cart.Cart.decode({ lines, member });
      return cart.issues !== undefined ? cart : bound.modules.cart.price(cart.value);
    } catch (ended) {
      return { ended: ended instanceof Error ? ended.message : String(ended) };
    }
  }, [bound, lines, member]);

  if (bound === null) {
    return <main className="page">Loading…</main>;
  }
  if (bound instanceof Error) {
    return (
      <main className="page">
        <p className="ended">The model would not load: {bound.message}</p>
        <p className="note">
          Compile it with <code>npm run model</code>.
        </p>
      </main>
    );
  }

  return (
    <main className="page">
      <header>
        <h1>What this basket costs</h1>
        <p className="note">
          The rules are in <code>model/src/cart.sou</code> and nowhere else. This page hands over
          what was typed and shows what came back.
        </p>
      </header>

      <section>
        <h2>Basket</h2>
        <table>
          <thead>
            <tr>
              <th>Product code</th>
              <th>Quantity</th>
              <th>Unit price</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {lines.map((line, at) => (
              <tr key={at}>
                {FIELDS.map((field) => {
                  const about = issuesAt(answer, ["lines", at, field]);
                  return (
                    <td key={field}>
                      <input
                        value={line[field]}
                        aria-label={`line ${at + 1}, ${field}`}
                        aria-invalid={about.length > 0}
                        onChange={(e) => setLines(changed(lines, at, field, e.target.value))}
                      />
                      <Complaints about={about} />
                    </td>
                  );
                })}
                <td>
                  <button onClick={() => setLines(lines.filter((_, i) => i !== at))}>Remove</button>
                </td>
              </tr>
            ))}
            {lines.map((_, at) => {
              const about = issuesAt(answer, ["lines", at]);
              return about.length === 0 ? null : (
                <tr key={`about-${at}`}>
                  <td colSpan={4}>
                    <Complaints about={about} prefix={`Line ${at + 1}: `} />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <button onClick={() => setLines([...lines, { sku: "", quantity: "1", unitPrice: "0.00" }])}>
          Add a line
        </button>
      </section>

      <section>
        <h2>Membership</h2>
        {(["Standard", "Premium"] as const).map((held) => (
          <label key={held}>
            <input
              type="radio"
              name="member"
              checked={member === held}
              onChange={() => setMember(held)}
            />
            {held === "Premium" ? "Premium (a tenth off)" : "Standard"}
          </label>
        ))}
      </section>

      <Answered answer={answer} />

      <details>
        <summary>What the model answered</summary>
        <pre>{JSON.stringify(answer, (_key, held) => (typeof held === "bigint" ? String(held) : held), 2)}</pre>
      </details>
    </main>
  );
}

function Answered({ answer }: { readonly answer: Answer | null }) {
  if (answer === null) {
    return null;
  }
  if ("ended" in answer) {
    return (
      <section className="ended">
        <h2>The call ended</h2>
        <p>{answer.ended}</p>
      </section>
    );
  }
  if (answer.issues !== undefined) {
    return (
      <section className="issues">
        <h2>This basket cannot be read</h2>
        <p className="note">
          Which part of it cannot is the model's answer, and it is on the lines above.
        </p>
      </section>
    );
  }
  const held = answer.value;
  if (held.type === "EmptyCart") {
    return (
      <section className="empty">
        <h2>The basket is empty</h2>
        <p className="note">
          Nothing is wrong with it. The answer is simply not a price, and the model says so with a
          type of its own rather than with a price of zero.
        </p>
      </section>
    );
  }
  return (
    <section className="priced">
      <h2>To pay</h2>
      <dl>
        <dt>Subtotal</dt>
        <dd>{yen(held.subtotal)}</dd>
        <dt>Discount</dt>
        <dd>{held.discount.signum === 0 ? "—" : `− ${yen(held.discount)}`}</dd>
        <dt>Shipping</dt>
        <dd>{held.shipping.signum === 0 ? "free" : yen(held.shipping)}</dd>
        <dt className="total">Total</dt>
        <dd className="total">{yen(held.total)}</dd>
      </dl>
    </section>
  );
}

function Complaints({ about, prefix = "" }: { readonly about: readonly Issue[]; readonly prefix?: string }) {
  return about.map((issue, at) => (
    <p key={at} className="complaint">{prefix}{messageOf(issue, LOCALE)}</p>
  ));
}

/**
 * What the model said about one place, if it said anything.
 *
 * An issue names what it is about as a path into what was read — `["lines", 1, "sku"]` is the
 * basket's second line's code — so nothing here works out which field a complaint belongs to. It
 * arrives knowing, and what it says is written from Raoh's catalog rather than here.
 */
function issuesAt(answer: Answer | null, path: readonly (string | number)[]): readonly Issue[] {
  return answer !== null && !("ended" in answer) ? answer.issues?.at(path) ?? [] : [];
}

function changed(
  lines: readonly Typed[],
  at: number,
  field: keyof Typed,
  held: string,
): readonly Typed[] {
  return lines.map((line, i) => (i === at ? { ...line, [field]: held } : line));
}

/** An amount as a person reads it: grouped, from its digits rather than from a nearest number. */
function yen(held: Decimal): string {
  return `¥${YEN.format(held.toString() as Intl.StringNumericLiteral)}`;
}
