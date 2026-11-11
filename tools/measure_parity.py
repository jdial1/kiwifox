"""Measure desktop (tools/desktop_shots.py) vs KiwiFox (screenshots.sh) colors per surface and print a
markdown table with the CIE76 difference for each pair.

Mobile frame layout (KiwiThemeScreenshotTest, 3x): status 0-72, toolbar 72-264, home 264-1014,
edit toolbar 1014-1206 (focus ring + field), suggestions 1206-1806, menu sheet from 1806.
Desktop (1100x720): tab strip / frame y<44, toolbar 44-85, content below; dropdown under the address bar.

Usage: python tools/measure_parity.py <desktop dir> <mobile dir> <desktop label>=<mobile label> ...
"""
import sys
from collections import Counter
from pathlib import Path

from PIL import Image


def lab(rgb):
    def lin(c):
        c /= 255
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = map(lin, rgb)
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
    f = [t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116 for t in (x, y, z)]
    return 116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2])


def de(a, b):
    return sum((p - q) ** 2 for p, q in zip(lab(a), lab(b))) ** 0.5


def dominant(im, box):
    return Counter(im.crop(box).get_flattened_data()).most_common(1)[0][0]


def ring(im, box):
    """The most common strongly saturated color in a box: the focus ring."""
    counts = Counter(p for p in im.crop(box).get_flattened_data() if max(p) - min(p) > 60)
    return counts.most_common(1)[0][0] if counts else None


def hx(c):
    return "-" if c is None else "#%02X%02X%02X" % c


def main():
    desk, mob = Path(sys.argv[1]), Path(sys.argv[2])
    rows = []
    for pair in sys.argv[3:]:
        d, m = pair.split("=", 1)
        for mode in ("light", "dark"):
            nt = Image.open(desk / f"{d}-{mode}-newtab.png").convert("RGB")
            pr = Image.open(desk / f"{d}-{mode}-prompt.png").convert("RGB")
            mo = Image.open(mob / f"{m}-{mode}.png").convert("RGB")
            one_line = d == "cascade"  # Cascade puts the address bar left of the tabs in one row
            desktop = {
                "status bar / tab strip": dominant(nt, (700, 2, 1090, 8) if one_line else (300, 2, 450, 10)),
                "new tab background": dominant(nt, (200, 200, 900, 600)),
                "dropdown": dominant(pr, (200, 100, 300, 240) if one_line else (600, 100, 900, 240)),
                # The ring's own 2 px band along the top of the focused address bar.
                "focus ring": dominant(pr, (60, 1, 300, 3) if one_line else (400, 48, 800, 50)),
                "field while typing": dominant(pr, (100, 26, 140, 34) if one_line else (600, 54, 880, 76)),
            }
            mobile = {
                "status bar / tab strip": dominant(mo, (500, 10, 1000, 60)),
                "new tab background": dominant(mo, (980, 280, 1060, 310)),
                "dropdown": dominant(mo, (900, 1400, 1060, 1500)),
                "focus ring": dominant(mo, (900, 1042, 1060, 1050)),
                "field while typing": dominant(mo, (900, 1080, 1000, 1150)),
            }
            for k in desktop:
                a, b = desktop[k], mobile[k]
                diff = "-" if a is None or b is None else f"{de(a, b):.1f}"
                rows.append((f"{d} {mode}", k, hx(a), hx(b), diff))
    print("| Theme | Surface | Desktop | KiwiFox | ΔE |")
    print("|---|---|---|---|---|")
    for r in rows:
        print("| " + " | ".join(r) + " |")


if __name__ == "__main__":
    main()
