"""splitvise HTTP API. uvicorn server.main:create_app --factory"""

from __future__ import annotations

import base64
import hashlib
import os
import sqlite3
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated, Any, Optional

from fastapi import Depends, FastAPI, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.staticfiles import StaticFiles

from server import crypto, db

AT_REST = "aes-256-gcm-v1"
ACCEPTED_AUDIO_TYPES = {
    "audio/mp4",
    "audio/aac",
    "audio/m4a",
    "audio/x-m4a",
    "audio/3gpp",
    "audio/3gpp2",
    "audio/amr",
    "application/octet-stream",
}
HERE = Path(__file__).resolve().parent
DEFAULT_DATA = HERE.parent / "data"
STATIC = HERE / "static"


def _utcnow() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _clip_path(data_dir: Path, clip_id: str) -> Path:
    return data_dir / "clips" / clip_id


def encode_cursor(created_at: str, clip_id: str) -> str:
    return base64.urlsafe_b64encode(f"{created_at}|{clip_id}".encode()).decode()


def decode_cursor(cursor: str) -> tuple[str, str]:
    try:
        raw = base64.urlsafe_b64decode(cursor.encode()).decode()
        created_at, clip_id = raw.split("|", 1)
        if not created_at or not clip_id:
            raise ValueError
        return created_at, clip_id
    except Exception as exc:
        raise HTTPException(400, "invalid cursor") from exc


@dataclass
class Settings:
    data_dir: Path
    psk: bytes
    bootstrap_token: str | None
    max_bytes: int = 8 * 1024 * 1024
    max_duration_ms: int = 60_000

    @classmethod
    def from_env(cls) -> "Settings":
        psk_raw = os.environ.get("SPLITVISE_PSK", "")
        if not psk_raw.strip():
            raise RuntimeError("SPLITVISE_PSK is required")
        data = Path(os.environ.get("SPLITVISE_DATA", str(DEFAULT_DATA)))
        token = os.environ.get("SPLITVISE_BOOTSTRAP_TOKEN") or None
        return cls(data_dir=data, psk=crypto.key_from_psk(psk_raw), bootstrap_token=token)


@dataclass
class Principal:
    id: str
    label: str | None


