#!/bin/sh
# Decrypt a Kotlin-produced fixture and diff against the expected JSONL.
# Usage: crosscheck.sh [fixture-dir]  (needs fixture.fdl, fixture_priv.der, fixture_expected.jsonl)
# Skips (exit 0) when the fixture is absent.
set -u
here=$(cd "$(dirname "$0")" && pwd)
dir=${1:-$here/fixture}
PY=${PY:-/Users/tom/src/github/homelab/.venv/bin/python}
for f in fixture.fdl fixture_priv.der fixture_expected.jsonl; do
  [ -f "$dir/$f" ] || { echo "crosscheck: $dir/$f missing; skipping"; exit 0; }
done
out=$(mktemp)
trap 'rm -f "$out"' EXIT
cd "$here" || exit 1
"$PY" -I decrypt.py --key-file "$dir/fixture_priv.der" "$dir/fixture.fdl" > "$out" || exit 1
# Compare as canonical JSON sorted lines so key order and whitespace do not matter.
"$PY" -I - "$out" "$dir/fixture_expected.jsonl" <<'PYEOF'
import difflib, json, sys
def load(p):
    return sorted(json.dumps(json.loads(l), sort_keys=True) for l in open(p) if l.strip())
a, b = load(sys.argv[1]), load(sys.argv[2])
if a == b:
    print("crosscheck: OK (%d events)" % len(a))
    sys.exit(0)
print("crosscheck: MISMATCH")
for l in difflib.unified_diff(b, a, "expected", "actual", lineterm=""):
    print(l)
sys.exit(1)
PYEOF
