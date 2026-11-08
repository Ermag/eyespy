"""Presence relay: phones publish home/away reports to an ntfy topic, the server subscribes
(outbound connection), so presence works without VPN or open ports.

Reports are end-to-end encrypted (AES-256-GCM, key derived from the API token), so ntfy.sh and
anyone who learns the topic see only random bytes: not who, not home/away. GCM also authenticates
them, and the per-device timestamp check in Hub.set_presence stops replays.

Wire format: "v1." + base64(nonce[12] + ciphertext + tag[16]); plaintext {"d": device, "s": state, "t": ts_ms}
"""
import asyncio
import base64
import hashlib
import hmac
import json
import logging
import os

import httpx
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .config import Settings
from .db import now_ms

log = logging.getLogger("eyespy.relay")
MAX_SKEW_MS = 15 * 60 * 1000


AAD = b"eyespy-presence"


def presence_key(token: str) -> bytes:
    return hmac.new(token.encode(), b"eyespy-presence-v1", hashlib.sha256).digest()


def encrypt(token: str, device: str, state: str, ts: int) -> str:
    nonce = os.urandom(12)
    pt = json.dumps({"d": device, "s": state, "t": ts}).encode()
    return "v1." + base64.b64encode(nonce + AESGCM(presence_key(token)).encrypt(nonce, pt, AAD)).decode()


def decrypt(token: str, message: str) -> tuple[str, str, int] | None:
    if not message.startswith("v1."):
        return None
    try:
        raw = base64.b64decode(message[3:], validate=True)
        payload = json.loads(AESGCM(presence_key(token)).decrypt(raw[:12], raw[12:], AAD))
        device, state, ts = str(payload["d"]), str(payload["s"]), int(payload["t"])
    except (InvalidTag, ValueError, KeyError, TypeError):
        return None
    if state not in ("home", "away") or not device:
        return None
    if abs(now_ms() - ts) > MAX_SKEW_MS:
        log.warning("stale presence report from %s ignored", device)
        return None
    return device, state, ts


async def run(s: Settings, hub) -> None:
    if not s.ntfy_presence_topic:
        log.info("presence relay disabled (no NTFY_TOPIC)")
        return
    url = f"{s.ntfy_server}/{s.ntfy_presence_topic}/json"
    headers = {"Authorization": f"Bearer {s.ntfy_token}"} if s.ntfy_token else {}
    since, backoff = "15m", 1
    timeout = httpx.Timeout(connect=15, read=120, write=15, pool=15)
    while True:
        try:
            async with httpx.AsyncClient(timeout=timeout) as c:
                async with c.stream("GET", url, params={"since": since}, headers=headers) as r:
                    r.raise_for_status()
                    log.info("presence relay subscribed")
                    backoff = 1
                    async for line in r.aiter_lines():
                        if not line.strip():
                            continue
                        ev = json.loads(line)
                        if ev.get("event") != "message":
                            continue
                        since = ev.get("id", since)
                        ok = decrypt(s.token, ev.get("message", "").strip())
                        if ok:
                            await hub.set_presence(*ok, via="relay")
                        else:
                            log.warning("rejected presence relay message")
        except asyncio.CancelledError:
            raise
        except Exception as e:
            log.warning("presence relay error: %r (retry in %ss)", e, backoff)
        await asyncio.sleep(backoff)
        backoff = min(60, backoff * 2)