def clip_json(row: sqlite3.Row, prefix: str = "") -> dict[str, Any]:
    return {
        "id": row["id"],
        "created_at": row["created_at"],
        "recorded_at": row["recorded_at"],
        "duration_ms": row["duration_ms"],
        "content_type": row["content_type"],
        "encryption": row["at_rest"],
        "bytes": row["bytes"],
        "lat": row["lat"],
        "lng": row["lng"],
        "accuracy_m": row["accuracy_m"],
        "transcript": row["transcript"],
        "transcript_status": row["transcript_status"],
        "place_name": row["place_name"],
        "audio_url": f"{prefix}/v1/clips/{row['id']}/audio",
    }


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    (settings.data_dir / "clips").mkdir(parents=True, exist_ok=True)
    conn = db.connect(settings.data_dir / "splitvise.sqlite")

    if settings.bootstrap_token:
        token_hash = _hash_token(settings.bootstrap_token)
        existing = conn.execute(
            "SELECT id FROM tokens WHERE token_hash = ?", (token_hash,)
        ).fetchone()
        if existing is None:
            conn.execute(
                "INSERT INTO tokens (id, token_hash, label, created_at) VALUES (?, ?, ?, ?)",
                (str(uuid.uuid4()), token_hash, "bootstrap", _utcnow()),
            )
            conn.commit()

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        yield
        conn.close()

    app = FastAPI(title="splitvise", lifespan=lifespan)
    bearer = HTTPBearer(auto_error=False)

    def require_principal(
        creds: HTTPAuthorizationCredentials | None = Depends(bearer),
    ) -> Principal:
        if creds is None or not creds.credentials:
            raise HTTPException(401, "missing bearer token")
        row = conn.execute(
            "SELECT id, label, revoked_at FROM tokens WHERE token_hash = ?",
            (_hash_token(creds.credentials),),
        ).fetchone()
        if row is None or row["revoked_at"]:
            raise HTTPException(401, "invalid token")
        return Principal(id=row["id"], label=row["label"])

    @app.get("/health")
    def health() -> dict[str, bool]:
        return {"ok": True}

    @app.get("/")
    def index() -> FileResponse:
        return FileResponse(STATIC / "index.html")

    @app.post("/v1/clips", status_code=201)
    async def upload_clip(
        principal: Principal = Depends(require_principal),
        audio: UploadFile = File(),
        recorded_at: Optional[str] = Form(None),
        duration_ms: Optional[int] = Form(None),
        lat: Optional[float] = Form(None),
        lng: Optional[float] = Form(None),
        accuracy_m: Optional[float] = Form(None),
        encryption: Optional[str] = Form(None),
    ) -> dict[str, Any]:
        if encryption not in (None, "", "none"):
            raise HTTPException(
                400,
                "client-side encryption is not accepted; send plaintext over TLS",
            )
        if duration_ms is not None and duration_ms > settings.max_duration_ms:
            raise HTTPException(400, f"duration_ms exceeds {settings.max_duration_ms}")
        if duration_ms is not None and duration_ms < 0:
            raise HTTPException(400, "duration_ms must be >= 0")

        content_type = (audio.content_type or "audio/mp4").split(";")[0].strip().lower()
        if content_type not in ACCEPTED_AUDIO_TYPES:
            raise HTTPException(400, f"unsupported content type: {content_type}")

        blob = await audio.read(settings.max_bytes + 1)
        if not blob:
            raise HTTPException(400, "empty audio")
        if len(blob) > settings.max_bytes:
            raise HTTPException(413, "audio exceeds 8 MiB")

        clip_id = str(uuid.uuid4())
        created_at = _utcnow()
        ciphertext = crypto.encrypt(settings.psk, blob, aad=clip_id.encode())
        path = _clip_path(settings.data_dir, clip_id)
        path.write_bytes(ciphertext)

        conn.execute(
            """
            INSERT INTO clips (
                id, owner_token_id, created_at, recorded_at, duration_ms,
                content_type, at_rest, bytes, lat, lng, accuracy_m,
                transcript_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'none')
            """,
            (
                clip_id,
                principal.id,
                created_at,
                recorded_at,
                duration_ms,
                content_type if content_type != "application/octet-stream" else "audio/mp4",
                AT_REST,
                len(blob),
                lat,
                lng,
                accuracy_m,
            ),
        )
        conn.commit()
        row = conn.execute("SELECT * FROM clips WHERE id = ?", (clip_id,)).fetchone()
        return clip_json(row)

    @app.get("/v1/clips")
    def list_clips(
        principal: Principal = Depends(require_principal),
        since: Annotated[Optional[str], Query()] = None,
        until: Annotated[Optional[str], Query()] = None,
        limit: Annotated[int, Query(ge=1, le=200)] = 50,
        cursor: Annotated[Optional[str], Query()] = None,
    ) -> dict[str, Any]:
        where = ["owner_token_id = ?"]
        args: list[Any] = [principal.id]
        if since:
            where.append("created_at >= ?")
            args.append(since)
        if until:
            where.append("created_at <= ?")
            args.append(until)
        if cursor:
            c_at, c_id = decode_cursor(cursor)
            where.append("(created_at, id) < (?, ?)")
            args.extend([c_at, c_id])
        sql = (
            "SELECT * FROM clips WHERE "
            + " AND ".join(where)
            + " ORDER BY created_at DESC, id DESC LIMIT ?"
        )
        args.append(limit + 1)
        rows = conn.execute(sql, args).fetchall()
        extra = None
        if len(rows) > limit:
            extra = rows[limit]
            rows = rows[:limit]
        next_cursor = None
        if extra is not None and rows:
            last = rows[-1]
            next_cursor = encode_cursor(last["created_at"], last["id"])
        return {"clips": [clip_json(r) for r in rows], "next": next_cursor}

    def _owned(principal: Principal, clip_id: str) -> sqlite3.Row:
        row = conn.execute(
            "SELECT * FROM clips WHERE id = ? AND owner_token_id = ?",
            (clip_id, principal.id),
        ).fetchone()
        if row is None:
            raise HTTPException(404, "clip not found")
        return row

    @app.get("/v1/clips/{clip_id}")
    def get_clip(
        clip_id: str,
        principal: Principal = Depends(require_principal),
    ) -> dict[str, Any]:
        return clip_json(_owned(principal, clip_id))

    @app.get("/v1/clips/{clip_id}/audio")
    def get_audio(
        clip_id: str,
        principal: Principal = Depends(require_principal),
    ) -> Response:
        row = _owned(principal, clip_id)
        path = _clip_path(settings.data_dir, clip_id)
        if not path.is_file():
            raise HTTPException(404, "audio missing")
        try:
            plaintext = crypto.decrypt(settings.psk, path.read_bytes(), aad=clip_id.encode())
        except Exception as exc:
            raise HTTPException(500, "failed to decrypt audio") from exc
        return Response(
            content=plaintext,
            media_type=row["content_type"],
            headers={"Content-Length": str(len(plaintext))},
        )

    @app.delete("/v1/clips/{clip_id}", status_code=204)
    def delete_clip(
        clip_id: str,
        principal: Principal = Depends(require_principal),
    ) -> Response:
        _owned(principal, clip_id)
        path = _clip_path(settings.data_dir, clip_id)
        conn.execute(
            "DELETE FROM clips WHERE id = ? AND owner_token_id = ?",
            (clip_id, principal.id),
        )
        conn.commit()
        try:
            path.unlink(missing_ok=True)
        except TypeError:
            if path.exists():
                path.unlink()
        return Response(status_code=204)

    if STATIC.is_dir():
        app.mount("/assets", StaticFiles(directory=STATIC), name="assets")
    return app
