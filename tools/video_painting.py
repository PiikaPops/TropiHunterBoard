#!/usr/bin/env python3
"""
Turns a video into what HunterBoard's video paintings read: its frames as small PNGs, its
sound as a mono OGG (mono so that Minecraft places it in the world: it fades with distance),
and a JSON with the timing.

    python tools/video_painting.py <video.mp4> <name> [fps] [height]

writes
    src/main/resources/assets/hunterboard/textures/video/<name>/0000.png …  + video.json
    src/main/resources/assets/hunterboard/sounds/video/<name>.ogg
and adds "video.<name>" to sounds.json. Which painting shows it is decided in
VideoPaintings.kt.

Each frame keeps 256 colours (one palette per frame): a video of a few seconds stays a few
megabytes, and Minecraft's own PNG reader decodes it — nothing depends on a video decoder in
the game. Needs ffmpeg (with libvorbis) on the PATH, and Pillow.
"""
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "hunterboard")


def main(video, name, fps=12.5, height=200):
    frames_dir = os.path.join(ASSETS, "textures", "video", name)
    shutil.rmtree(frames_dir, ignore_errors=True)
    os.makedirs(frames_dir)
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", video,
                        "-vf", f"fps={fps},scale=-2:{height}:flags=lanczos", os.path.join(tmp, "%04d.png")], check=True)
        raw = sorted(glob.glob(os.path.join(tmp, "*.png")))
        for i, path in enumerate(raw):
            im = Image.open(path).convert("RGB").quantize(256, method=Image.Quantize.MEDIANCUT)
            im.save(os.path.join(frames_dir, f"{i:04d}.png"), optimize=True)
        width, h = Image.open(raw[0]).size
    sound_dir = os.path.join(ASSETS, "sounds", "video")
    os.makedirs(sound_dir, exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", video, "-vn", "-ac", "1", "-c:a", "libvorbis", "-q:a", "3",
                    os.path.join(sound_dir, name + ".ogg")], check=True)
    duration = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video],
                                    check=True, capture_output=True, text=True).stdout.strip())
    meta = {"frames": len(raw), "frameMs": round(1000 / fps), "width": width, "height": h,
            "durationMs": round(duration * 1000), "sound": f"hunterboard:video.{name}"}
    with open(os.path.join(frames_dir, "video.json"), "w", encoding="utf8") as fh:
        json.dump(meta, fh)

    # the entry added at the end of sounds.json, the rest of the file left as it is
    sounds_json = os.path.join(ASSETS, "sounds.json")
    with open(sounds_json, encoding="utf8") as fh:
        text = fh.read()
    if f'"video.{name}"' not in text:
        body = text.rstrip()
        assert body.endswith("}")
        entry = f'  "video.{name}": {{\n    "sounds": [{{"name": "hunterboard:video/{name}", "stream": true}}]\n  }}\n'
        text = body[:-1].rstrip() + ",\n" + entry + "}\n"
        json.loads(text)
        with open(sounds_json, "w", encoding="utf8") as fh:
            fh.write(text)

    size = sum(os.path.getsize(p) for p in glob.glob(os.path.join(frames_dir, "*.png")))
    print(f"{name}: {len(raw)} frames of {width}x{h} ({size // 1024} KB), "
          f"sound {os.path.getsize(os.path.join(sound_dir, name + '.ogg')) // 1024} KB, {meta}")


if __name__ == "__main__":
    a = sys.argv[1:]
    main(a[0], a[1], float(a[2]) if len(a) > 2 else 12.5, int(a[3]) if len(a) > 3 else 200)
