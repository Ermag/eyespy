import asyncio
import hashlib
import hmac
import json
import logging
import shutil
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, StreamingResponse
from pydantic import BaseModel, Field

from . import media, relay, retention
from .config import Settings, load
from .db import DB, now_ms
from .hub import MODES, Hub
from .notify import Notifier

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
log = logging.getLogger("eyespy")

MEDIA_TOKEN_TTL_S = 2 * 3600
MAX_UPLOAD_BYTES = 1024 ** 3  # 1 GiB per segment


class _StripQuery(logging.Filter):
    """Never write query strings (which may carry media tokens) to the access log."""
    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.args, tuple) and len(record.args) >= 3 and isinstance(record.args[2], str):
            args = list(record.args)
            args[2] = args[2].split("?", 1)[0]
            record.args = tuple(args)
        return True


logging.getLogger("uvicorn.access").addFilter(_StripQuery())


class ModeIn(BaseModel):
    mode: str


class PresenceIn(BaseModel):
    device: str = Field(min_length=1, max_length=64)
    state: str
    ts: int | None = None


def create_app(s: Settings) -> FastAPI:
    s.data_dir.mkdir(parents=True, exist_ok=True)
    (s.data_dir / ".tmp").mkdir(exist_ok=True)  # upload spool dir (TMPDIR in compose)
    db = DB(s.data_dir / "eyespy.db")
    notifier = Notifier(s)
    hub = Hub(s, db, notifier)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        tasks = [asyncio.create_task(relay.run(s, hub)), asyncio.create_task(retention.loop(s, db))]
        log.info("eyespy up: data=%s push=%s mode=%s armed=%s", s.data_dir,
                 "ntfy" if s.ntfy_topic else "off", hub.mode, hub.armed)
        yield
        for t in tasks:
            t.cancel()
        await notifier.close()

    app = FastAPI(title="eyeSpy", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.hub, app.state.db = hub, db

    def token_ok(header: str | None) -> bool:
        t = header[7:].strip() if header and header.lower().startswith("bearer ") else ""
        return bool(t) and hmac.compare_digest(t.encode(), s.token.encode())

    def _media_sig(exp: int) -> str:
        return hmac.new(s.token.encode(), f"media|{exp}".encode(), hashlib.sha256).hexdigest()[:32]

    def media_token_ok(mt: str | None) -> bool:
        """Short-lived, read-only token for <img>/<video> URLs in the web dashboard."""
        try:
            exp_s, sig = (mt or "").split(".", 1)
            exp = int(exp_s)
        except ValueError:
            return False
        return exp > time.time() and hmac.compare_digest(sig, _media_sig(exp))

    async def auth(request: Request):
        if token_ok(request.headers.get("authorization")):
            return
        path = request.url.path
        is_media = path == "/api/live.mjpeg" or path.endswith(("/video", "/thumb", "/snapshot"))
        if request.method == "GET" and is_media and media_token_ok(request.query_params.get("mt")):
            return
        raise HTTPException(401, "bad token")

    A = [Depends(auth)]

    def seg_out(r: dict) -> dict:
        return {"id": r["id"], "started_at": r["started_at"], "duration_ms": r["duration_ms"], "size": r["size"],
                "has_motion": bool(r["has_motion"]), "video": f"/api/segments/{r['id']}/video",
                "thumb": f"/api/segments/{r['id']}/thumb" if r["thumb"] else None}

    def ev_out(r: dict) -> dict:
        return {"id": r["id"], "ts": r["ts"], "type": r["type"], "detail": json.loads(r["detail"] or "{}"),
                "snapshot": f"/api/events/{r['id']}/snapshot" if r["snapshot"] else None}

    def local_file(rel: str | None) -> Path:
        if not rel:
            raise HTTPException(404)
        p = (s.data_dir / rel).resolve()
        if not p.is_file() or s.data_dir.resolve() not in p.parents:
            raise HTTPException(404)
        return p

    # ---- general --------------------------------------------------------
    @app.get("/healthz")
    async def healthz():
        return {"ok": True}

    @app.get("/api/status", dependencies=A)
    async def status():
        st = hub.status()
        agg = db.q("SELECT COUNT(*) AS n, COALESCE(SUM(size),0) AS bytes FROM segments")[0]
        st["storage"] = {"segments": agg["n"], "bytes": agg["bytes"],
                         "disk_free": shutil.disk_usage(s.data_dir).free}
        return st

    @app.get("/api/media-token")
    async def media_token(request: Request):
        if not token_ok(request.headers.get("authorization")):  # a media token can't mint another
            raise HTTPException(401, "bad token")
        exp = int(time.time()) + MEDIA_TOKEN_TTL_S
        return {"mt": f"{exp}.{_media_sig(exp)}", "expires": exp}

    @app.get("/api/client-config", dependencies=A)
    async def client_config():
        """Lets the app configure itself after you enter server URL + token."""
        return {"ntfy_server": s.ntfy_server, "ntfy_topic": s.ntfy_topic,
                "presence_topic": s.ntfy_presence_topic, "ntfy_token": s.ntfy_token}

    @app.post("/api/mode", dependencies=A)
    async def set_mode(body: ModeIn):
        if body.mode not in MODES:
            raise HTTPException(400, f"mode must be one of {MODES}")
        await hub.set_mode(body.mode)
        return hub.status()

    @app.post("/api/presence", dependencies=A)
    async def presence(body: PresenceIn):
        if body.state not in ("home", "away"):
            raise HTTPException(400, "state must be home|away")
        applied = await hub.set_presence(body.device, body.state, body.ts or now_ms(), via="direct")
        return {"applied": applied, "armed": hub.armed}

    @app.delete("/api/presence/{device}", dependencies=A)
    async def presence_delete(device: str):
        await hub.remove_presence(device)
        return {"ok": True}

    @app.post("/api/test-notification", dependencies=A)
    async def test_notification():
        if not notifier.enabled:
            raise HTTPException(400, "push disabled: set NTFY_TOPIC")
        ok = await notifier.send("eyeSpy test", "Push notifications work.", tags=["tada"], click="eyespy://status")
        return {"sent": ok}

    # ---- camera ---------------------------------------------------------
    @app.websocket("/api/camera/ws")
    async def camera_ws(ws: WebSocket):
        if not token_ok(ws.headers.get("authorization")):
            await ws.close(code=4401)
            return
        await ws.accept()
        await hub.camera_connected(ws)
        try:
            while True:
                m = await ws.receive()
                if m["type"] == "websocket.disconnect":
                    break
                if m.get("bytes") is not None:
                    hub.on_camera_frame(m["bytes"])
                elif m.get("text"):
                    await hub.on_camera_message(json.loads(m["text"]))
        except WebSocketDisconnect:
            pass
        except Exception:
            log.exception("camera socket error")
        finally:
            await hub.camera_disconnected(ws)

    @app.post("/api/segments", dependencies=A)
    async def upload_segment(request: Request, started_at: int = Form(...), duration_ms: int = Form(0),
                             file: UploadFile = File(...)):
        if int(request.headers.get("content-length") or 0) > MAX_UPLOAD_BYTES:
            raise HTTPException(413, "segment too large")
        dup = db.q("SELECT id FROM segments WHERE started_at=?", (started_at,))
        if dup:
            return {"id": dup[0]["id"], "duplicate": True}
        lt = time.localtime(started_at / 1000)
        day_rel = Path("segments") / time.strftime("%Y-%m-%d", lt)
        (s.data_dir / day_rel).mkdir(parents=True, exist_ok=True)
        name = f"{time.strftime('%H%M%S', lt)}_{started_at}"
        tmp = s.data_dir / day_rel / f".{name}.upload"
        out_rel, thumb_rel = day_rel / f"{name}.mp4", day_rel / f"{name}.jpg"

        def save() -> bool:
            n = 0
            with open(tmp, "wb") as f:
                while chunk := file.file.read(1024 * 1024):
                    n += len(chunk)
                    if n > MAX_UPLOAD_BYTES:
                        return False
                    f.write(chunk)
            return True
        if not await asyncio.to_thread(save):
            tmp.unlink(missing_ok=True)
            raise HTTPException(413, "segment too large")
        if tmp.stat().st_size == 0:
            tmp.unlink()
            raise HTTPException(400, "empty file")
        await media.process_segment(tmp, s.data_dir / out_rel, s.data_dir / thumb_rel)
        if duration_ms <= 0:
            duration_ms = await media.probe_duration_ms(s.data_dir / out_rel)
        slack = 3000  # motion in the gap between segments counts for both neighbours
        has_motion = bool(db.q("SELECT 1 FROM events WHERE type='motion' AND ts BETWEEN ? AND ? LIMIT 1",
                               (started_at - slack, started_at + duration_ms + slack)))
        thumb = str(thumb_rel) if (s.data_dir / thumb_rel).exists() else None
        sid = db.x("INSERT INTO segments(started_at,duration_ms,path,thumb,size,has_motion,created_at) "
                   "VALUES(?,?,?,?,?,?,?)",
                   (started_at, duration_ms, str(out_rel), thumb, (s.data_dir / out_rel).stat().st_size,
                    int(has_motion), now_ms()))
        return {"id": sid}

    # ---- footage --------------------------------------------------------
    @app.get("/api/days", dependencies=A)
    async def days():
        return db.q("SELECT date(started_at/1000,'unixepoch','localtime') AS day, COUNT(*) AS segments, "
                    "SUM(has_motion) AS motion_segments, SUM(size) AS bytes FROM segments "
                    "GROUP BY day ORDER BY day DESC")

    @app.get("/api/segments", dependencies=A)
    async def segments(day: str | None = None, start: int | None = None, end: int | None = None,
                       motion_only: bool = False, limit: int = 1000):
        where, args = [], []
        if day:
            where.append("date(started_at/1000,'unixepoch','localtime')=?")
            args.append(day)
        if start is not None:
            where.append("started_at>=?")
            args.append(start)
        if end is not None:
            where.append("started_at<?")
            args.append(end)
        if motion_only:
            where.append("has_motion=1")
        sql = "SELECT * FROM segments" + (" WHERE " + " AND ".join(where) if where else "")
        rows = db.q(sql + " ORDER BY started_at DESC LIMIT ?", (*args, min(limit, 5000)))
        return [seg_out(r) for r in rows]

    def seg_row(sid: int) -> dict:
        rows = db.q("SELECT * FROM segments WHERE id=?", (sid,))
        if not rows:
            raise HTTPException(404)
        return rows[0]

    @app.get("/api/segments/{sid}/video", dependencies=A)
    async def segment_video(sid: int, request: Request):
        return media.file_response(request, local_file(seg_row(sid)["path"]), "video/mp4")

    @app.get("/api/segments/{sid}/thumb", dependencies=A)
    async def segment_thumb(sid: int, request: Request):
        return media.file_response(request, local_file(seg_row(sid)["thumb"]), "image/jpeg")

    @app.delete("/api/segments/{sid}", dependencies=A)
    async def segment_delete(sid: int):
        retention._delete_segment(s, db, seg_row(sid))
        return {"ok": True}

    @app.get("/api/events", dependencies=A)
    async def events(before: int | None = None, types: str | None = None, limit: int = 100):
        where, args = [], []
        if before:
            where.append("ts<?")
            args.append(before)
        if types:
            tl = [t for t in types.split(",") if t]
            where.append(f"type IN ({','.join('?' * len(tl))})")
            args.extend(tl)
        sql = "SELECT * FROM events" + (" WHERE " + " AND ".join(where) if where else "")
        return [ev_out(r) for r in db.q(sql + " ORDER BY ts DESC LIMIT ?", (*args, min(limit, 1000)))]

    @app.get("/api/events/{eid}", dependencies=A)
    async def event(eid: int):
        rows = db.q("SELECT * FROM events WHERE id=?", (eid,))
        if not rows:
            raise HTTPException(404)
        out = ev_out(rows[0])
        seg = db.q("SELECT * FROM segments WHERE started_at<=? AND started_at+duration_ms>=? LIMIT 1",
                   (rows[0]["ts"], rows[0]["ts"]))
        if not seg:  # nearest segment within 2 minutes (e.g. event between segments)
            seg = db.q("SELECT * FROM segments WHERE ABS(started_at-?)<120000 ORDER BY ABS(started_at-?) LIMIT 1",
                       (rows[0]["ts"], rows[0]["ts"]))
        out["segment"] = seg_out(seg[0]) if seg else None
        out["offset_ms"] = max(0, rows[0]["ts"] - seg[0]["started_at"]) if seg else 0
        return out

    @app.get("/api/events/{eid}/snapshot", dependencies=A)
    async def event_snapshot(eid: int, request: Request):
        rows = db.q("SELECT snapshot FROM events WHERE id=?", (eid,))
        return media.file_response(request, local_file(rows[0]["snapshot"] if rows else None), "image/jpeg")

    # ---- live -----------------------------------------------------------
    @app.get("/api/live.mjpeg", dependencies=A)
    async def live(request: Request):
        q = hub.add_live()

        async def gen():
            try:
                while True:
                    try:
                        frame = await asyncio.wait_for(q.get(), timeout=10)
                    except asyncio.TimeoutError:
                        if await request.is_disconnected():
                            break
                        continue
                    yield (b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                           + str(len(frame)).encode() + b"\r\n\r\n" + frame + b"\r\n")
            finally:
                hub.remove_live(q)

        return StreamingResponse(gen(), media_type="multipart/x-mixed-replace; boundary=frame",
                                 headers={"Cache-Control": "no-store"})

    @app.get("/", response_class=HTMLResponse)
    async def index():
        return (Path(__file__).parent / "index.html").read_text()

    return app


def __getattr__(name):  # lazy so `import eyespy.main` works without env (tests)
    if name == "app":
        globals()["app"] = create_app(load())
        return globals()["app"]
    raise AttributeError(name)
