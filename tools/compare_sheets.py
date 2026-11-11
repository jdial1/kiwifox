"""Side-by-side sheets: desktop Firefox (tools/desktop_shots.py) next to KiwiFox (screenshots.sh) per theme and mode.

Left column: desktop new tab, address bar dropdown, Settings (each 550 px wide). Right: the KiwiFox render
(toolbar, edit toolbar, home card, menu sheet) scaled to the same height.

Usage: python tools/compare_sheets.py <desktop dir> <mobile dir> <out dir> <desktop label>=<mobile label> ...
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

DESKTOP_STATES = ["newtab", "prompt", "settings"]
PANEL_W = 550
GAP = 10


def main():
    desk, mob, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    out.mkdir(parents=True, exist_ok=True)
    for pair in sys.argv[4:]:
        d_label, m_label = pair.split("=", 1)
        for mode in ("light", "dark"):
            panels = []
            for state in DESKTOP_STATES:
                im = Image.open(desk / f"{d_label}-{mode}-{state}.png").convert("RGB")
                panels.append(im.resize((PANEL_W, round(im.height * PANEL_W / im.width)), Image.LANCZOS))
            col_h = sum(p.height for p in panels) + GAP * (len(panels) - 1)
            phone = Image.open(mob / f"{m_label}-{mode}.png").convert("RGB")
            phone = phone.resize((round(phone.width * col_h / phone.height), col_h), Image.LANCZOS)
            sheet = Image.new("RGB", (PANEL_W + GAP * 3 + phone.width, col_h + 34), (90, 90, 90))
            draw = ImageDraw.Draw(sheet)
            draw.text((8, 10), f"{d_label} ({mode}) - desktop Firefox 159: new tab, address bar dropdown, Settings",
                      fill=(255, 255, 255))
            draw.text((PANEL_W + GAP * 2, 10), "KiwiFox (Android)", fill=(255, 255, 255))
            y = 34
            for p in panels:
                sheet.paste(p, (0, y))
                y += p.height + GAP
            sheet.paste(phone, (PANEL_W + GAP * 2, 34))
            target = out / f"{d_label}-{mode}.png"
            sheet.save(target, optimize=True)
            print(target, sheet.size)


if __name__ == "__main__":
    main()
