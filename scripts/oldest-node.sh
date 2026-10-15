#!/usr/bin/env bash
# The oldest Node a package's package.json names, to the patch, for CI to run on exactly that one.
#
#     scripts/oldest-node.sh packages/wasm runtime   # engines.node: what the published JavaScript runs on
#     scripts/oldest-node.sh packages/wasm tooling   # devEngines.runtime: what its tests, run as TypeScript, need
#
# package.json and CI are one statement, so CI reads the version here rather than write it again:
# a version written in the workflow as well, or one naming only a major, which setup-node takes as
# the newest of it, would let a run pass on a Node the package does not name and leave the one it
# does untried.
set -euo pipefail
manifest="${1:?usage: $0 <package directory> runtime|tooling}/package.json"
case "${2:-}" in
  runtime) field=engines.node; range=$(jq -r '.engines.node' "$manifest") ;;
  tooling) field=devEngines.runtime; range=$(jq -r 'if .devEngines.runtime.name == "node" then .devEngines.runtime.version else "" end' "$manifest") ;;
  *) echo "usage: $0 <package directory> runtime|tooling" >&2; exit 2 ;;
esac
if [[ ! "$range" =~ ^\>=([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
  echo "$manifest's $field is '$range'; write the oldest Node as >=X.Y.Z" >&2
  exit 1
fi
echo "${BASH_REMATCH[1]}"
