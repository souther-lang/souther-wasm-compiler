// Every lockfile in the repository says of this repository's own packages what their package.json
// says.
//
// A lockfile records, beside what it resolved from the registry, the project it belongs to and any
// package of this repository it links to by path or installs a copy of: its name, its version, its
// commands, the Node it needs, what it depends on and asks of its peers. Those are copies of a package.json, and nothing makes a copy follow when the
// package.json changes: `npm ci` refuses a lockfile whose dependencies disagree, and installs one
// whose commands and engines do without a word. So the two can come to say two things about one
// package, and did — package.json pointing its command at the built glue and asking for Node 22,
// both lockfiles still at the sources and Node 24.
//
// Read from the files and not by running npm, so that an npm of another version writing the same
// lockfile another way is no difference.
//
//     node scripts/lockfiles-agree.mjs

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, join, posix } from "node:path";

/**
 * What a lockfile copies out of a package.json at least. Whatever else an entry holds is compared
 * too, unless it is what npm writes of the install rather than of the package (`LOCKED_ONLY`): so a
 * field npm comes to copy is held to package.json without anyone adding it here, and a field npm
 * comes to write of its own is named as a disagreement rather than passed over.
 */
const COPIED = [
  "name", "version", "license", "bin", "engines",
  "dependencies", "devDependencies", "peerDependencies", "optionalDependencies",
];

/** What npm writes into an entry about the install, which no package.json says. */
const LOCKED_ONLY = new Set([
  "resolved", "integrity", "link", "dev", "optional", "devOptional", "peer", "inBundle",
  "extraneous", "hasInstallScript", "hasShrinkwrap",
]);

const root = execFileSync("git", ["rev-parse", "--show-toplevel"], { encoding: "utf-8" }).trim();
const lockfiles = execFileSync("git", ["ls-files", "*package-lock.json"], { cwd: root, encoding: "utf-8" })
  .split("\n").filter((each) => each !== "");

let disagreeing = 0;
for (const lockfile of lockfiles) {
  const at = dirname(lockfile);
  const locked = JSON.parse(readFileSync(join(root, lockfile), "utf-8")).packages ?? {};
  // The project is the entry with no path, and a package linked by path is an entry whose path
  // leaves node_modules: both are directories of this repository with a package.json of their own.
  // A package of this repository installed as a copy (install-links) is an entry under
  // node_modules resolved from a path, the directory it was copied from; npm writes what it
  // depends on and asks of its peers, and not what it needs to be developed, nor its name, which
  // its path under node_modules is.
  for (const [path, entry] of Object.entries(locked)) {
    const copiedFrom = typeof entry.resolved === "string" && entry.resolved.startsWith("file:")
      ? entry.resolved.slice("file:".length) : undefined;
    const linked = path === "" || !path.split("/").includes("node_modules");
    if (!linked && copiedFrom === undefined) {
      continue;
    }
    const directory = linked ? path : copiedFrom;
    const manifest = JSON.parse(readFileSync(join(root, at, directory, "package.json"), "utf-8"));
    const copiedFields = linked ? COPIED : COPIED.filter((each) => each !== "devDependencies" && each !== "name");
    const fields = new Set([...copiedFields, ...Object.keys(entry).filter((each) => !LOCKED_ONLY.has(each))]);
    for (const field of fields) {
      const said = normalised(field, manifest[field], manifest.name);
      const copied = normalised(field, entry[field], manifest.name);
      if (canonical(said) !== canonical(copied)) {
        console.error(`${lockfile} says ${posix.join(at, path) || "."}'s ${field} is `
          + `${JSON.stringify(copied)}, and its package.json says ${JSON.stringify(said)}`);
        disagreeing += 1;
      }
    }
  }
}

if (disagreeing > 0) {
  console.error("Write the lockfile again where its package.json changed: npm install --package-lock-only. "
    + "npm keeps what it wrote of a package installed as a copy, so take that package's entry out of "
    + "the lockfile first.");
  process.exit(1);
}
console.log(`${lockfiles.length} lockfiles say what their packages say`);

/** A value written with its keys in order: npm sorts what it copies, and order is no difference. */
function canonical(value) {
  return JSON.stringify(value, (_key, held) => held !== null && typeof held === "object"
    && !Array.isArray(held)
    ? Object.fromEntries(Object.entries(held).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)))
    : held);
}

/**
 * A field as npm writes it into a lockfile: a command written as one path is the package's command,
 * named for the package without its scope, and a command's path has no `./` before it.
 */
function normalised(field, value, name) {
  if (field !== "bin" || value === undefined) {
    return value;
  }
  const commands = typeof value === "string" ? { [name.replace(/^@[^/]+\//, "")]: value } : value;
  return Object.fromEntries(Object.entries(commands)
    .map(([name, path]) => [name, path.replace(/^\.\//, "")]));
}
