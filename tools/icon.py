#!/usr/bin/env python3
"""Draws PluralWare's icon (front dial B1c, member colors) into the apps' resources.

The front member at the top of a ring, two more members on it. Everything is
computed here, so a change to the design is a change to the numbers below; run
this, then commit what it writes:

  - shared/src/main/res: the adaptive launcher icon both apps use, with a
    monochrome layer for Android's themed icons;
  - the small single-color mark: the phone's notification icon and the watch's
    complication icon;
  - docs/icon: icon.svg, the full-color icon; icon-512.png, the Play Store icon
    (docs/play-release.md); and icon-round.png, the circular one the README shows.

Needs Pillow for the PNGs.
"""
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# The adaptive icon's 108-unit canvas.
CX, CY, R = 54, 56, 22      # the ring's center and radius
RING = 5                    # the ring's width
FRONT = (54, 33, 9, 13)     # the front member: x, y, radius, gap cut around it
SIDE_R, SIDE_GAP, SIDE_ANGLE = 5.45, 8.4, 30

BACKGROUND = "#000000"
COLORS = {"ring": "#6B6F7A", "front": "#E6A12B", "left": "#E57676", "right": "#4FB3A9"}


def f(x):
    return f"{x:.2f}".rstrip("0").rstrip(".")


def polar(r, deg):
    a = math.radians(deg)
    return CX + r * math.cos(a), CY + r * math.sin(a)


def side(deg):
    x, y = polar(R, deg)
    return x, y, SIDE_R, SIDE_GAP


LEFT, RIGHT = side(180 - SIDE_ANGLE), side(SIDE_ANGLE)


def circle(x, y, r):
    """A circle as path data, which both SVG and Android's vector drawables take."""
    return f"M{f(x - r)},{f(y)}a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0z"


def gap_arc(gx, gy, g, x0, y0, x1, y1, inner, outer):
    """The arc of a gap's circle from one edge of the ring to the other: the one through the
    ring, which is where the gap cuts it."""
    a0, a1 = math.atan2(y0 - gy, x0 - gx), math.atan2(y1 - gy, x1 - gx)
    for sweep in (0, 1):
        span = ((a1 - a0) if sweep else (a0 - a1)) % (2 * math.pi)
        mid = a0 + (span / 2 if sweep else -span / 2)
        if inner < math.hypot(gx + g * math.cos(mid) - CX, gy + g * math.sin(mid) - CY) < outer:
            return f"A{f(g)},{f(g)} 0 0,{sweep} {f(x1)},{f(y1)}"
    raise ValueError("the gap doesn't cross the ring")


def ring():
    """The ring with a gap cut around each member: three filled pieces, each bounded by the
    ring's two edges and the arcs of the gaps at its ends."""
    outer, inner = R + RING / 2, R - RING / 2
    gaps = []
    for x, y, _, g in (FRONT, RIGHT, LEFT):
        d = math.hypot(x - CX, y - CY)
        at = math.degrees(math.atan2(y - CY, x - CX)) % 360
        # Where the gap's circle crosses each edge of the ring, as an angle either side of it.
        half = lambda rho: math.degrees(math.acos((d * d + rho * rho - g * g) / (2 * d * rho)))
        gaps.append((at, x, y, g, half(outer), half(inner)))
    gaps.sort()
    pieces = []
    for a, b in zip(gaps, gaps[1:] + gaps[:1]):
        a0, gx0, gy0, g0, o0, i0 = a
        a1, gx1, gy1, g1, o1, i1 = b
        if a1 < a0:
            a1 += 360
        start, end = a0 + o0, a1 - o1  # along the outer edge, clockwise on screen
        ox0, oy0 = polar(outer, start)
        ox1, oy1 = polar(outer, end)
        ix1, iy1 = polar(inner, a1 - i1)
        ix0, iy0 = polar(inner, a0 + i0)
        large = 1 if end - start > 180 else 0
        pieces.append(
            f"M{f(ox0)},{f(oy0)}"
            f"A{f(outer)},{f(outer)} 0 {large},1 {f(ox1)},{f(oy1)}"
            + gap_arc(gx1, gy1, g1, ox1, oy1, ix1, iy1, inner, outer)
            + f"A{f(inner)},{f(inner)} 0 {large},0 {f(ix0)},{f(iy0)}"
            + gap_arc(gx0, gy0, g0, ix0, iy0, ox0, oy0, inner, outer)
            + "z"
        )
    return "".join(pieces)


