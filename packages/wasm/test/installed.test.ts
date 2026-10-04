// The package as a project installs it: packed as it is published, installed into a project of its
// own, and used from there the way the README says — the command writing a binding, a page written
// against the binding type-checked against what the package declares and compiled, and the
// JavaScript that comes of it run.
//
// Everything else here reads the package's sources. Node runs no TypeScript under node_modules, and
// a file the package does not carry is not there once it is installed, so what works in this
// directory says nothing about what works where the package is installed. This is what does.
//
// It is also what holds the package to the oldest Node engines names, which is older than the Node
// the tools need. What a project builds with its own tools — the archives it installs from, the
// module, the page compiled to JavaScript — is built once on the tools' Node and kept where
// SOUTHER_WASM_BUILD_INTO names. Run on the oldest Node with SOUTHER_WASM_BUILT naming that
// directory, the test builds nothing: it installs the archives with the npm of that Node, runs the
// command, which writes the binding the page was compiled from, and runs the JavaScript. CI takes
// the repository's node_modules away before it does, so no tool of the repository's can be run there.

import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { it } from "node:test";
import { promisify } from "node:util";
import { compiled, PACKAGE, ROOT } from "./compiled.ts";

const TSC = join(PACKAGE, "node_modules", ".bin", "tsc");
const ran = promisify(execFile);

/** Runs a command, and fails with everything it wrote where it fails, npm's own account included. */
async function run(command: string, args: readonly string[], options: { cwd?: string } = {}):
  Promise<{ stdout: string; stderr: string }> {
  try {
    return await ran(command, args, options);
  } catch (failed) {
    const { stdout, stderr } = failed as { stdout?: string; stderr?: string };
    throw new Error(`${command} ${args.join(" ")} failed:\n${stdout ?? ""}${stderr ?? ""}`, { cause: failed });
  }
}

const MODEL = `module shop

data Line = { sku: String, quantity: Int, note: String? }

behavior counted : (lines: List<Line>) -> Int

let counted (lines) = List.length(lines)
`;

// The page reads a form of its own with Raoh, the model's type a part of it: the project's
// @raoh/core and the one the package was built against have to be one, or the part's issues could
// not be said at the part's path.
const PAGE = `import { field, int, list, object } from "@raoh/core";
import { amount, messageOf } from "@souther/wasm";
import { load } from "./binding.ts";

export async function counted(bytes: Uint8Array): Promise<unknown[]> {
  const shop = await load(bytes);
  const order = object(field("lines", list(shop.decode.shop.Line)), field("count", int()));
  const read = order.decode({ lines: [{ sku: "A", quantity: "two" }], count: 1 });
  return [
    shop.modules.shop.counted([{ sku: "A", quantity: amount("1"), note: null }]).value,
    read.issues?.list.map((issue) => [issue.path.toString(), messageOf(issue, "en")]),
  ];
}
`;

/** The files a project builds with its own tools, by the names they are kept under. */
const BUILT = {
  souther: "souther-wasm.tgz",
  raoh: "raoh-core.tgz",
  model: "model.wasm",
  binding: "binding.ts",
  compiled: ["binding.js", "page.js", "entries.js"],
} as const;

/** The entries of the package, as a project imports them. */
const ENTRIES = Object.keys(JSON.parse(readFileSync(join(PACKAGE, "package.json"), "utf-8")).exports)
  .map((entry) => entry.replace(/^\./, "@souther/wasm"));

