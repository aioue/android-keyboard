"""Tests use a Python reference encryptor following SPEC.md, with a throwaway key."""
import base64
import gzip
import json
import os
import struct
import subprocess
import sys
import zipfile

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
sys.path.insert(0, TOOLS)
from fdl import AAD  # noqa: E402


def make_frame(pub, events):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    key, iv = os.urandom(32), os.urandom(12)
    plain = gzip.compress("\n".join(json.dumps(e) for e in events).encode() + b"\n")
    ct = AESGCM(key).encrypt(iv, plain, AAD)
    wrapped = pub.encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
    body = struct.pack(">H", len(wrapped)) + wrapped + iv + ct
    return struct.pack(">I", len(body)) + body


@pytest.fixture(scope="module")
def keys(tmp_path_factory):
    k = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    p = tmp_path_factory.mktemp("k") / "priv.der"
    p.write_bytes(k.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    return k.public_key(), str(p)


def run(script, *args, stdin=None):
    return subprocess.run([sys.executable, "-I", os.path.join(TOOLS, script), *args], input=stdin,
                          capture_output=True, text=True)


EV1 = [{"t": 300, "type": "commit", "typed": "teh", "committed": "the", "commitType": "autocorrect"},
       {"t": 100, "type": "session"}]
EV2 = [{"t": 200, "type": "revert", "typed": "teh", "corrected": "the", "kind": "autocorrect"}]


def test_roundtrip_sorted_and_zip(keys, tmp_path):
    pub, kf = keys
    f = tmp_path / "diag-20260101.fdl"
    f.write_bytes(make_frame(pub, EV1) + make_frame(pub, EV2))
    r = run("decrypt.py", "--key-file", kf, str(f))
    assert r.returncode == 0, r.stderr
    assert [json.loads(l)["t"] for l in r.stdout.splitlines()] == [100, 200, 300]
    z = tmp_path / "x.zip"
    with zipfile.ZipFile(z, "w", zipfile.ZIP_STORED) as zf:
        zf.write(f, f.name)
    r2 = run("decrypt.py", "--key-file", kf, str(z))
    assert r2.stdout == r.stdout


def test_truncated_final_frame(keys, tmp_path):
    pub, kf = keys
    f = tmp_path / "a.fdl"
    f.write_bytes(make_frame(pub, EV1) + make_frame(pub, EV2)[:-20])
    r = run("decrypt.py", "--key-file", kf, str(f))
    assert r.returncode == 0
    assert len(r.stdout.splitlines()) == 2
    assert "truncated" in r.stderr


def test_tampered_frame_skipped(keys, tmp_path):
    pub, kf = keys
    bad = bytearray(make_frame(pub, EV2))
    bad[-1] ^= 1
    f = tmp_path / "a.fdl"
    f.write_bytes(make_frame(pub, EV1) + bytes(bad))
    r = run("decrypt.py", "--key-file", kf, str(f))
    assert len(r.stdout.splitlines()) == 2 and "failed" in r.stderr


def test_report(keys):
    evs = [{"t": 1, "type": "suggest", "typed": "teh", "candidates": [["the", 1, "x", "y"]], "lm": "ok"},
           {"t": 2, "type": "commit", "typed": "teh", "committed": "the", "commitType": "autocorrect"},
           {"t": 3, "type": "revert", "typed": "teh", "corrected": "the", "kind": "autocorrect"},
           {"t": 4, "type": "commit", "typed": "teh", "committed": "teh", "commitType": "typed"},
           {"t": 5, "type": "pick", "rank": 1},
           {"t": 6, "type": "swipe", "decoder": "new", "latencyMs": 40}, {"t": 7, "type": "weird"}]
    r = run("report.py", stdin="\n".join(json.dumps(e) for e in evs))
    assert r.returncode == 0, r.stderr
    assert "100.0% (1/1)" in r.stdout and "teh -> the" in r.stdout and "rank 1: 1" in r.stdout


def test_report_empty():
    assert run("report.py", stdin="").returncode == 0
