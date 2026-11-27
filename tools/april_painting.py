#!/usr/bin/env python3
"""
Turns an animated GIF into what HunterBoard's April Fools paintings read: the frames stacked
in one PNG strip (top to bottom), and a small JSON with their size and timing.

    python tools/april_painting.py tablo/fish.gif

writes src/main/resources/assets/hunterboard/textures/april/fish.png and fish.json (named
after the GIF). The game decodes the PNG itself (Minecraft's own PNG reader), so nothing in
the mod depends on Java's GIF support or on how a GIF composes its frames: Pillow does that
here, once, frame after frame, each frame complete.

Needs Pillow.
"""
import json
import os
import sys

from PIL import Image, ImageSequence

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "hunterboard", "textures", "april")


def main(path):
    gif = Image.open(path)
    frames, durations = [], []
    for frame in ImageSequence.Iterator(gif):
        frames.append(frame.convert("RGB"))   # each frame complete, as shown
        durations.append(frame.info.get("duration") or 100)
    w, h = frames[0].size
    strip = Image.new("RGB", (w, h * len(frames)))
    for i, f in enumerate(frames):
        strip.paste(f, (0, i * h))
    # one palette for the whole strip: as small as the GIF, read by any PNG decoder
    strip = strip.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    name = os.path.splitext(os.path.basename(path))[0]
    os.makedirs(OUT, exist_ok=True)
    png = os.path.join(OUT, name + ".png")
    strip.save(png, optimize=True)
    # a single frame time: the game shows every frame for as long (the GIF's most common one)
    frame_ms = max(set(durations), key=durations.count)
    with open(os.path.join(OUT, name + ".json"), "w", encoding="utf8") as fh:
        json.dump({"frames": len(frames), "width": w, "height": h, "frameMs": frame_ms}, fh)
    print(f"{png}: {len(frames)} frames of {w}x{h}, {frame_ms} ms each, {os.path.getsize(png) // 1024} KB")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "tablo", "fish.gif"))
