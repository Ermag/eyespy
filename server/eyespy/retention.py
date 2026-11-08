import asyncio
import logging
import shutil

from .config import Settings
from .db import DB, now_ms
from .media import remove_empty_dirs

log = logging.getLogger("eyespy.retention")
DAY_MS = 86_400_000
GB = 1024 ** 3


def _delete_segment(s: Settings, db: DB, row: dict) -> None:
    for rel in (row["path"], row["thumb"]):
        if rel:
            (s.data_dir / rel).unlink(missing_ok=True)
    db.x("DELETE FROM segments WHERE id=?", (row["id"],))


def run_once(s: Settings, db: DB) -> int:
    now = now_ms()
    cut = now - int(s.retention_days * DAY_MS)
    mcut = now - int(s.motion_retention_days * DAY_MS)
    removed = 0
    for row in db.q("SELECT * FROM segments WHERE (has_motion=0 AND started_at<?) OR started_at<?", (cut, mcut)):
        _delete_segment(s, db, row)
        removed += 1
    for ev in db.q("SELECT id, snapshot FROM events WHERE ts<?", (mcut,)):
        if ev["snapshot"]:
            (s.data_dir / ev["snapshot"]).unlink(missing_ok=True)
    db.x("DELETE FROM events WHERE ts<?", (mcut,))

    # size cap / free-space floor: drop oldest footage, non-motion first
    def over() -> bool:
        if s.max_storage_gb > 0:
            used = db.q("SELECT COALESCE(SUM(size),0) AS n FROM segments")[0]["n"]
            if used > s.max_storage_gb * GB:
                return True
        return shutil.disk_usage(s.data_dir).free < s.min_free_gb * GB

    if over():
        for row in db.q("SELECT * FROM segments ORDER BY has_motion ASC, started_at ASC"):
            if not over():
                break
            _delete_segment(s, db, row)
            removed += 1
    for sub in ("segments", "snapshots"):
        remove_empty_dirs(s.data_dir / sub)
    if removed:
        log.info("retention removed %d segments", removed)
    return removed


async def loop(s: Settings, db: DB, every_s: int = 900) -> None:
    while True:
        try:
            await asyncio.to_thread(run_once, s, db)
        except Exception:
            log.exception("retention failed")
        await asyncio.sleep(every_s)
