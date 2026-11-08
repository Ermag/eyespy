import asyncio
import base64
import logging

import httpx

from .config import Settings

log = logging.getLogger("eyespy.notify")


def _hdr(v: str) -> str:
    """HTTP headers must be ASCII; ntfy accepts RFC 2047 encoded values."""
    try:
        v.encode("ascii")
        return v
    except UnicodeEncodeError:
        return "=?UTF-8?B?" + base64.b64encode(v.encode()).decode() + "?="


class Notifier:
    def __init__(self, s: Settings):
        self.s = s
        self.client = httpx.AsyncClient(timeout=20)
        self._tasks: set[asyncio.Task] = set()

    @property
    def enabled(self) -> bool:
        return bool(self.s.ntfy_topic)

    def fire(self, *args, **kwargs) -> None:
        """Send without blocking the caller."""
        t = asyncio.create_task(self.send(*args, **kwargs))
        self._tasks.add(t)
        t.add_done_callback(self._tasks.discard)

    async def send(self, title: str, message: str, tags=(), priority: int = 3,
                   click: str | None = None, attachment: bytes | None = None) -> bool:
        if not self.enabled:
            return False
        url = f"{self.s.ntfy_server}/{self.s.ntfy_topic}"
        headers = {"Title": _hdr(title), "Priority": str(priority)}
        if tags:
            headers["Tags"] = ",".join(tags)
        if click:
            headers["Click"] = click
        if self.s.ntfy_token:
            headers["Authorization"] = f"Bearer {self.s.ntfy_token}"
        try:
            if attachment:
                headers["Filename"] = "snapshot.jpg"
                headers["Message"] = _hdr(message)
                r = await self.client.put(url, content=attachment, headers=headers)
            else:
                r = await self.client.post(url, content=message.encode(), headers=headers)
            r.raise_for_status()
            return True
        except Exception as e:  # never let push failures break the server
            log.warning("ntfy push failed: %s", e)
            return False

    async def close(self):
        await self.client.aclose()
