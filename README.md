# splitvise

Voice inbox for split-bill annotations. Record a ≤60s clip on Android, upload
it here, replay it later while doing the books.

Public URL (this playground): `https://staging.zyc.moe/splitvise/`

The server stores audio **encrypted at rest** (AES-256-GCM, PSK). It decrypts
in-process to serve the player and (later) a transcription job. Clients send
plaintext over TLS; there is no client-side E2E.

## Layout

```
server/          FastAPI app (uvicorn server.main:create_app --factory)
android/         Gradle + Jetpack Compose client
data/            sqlite + ciphertext clips (gitignored)
```

## API

All `/v1/*` routes need `Authorization: Bearer <token>`. `/health` does not.

| Method | Path | |
|---|---|---|
| POST | `/v1/clips` | multipart: `audio` (required), `recorded_at`, `duration_ms` (≤60000), `lat`, `lng`, `accuracy_m` |
| GET | `/v1/clips` | list, newest first (`since`, `until`, `limit`, `cursor`) |
| GET | `/v1/clips/{id}` | metadata |
| GET | `/v1/clips/{id}/audio` | decrypted bytes |
| DELETE | `/v1/clips/{id}` | |
| GET | `/health` | `{ok: true}` |
| GET | `/` | tiny authenticated list/player |

## Run locally

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt -r requirements-dev.txt
export SPLITVISE_PSK=$(openssl rand -hex 32)
export SPLITVISE_BOOTSTRAP_TOKEN=$(openssl rand -hex 32)
export SPLITVISE_DATA=$PWD/data
.venv/bin/uvicorn server.main:create_app --factory --host 127.0.0.1 --port 10234
```

On this box the systemd unit `splitvise` binds `127.0.0.1:10234` and nginx
strips `/splitvise`. See `../splitvise-deploy/README.md`.

## Android

Gradle project under `android/`. Needs a JDK + Android SDK (not this VPS).

```sh
cd android
./gradlew :app:assembleDebug
```

Settings in the app: server URL (default `https://staging.zyc.moe/splitvise`)
and bearer token (EncryptedSharedPreferences).

## Remotes

| Remote | URL | |
|---|---|---|
| `fork` | `git@github.com:mcer4294967296-agents/splitvise.git` | push |
| `origin` | `https://github.com/MCer4294967296/splitvise.git` | upstream, no push |

This checkout is jj-managed (`jj git clone --colocate`). Commit with `jj`.
