"""Live state: camera connection, arming logic, presence, live viewers."""
import asyncio
import base64
import json
import logging
import time

from fastapi import WebSocket

from .config import Settings
from .db import DB, now_ms
from .notify import Notifier

log = logging.getLogger("eyespy.hub")

MODES = ("auto", "armed", "disarmed")
# motion in the ~1 s gap between segments still marks the neighbouring segments
MOTION_SLACK_MS = 3000


class Hub:
    def __init__(self, s: Settings, db: DB, notifier: Notifier):
        self.s, self.db, self.notify = s, db, notifier
        self.camera: WebSocket | None = None
        self.camera_info: dict = {}
        self.camera_seen = 0
        self.recording = False
        self.armed, self.arm_reason = self.desired()
        self.live_queues: set[asyncio.Queue] = set()
        self.latest_frame: bytes | None = None
        self._last_motion_push = 0.0
        self._offline_task: asyncio.Task | None = None
        self._offline_alerted = False
        self._send_lock = asyncio.Lock()

    # ---- arming ---------------------------------------------------------
    @property
    def mode(self) -> str:
        return self.db.get("mode", "auto")

    def presence(self) -> list[dict]:
        return self.db.q("SELECT device, state, ts, via FROM presence ORDER BY device")

    def desired(self) -> tuple[bool, str]:
        mode = self.mode
        if mode == "armed":
            return True, "armed manually"
        if mode == "disarmed":
            return False, "disarmed manually"
        p = self.presence()
        if not p:
            return False, "no presence devices registered"
        home = [x["device"] for x in p if x["state"] != "away"]
        if not home:
            return True, "everyone left home"
        return False, f"{', '.join(home)} at home"

    async def reevaluate(self) -> None:
        armed, reason = self.desired()
        if armed != self.armed:
            self.armed, self.arm_reason = armed, reason
            self.db.event("armed" if armed else "disarmed", {"reason": reason})
            log.info("%s: %s", "ARMED" if armed else "DISARMED", reason)
            if armed and self.camera is None:
                self.notify.fire("Camera problem", "Open eyeSpy to check the camera.", tags=["warning"], priority=4)
        else:
            self.arm_reason = reason
        await self.send_state()

    async def set_mode(self, mode: str) -> None:
        if mode not in MODES:
            raise ValueError(mode)
        self.db.set("mode", mode)
        self.db.event("mode", {"mode": mode})
        await self.reevaluate()

    async def set_presence(self, device: str, state: str, ts: int, via: str) -> bool:
        """Returns False if this report is older than what we already have (replay / out of order)."""
        rows = self.db.q("SELECT ts, state FROM presence WHERE device=?", (device,))
        if rows and rows[0]["ts"] >= ts:
            return False
        self.db.x(
            "INSERT INTO presence(device,state,ts,via) VALUES(?,?,?,?) "
            "ON CONFLICT(device) DO UPDATE SET state=excluded.state, ts=excluded.ts, via=excluded.via",
            (device, state, ts, via),
        )
        if rows and rows[0]["state"] == state:
            # periodic check-in: same state, just fresher. No event, no log line.
            return True
        self.db.event("presence", {"device": device, "state": state, "via": via}, ts=ts)
        log.info("presence %s -> %s (via %s)", device, state, via)
        await self.reevaluate()
        return True

    async def remove_presence(self, device: str) -> None:
        self.db.x("DELETE FROM presence WHERE device=?", (device,))
        await self.reevaluate()

    # ---- camera link ----------------------------------------------------
    async def send_state(self) -> None:
        ws = self.camera
        if ws is None:
            return
        msg = {"type": "state", "armed": self.armed, "live": bool(self.live_queues), "reason": self.arm_reason}
        try:
            async with self._send_lock:
                await ws.send_text(json.dumps(msg))
        except Exception as e:
            log.debug("send_state failed: %s", e)

    async def camera_connected(self, ws: WebSocket) -> None:
        old, self.camera = self.camera, ws
        if old is not None:
            try:
                await old.close(code=4000, reason="replaced")
            except Exception:
                pass
        self.camera_seen = time.time()
        if self._offline_task:
            self._offline_task.cancel()
            self._offline_task = None
        self.db.event("camera_connected")
        if self._offline_alerted:
            self._offline_alerted = False
            self.notify.fire("Camera back online", "The camera reconnected to the server.",
                             tags=["white_check_mark"], priority=3)
        log.info("camera connected")
        await self.send_state()

    async def camera_disconnected(self, ws: WebSocket) -> None:
        if self.camera is not ws:
            return
        self.camera = None
        self.recording = False
        self.db.event("camera_disconnected")
        log.warning("camera disconnected")
        self._offline_task = asyncio.create_task(self._offline_watch())

    async def _offline_watch(self) -> None:
        await asyncio.sleep(self.s.camera_offline_alert_s)
        if self.camera is None:
            self._offline_alerted = True
            self.db.event("camera_offline")
            mins = max(1, self.s.camera_offline_alert_s // 60)
            self.notify.fire("Camera offline", f"No connection from the camera for {mins} min.",
                             tags=["warning"], priority=4)

    async def on_camera_message(self, msg: dict) -> None:
        self.camera_seen = time.time()
        t = msg.get("type")
        if t == "hello":
            self.camera_info.update(msg.get("info") or {})
            self.recording = bool(msg.get("recording"))
        elif t == "heartbeat":
            self.camera_info.update(msg.get("info") or {})
        elif t == "recording":
            started = msg.get("state") == "started"
            if started != self.recording:
                self.recording = started
                self._recording_changed(started, msg.get("error"))
        elif t == "motion":
            await self._motion(msg)
        else:
            log.debug("unknown camera message %s", t)

    def _recording_changed(self, started: bool, error: str | None) -> None:
        detail = {"reason": self.arm_reason}
        if error:
            detail["error"] = error
        eid = self.db.event("recording_started" if started else "recording_stopped", detail)
        # Push text stays generic (no names or reasons): it passes through ntfy.sh and Google.
        if started:
            self.notify.fire("Recording started", "eyeSpy is recording.",
                             tags=["red_circle"], priority=3, click="eyespy://live")
        else:
            body = "Recording stopped unexpectedly. Check the camera." if error else "eyeSpy stopped recording."
            self.notify.fire("Recording stopped", body, tags=["stop_button"],
                             priority=3, click=f"eyespy://event/{eid}")

    async def _motion(self, msg: dict) -> None:
        ts = int(msg.get("ts") or now_ms())
        snapshot_rel, jpeg = None, None
        if msg.get("snapshot"):
            try:
                jpeg = base64.b64decode(msg["snapshot"])
                day = time.strftime("%Y-%m-%d", time.localtime(ts / 1000))
                rel = f"snapshots/{day}/{ts}.jpg"
                p = self.s.data_dir / rel
                p.parent.mkdir(parents=True, exist_ok=True)
                await asyncio.to_thread(p.write_bytes, jpeg)
                snapshot_rel = rel
            except Exception as e:
                log.warning("bad snapshot: %s", e)
        eid = self.db.event("motion", {"score": msg.get("score")}, ts=ts, snapshot=snapshot_rel)
        # in case the segment covering this moment was already uploaded
        self.db.x("UPDATE segments SET has_motion=1 WHERE started_at<=? AND started_at+duration_ms>=?",
                  (ts + MOTION_SLACK_MS, ts - MOTION_SLACK_MS))
        now = time.time()
        if now - self._last_motion_push >= self.s.motion_push_cooldown_s:
            self._last_motion_push = now
            when = time.strftime("%H:%M:%S", time.localtime(ts / 1000))
            self.notify.fire(
                "Motion detected", f"Motion at {when}. Tap to view.",
                tags=["rotating_light"], priority=4, click=f"eyespy://event/{eid}",
                attachment=jpeg if self.s.ntfy_attach_snapshot else None,
            )

    def on_camera_frame(self, data: bytes) -> None:
        self.camera_seen = time.time()
        if len(data) < 128 or data[:2] != b"\xff\xd8":  # not a JPEG
            return
        self.latest_frame = data
        for q in list(self.live_queues):
            if q.full():
                try:
                    q.get_nowait()
                except asyncio.QueueEmpty:
                    pass
            q.put_nowait(data)

    # ---- live viewers ---------------------------------------------------
    def add_live(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=2)
        self.live_queues.add(q)
        if len(self.live_queues) == 1:
            asyncio.create_task(self.send_state())
        return q

    def remove_live(self, q: asyncio.Queue) -> None:
        if q in self.live_queues:
            self.live_queues.discard(q)
            if not self.live_queues:
                asyncio.create_task(self.send_state())

    def status(self) -> dict:
        return {
            "mode": self.mode,
            "armed": self.armed,
            "arm_reason": self.arm_reason,
            "recording": self.recording,
            "camera": {
                "online": self.camera is not None,
                "last_seen": int(self.camera_seen * 1000) if self.camera_seen else None,
                "info": self.camera_info,
            },
            "presence": self.presence(),
            "live_viewers": len(self.live_queues),
            "push_enabled": self.notify.enabled,
            "server_time": now_ms(),
        }
