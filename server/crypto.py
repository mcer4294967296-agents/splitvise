"""AES-256-GCM at-rest encryption. The server decrypts in-process; clients send plaintext over TLS."""

from __future__ import annotations

import hashlib
import os

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"SV1\x01"
NONCE_LEN = 12
KEY_LEN = 32


def key_from_psk(psk: str) -> bytes:
    """Accept a 32-byte hex key, or any passphrase (SHA-256)."""
    raw = psk.strip()
    if not raw:
        raise ValueError("empty PSK")
    try:
        key = bytes.fromhex(raw)
        if len(key) == KEY_LEN:
            return key
    except ValueError:
        pass
    return hashlib.sha256(raw.encode("utf-8")).digest()


def encrypt(key: bytes, plaintext: bytes, aad: bytes = b"") -> bytes:
    nonce = os.urandom(NONCE_LEN)
    ct = AESGCM(key).encrypt(nonce, plaintext, aad)
    return MAGIC + nonce + ct


def decrypt(key: bytes, blob: bytes, aad: bytes = b"") -> bytes:
    if len(blob) < len(MAGIC) + NONCE_LEN + 16:
        raise ValueError("ciphertext too short")
    if not blob.startswith(MAGIC):
        raise ValueError("unrecognized ciphertext (missing SV1 magic)")
    nonce = blob[len(MAGIC) : len(MAGIC) + NONCE_LEN]
    ct = blob[len(MAGIC) + NONCE_LEN :]
    return AESGCM(key).decrypt(nonce, ct, aad)
