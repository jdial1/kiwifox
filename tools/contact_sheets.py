"""Pairs the light and dark renders of each theme (from KiwiThemeScreenshotTest / screenshots.sh)
side by side at 1/3 scale (1x of the 360 dp frame) for the README.

Usage: python tools/contact_sheets.py <shot dir> <out dir>
"""
import sys
from pathlib import Path

from PIL import Image

SCALE = 3  # renders are xxhdpi (3x); README images are 1x
GAP = 12


def main() -> None:
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    labels = sorted({p.name.rsplit("-", 1)[0] for p in src.glob("*-light.png")})
    for label in labels:
        light = Image.open(src / f"{label}-light.png").convert("RGB")
        dark = Image.open(src / f"{label}-dark.png").convert("RGB")
        w, h = light.width // SCALE, max(light.height, dark.height) // SCALE
        sheet = Image.new("RGB", (w * 2 + GAP, h), (128, 128, 128))
        sheet.paste(light.resize((w, light.height // SCALE), Image.LANCZOS), (0, 0))
        sheet.paste(dark.resize((w, dark.height // SCALE), Image.LANCZOS), (w + GAP, 0))
        target = out / f"{label}.png"
        sheet.save(target, optimize=True)
        print(f"{target}  {sheet.size[0]}x{sheet.size[1]}  {target.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
