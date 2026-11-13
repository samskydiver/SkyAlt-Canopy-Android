#!/usr/bin/env python3
"""Draws the dropzone test card and the settings that go with it.

The emulator script puts both into the debug build: a picture taller than
the screen, turned and off centre, with a mark where the target must come
and a painted runway where the runway must come. The picture editor, the
start-point map and the 3D scene then all have to show the rings on the
mark and the runway outline on the painted runway.

Needs Pillow; run it again after changing a number and commit what it writes:
    python3 scripts/dropzone-card.py
"""
import json
import math
import os
from xml.sax.saxutils import escape

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))

# The dropzone settings the card is drawn for (DropzoneSettings).
WIDTH_METERS = 400.0
EAST_OFFSET = 30.0
SOUTH_OFFSET = -20.0
ROTATION = 20.0  # counter-clockwise seen from above
# RunwayPlacement: fractions of the picture's width from its centre, the angle clockwise from its horizontal.
RUNWAY = {"centreX": 0.10, "centreY": 0.18, "length": 0.45, "width": 0.075, "angleDegrees": -35.0}
# The run starts over the target, for the view from above.
CONFIGURATION = {"startEastMeters": 0.0, "startSouthMeters": 0.0, "startAltitudeMeters": 150.0, "windSpeed": 0.0}

CARD = (1000, 1250)  # pixels: 2.5 to the metre, and taller than a landscape screen is at the editor's first zoom
SCALE = 2  # drawn larger and reduced, for smooth lines

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def target_fraction():
    """Where the target falls on the picture: PictureFrame.fraction of the rings' centre."""
    theta = math.radians(ROTATION)
    dx = -EAST_OFFSET / WIDTH_METERS
    dy = -SOUTH_OFFSET / WIDTH_METERS
    return dx * math.cos(theta) - dy * math.sin(theta), dx * math.sin(theta) + dy * math.cos(theta)


def runway_corners(grow=0.0):
    """The runway's corners as picture fractions, grown all round by a fraction of the width."""
    phi = math.radians(RUNWAY["angleDegrees"])
    along = (math.cos(phi), math.sin(phi))
    across = (-math.sin(phi), math.cos(phi))
    l = RUNWAY["length"] / 2 + grow
    w = RUNWAY["width"] / 2 + grow
    return [
        (RUNWAY["centreX"] + along[0] * l * a + across[0] * w * b, RUNWAY["centreY"] + along[1] * l * a + across[1] * w * b)
        for a, b in ((1, 1), (1, -1), (-1, -1), (-1, 1))
    ]


