import os
from dataclasses import dataclass
from pathlib import Path


def _bool(v: str) -> bool:
    return v.strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Settings:
    token: str
    data_dir: Path
    retention_days: float          # footage without motion
    motion_retention_days: float   # footage containing motion + events/snapshots
    max_storage_gb: float          # 0 = no cap
    min_free_gb: float             # always keep this much disk free
    ntfy_server: str
    ntfy_topic: str                # notifications (server -> your phone)
    ntfy_presence_topic: str       # presence relay (your phone -> server)
    ntfy_token: str
    ntfy_attach_snapshot: bool
    motion_push_cooldown_s: int
    camera_offline_alert_s: int


def load() -> Settings:
    e = os.environ.get
    token = e("EYESPY_TOKEN", "").strip()
    if len(token) < 16:
        raise SystemExit("EYESPY_TOKEN must be set (16+ chars). Generate one: openssl rand -hex 24")
    topic = e("NTFY_TOPIC", "").strip()
    return Settings(
        token=token,
        data_dir=Path(e("EYESPY_DATA", "/data")),
        retention_days=float(e("RETENTION_DAYS", "3")),
        motion_retention_days=float(e("MOTION_RETENTION_DAYS", "14")),
        max_storage_gb=float(e("MAX_STORAGE_GB", "0")),
        min_free_gb=float(e("MIN_FREE_GB", "2")),
        ntfy_server=e("NTFY_SERVER", "https://ntfy.sh").rstrip("/"),
        ntfy_topic=topic,
        ntfy_presence_topic=e("NTFY_PRESENCE_TOPIC", f"{topic}-presence" if topic else "").strip(),
        ntfy_token=e("NTFY_TOKEN", "").strip(),
        ntfy_attach_snapshot=_bool(e("NTFY_ATTACH_SNAPSHOT", "false")),
        motion_push_cooldown_s=int(e("MOTION_PUSH_COOLDOWN_S", "120")),
        camera_offline_alert_s=int(e("CAMERA_OFFLINE_ALERT_S", "120")),
    )
