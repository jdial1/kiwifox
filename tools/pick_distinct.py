"""Greedy pick of popular AMO themes that look distinct on Android.

A theme qualifies when it is image-free (Android drops theme images), sets >= 10 color keys,
has URL text >= 4.5:1 on the field and a visible field (field/bar contrast >= 1.10 in light, as stock Firefox).
Its signature is bar, field and text in light and dark mode (light reused when there is no
dark_theme, as desktop does). It is picked in order of daily users if its CIE76 distance to
every already chosen theme (the bundled ones and Firefox default included) is >= MIN_DE.

Usage: python tools/pick_distinct.py <survey.json> <theme dir> [count] [min_de]
"""
import json
import sys
from pathlib import Path


def lab(hex_color):
    r, g, b = (int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5))
    lin = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in (r, g, b)]
    x = (0.4124 * lin[0] + 0.3576 * lin[1] + 0.1805 * lin[2]) / 0.95047
    y = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
    z = (0.0193 * lin[0] + 0.1192 * lin[1] + 0.9505 * lin[2]) / 1.08883
    f = [t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116 for t in (x, y, z)]
    return 116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2])


def signature(light, dark):
    dark = dark or light
    return [lab(light["bar"]), lab(light["field"]), lab(dark["bar"]), lab(dark["field"])]


def distance(a, b):
    # Mean over the four swatches: bars weigh the most visually, fields next.
    weights = [2, 1, 2, 1]
    return sum(w * sum((p - q) ** 2 for p, q in zip(x, y)) ** 0.5 for w, x, y in zip(weights, a, b)) / sum(weights)


def bundled(theme_dir):
    out = {"firefox-default": signature({"bar": "#F9F9FB", "field": "#EDEDEE"}, {"bar": "#2B2A33", "field": "#1C1B22"})}
    for p in sorted(Path(theme_dir).glob("*.json")):
        d = json.loads(p.read_text(encoding="utf-8"))
        def pal(m):
            return {"bar": m.get("surface", "#FFFFFF"), "field": m.get("surfaceContainerHighest", "#FFFFFF")}
        out["bundled-" + p.stem] = signature(pal(d["light"]), pal(d.get("dark") or d["light"]))
    return out


def main():
    survey = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    count = int(sys.argv[3]) if len(sys.argv) > 3 else 10
    min_de = float(sys.argv[4]) if len(sys.argv) > 4 else 20
    chosen = bundled(sys.argv[2])
    picks = []
    for e in sorted(survey, key=lambda e: -e["users"]):
        light, dark = e.get("light"), e.get("dark")
        why = None
        if not light:
            why = "no colors"
        elif e["color_keys"] < 10:
            why = f"only {e['color_keys']} color keys"
        elif e["images"]:
            why = "look comes from images (" + ", ".join(e["images"]) + "), which Android drops"
        elif min(p["text_contrast"] for p in (light, dark) if p) < 4.5:
            why = "URL text below 4.5:1"
        elif light["field_vs_bar"] < 1.10:
            why = f"field invisible on bar ({light['field_vs_bar']}:1)"
        if why is None:
            sig = signature(light, dark)
            nearest, de = min(((k, distance(sig, s)) for k, s in chosen.items()), key=lambda t: t[1])
            if de < min_de:
                why = f"too close to {nearest} (dE {de:.0f})"
            else:
                chosen[e["slug"]] = sig
                picks.append(e)
                print(f"PICK {e['users']:>7} {e['slug']:28} nearest {nearest} dE {de:.0f}  light {light['bar']}/{light['field']}  dark {(dark or light)['bar']}/{(dark or light)['field']}")
        if why and e["users"] > 20000:
            print(f"skip {e['users']:>7} {e['slug']:28} {why}")
        if len(picks) == count:
            break
    Path(sys.argv[1]).with_name("picks.json").write_text(json.dumps(picks, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
