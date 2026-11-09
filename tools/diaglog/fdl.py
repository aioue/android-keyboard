"""Shared helpers for the diaglog Mac tools (frame format per SPEC.md)."""
import gzip
import json
import struct
import subprocess
import sys
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SERVICE = "futo-diaglog-private-key"
ACCOUNT = "tom"
AAD = b"FDL1"


def oaep():
    return padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None)


def load_private_key(key_file=None):
    """Key from a PKCS#8 DER file (testing) or the login Keychain. Never printed."""
    if key_file:
        der = open(key_file, "rb").read()
    else:
        import base64
        r = subprocess.run(["security", "find-generic-password", "-w", "-s", SERVICE, "-a", ACCOUNT],
                           capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit("decrypt: private key not found in Keychain (service %s); run keygen.py or use --key-file" % SERVICE)
        der = base64.b64decode(r.stdout.strip())
    return serialization.load_der_private_key(der, password=None)


def iter_frames(data, name, warn):
    """Yield decrypted-ready (wrapped, iv, ct) tuples; warn and stop on truncation."""
    pos = 0
    while pos < len(data):
        if len(data) - pos < 4:
            warn("%s: truncated frame header at offset %d" % (name, pos))
            return
        (n,) = struct.unpack_from(">I", data, pos)
        if n < 2 + 12 + 16 or pos + 4 + n > len(data):
            warn("%s: truncated/invalid final frame at offset %d (len %d, have %d)" % (name, pos, n, len(data) - pos - 4))
            return
        body = data[pos + 4: pos + 4 + n]
        (w,) = struct.unpack_from(">H", body, 0)
        if 2 + w + 12 + 16 > n:
            warn("%s: bad wrapped key length at offset %d" % (name, pos))
            return
        yield body[2:2 + w], body[2 + w:2 + w + 12], body[2 + w + 12:], pos
        pos += 4 + n


def decrypt_blob(data, name, key, warn):
    """Return list of event dicts from one .fdl byte string."""
    events = []
    for wrapped, iv, ct, pos in iter_frames(data, name, warn):
        try:
            aes = key.decrypt(wrapped, oaep())
            plain = gzip.decompress(AESGCM(aes).decrypt(iv, ct, AAD))
        except Exception as e:  # bad tag, wrong key, corrupt gzip
            warn("%s: frame at offset %d failed (%s); skipping" % (name, pos, type(e).__name__))
            continue
        for line in plain.decode("utf-8").splitlines():
            if not line.strip():
                continue
            try:
                events.append(json.loads(line))
            except ValueError:
                warn("%s: bad JSON line in frame at offset %d" % (name, pos))
    return events


def read_inputs(paths):
    """Yield (name, bytes) for .fdl files and .fdl members of zips."""
    for p in paths:
        if p.lower().endswith(".zip"):
            with zipfile.ZipFile(p) as z:
                for n in sorted(z.namelist()):
                    if n.endswith(".fdl"):
                        yield "%s:%s" % (p, n), z.read(n)
        else:
            with open(p, "rb") as f:
                yield p, f.read()
