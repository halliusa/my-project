#!/usr/bin/env python3
"""
Generates every PNG used by the Sandevistan: Apogee mod (pure standard library, no Pillow needed).

    python generate_assets.py                      # create all textures
    python generate_assets.py --use-custom-sounds  # switch sounds.json to your own .ogg files (see README)

Outputs (relative to this script):
    src/main/resources/assets/sandevistan_apogee/textures/item/sandevistan_apogee.png   16x16
    src/main/resources/assets/sandevistan_apogee/textures/particle/spark.png            16x16
    src/main/resources/assets/sandevistan_apogee/textures/entity/ghost.png              64x64
    src/main/resources/assets/sandevistan_apogee/icon.png                               128x128
"""
import json
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "sandevistan_apogee")


def write_png(path, width, height, pixels):
    """pixels: list of rows, each row a list of (r, g, b, a)."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for r, g, b, a in row:
            raw.extend((r, g, b, a))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    print("wrote", os.path.relpath(path, ROOT))


def canvas(w, h, fill=(0, 0, 0, 0)):
    return [[fill for _ in range(w)] for _ in range(h)]


def mix(c1, c2, t):
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(len(c1)))


def make_item():
    """A chip-shaped implant with a glowing red-orange core and cyan circuitry."""
    img = canvas(16, 16)
    body_dark = (14, 18, 34, 255)
    body_mid = (26, 34, 62, 255)
    edge = (62, 76, 124, 255)
    cyan = (43, 217, 255, 255)
    cyan_dim = (26, 120, 160, 255)
    gold = (255, 190, 80, 255)

    # body
    for y in range(3, 13):
        for x in range(2, 14):
            img[y][x] = mix(body_dark, body_mid, (y - 3) / 9.0)
    # rounded corners
    for (x, y) in ((2, 3), (13, 3), (2, 12), (13, 12)):
        img[y][x] = (0, 0, 0, 0)
    # border
    for x in range(3, 13):
        img[3][x] = edge
        img[12][x] = (36, 44, 78, 255)
    for y in range(4, 12):
        img[y][2] = edge
        img[y][13] = (36, 44, 78, 255)
    # pins
    for x in (4, 6, 9, 11):
        img[1][x] = gold
        img[2][x] = gold
        img[13][x] = gold
        img[14][x] = gold
    # circuit traces
    for x in range(3, 6):
        img[7][x] = cyan_dim
    for x in range(10, 13):
        img[8][x] = cyan_dim
    for y in range(4, 6):
        img[y][7] = cyan_dim
    for y in range(10, 12):
        img[y][8] = cyan_dim
    img[7][3] = cyan
    img[8][12] = cyan
    img[4][7] = cyan
    img[11][8] = cyan
    # glowing core 4x4
    core = {
        (6, 6): (255, 120, 60, 255), (7, 6): (255, 170, 90, 255), (8, 6): (255, 170, 90, 255), (9, 6): (255, 120, 60, 255),
        (6, 7): (255, 170, 90, 255), (7, 7): (255, 240, 200, 255), (8, 7): (255, 235, 190, 255), (9, 7): (255, 150, 80, 255),
        (6, 8): (255, 150, 80, 255), (7, 8): (255, 235, 190, 255), (8, 8): (255, 240, 200, 255), (9, 8): (255, 170, 90, 255),
        (6, 9): (230, 74, 43, 255), (7, 9): (255, 120, 60, 255), (8, 9): (255, 120, 60, 255), (9, 9): (230, 74, 43, 255),
    }
    for (x, y), c in core.items():
        img[y][x] = c
    # core halo
    halo = (255, 74, 43, 140)
    for x in range(5, 11):
        if img[5][x][3] == 255 and img[5][x] == mix(body_dark, body_mid, 2 / 9.0):
            img[5][x] = halo
        if img[10][x] == mix(body_dark, body_mid, 7 / 9.0):
            img[10][x] = halo
    for y in range(6, 10):
        if img[y][5] == mix(body_dark, body_mid, (y - 3) / 9.0):
            img[y][5] = halo
        if img[y][10] == mix(body_dark, body_mid, (y - 3) / 9.0):
            img[y][10] = halo
    # top-left highlight
    for x in range(3, 8):
        img[4][x] = mix(img[4][x][:3] + (255,), (120, 140, 200, 255), 0.25) if img[4][x][3] == 255 else img[4][x]
    return img


def make_spark():
    """White radial glow, tinted by the particle class at runtime."""
    size = 16
    img = canvas(size, size)
    c = (size - 1) / 2.0
    for y in range(size):
        for x in range(size):
            d = ((x - c) ** 2 + (y - c) ** 2) ** 0.5 / c
            if d <= 1.0:
                a = max(0.0, 1.0 - d) ** 1.6
                v = 255
                img[y][x] = (v, v, v, int(a * 255))
    # bright cross core
    for i in range(3, 13):
        for (x, y) in ((i, 7), (i, 8), (7, i), (8, i)):
            r, g, b, a = img[y][x]
            img[y][x] = (255, 255, 255, min(255, a + 70))
    return img


def make_ghost():
    """Flat white holographic texture with subtle scan bands; the model is tinted per afterimage in code."""
    size = 64
    img = canvas(size, size, (255, 255, 255, 255))
    for y in range(size):
        v = 255 if (y % 4) < 2 else 205
        for x in range(size):
            img[y][x] = (v, v, v, 255)
    return img


def make_icon():
    """128x128 mod icon: dark gradient with the chip scaled 6x and a neon frame."""
    size = 128
    chip = make_item()
    img = canvas(size, size)
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2.0 * size)
            base = mix((9, 12, 28), (38, 12, 24), t)
            img[y][x] = (base[0], base[1], base[2], 255)
    scale = 6
    ox = (size - 16 * scale) // 2
    oy = (size - 16 * scale) // 2
    for y in range(16):
        for x in range(16):
            r, g, b, a = chip[y][x]
            if a == 0:
                continue
            for dy in range(scale):
                for dx in range(scale):
                    px, py = ox + x * scale + dx, oy + y * scale + dy
                    if a == 255:
                        img[py][px] = (r, g, b, 255)
                    else:
                        br, bg, bb, _ = img[py][px]
                        k = a / 255.0
                        img[py][px] = (int(br + (r - br) * k), int(bg + (g - bg) * k), int(bb + (b - bb) * k), 255)
    # neon frame
    for i in range(size):
        for t in range(2):
            col = (43, 217, 255, 255) if i < size // 2 else (255, 74, 43, 255)
            img[t][i] = col
            img[size - 1 - t][i] = (255, 74, 43, 255) if i < size // 2 else (43, 217, 255, 255)
            img[i][t] = (43, 217, 255, 255) if i < size // 2 else (255, 74, 43, 255)
            img[i][size - 1 - t] = (255, 74, 43, 255) if i < size // 2 else (43, 217, 255, 255)
    return img


def generate_textures():
    write_png(os.path.join(ASSETS, "textures", "item", "sandevistan_apogee.png"), 16, 16, make_item())
    write_png(os.path.join(ASSETS, "textures", "particle", "spark.png"), 16, 16, make_spark())
    write_png(os.path.join(ASSETS, "textures", "entity", "ghost.png"), 64, 64, make_ghost())
    write_png(os.path.join(ASSETS, "icon.png"), 128, 128, make_icon())


CUSTOM_EVENTS = {
    "sandevistan.activate": ("activate", "subtitles.sandevistan_apogee.activate", 1.0),
    "sandevistan.loop": ("loop", "subtitles.sandevistan_apogee.loop", 0.8),
    "sandevistan.deactivate": ("deactivate", "subtitles.sandevistan_apogee.deactivate", 1.0),
    "sandevistan.ready": ("ready", "subtitles.sandevistan_apogee.ready", 0.8),
}


def use_custom_sounds():
    folder = os.path.join(ASSETS, "sounds", "sandevistan")
    missing = [n for (n, _, _) in CUSTOM_EVENTS.values() if not os.path.isfile(os.path.join(folder, n + ".ogg"))]
    if missing:
        print("Missing .ogg files in", os.path.relpath(folder, ROOT))
        for n in missing:
            print("  -", n + ".ogg")
        print("sounds.json was NOT changed.")
        sys.exit(1)
    data = {}
    for event, (name, subtitle, volume) in CUSTOM_EVENTS.items():
        data[event] = {
            "subtitle": subtitle,
            "sounds": [{"name": "sandevistan_apogee:sandevistan/" + name, "volume": volume}],
        }
    path = os.path.join(ASSETS, "sounds.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    print("sounds.json now points to your .ogg files.")


if __name__ == "__main__":
    if "--use-custom-sounds" in sys.argv:
        use_custom_sounds()
    else:
        generate_textures()
