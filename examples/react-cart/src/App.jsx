import { useEffect, useMemo, useState } from "react";
import { amount, load } from "./souther.js";
import "./app.css";

const START = [{ sku: "ABC-1234", quantity: "2", unitPrice: "1500.00" }];

export default function App() {
  const [program, setProgram] = useState(null);
  const [lines, setLines] = useState(START);
  const [member, setMember] = useState("Standard");

  useEffect(() => {
    load("/cart.wasm").then(setProgram, setProgram);
  }, []);

  const answer = useMemo(() => {
    if (!program || program instanceof Error) {
      return null;
    }
    // What was typed goes over as it was typed. Nothing here decides whether a code is well formed
    // or a quantity is a quantity: the model decides that, and says where.
    const cart = {
      lines: lines.map((line) => ({
        sku: line.sku,
        quantity: asNumber(line.quantity),
        unitPrice: asAmount(line.unitPrice),
      })),
      member,
    };
    try {
      return program.call("cart.price", [cart]);
    } catch (ended) {
      return { ended: ended.message };
    }
  }, [program, lines, member]);

  if (program === null) {
    return <main className="page">Loading…</main>;
  }
  if (program instanceof Error) {
    return (
      <main className="page">
        <p className="ended">The model would not load: {program.message}</p>
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
                {FIELDS.map((field) => (
                  <td key={field}>
                    <input
                      value={line[field]}
                      aria-label={`line ${at + 1}, ${field}`}
                      aria-invalid={complaintAt(answer, at, field) !== undefined}
                      onChange={(e) => setLines(changed(lines, at, field, e.target.value))}
                    />
                    <Complaint about={complaintAt(answer, at, field)} />
                  </td>
                ))}
                <td>
                  <button onClick={() => setLines(lines.filter((_, i) => i !== at))}>Remove</button>
                </td>
              </tr>
            ))}
            {lines.flatMap((_, at) =>
              aboutTheLine(answer, at).map((issue) => (
                <tr key={`about-${at}`}>
                  <td colSpan={4}>
                    <p className="complaint">
                      Line {at + 1} does not hold what a {issue.meta.expected} must
                    </p>
                  </td>
                </tr>
              )),
            )}
          </tbody>
        </table>
        <button onClick={() => setLines([...lines, { sku: "", quantity: "1", unitPrice: "0.00" }])}>
          Add a line
        </button>
      </section>

      <section>
        <h2>Membership</h2>
        {["Standard", "Premium"].map((held) => (
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

      <Answer answer={answer} />

      <details>
        <summary>What the model answered</summary>
        <pre>{JSON.stringify(answer, null, 2)}</pre>
      </details>
    </main>
  );
}

const FIELDS = ["sku", "quantity", "unitPrice"];

function Answer({ answer }) {
  if (answer?.ended) {
    return (
      <section className="ended">
        <h2>The call ended</h2>
        <p>{answer.ended}</p>
      </section>
    );
  }
  if (answer?.issues) {
    return (
      <section className="issues">
        <h2>This basket cannot be read</h2>
        <p className="note">
          Which part of it cannot is the model's answer, and it is on the lines above.
        </p>
      </section>
    );
  }
  const held = answer?.value;
  if (held?.type === "EmptyCart") {
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
  if (held?.type !== "Priced") {
    return null;
  }
  return (
    <section className="priced">
      <h2>To pay</h2>
      <dl>
        <dt>Subtotal</dt>
        <dd>{money(held.subtotal)}</dd>
        <dt>Discount</dt>
        <dd>{isNothing(held.discount) ? "—" : `− ${money(held.discount)}`}</dd>
        <dt>Shipping</dt>
        <dd>{isNothing(held.shipping) ? "free" : money(held.shipping)}</dd>
        <dt className="total">Total</dt>
        <dd className="total">{money(held.total)}</dd>
      </dl>
    </section>
  );
}

/** Whether an amount is nothing, however wide the amount it is nothing beside was. */
function isNothing(held) {
  return Number(held) === 0;
}

function Complaint({ about }) {
  return about === undefined ? null : <p className="complaint">{said(about)}</p>;
}

/**
 * What the model said about one field of one line, if it said anything.
 *
 * An issue names what it is about as a path into the arguments — `/0/lines/1/sku` is the first
 * argument's second line's code — so nothing here works out which field a complaint belongs to. It
 * arrives knowing.
 */
function complaintAt(answer, at, field) {
  return answer?.issues?.find((issue) => issue.path === `/0/lines/${at}/${field}`);
}

/**
 * What the model said about a whole line.
 *
 * A rule about a line is not a rule about one of its fields, and the boundary says so by naming the
 * line. Which of the line's rules it was comes back as a number rather than as the name the model
 * gave it, so this does not say which: putting the name here would be that rule written a second
 * time, in a language nobody reads when they change the first.
 */
function aboutTheLine(answer, at) {
  return (answer?.issues ?? []).filter((issue) => issue.path === `/0/lines/${at}`);
}

function said(issue) {
  if (issue.code === "invariant_violation") {
    return `does not hold what a ${issue.meta.expected} must`;
  }
  if (issue.code === "type_mismatch") {
    return `wanted ${issue.meta.expected}, and this is ${issue.meta.actual}`;
  }
  return issue.code;
}

function changed(lines, at, field, held) {
  return lines.map((line, i) => (i === at ? { ...line, [field]: held } : line));
}

/**
 * What was typed, as a number where it reads as one, and as it was typed where it does not.
 *
 * The model is what says a quantity is a quantity. Something here deciding first would be the same
 * rule written twice, in two languages, one of which nobody is reading when they change the other.
 */
function asNumber(written) {
  const held = Number(written);
  return written.trim() !== "" && Number.isFinite(held) ? held : written;
}

/**
 * What was typed, as an amount where it reads as one.
 *
 * The digits, and not the nearest number to them: a price is held to what it was written with, and
 * a JavaScript number is not, so putting one through a number here would round it before the model
 * ever saw it.
 */
function asAmount(written) {
  return /^-?\d+(\.\d+)?$/.test(written.trim()) ? amount(written.trim()) : written;
}

/**
 * An amount, for reading.
 *
 * What comes back is a number where a number holds it and the digits where one does not, so this
 * takes both. Where it is the digits, they are shown as they are: a number is what would lose
 * them, and grouping them is the same loss with a comma in it.
 */
function money(held) {
  return typeof held === "string"
    ? `¥${held}`
    : `¥${held.toLocaleString("en", { minimumFractionDigits: 0 })}`;
}
