"""ffmpeg post-processing and HTTP range responses."""
import asyncio
import logging
import os
import re
import shutil
from pathlib import Path

from fastapi import Request
from fastapi.responses import Response, StreamingResponse

log = logging.getLogger("eyespy.media")


async def _run(*args: str) -> bool:
    p = await asyncio.create_subprocess_exec(*args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    _, err = await p.communicate()
    if p.returncode != 0:
        log.warning("%s failed: %s", args[0], err.decode(errors="replace")[-400:])
    return p.returncode == 0


async def process_segment(tmp: Path, out: Path, thumb: Path) -> None:
    """Remux so the moov atom is at the front (instant seeking/streaming) and grab a thumbnail."""
    ok = await _run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(tmp),
                    "-map", "0", "-c", "copy", "-movflags", "+faststart", str(out))
    if ok:
        tmp.unlink(missing_ok=True)
    else:
        out.unlink(missing_ok=True)
        shutil.move(tmp, out)
    await _run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", "1", "-i", str(out),
               "-frames:v", "1", "-vf", "scale=320:-2", "-q:v", "6", str(thumb))


async def probe_duration_ms(path: Path) -> int:
    p = await asyncio.create_subprocess_exec(
        "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(path),
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
    out, _ = await p.communicate()
    try:
        return int(float(out.decode().strip()) * 1000)
    except ValueError:
        return 0


_RANGE = re.compile(r"bytes=(\d*)-(\d*)")


def file_response(request: Request, path: Path, media_type: str) -> Response:
    """Serve a file with Range support (needed for seeking in the video player)."""
    size = path.stat().st_size
    start, end, status = 0, size - 1, 200
    m = _RANGE.fullmatch(request.headers.get("range", "").strip())
    if m and (m.group(1) or m.group(2)):
        if m.group(1):
            start = int(m.group(1))
            end = int(m.group(2)) if m.group(2) else size - 1
        else:  # suffix range: last N bytes
            start = max(0, size - int(m.group(2)))
        end = min(end, size - 1)
        if start > end or start >= size:
            return Response(status_code=416, headers={"Content-Range": f"bytes */{size}"})
        status = 206

    def chunks(chunk=256 * 1024):
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                b = f.read(min(chunk, left))
                if not b:
                    break
                left -= len(b)
                yield b

    headers = {"Accept-Ranges": "bytes", "Content-Length": str(end - start + 1), "Cache-Control": "private, max-age=3600"}
    if status == 206:
        headers["Content-Range"] = f"bytes {start}-{end}/{size}"
    return StreamingResponse(chunks(), status_code=status, media_type=media_type, headers=headers)


def remove_empty_dirs(root: Path) -> None:
    for d, _, _ in sorted(os.walk(root), key=lambda x: -len(x[0])):
        p = Path(d)
        if p != root:
            try:
                p.rmdir()
            except OSError:
                pass
