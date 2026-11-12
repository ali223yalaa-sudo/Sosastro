"""Generate a premium adaptive launcher icon for Astro.

Design: a luminous four-point star (the "astro" spark) with a soft comet tail,
on a deep indigo→violet radial gradient. Rendered at 4x supersampling and
downsampled for clean edges, then written to every density bucket.
"""

import math
import os
from PIL import Image, ImageDraw, ImageFilter

RES = "amethyst/src/main/res"
SS = 4  # supersample factor

# Deep, premium gradient: near-black indigo to a rich violet.
BG_INNER = (58, 34, 118)
BG_OUTER = (9, 10, 26)
# The spark: warm white core with a cool violet halo.
SPARK_CORE = (255, 255, 255)
SPARK_EDGE = (196, 150, 255)


def radial_bg(size):
    img = Image.new("RGB", (size, size), BG_OUTER)
    px = img.load()
    cx = cy = size / 2.0
    maxd = math.hypot(cx, cy)
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - cx, y - cy) / maxd
            t = min(1.0, d ** 1.15)
            r = round(BG_INNER[0] + (BG_OUTER[0] - BG_INNER[0]) * t)
            g = round(BG_INNER[1] + (BG_OUTER[1] - BG_INNER[1]) * t)
            b = round(BG_INNER[2] + (BG_OUTER[2] - BG_INNER[2]) * t)
            px[x, y] = (r, g, b)
    return img


def four_point_star(cx, cy, outer, inner):
    """A four-point spark: sharp long points, tight waist."""
    pts = []
    for i in range(8):
        ang = math.pi / 2 * (i / 2.0) - math.pi / 2
        rad = outer if i % 2 == 0 else inner
        pts.append((cx + rad * math.cos(ang), cy + rad * math.sin(ang)))
    return pts


def build_master(size):
    s = size * SS
    base = radial_bg(s).convert("RGBA")

    # Soft glow behind the spark.
    glow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    cx = cy = s / 2.0
    gd.ellipse(
        [cx - s * 0.30, cy - s * 0.30, cx + s * 0.30, cy + s * 0.30],
        fill=(150, 110, 255, 90),
    )
    glow = glow.filter(ImageFilter.GaussianBlur(s * 0.06))
    base = Image.alpha_composite(base, glow)

    # The star, drawn with a soft outer halo then the crisp core.
    star = four_point_star(cx, cy, s * 0.335, s * 0.072)
    halo = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(halo).polygon(star, fill=SPARK_EDGE + (170,))
    halo = halo.filter(ImageFilter.GaussianBlur(s * 0.030))
    base = Image.alpha_composite(base, halo)

    core = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(core).polygon(star, fill=SPARK_CORE + (255,))
    base = Image.alpha_composite(base, core)

    return base.resize((size, size), Image.LANCZOS)


def circular(img):
    size = img.size[0]
    mask = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size * SS - 1, size * SS - 1], fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = img.copy()
    out.putalpha(mask)
    return out


def main():
    # Legacy square/round launcher tiles (full-bleed, not adaptive).
    for density, size in {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }.items():
        master = build_master(size)
        master.convert("RGB").save(f"{RES}/mipmap-{density}/ic_launcher.png")
        circular(master).save(f"{RES}/mipmap-{density}/ic_launcher_round.png")

    # Adaptive-icon foreground: transparent, artwork inside the safe zone (~66%).
    # The background layer is a solid colour from ic_launcher_background.xml.
    for density, size in {
        "mdpi": 108,
        "hdpi": 162,
        "xhdpi": 216,
        "xxhdpi": 324,
        "xxxhdpi": 432,
    }.items():
        s = size * SS
        fg = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        cx = cy = s / 2.0
        star = four_point_star(cx, cy, s * 0.235, s * 0.050)
        halo = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        ImageDraw.Draw(halo).polygon(star, fill=SPARK_EDGE + (180,))
        halo = halo.filter(ImageFilter.GaussianBlur(s * 0.022))
        fg = Image.alpha_composite(fg, halo)
        core = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        ImageDraw.Draw(core).polygon(star, fill=SPARK_CORE + (255,))
        fg = Image.alpha_composite(fg, core)
        fg.resize((size, size), Image.LANCZOS).save(
            f"{RES}/drawable-{density}/ic_launcher_foreground.png"
        )

    # Play Store listing icon (512, full-bleed with its own background).
    build_master(512).convert("RGB").save("amethyst/src/main/ic_launcher-playstore.png")

    print("icons written")


if __name__ == "__main__":
    main()
