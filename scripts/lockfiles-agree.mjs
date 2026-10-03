// Every lockfile in the repository says of this repository's own packages what their package.json
// says.
//
// A lockfile records, beside what it resolved from the registry, the project it belongs to and any
// package it links to by path: its name, its version, its commands, the Node it needs, what it
// depends on. Those are copies of a package.json, and nothing makes a copy follow when the
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

/** What a lockfile copies out of a package.json, and so what has to say the same in both. */
const COPIED = [
  "name", "version", "license", "bin", "engines",
  "dependencies", "devDependencies", "peerDependencies", "optionalDependencies",
];

const root = execFileSync("git", ["rev-parse", "--show-toplevel"], { encoding: "utf-8" }).trim();
const lockfiles = execFileSync("git", ["ls-files", "*package-lock.json"], { cwd: root, encoding: "utf-8" })
  .split("\n").filter((each) => each !== "");

let disagreeing = 0;
for (const lockfile of lockfiles) {
  const at = dirname(lockfile);
  const locked = JSON.parse(readFileSync(join(root, lockfile), "utf-8")).packages ?? {};
  // The project is the entry with no path, and a package linked by path is an entry whose path
  // leaves node_modules: both are directories of this repository with a package.json of their own.
  for (const [path, entry] of Object.entries(locked)) {
    if (path !== "" && path.split("/").includes("node_modules")) {
      continue;
    }
    const manifest = JSON.parse(readFileSync(join(root, at, path, "package.json"), "utf-8"));
    for (const field of COPIED) {
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
  console.error("Write the lockfile again where its package.json changed: npm install --package-lock-only");
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