SHAPES = [
    ("ring", ring()),
    ("front", circle(*FRONT[:3])),
    ("left", circle(*LEFT[:3])),
    ("right", circle(*RIGHT[:3])),
]


def vector(width, viewport, paths, comment, tint=False, translate=(0, 0)):
    attrs = '\n    android:tint="?android:attr/colorControlNormal"' if tint else ""
    body = "".join(
        f'\n        <path\n            android:fillColor="{color}"\n            android:pathData="{data}" />'
        for color, data in paths
    )
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} Drawn by tools/icon.py; change it there. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{width}dp"
    android:height="{width}dp"
    android:viewportWidth="{viewport}"
    android:viewportHeight="{viewport}"{attrs}>
    <group
        android:translateX="{translate[0]}"
        android:translateY="{translate[1]}">{body}
    </group>
</vector>
"""


def write(path, text):
    path = ROOT / path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


res = "shared/src/main/res"
write(f"{res}/drawable/ic_launcher_foreground.xml",
      vector(108, 108, [(COLORS[n], d) for n, d in SHAPES], "The launcher icon's mark, in color."))
write(f"{res}/drawable/ic_launcher_monochrome.xml",
      vector(108, 108, [("#FFFFFFFF", d) for _, d in SHAPES], "The mark in one color, for themed icons."))
write(f"{res}/values/ic_launcher_colors.xml", f"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">{BACKGROUND}</color>
</resources>
""")
for name in ("ic_launcher", "ic_launcher_round"):
    write(f"{res}/mipmap-anydpi-v26/{name}.xml", """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
""")

# The small mark: a 62-unit square centered on the dial, drawn at 24 dp, so it fills the
# 22 dp that status-bar icons draw in.
mono = [("#FFFFFFFF", d) for _, d in SHAPES]
write("mobile/src/main/res/drawable/ic_notification.xml",
      vector(24, 62, mono, "Status-bar icon for friends' switch notifications: the app's mark.", translate=(-23, -21.25)))
write("wear/src/main/res/drawable/ic_complication.xml",
      vector(24, 62, mono, "The app's mark for complications and the tile; tinted by the watch face.",
             tint=True, translate=(-23, -21.25)))

paths = "".join(f'<path fill="{COLORS[n]}" d="{d}"/>' for n, d in SHAPES)
write("docs/icon/icon.svg",
      # The 72-unit square a launcher shows of the 108-unit canvas, so it matches the launcher.
      f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="512" height="512">'
      f'<rect x="18" y="18" width="72" height="72" fill="{BACKGROUND}"/>{paths}</svg>\n')


def png(size, round_=False):
    """The launcher's view of the icon (its middle 72 units) as a picture, drawn the way the
    design was: the ring with the gaps erased from it, then the members."""
    from PIL import Image, ImageDraw

    scale = 8  # drawn large, then shrunk, for smooth edges
    px = size * scale / 72
    at = lambda x, y: ((x - 18) * px, (y - 18) * px)
    disc = lambda x, y, r: [*at(x - r, y - r), *at(x + r, y + r)]
    ring_layer = Image.new("RGBA", (size * scale,) * 2, (0, 0, 0, 0))
    d = ImageDraw.Draw(ring_layer)
    d.ellipse(disc(CX, CY, R + RING / 2), outline=COLORS["ring"], width=round(RING * px))
    for x, y, _, g in (FRONT, LEFT, RIGHT):
        d.ellipse(disc(x, y, g), fill=(0, 0, 0, 0))
    image = Image.new("RGBA", ring_layer.size, BACKGROUND)
    image.alpha_composite(ring_layer)
    d = ImageDraw.Draw(image)
    for (x, y, r, _), name in ((FRONT, "front"), (LEFT, "left"), (RIGHT, "right")):
        d.ellipse(disc(x, y, r), fill=COLORS[name])
    if round_:
        mask = Image.new("L", image.size, 0)
        ImageDraw.Draw(mask).ellipse([0, 0, *image.size], fill=255)
        image.putalpha(mask)
    return image.resize((size, size), Image.LANCZOS)


png(512).convert("RGB").save(ROOT / "docs/icon/icon-512.png", optimize=True)
png(256, round_=True).save(ROOT / "docs/icon/icon-round.png", optimize=True)
