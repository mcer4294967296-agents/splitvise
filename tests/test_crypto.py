from server.crypto import MAGIC, decrypt, encrypt, key_from_psk


def test_roundtrip_and_aad():
    key = key_from_psk("0" * 64)
    pt = b"hello splitvise"
    aad = b"clip-id"
    blob = encrypt(key, pt, aad=aad)
    assert blob.startswith(MAGIC)
    assert pt not in blob
    assert decrypt(key, blob, aad=aad) == pt


def test_wrong_aad_fails():
    key = key_from_psk("passphrase-not-hex")
    blob = encrypt(key, b"x", aad=b"a")
    try:
        decrypt(key, blob, aad=b"b")
        assert False, "should have failed"
    except Exception:
        pass


def test_hex_and_passphrase_keys_differ():
    assert key_from_psk("0" * 64) != key_from_psk("0" * 63 + "1")
    assert len(key_from_psk("hello")) == 32
    assert len(key_from_psk("0" * 64)) == 32
