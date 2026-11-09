#!/usr/bin/env python
"""Decrypt .fdl files and/or an export .zip to JSONL on stdout, sorted by t."""
import argparse
import json
import sys

sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))  # needed under python -I

from fdl import decrypt_blob, load_private_key, read_inputs


def warn(m):
    print("warning: " + m, file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("--key-file", help="PKCS#8 DER private key (testing only); default: Keychain")
    a = ap.parse_args()
    key = load_private_key(a.key_file)
    events = []
    for name, data in read_inputs(a.inputs):
        events += decrypt_blob(data, name, key, warn)
    events.sort(key=lambda e: e.get("t", 0) if isinstance(e, dict) else 0)
    out = sys.stdout
    for e in events:
        out.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    main()
