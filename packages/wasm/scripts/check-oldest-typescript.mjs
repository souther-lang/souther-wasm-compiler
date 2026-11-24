// Compiles a binding under the oldest TypeScript the package says it is for.
//
//     node scripts/check-oldest-typescript.mjs <package.tgz> <module.wasm>
//
// What the README promises is that a binding compiles under that TypeScript, with its declarations
// checked: the package's own among them, which is where a feature of a newer TypeScript gets in without
// the binding's names being at fault. So the package is installed as a project installs it, beside the
// oldest TypeScript and not the one this repository builds with, the command writes the binding of a
// module, and the binding is compiled without skipping what the declarations say.
//
// It fetches the TypeScript and Raoh from the registry, so it needs the network.

import { execFileSync } from "node:child_process";
import { readFileSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const [archive, module] = process.argv.slice(2).map((each) => resolve(each));
if (archive === undefined || module === undefined) {
  process.stderr.write("usage: node scripts/check-oldest-typescript.mjs <package.tgz> <module.wasm>\n");
  process.exit(2);
}
const here = join(import.meta.dirname, "..");
// What the README names is what the table is for, and the table says which that is.
const OLDEST = /OLDEST_TYPESCRIPT = "([^"]+)"/.exec(readFileSync(join(here, "src", "ts-identifier-table.ts"), "utf8"))?.[1];
if (OLDEST === undefined) {
  throw new Error("src/ts-identifier-table.ts does not say which TypeScript it is for");
}
const raoh = JSON.parse(readFileSync(join(here, "package.json"), "utf8")).peerDependencies["@raoh/core"];

const project = mkdtempSync(join(tmpdir(), "oldest-typescript-"));
const run = (command, args) => execFileSync(command, args, { cwd: project, encoding: "utf8", stdio: ["ignore", "pipe", "inherit"] });
writeFileSync(join(project, "package.json"), JSON.stringify({ type: "module", private: true }));
run("npm", ["install", "--no-audit", "--no-fund", archive, `@raoh/core@${raoh}`, `typescript@${OLDEST}`]);
const tsc = join(project, "node_modules", ".bin", "tsc");
const version = run(tsc, ["--version"]).trim();
if (version !== `Version ${OLDEST}`) {
  throw new Error(`asked for TypeScript ${OLDEST} and tsc is ${version}`);
}
run(join(project, "node_modules", ".bin", "souther-wasm-bindings"), [module, "-o", "binding.ts"]);
writeFileSync(join(project, "check.ts"), [
  `import * as runtime from "@souther-lang/wasm";`,
  `import { load, type Bound } from "./binding.ts";`,
  `export { runtime, load };`,
  `export type { Bound };`,
  "",
].join("\n"));
try {
  run(tsc, ["--noEmit", "--strict", "--target", "es2022", "--module", "nodenext", "--moduleResolution", "nodenext",
    "--allowImportingTsExtensions", "--lib", "esnext,dom", "check.ts"]);
} catch (refused) {
  process.stderr.write(`${refused.stdout ?? ""}\nthe binding does not compile under TypeScript ${OLDEST}\n`);
  process.exit(1);
}
process.stdout.write(`a binding compiles under TypeScript ${OLDEST}, its declarations checked\n`);