def main():
    w, h = CARD[0] * SCALE, CARD[1] * SCALE
    per_meter = w / WIDTH_METERS
    image = Image.new("RGB", (w, h), (198, 203, 172))
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(FONT, 15 * SCALE)
    small = ImageFont.truetype(FONT, 11 * SCALE)
    big = ImageFont.truetype(FONT, 26 * SCALE)

    def px(fx, fy):
        return w / 2 + fx * w, h / 2 + fy * w

    # 50 m squares from the centre, then the lines with their distances.
    step = 50 * per_meter
    columns = int(w / 2 / step) + 1
    rows = int(h / 2 / step) + 1
    for i in range(-columns, columns):
        for j in range(-rows, rows):
            if (i + j) % 2 == 0:
                draw.rectangle([w / 2 + i * step, h / 2 + j * step, w / 2 + (i + 1) * step, h / 2 + (j + 1) * step], fill=(186, 192, 158))
    for i in range(-columns, columns + 1):
        x = w / 2 + i * step
        draw.line([x, 0, x, h], fill=(128, 134, 108), width=SCALE)
        if abs(i) < columns:
            draw.text((x + 3 * SCALE, 64 * SCALE), f"{i * 50:+d}", font=small, fill=(70, 75, 60))
    for j in range(-rows, rows + 1):
        y = h / 2 + j * step
        draw.line([0, y, w, y], fill=(128, 134, 108), width=SCALE)
        if abs(j) < rows:
            draw.text((66 * SCALE, y + 3 * SCALE), f"{j * 50:+d}", font=small, fill=(70, 75, 60))

    # The corners and the edges, to tell a mirrored or wrongly turned picture.
    block = 60 * SCALE
    for (x, y, colour, name) in (
        (0, 0, (214, 40, 40), "TL"), (w - block, 0, (40, 160, 60), "TR"),
        (0, h - block, (40, 80, 210), "BL"), (w - block, h - block, (235, 200, 30), "BR"),
    ):
        draw.rectangle([x, y, x + block, y + block], fill=colour)
        draw.text((x + block / 2, y + block / 2), name, font=font, fill=(255, 255, 255), anchor="mm")
    draw.text((w / 2, 30 * SCALE), "TOP", font=big, fill=(40, 44, 36), anchor="mm")
    draw.text((w / 2, h - 30 * SCALE), "BOTTOM", font=big, fill=(40, 44, 36), anchor="mm")
    arrow = [(w / 2 + 110 * SCALE, 12 * SCALE), (w / 2 + 96 * SCALE, 46 * SCALE), (w / 2 + 124 * SCALE, 46 * SCALE)]
    draw.polygon(arrow, fill=(40, 44, 36))

    # The picture's own centre.
    cx, cy = px(0, 0)
    draw.line([cx - 14 * SCALE, cy, cx + 14 * SCALE, cy], fill=(0, 0, 0), width=2 * SCALE)
    draw.line([cx, cy - 14 * SCALE, cx, cy + 14 * SCALE], fill=(0, 0, 0), width=2 * SCALE)
    draw.text((cx + 8 * SCALE, cy + 8 * SCALE), "picture centre", font=small, fill=(0, 0, 0))

    # The runway: tarmac of exactly its size inside a pale shoulder 4 m wide, chevrons toward its far end.
    shoulder = 4.0 / WIDTH_METERS
    draw.polygon([px(*c) for c in runway_corners(shoulder)], fill=(250, 240, 150))
    draw.polygon([px(*c) for c in runway_corners()], fill=(72, 74, 82))
    phi = math.radians(RUNWAY["angleDegrees"])
    along = (math.cos(phi), math.sin(phi))
    across = (-math.sin(phi), math.cos(phi))
    for k in (0.55, 0.7, 0.85):
        tip = (RUNWAY["centreX"] + along[0] * RUNWAY["length"] / 2 * k, RUNWAY["centreY"] + along[1] * RUNWAY["length"] / 2 * k)
        back = 0.02
        half = RUNWAY["width"] * 0.3
        a = (tip[0] - along[0] * back + across[0] * half, tip[1] - along[1] * back + across[1] * half)
        b = (tip[0] - along[0] * back - across[0] * half, tip[1] - along[1] * back - across[1] * half)
        draw.line([px(*a), px(*tip), px(*b)], fill=(255, 255, 255), width=2 * SCALE)
    draw.text(px(RUNWAY["centreX"] - along[0] * 0.12, RUNWAY["centreY"] - along[1] * 0.12), "RWY", font=font, fill=(255, 255, 255), anchor="mm")

    # The target: a cross, a circle 25 m out (just clear of the 20 m rings) and one 40 m out.
    fx, fy = target_fraction()
    tx, ty = px(fx, fy)
    blue = (20, 50, 200)
    for radius, width in ((25.0, 3), (40.0, 1)):
        r = radius * per_meter
        draw.ellipse([tx - r, ty - r, tx + r, ty + r], outline=blue, width=width * SCALE)
    reach = 45 * per_meter
    draw.line([tx - reach, ty, tx + reach, ty], fill=blue, width=SCALE)
    draw.line([tx, ty - reach, tx, ty + reach], fill=blue, width=SCALE)
    draw.text((tx, ty - 50 * per_meter), "TARGET", font=font, fill=blue, anchor="mm")

    card = image.resize(CARD, Image.LANCZOS)
    card.save(os.path.join(HERE, "dropzone-card.jpg"), quality=82, optimize=True)

    dropzone = {
        "widthMeters": WIDTH_METERS, "eastOffsetMeters": EAST_OFFSET, "southOffsetMeters": SOUTH_OFFSET,
        "rotationDegrees": ROTATION, "runway": RUNWAY,
    }
    with open(os.path.join(HERE, "dropzone-card-prefs.xml"), "w", encoding="utf-8") as out:
        out.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
        for name, value in (("dropzone", dropzone), ("configuration", CONFIGURATION)):
            text = escape(json.dumps(value, separators=(",", ":")), {'"': "&quot;"})
            out.write(f'    <string name="{name}">{text}</string>\n')
        out.write("</map>\n")

    theta = math.radians(ROTATION)
    c, s = math.cos(theta), math.sin(theta)
    east = EAST_OFFSET + (RUNWAY["centreX"] * c + RUNWAY["centreY"] * s) * WIDTH_METERS
    south = SOUTH_OFFSET + (-RUNWAY["centreX"] * s + RUNWAY["centreY"] * c) * WIDTH_METERS
    print(f"target at picture fraction ({fx:.4f}, {fy:.4f}), pixel ({tx / SCALE:.1f}, {ty / SCALE:.1f})")
    print(f"runway centre {east:.1f} m east, {south:.1f} m south, heading {(90 + RUNWAY['angleDegrees'] - ROTATION) % 360:.0f} deg, "
          f"{RUNWAY['length'] * WIDTH_METERS:.0f} x {RUNWAY['width'] * WIDTH_METERS:.0f} m")


if __name__ == "__main__":
    main()
