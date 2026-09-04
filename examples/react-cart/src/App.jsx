import { useEffect, useMemo, useState } from "react";
import { load } from "./souther.js";
import "./app.css";

const START = [{ sku: "ABC-1234", quantity: 2, unitPrice: "1500.00" }];

export default function App() {
  const [program, setProgram] = useState(null);
  const [lines, setLines] = useState(START);
  const [member, setMember] = useState("Standard");

  useEffect(() => {
    load("/cart.wasm").then(setProgram, (e) => setProgram(e));
  }, []);

  const answer = useMemo(() => {
    if (!program || program instanceof Error) {
      return null;
    }
    // Everything typed into the form goes over as it was typed. Nothing here decides whether a
    // code is well formed or a quantity is a quantity — the model decides, and says where.
    const cart = {
      lines: lines.map((line) => ({
        sku: line.sku,
        quantity: asNumber(line.quantity),
        unitPrice: asNumber(line.unitPrice),
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
    return <main className="page">読み込み中…</main>;
  }
  if (program instanceof Error) {
    return (
      <main className="page">
        <p className="ended">モデルを読み込めませんでした: {program.message}</p>
        <p className="note">
          <code>npm run model</code> でモデルをコンパイルしてください。
        </p>
      </main>
    );
  }

  return (
    <main className="page">
      <header>
        <h1>かごの値付け</h1>
        <p className="note">
          値段の規則は <code>model/src/cart.sou</code> にしか書かれていません。この画面は入力を
          そのまま渡し、返ってきたものを表示するだけです。
        </p>
      </header>

      <section>
        <h2>かご</h2>
        <table>
          <thead>
            <tr>
              <th>商品コード</th>
              <th>数量</th>
              <th>単価</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {lines.map((line, at) => (
              <tr key={at}>
                {["sku", "quantity", "unitPrice"].map((field) => (
                  <td key={field}>
                    <input
                      value={line[field]}
                      aria-label={`${at + 1}行目の${field}`}
                      aria-invalid={complaintAt(answer, at, field) !== undefined}
                      onChange={(e) => setLines(changed(lines, at, field, e.target.value))}
                    />
                    <Complaint about={complaintAt(answer, at, field)} />
                  </td>
                ))}
                <td>
                  <button onClick={() => setLines(lines.filter((_, i) => i !== at))}>削除</button>
                </td>
              </tr>
            ))}
            {lines.map((line, at) =>
              aboutTheLine(answer, at).map((issue) => (
                <tr key={`${at}-line`} className="about-the-line">
                  <td colSpan={4}>
                    <p className="complaint">
                      {at + 1}行目が {issue.meta.expected} の規則を満たしていません
                    </p>
                  </td>
                </tr>
              )),
            )}
          </tbody>
        </table>
        <button onClick={() => setLines([...lines, { sku: "", quantity: "1", unitPrice: "0.00" }])}>
          行を足す
        </button>
      </section>

      <section>
        <h2>会員</h2>
        {["Standard", "Premium"].map((held) => (
          <label key={held}>
            <input
              type="radio"
              name="member"
              checked={member === held}
              onChange={() => setMember(held)}
            />
            {held === "Premium" ? "プレミアム（一割引き）" : "一般"}
          </label>
        ))}
      </section>

      <Answer answer={answer} />

      <details>
        <summary>モデルが返したもの</summary>
        <pre>{JSON.stringify(answer, null, 2)}</pre>
      </details>
    </main>
  );
}

function Answer({ answer }) {
  if (answer?.ended) {
    return (
      <section className="ended">
        <h2>呼び出しが終わりました</h2>
        <p>{answer.ended}</p>
      </section>
    );
  }
  if (answer?.issues) {
    return (
      <section className="issues">
        <h2>このかごは読めません</h2>
        <p className="note">
          どこが読めないかはモデルが言っています。下の行にそのまま出ています。
        </p>
      </section>
    );
  }
  const held = answer?.value;
  if (held?.type === "EmptyCart") {
    return (
      <section className="empty">
        <h2>かごが空です</h2>
        <p className="note">
          間違いではありません。答えが値段でないだけで、モデルはそれを別の型で言っています。
        </p>
      </section>
    );
  }
  if (held?.type !== "Priced") {
    return null;
  }
  return (
    <section className="priced">
      <h2>お会計</h2>
      <dl>
        <dt>小計</dt>
        <dd>{yen(held.subtotal)}</dd>
        <dt>割引</dt>
        <dd>{held.discount === 0 ? "—" : `− ${yen(held.discount)}`}</dd>
        <dt>送料</dt>
        <dd>{held.shipping === 0 ? "無料" : yen(held.shipping)}</dd>
        <dt className="total">合計</dt>
        <dd className="total">{yen(held.total)}</dd>
      </dl>
    </section>
  );
}

function Complaint({ about }) {
  if (about === undefined) {
    return null;
  }
  return <p className="complaint">{said(about)}</p>;
}

/**
 * What the model said about one field of one line, if it said anything.
 *
 * An issue names where it is about as a JSON Pointer into the arguments — `/0/lines/1/sku` is the
 * first argument's second line's code — so nothing here has to work out which field a complaint
 * belongs to. It is in the complaint.
 */
function complaintAt(answer, at, field) {
  return answer?.issues?.find((issue) => issue.path === `/0/lines/${at}/${field}`);
}

/**
 * What the model said about a whole line.
 *
 * A rule about a line is not a rule about one of its fields, and the boundary says so by naming
 * the line. Which of the line's rules it was comes back as a number rather than as the name the
 * model gave it, so this does not say — putting the clause's name here would be the model's own
 * rule written a second time, in a language nobody reads when they change the first.
 */
function aboutTheLine(answer, at) {
  return (answer?.issues ?? []).filter((issue) => issue.path === `/0/lines/${at}`);
}

function said(issue) {
  if (issue.code === "invariant_violation") {
    return `${issue.meta.expected} が持つ規則に合いません`;
  }
  if (issue.code === "type_mismatch") {
    return `${issue.meta.expected} を書いてください（${issue.meta.actual} が書かれています）`;
  }
  return issue.code;
}

function changed(lines, at, field, held) {
  return lines.map((line, i) => (i === at ? { ...line, [field]: held } : line));
}

/**
 * What was typed, as a number where it reads as one.
 *
 * Left as it was typed where it does not. The model is what says a quantity is a quantity, and
 * something here deciding first would be the same rule written twice — in two languages, one of
 * which nobody is reading when they change the other.
 */
function asNumber(written) {
  const held = Number(written);
  return written.trim() !== "" && Number.isFinite(held) ? held : written;
}

function yen(amount) {
  return `¥${Number(amount).toLocaleString("ja-JP", { minimumFractionDigits: 0 })}`;
}
