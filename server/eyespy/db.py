import json
import sqlite3
import threading
import time
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS segments(
  id INTEGER PRIMARY KEY,
  started_at INTEGER NOT NULL UNIQUE,   -- epoch ms
  duration_ms INTEGER NOT NULL,
  path TEXT NOT NULL,                   -- relative to data dir
  thumb TEXT,
  size INTEGER NOT NULL,
  has_motion INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS seg_time ON segments(started_at);
CREATE TABLE IF NOT EXISTS events(
  id INTEGER PRIMARY KEY,
  ts INTEGER NOT NULL,
  type TEXT NOT NULL,
  detail TEXT,
  snapshot TEXT
);
CREATE INDEX IF NOT EXISTS ev_time ON events(ts);
CREATE TABLE IF NOT EXISTS presence(
  device TEXT PRIMARY KEY,
  state TEXT NOT NULL,
  ts INTEGER NOT NULL,
  via TEXT
);
CREATE TABLE IF NOT EXISTS kv(key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


def now_ms() -> int:
    return int(time.time() * 1000)


class DB:
    def __init__(self, path: Path):
        self._conn = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.executescript(SCHEMA)
        self._lock = threading.Lock()

    def q(self, sql: str, args=()) -> list[dict]:
        with self._lock:
            return [dict(r) for r in self._conn.execute(sql, args).fetchall()]

    def x(self, sql: str, args=()) -> int:
        with self._lock:
            return self._conn.execute(sql, args).lastrowid or 0

    def get(self, key: str, default: str) -> str:
        rows = self.q("SELECT value FROM kv WHERE key=?", (key,))
        return rows[0]["value"] if rows else default

    def set(self, key: str, value: str) -> None:
        self.x("INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, value))

    def event(self, type_: str, detail: dict | None = None, ts: int | None = None, snapshot: str | None = None) -> int:
        return self.x(
            "INSERT INTO events(ts,type,detail,snapshot) VALUES(?,?,?,?)",
            (ts or now_ms(), type_, json.dumps(detail or {}), snapshot),
        )
