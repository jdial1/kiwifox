"""Desktop vs KiwiFox header sheets for image themes: desktop's tab strip + toolbar + a bit of the new tab
page above KiwiFox's status bar + toolbar + top of the homepage, light and dark side by side.

Usage: python tools/header_sheets.py <desktop dir> <mobile dir> <out dir> <desktop label>=<mobile label> ...
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

W = 560  # each mode column


def column(desk, mob, d, m, mode):
    dt = Image.open(desk / f"{d}-{mode}-newtab.png").convert("RGB").crop((0, 0, 1100, 130))
    dt = dt.resize((W, round(130 * W / 1100)), Image.LANCZOS)
    mb = Image.open(mob / f"{m}-{mode}.png").convert("RGB").crop((0, 0, 1080, 420))
    mb = mb.resize((W // 2, round(420 * (W // 2) / 1080)), Image.LANCZOS)
    col = Image.new("RGB", (W, dt.height + mb.height + 30), (70, 70, 70))
    draw = ImageDraw.Draw(col)
    draw.text((4, 2), f"desktop Firefox 159 ({mode})", fill=(255, 255, 255))
    col.paste(dt, (0, 14))
    draw.text((4, dt.height + 16), "KiwiFox", fill=(255, 255, 255))
    col.paste(mb, (0, dt.height + 28))
    return col


def main():
    desk, mob, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    out.mkdir(parents=True, exist_ok=True)
    for pair in sys.argv[4:]:
        d, m = pair.split("=", 1)
        cols = [column(desk, mob, d, m, mode) for mode in ("light", "dark")]
        sheet = Image.new("RGB", (W * 2 + 10, max(c.height for c in cols)), (70, 70, 70))
        sheet.paste(cols[0], (0, 0))
        sheet.paste(cols[1], (W + 10, 0))
        target = out / f"image-{d}.png"
        sheet.save(target, optimize=True)
        print(target, sheet.size)


if __name__ == "__main__":
    main()
