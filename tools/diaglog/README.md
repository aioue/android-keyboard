# diaglog Mac tools

Contract: `SPEC.md`. Run with the homelab venv: `PY=/Users/tom/src/github/homelab/.venv/bin/python`.
Use `-I` for anything that reads downloaded data (scripts add their own directory to the path).

## Key setup (once)
    $PY -I tools/diaglog/keygen.py            # refuses to overwrite; --rotate replaces (old logs become unreadable)
Private key (PKCS#8 DER, base64) goes into the login Keychain, service `futo-diaglog-private-key`, account `tom`.
Public key goes to `java/assets/diaglog_pub.der` (tracked). Rebuild the app after generating or rotating.

Residual risk: the secret is passed to `security -i` on stdin, not argv, so `ps` never shows it. It does exist
briefly in this Python process's memory, and anything running as you can read the Keychain item (macOS may prompt).

## Pull logs
Debug (debuggable) unstable build:

    adb shell run-as org.futo.inputmethod.latin.unstable ls no_backup/diaglog
    adb exec-out run-as org.futo.inputmethod.latin.unstable cat no_backup/diaglog/diag-YYYYMMDD-HHMMSS.fdl > diag-YYYYMMDD-HHMMSS.fdl

(equivalent to /data/data/<applicationId>/no_backup/diaglog). Release builds: use the in-app export, which produces
`futo-diag-YYYYMMDD-HHMMSS.zip`; share it to yourself via Signal and save it on the Mac.

## Decrypt and report
    $PY -I tools/diaglog/decrypt.py futo-diag-*.zip diag-*.fdl > all.jsonl   # sorted by t; warns on truncated frames
    $PY -I tools/diaglog/report.py all.jsonl                                   # or pipe: decrypt.py ... | report.py
`--key-file priv.der` uses a PKCS#8 DER file instead of the Keychain (testing only).

## Tests
    $PY -I -m pytest tools/diaglog/tests
    sh tools/diaglog/crosscheck.sh [dir]   # dir holds fixture.fdl, fixture_priv.der, fixture_expected.jsonl; default tools/diaglog/fixture
