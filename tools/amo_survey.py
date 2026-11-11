"""Survey the most-used addons.mozilla.org static themes for the KiwiFox catalogue.

Downloads each theme's .xpi (manifest only is used), and reports what Android can show:
bar / field / text colors (desktop rules: toolbar over frame, field over bar), whether it has a
dark_theme block, how many color keys it sets, and whether it depends on images.

Usage: python tools/amo_survey.py <search json>... --out <dir>
Writes <dir>/survey.json and downloads to <dir>/xpi/.
"""
import io
import json
import sys
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import import_theme as it  # noqa: E402  (desktop color parsing rules)

UA = {"User-Agent": "KiwiFox-theme-survey/1.0"}


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read()


def palette(colors: dict) -> dict:
    def get(k):
        v = colors.get(k)
        return None if v in (None, "") else it.parse_color(k, v)
    white, black = it.RGBA(255, 255, 255), it.RGBA(0, 0, 0)
    frame = get("frame")
    frame = white if frame is None or frame.a == 0 else frame.opaque()
    bar = get("toolbar").over(frame) if get("toolbar") else frame
    field = (get("toolbar_field") or it.RGBA(255, 255, 255, 0.8)).over(bar)
    text = (get("toolbar_field_text") or black).over(field)
    accent = get("toolbar_field_border_focus") or get("button_background_active") or get("tab_line")
    return {
        "bar": bar.hex(), "field": field.hex(), "text": text.hex(),
        "accent": accent.over(bar).hex() if accent else None,
        "text_contrast": round(it.contrast(text, field), 2),
        "field_vs_bar": round(it.contrast(field, bar), 2),
    }


def main() -> None:
    args = sys.argv[1:]
    out = Path(args[args.index("--out") + 1])
    searches = [a for a in args if a != "--out" and Path(a).suffix == ".json" and Path(a).exists()]
    (out / "xpi").mkdir(parents=True, exist_ok=True)
    results = []
    for s in searches:
        for a in json.loads(Path(s).read_text(encoding="utf-8"))["results"]:
            slug = a["slug"]
            name = a["name"].get("en-US") or next(iter(a["name"].values()))
            entry = {"slug": slug, "name": name, "users": a["average_daily_users"],
                     "rating": round(a["ratings"]["average"], 1), "url": a["url"]}
            try:
                xpi = out / "xpi" / f"{slug}.xpi"
                if not xpi.exists():
                    xpi.write_bytes(fetch(a["current_version"]["file"]["url"]))
                with zipfile.ZipFile(io.BytesIO(xpi.read_bytes())) as z:
                    name_in_zip = next(n for n in z.namelist() if n.endswith("manifest.json"))
                    manifest = json.loads(z.read(name_in_zip).decode("utf-8-sig"))
                theme = manifest.get("theme", {})
                colors = theme.get("colors", {})
                dark = (manifest.get("dark_theme") or {}).get("colors")
                entry.update(
                    color_keys=len(colors), images=sorted((theme.get("images") or {}).keys()),
                    has_dark=bool(dark), light=palette(colors) if colors else None,
                    dark=palette(dark) if dark else None,
                )
            except Exception as e:  # report and continue; the survey is about what converts
                entry["error"] = f"{type(e).__name__}: {e}"
            results.append(entry)
            print(json.dumps({k: entry.get(k) for k in ("slug", "users", "color_keys", "has_dark", "light", "error")}))
    (out / "survey.json").write_text(json.dumps(results, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
