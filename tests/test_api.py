from __future__ import annotations

import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from server import crypto
from server.main import Settings, create_app

TOKEN = "test-token-aaaaaaaa"
PSK = "0" * 64


@pytest.fixture
def client(tmp_path: Path):
    settings = Settings(
        data_dir=tmp_path,
        psk=crypto.key_from_psk(PSK),
        bootstrap_token=TOKEN,
    )
    with TestClient(create_app(settings)) as c:
        yield c, tmp_path


def auth():
    return {"Authorization": f"Bearer {TOKEN}"}


def test_health_no_auth(client):
    c, _ = client
    r = c.get("/health")
    assert r.status_code == 200
    assert r.json() == {"ok": True}


def test_v1_requires_auth(client):
    c, _ = client
    assert c.get("/v1/clips").status_code == 401
    assert c.get("/v1/clips", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_upload_roundtrip_and_at_rest_encryption(client):
    c, data_dir = client
    payload = b"RIFF" + b"\x00" * 64 + b"not-really-wav-but-fine"
    r = c.post(
        "/v1/clips",
        headers=auth(),
        files={"audio": ("clip.m4a", payload, "audio/mp4")},
        data={"duration_ms": "1800", "lat": "37.87", "lng": "-122.27", "accuracy_m": "15"},
    )
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["bytes"] == len(payload)
    assert body["encryption"] == "aes-256-gcm-v1"
    assert body["duration_ms"] == 1800
    assert body["lat"] == pytest.approx(37.87)
    assert body["transcript"] is None
    clip_id = body["id"]

    on_disk = (data_dir / "clips" / clip_id).read_bytes()
    assert on_disk.startswith(crypto.MAGIC)
    assert payload not in on_disk

    audio = c.get(f"/v1/clips/{clip_id}/audio", headers=auth())
    assert audio.status_code == 200
    assert audio.content == payload
    assert audio.headers["content-type"].startswith("audio/mp4")

    listed = c.get("/v1/clips", headers=auth()).json()
    assert listed["clips"][0]["id"] == clip_id
    assert listed["next"] is None


def test_duration_cap(client):
    c, _ = client
    r = c.post(
        "/v1/clips",
        headers=auth(),
        files={"audio": ("clip.m4a", b"abcd", "audio/mp4")},
        data={"duration_ms": "60001"},
    )
    assert r.status_code == 400


def test_rejects_client_encryption(client):
    c, _ = client
    r = c.post(
        "/v1/clips",
        headers=auth(),
        files={"audio": ("clip.m4a", b"abcd", "audio/mp4")},
        data={"encryption": "splitvise-v1"},
    )
    assert r.status_code == 400


def test_owner_isolation_and_delete(client):
    c, data_dir = client
    r = c.post(
        "/v1/clips",
        headers=auth(),
        files={"audio": ("clip.m4a", b"secret-audio", "audio/mp4")},
    )
    clip_id = r.json()["id"]

    other = Settings(
        data_dir=data_dir,
        psk=crypto.key_from_psk(PSK),
        bootstrap_token="other-token-bbbbbbbb",
    )
    # same db file, new app instance to insert the other bootstrap token
    other_app = create_app(other)
    with TestClient(other_app) as c2:
        listed = c2.get("/v1/clips", headers={"Authorization": "Bearer other-token-bbbbbbbb"})
        assert listed.status_code == 200
        assert listed.json()["clips"] == []
        assert c2.get(f"/v1/clips/{clip_id}", headers={"Authorization": "Bearer other-token-bbbbbbbb"}).status_code == 404

    gone = c.delete(f"/v1/clips/{clip_id}", headers=auth())
    assert gone.status_code == 204
    assert not (data_dir / "clips" / clip_id).exists()
    assert c.get(f"/v1/clips/{clip_id}", headers=auth()).status_code == 404


def test_list_cursor(client):
    c, _ = client
    ids = []
    for i in range(3):
        r = c.post(
            "/v1/clips",
            headers=auth(),
            files={"audio": (f"{i}.m4a", f"clip-{i}".encode(), "audio/mp4")},
        )
        ids.append(r.json()["id"])
    page = c.get("/v1/clips?limit=2", headers=auth()).json()
    assert len(page["clips"]) == 2
    assert page["next"]
    page2 = c.get(f"/v1/clips?limit=2&cursor={page['next']}", headers=auth()).json()
    assert len(page2["clips"]) == 1
    seen = {x["id"] for x in page["clips"] + page2["clips"]}
    assert seen == set(ids)


def test_index_served(client):
    c, _ = client
    r = c.get("/")
    assert r.status_code == 200
    assert b"splitvise" in r.content
    assert b"loadAudio" in r.content
    assert b'addEventListener("play"' not in r.content