it("is used from where it is installed, as the README says", async () => {
  const handed = process.env.SOUTHER_WASM_BUILT;
  const built = handed ?? process.env.SOUTHER_WASM_BUILD_INTO ?? mkdtempSync(join(tmpdir(), "souther-built-"));
  if (handed === undefined) {
    mkdirSync(built, { recursive: true });
    await archive(built);
  }

  const project = mkdtempSync(join(tmpdir(), "souther-installed-"));
  writeFileSync(join(project, "package.json"), JSON.stringify({
    type: "module",
    private: true,
    // The package asks the project for @raoh/core, as a peer, so the project depends on it itself.
    dependencies: { "@raoh/core": `file:${join(built, BUILT.raoh)}` },
    // The package names the peer it was built against by its git commit; the archive is that.
    overrides: { "@raoh/core": "$@raoh/core" },
  }));
  await run("npm", ["install", "--offline", "--no-audit", "--no-fund", join(built, BUILT.souther)],
    { cwd: project });
  const installed = join(project, "node_modules", "@souther", "wasm");
  // One copy, the project's: the package brought none of its own.
  assert.throws(() => readFileSync(join(installed, "node_modules", "@raoh", "core", "package.json")),
    /ENOENT/);
  // The package is packed from its own directory, and the repository's license is outside it, so
  // the package carries a copy, which is the repository's.
  assert.equal(readFileSync(join(installed, "LICENSE"), "utf-8"), readFileSync(join(ROOT, "LICENSE"), "utf-8"));

  cpSync(join(built, BUILT.model), join(project, BUILT.model));
  await run(join(project, "node_modules", ".bin", "souther-wasm-bindings"),
    [BUILT.model, "-o", BUILT.binding], { cwd: project });
  const binding = readFileSync(join(project, BUILT.binding), "utf-8");
  assert.match(binding, /import \* as souther from "@souther\/wasm";/);
  if (handed === undefined) {
    await compile(project);
    for (const each of [BUILT.binding, ...BUILT.compiled]) {
      cpSync(join(project, each), join(built, each));
    }
  } else {
    // The command writes on this Node the binding the page was compiled from on the tools' Node.
    assert.equal(binding, readFileSync(join(built, BUILT.binding), "utf-8"));
    for (const each of BUILT.compiled) {
      cpSync(join(built, each), join(project, each));
    }
  }

  const imported = await import(join(project, "entries.js"));
  for (const [at, entry] of ENTRIES.entries()) {
    assert.ok(Object.keys(imported[`entry${at}`]).length > 0, `${entry} exports nothing`);
  }
  const page = await import(join(project, "page.js"));
  assert.deepEqual(await page.counted(new Uint8Array(readFileSync(join(project, BUILT.model)))),
    [1, [["/lines/0/quantity", "expected long"]]]);
});

/**
 * The archives a project installs from, and the module, built with the tools into `built`.
 *
 * Packing builds the package, as publishing does: what is installed is what would be published.
 * @raoh/core is archived from what this directory installed, so that installing reaches nothing
 * outside this machine: it is depended on from git, which an offline install cannot fetch. Its files
 * are archived as they were installed, built, as the registry would hand them over: npm runs a
 * package's prepare when it packs it or installs it from a directory, and an installed package
 * carries no sources to build from.
 */
async function archive(built: string): Promise<void> {
  const packed = (await run("npm", ["pack", "--silent", "--pack-destination", built], { cwd: PACKAGE }))
    .stdout.trim();
  renameSync(join(built, packed), join(built, BUILT.souther));
  const raoh = mkdtempSync(join(tmpdir(), "raoh-core-"));
  cpSync(join(PACKAGE, "node_modules", "@raoh", "core"), join(raoh, "package"), { recursive: true });
  await run("tar", ["-czf", join(built, BUILT.raoh), "package"], { cwd: raoh });
  writeFileSync(join(built, BUILT.model), (await compiled(MODEL)).bytes);
}

/** The page, and an import of every entry the package has, compiled as a project compiles them. */
async function compile(project: string): Promise<void> {
  writeFileSync(join(project, "page.ts"), PAGE);
  // Every entry the package says it has is one a project can import from where it is installed.
  writeFileSync(join(project, "entries.ts"), ENTRIES
    .map((entry, at) => `export * as entry${at} from ${JSON.stringify(entry)};`).join("\n") + "\n");
  try {
    await run(TSC, ["--strict", "--target", "es2024", "--module", "nodenext",
      "--moduleResolution", "nodenext", "--rewriteRelativeImportExtensions", "--lib", "esnext,dom",
      "--skipLibCheck", "page.ts", "entries.ts"], { cwd: project });
  } catch (refused) {
    assert.fail(`the page does not compile against what was installed:\n${(refused as Error).message}`);
  }
}
