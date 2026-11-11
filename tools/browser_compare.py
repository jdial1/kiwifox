"""Side-by-side sheets for the browser look-alike themes: the real browser (top: new tab page header
strip) next to KiwiFox's render of the matching bundled theme, light and dark.
Usage: python tools/browser_compare.py <browsers dir> <desktop dir> <shots dir> <out dir>"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

browsers, desktop, shots, out = (Path(a) for a in sys.argv[1:5])
PAIRS = {
    "chrome": lambda m: browsers / f"chrome-{m}-newtab.png",
    "firefox-desktop": lambda m: desktop / f"default-{m}-newtab.png",
    "edge": lambda m: None,  # Edge crashes on launch on the capture PC: no reference
}
W = 1100
for slug, ref in PAIRS.items():
    cols = []
    for mode in ("light", "dark"):
        mob = Image.open(shots / f"bundled-{slug}-{mode}.png").convert("RGB")
        mob = mob.resize((W // 2 - 10, int(mob.height * (W // 2 - 10) / mob.width)))
        r = ref(mode)
        top = Image.open(r).convert("RGB") if r and r.is_file() else None
        if top is not None:
            top = top.resize((W // 2 - 10, int(top.height * (W // 2 - 10) / top.width)))
        h = (top.height + 20 if top else 40) + mob.height
        col = Image.new("RGB", (W // 2, h), "#808080")
        y = 0
        if top:
            col.paste(top, (5, 0)); y = top.height + 20
        else:
            ImageDraw.Draw(col).text((10, 12), "no browser reference (approximate theme)", fill="white"); y = 40
        col.paste(mob, (5, y))
        cols.append(col)
    sheet = Image.new("RGB", (W, max(c.height for c in cols)), "#808080")
    for i, c in enumerate(cols):
        sheet.paste(c, (i * W // 2, 0))
    out.mkdir(parents=True, exist_ok=True)
    sheet.save(out / f"browser-{slug}.png")
    print("wrote", out / f"browser-{slug}.png")
