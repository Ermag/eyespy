"""Fake camera for testing the server without a phone.
usage: python tools/sim_camera.py http://localhost:8080 TOKEN"""
import asyncio, base64, json, subprocess, sys, tempfile, time
from pathlib import Path
import httpx, websockets

base, token = sys.argv[1].rstrip("/"), sys.argv[2]
H = {"Authorization": f"Bearer {token}"}


def make_clip(path: Path, secs=4):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"testsrc=size=1280x720:rate=15:duration={secs}",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", str(path)], check=True)


def jpeg() -> bytes:
    with tempfile.NamedTemporaryFile(suffix=".jpg") as f:
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=640x480", "-frames:v", "1", f.name], check=True)
        return Path(f.name).read_bytes()


async def main():
    ws_url = base.replace("http", "ws", 1) + "/api/camera/ws"
    async with websockets.connect(ws_url, additional_headers=H) as ws:
        await ws.send(json.dumps({"type": "hello", "recording": False, "info": {"battery": 88, "temp_c": 31.5, "model": "sim"}}))
        state = json.loads(await ws.recv())
        print("server state:", state)
        while not state.get("armed"):
            state = json.loads(await ws.recv())
            print("server state:", state)
        await ws.send(json.dumps({"type": "recording", "state": "started"}))
        t0 = int(time.time() * 1000)
        await asyncio.sleep(1)
        snap = jpeg()
        await ws.send(json.dumps({"type": "motion", "ts": t0 + 1500, "score": 0.12, "snapshot": base64.b64encode(snap).decode()}))
        for _ in range(5):
            await ws.send(snap)
            await asyncio.sleep(0.2)
        with tempfile.TemporaryDirectory() as d:
            clip = Path(d) / "seg.mp4"
            make_clip(clip)
            async with httpx.AsyncClient(headers=H) as c:
                r = await c.post(f"{base}/api/segments", data={"started_at": t0, "duration_ms": 4000},
                                 files={"file": ("seg.mp4", clip.read_bytes(), "video/mp4")})
                print("upload:", r.status_code, r.json())
                r = await c.post(f"{base}/api/segments", data={"started_at": t0, "duration_ms": 4000},
                                 files={"file": ("seg.mp4", clip.read_bytes(), "video/mp4")})
                print("re-upload:", r.status_code, r.json())
        await ws.send(json.dumps({"type": "recording", "state": "stopped"}))
        await asyncio.sleep(0.5)

asyncio.run(main())
