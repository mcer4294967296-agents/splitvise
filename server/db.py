"""SQLite schema and helpers."""

from __future__ import annotations

import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS tokens (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    label TEXT,
    created_at TEXT NOT NULL,
    revoked_at TEXT
);

CREATE TABLE IF NOT EXISTS clips (
    id TEXT PRIMARY KEY,
    owner_token_id TEXT NOT NULL REFERENCES tokens(id),
    created_at TEXT NOT NULL,
    recorded_at TEXT,
    duration_ms INTEGER,
    content_type TEXT NOT NULL,
    at_rest TEXT NOT NULL,
    bytes INTEGER NOT NULL,
    lat REAL,
    lng REAL,
    accuracy_m REAL,
    transcript TEXT,
    transcript_status TEXT NOT NULL DEFAULT 'none',
    place_name TEXT
);

CREATE INDEX IF NOT EXISTS clips_owner_created
    ON clips (owner_token_id, created_at DESC, id DESC);
"""


def connect(path: Path) -> sqlite3.Connection:
    path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.executescript(SCHEMA)
    conn.commit()
    return conn
