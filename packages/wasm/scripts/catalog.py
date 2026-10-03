"""Writes src/catalog.ts from raoh-specification's message catalog.

    python3 scripts/catalog.py <raoh-specification checkout>

The templates are Raoh's and are read, not written here, so the TypeScript side says what the
JVM's resolver says for the same issue. What commit they were read at goes into the file, and the
conformance suite holds what they resolve to against what the JVM resolves.
"""

import json
import pathlib
import subprocess
import sys

spec = pathlib.Path(sys.argv[1])
commit = subprocess.run(["git", "-C", str(spec), "rev-parse", "HEAD"], capture_output=True,
                        text=True, check=True).stdout.strip()


def templates(locale):
    held = {}
    for line in (spec / "catalog" / "messages" / f"{locale}.properties").read_text("utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        held[key.strip()] = value
    return held


out = [
    "// Generated from raoh-specification's catalog/messages by packages/wasm/scripts/catalog.py,",
    f"// at {commit}. Do not edit: regenerate from the catalog instead.",
    "//",
    "// The catalog is Raoh's, Apache License 2.0 (https://github.com/raoh-project/raoh-specification).",
    "",
    "/** Each locale's templates, by the key a message is looked up under. */",
    "export const CATALOG: Readonly<Record<string, Readonly<Record<string, string>>>> = {",
]
for locale in ("en", "ja"):
    out.append(f"  {json.dumps(locale)}: {{")
    for key, value in templates(locale).items():
        out.append(f"    {json.dumps(key)}: {json.dumps(value, ensure_ascii=False)},")
    out.append("  },")
out.append("};")
pathlib.Path(__file__).parent.parent.joinpath("src", "catalog.ts").write_text("\n".join(out) + "\n", "utf-8")
