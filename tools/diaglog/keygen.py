#!/usr/bin/env python
"""Generate the diaglog RSA-3072 key pair.

Private key (PKCS#8 DER, base64) goes into the macOS login Keychain only.
Public key (X.509 SPKI DER) goes to java/assets/diaglog_pub.der.
"""
import argparse
import base64
import os
import subprocess
import sys

sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))  # needed under python -I

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from fdl import ACCOUNT, SERVICE

PUB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "java", "assets", "diaglog_pub.der")


def key_exists():
    return subprocess.run(["security", "find-generic-password", "-s", SERVICE, "-a", ACCOUNT],
                          capture_output=True).returncode == 0


def store(b64):
    # `security -i` reads commands from stdin, so the secret never appears in
    # any process's argv (visible to `ps`). Base64 has no quote characters.
    cmd = "add-generic-password -U -s %s -a %s -w %s\n" % (SERVICE, ACCOUNT, b64)
    r = subprocess.run(["security", "-i"], input=cmd, capture_output=True, text=True)
    if r.returncode != 0 or "error" in (r.stderr + r.stdout).lower():
        # Do not echo output: it could contain the command line.
        sys.exit("keygen: Keychain write failed (exit %d). Run in Terminal.app if a prompt is needed." % r.returncode)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rotate", action="store_true", help="replace an existing key (old logs become undecryptable)")
    a = ap.parse_args()
    if key_exists() and not a.rotate:
        sys.exit("keygen: key already in Keychain; refusing to overwrite (use --rotate)")
    k = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    priv = k.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    pub = k.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    store(base64.b64encode(priv).decode())
    os.makedirs(os.path.dirname(PUB), exist_ok=True)
    with open(PUB, "wb") as f:
        f.write(pub)
    print("stored private key in Keychain (%s/%s); wrote %s (%d bytes)" % (SERVICE, ACCOUNT, os.path.normpath(PUB), len(pub)))


if __name__ == "__main__":
    main()
