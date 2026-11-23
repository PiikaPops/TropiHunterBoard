"""
Generates the textures of HunterBoard's "modern" interface (UiKit, MODERN style).

    python tools/ui_textures.py            (needs Pillow: pip install pillow)

Every file lands in src/main/resources/assets/hunterboard/textures/gui/sprites/ui/ — the
folder Minecraft stitches GUI sprites from — next to a .mcmeta that says how it stretches
(nine-slice borders). The PNGs can also be retouched by hand: the game only reads the
files, this script is just how they were first drawn.

The palette and shapes follow Cobblemon's own interface: charcoal casings with a lit rim
and cut corners (summary, PC), the Pokédex's brick red for the title bar, and its cyan
screen for whatever is selected.
"""
import json
import os
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources",
                   "assets", "hunterboard", "textures", "gui", "sprites", "ui")


def rgb(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


# Cobblemon's greys (sampled from its summary / PC textures)
OUTLINE = rgb("1b1b1b")
FRAME = rgb("2f2f2f")
SCREEN = rgb("3d3d3d")
SHADE = rgb("4b4b4b")
PLATE = rgb("676767")
RIM = rgb("8d8d8d")
GLINT = rgb("c6c6c6")
# Pokédex red
RED_DEEP = rgb("792c27")
RED = rgb("ba3a30")
RED_LIT = rgb("d74845")
RED_GLINT = rgb("f97d68")
# Pokédex screen cyan
CYAN_DEEP = rgb("22658b")
CYAN_DARK = rgb("3a96b6")
CYAN = rgb("5bd1da")
CYAN_LIT = rgb("7ee7e5")
WHITE = rgb("ffffff")
CLEAR = (0, 0, 0, 0)


def plate(w, h, layers, body, cut=1, cuts=None, two_tone=None):
    """
    A casing: concentric 1px layers from the outside in, then the body.

    Each layer is one colour, or (lit, dark): lit on the top and left edges, dark on the
    bottom and right — the light comes from the top left, as in Cobblemon's textures.
    `cut` is how many pixels each corner is cut by (0 = square); `cuts` overrides it per
    corner as (top-left, top-right, bottom-left, bottom-right).
    `two_tone` darkens the lower half of the body, the way Cobblemon's bars are shaded.
    """
    tl, tr, bl, br = cuts if cuts else (cut, cut, cut, cut)
    im = Image.new("RGBA", (w, h), CLEAR)
    px = im.load()
    for y in range(h):
        for x in range(w):
            # distance to each straight edge and to each cut corner
            d = [
                (x, "lit"), (y, "lit"), (w - 1 - x, "dark"), (h - 1 - y, "dark"),
                (x + y - tl, "lit"), ((w - 1 - x) + y - tr, "mid"),
                (x + (h - 1 - y) - bl, "mid"), ((w - 1 - x) + (h - 1 - y) - br, "dark"),
            ]
            depth, side = min(d, key=lambda t: t[0])
            if depth < 0:
                continue
            if depth < len(layers):
                c = layers[depth]
                if isinstance(c, tuple) and len(c) == 2:
                    lit, dark = c
                    c = lit if side == "lit" else dark if side == "dark" else mix(lit, dark)
                px[x, y] = c
            else:
                px[x, y] = two_tone if (two_tone and y >= h // 2) else body
    return im


def mix(a, b, t=0.5):
    return tuple(int(round(a[i] * (1 - t) + b[i] * t)) for i in range(4))


def save(name, im, border=None, tile=None):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".png")
    im.save(path)
    meta = path + ".mcmeta"
    if border is not None:
        l, t, r, b = border if isinstance(border, tuple) else (border,) * 4
        scaling = {"type": "nine_slice", "width": im.size[0], "height": im.size[1],
                   "border": {"left": l, "top": t, "right": r, "bottom": b}}
    elif tile:
        scaling = {"type": "tile", "width": im.size[0], "height": im.size[1]}
    else:
        scaling = None
    if scaling:
        with open(meta, "w") as f:
            json.dump({"gui": {"scaling": scaling}}, f, indent=2)
    elif os.path.exists(meta):
        os.remove(meta)


def draw(im, rows, colors, ox=0, oy=0):
    """Paint an ASCII bitmap: each character is looked up in `colors` ('.' = skip)."""
    px = im.load()
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != "." and ch in colors:
                px[ox + x, oy + y] = colors[ch]
    return im


def main():
    # ── the panel: a charcoal casing with a lit rim and cut corners ──────────────────
    save("panel", plate(48, 48, [OUTLINE, (RIM, SHADE), PLATE, rgb("1f1f1f")], rgb("2c2c2c"), cut=2), 8)

    # ── its title bar: Pokédex red, lit from above, shaded below ─────────────────────
    bar = plate(16, 16, [(RED_LIT, RED_DEEP)], rgb("c13d34"), cut=0, two_tone=rgb("ad362d"))
    px = bar.load()
    for x in range(16):
        px[x, 0] = RED_LIT
        px[x, 15] = RED_DEEP
    for y in range(1, 15):   # the side edges belong to the casing, not to the bar
        px[0, y] = px[1, y]
        px[15, y] = px[14, y]
    save("header", bar)   # stretched as a whole: it is uniform along its length

    # ── buttons ───────────────────────────────────────────────────────────────────
    save("button", plate(16, 16, [OUTLINE, (RIM, SHADE)], PLATE, cut=1), 3)
    save("button_hover", plate(16, 16, [OUTLINE, (GLINT, PLATE)], rgb("7d7d7d"), cut=1), 3)
    save("button_active", plate(16, 16, [OUTLINE, (CYAN_LIT, CYAN_DEEP)], CYAN_DARK, cut=1), 3)
    save("button_gold", plate(16, 16, [OUTLINE, (rgb("f2cf63"), rgb("6b5116"))], rgb("9c7a23"), cut=1), 3)
    save("button_gold_hover", plate(16, 16, [OUTLINE, (rgb("ffe894"), rgb("8a6a1e"))], rgb("bd9630"), cut=1), 3)
    save("button_danger", plate(16, 16, [OUTLINE, (rgb("e7545a"), RED_DEEP)], rgb("d03c3d"), cut=1), 3)

    # ── tabs: cut top corners, open at the bottom ─────────────────────────────────────
    def tab(layers, body):
        im = plate(16, 16, layers, body, cuts=(2, 2, 0, 0))
        px = im.load()
        for x in range(1, 15):      # no bottom edge: the tab stands on the line below it
            px[x, 15] = body
            px[x, 14] = body
        px[1, 14] = px[1, 13]; px[1, 15] = px[1, 13]
        px[14, 14] = px[14, 13]; px[14, 15] = px[14, 13]
        return im
    save("tab", tab([OUTLINE, (SHADE, FRAME)], rgb("383838")), (3, 3, 3, 2))
    save("tab_hover", tab([OUTLINE, (RIM, SHADE)], rgb("555555")), (3, 3, 3, 2))
    save("tab_active", tab([OUTLINE, (CYAN_LIT, CYAN_DEEP)], CYAN_DARK), (3, 3, 3, 2))

    # ── cards and slots ───────────────────────────────────────────────────────────
    save("card", plate(16, 16, [rgb("1f1f1f"), (rgb("565656"), rgb("404040"))], rgb("383838"), cut=1), 3)
    save("card_hover", plate(16, 16, [rgb("1f1f1f"), (CYAN_LIT, CYAN_DARK)], rgb("404040"), cut=1), 3)
    # sunken: shadow on the top-left, a catch of light on the bottom-right
    save("slot", plate(16, 16, [(rgb("151515"), rgb("595959")), rgb("1a1a1a")], rgb("222222"), cut=1), 3)
    save("slot_focus", plate(16, 16, [(CYAN_DARK, CYAN_LIT), rgb("1a1a1a")], rgb("222222"), cut=1), 3)

    # ── selection: the Pokédex's white corner brackets ───────────────────────────────
    sel = Image.new("RGBA", (16, 16), CLEAR)
    draw(sel, [
        "WWWW........WWWW",
        "W..............W",
        "W..............W",
        "W..............W",
    ], {"W": WHITE})
    draw(sel, [
        "W..............W",
        "W..............W",
        "W..............W",
        "WWWW........WWWW",
    ], {"W": WHITE}, oy=12)
    save("select", sel, 4)

    # ── scrollbar thumb ──────────────────────────────────────────────────────────
    save("thumb", plate(3, 8, [(GLINT, RIM)], rgb("b0b0b0"), cut=0), (1, 2, 1, 2))

    # ── close button ─────────────────────────────────────────────────────────────
    cross = [
        "...........",
        "...........",
        "...W...W...",
        "....W.W....",
        ".....W.....",
        "....W.W....",
        "...W...W...",
        "...........",
        "...........",
        "...........",
        "...........",
    ]
    c = plate(11, 11, [OUTLINE, (RED_DEEP, RED_DEEP)], rgb("8e2f29"), cut=1)
    save("close", draw(c, cross, {"W": rgb("f2d6d3")}, oy=1))
    c = plate(11, 11, [OUTLINE, (WHITE, GLINT)], rgb("f2f2f2"), cut=1)
    save("close_hover", draw(c, cross, {"W": rgb("d03c3d")}, oy=1))

    # ── icons ────────────────────────────────────────────────────────────────────
    save("icon_search", draw(Image.new("RGBA", (9, 9), CLEAR), [
        ".WWWW....",
        "W....W...",
        "W....W...",
        "W....W...",
        "W....W...",
        ".WWWW....",
        ".....WW..",
        "......WW.",
        ".......W.",
    ], {"W": WHITE}))
    # a small Poké Ball for the title bar
    save("icon_ball", draw(Image.new("RGBA", (9, 9), CLEAR), [
        "..OOOOO..",
        ".ORRRRRO.",
        "ORRRRRRRO",
        "ORROOORRO",
        "OOOOWOOOO",
        "OWWOOOWWO",
        "OWWWWWWWO",
        ".OWWWWWO.",
        "..OOOOO..",
    ], {"O": OUTLINE, "R": rgb("e7545a"), "W": rgb("f2f2f2")}))
    save("icon_back", draw(Image.new("RGBA", (7, 7), CLEAR), [
        "...W...",
        "..WW...",
        ".WWWWWW",
        "WWWWWWW",
        ".WWWWWW",
        "..WW...",
        "...W...",
    ], {"W": WHITE}))

    # ── dotted separator (Cobblemon's summary dividers) ───────────────────────────────
    dots = Image.new("RGBA", (4, 1), CLEAR)
    dots.load()[0, 0] = WHITE
    dots.load()[1, 0] = WHITE
    save("dots", dots, tile=True)

    # ── toggle switch ─────────────────────────────────────────────────────────────
    off = plate(20, 10, [OUTLINE, (rgb("151515"), rgb("4b4b4b"))], rgb("242424"), cut=1)
    knob = plate(8, 6, [(GLINT, RIM)], rgb("a8a8a8"), cut=0)
    off.alpha_composite(knob, (2, 2))
    save("switch_off", off)
    on = plate(20, 10, [OUTLINE, (CYAN_DEEP, CYAN_LIT)], CYAN_DARK, cut=1)
    knob = plate(8, 6, [(WHITE, GLINT)], rgb("f2f2f2"), cut=0)
    on.alpha_composite(knob, (10, 2))
    save("switch_on", on)

    print("textures written to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
