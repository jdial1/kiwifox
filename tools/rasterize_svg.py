"""Rasterize SVG theme images to PNG for bundled themes (Android cannot decode SVG).

Renders each SVG at its own width/height with headless Chromium's command line at SCALE device pixels
per CSS px on a transparent background. The PNG keeps the SVG's name with `.png` appended
(`background.svg` -> `background.svg.png`); the bundled-theme generator passes SCALE on as pxPerCss.

Usage: python tools/rasterize_svg.py <out dir> <file.svg>...   (CHROME=<chrome.exe> to override)
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SCALE = 3


def chrome() -> str:
    if os.environ.get("CHROME"):
        return os.environ["CHROME"]
    # The headless shell: its --window-size is the viewport (full Chrome's new headless mode subtracts its UI).
    found = sorted(glob.glob(os.path.expandvars(
        r"%LOCALAPPDATA%\ms-playwright\chromium_headless_shell-*\chrome-headless-shell-win64\chrome-headless-shell.exe")))
    if not found:
        raise SystemExit("no Chromium found; set CHROME")
    return found[-1]


def size_of(svg: str) -> tuple[int, int]:
    tag = re.search(r"<svg\b[^>]*>", svg, re.S).group(0)
    w = re.search(r'\bwidth="([\d.]+)', tag)
    h = re.search(r'\bheight="([\d.]+)', tag)
    if not (w and h):
        raise SystemExit("SVG without width/height: cannot size it like desktop does")
    return round(float(w.group(1))), round(float(h.group(1)))


def main() -> None:
    out = Path(sys.argv[1]).resolve()
    out.mkdir(parents=True, exist_ok=True)
    exe = chrome()
    for path in map(Path, sys.argv[2:]):
        w, h = size_of(path.read_text(encoding="utf-8"))
        target = out / (path.name + ".png")
        with tempfile.TemporaryDirectory() as profile:
            html = Path(profile) / "page.html"
            html.write_text(f'<html><body style="margin:0;background:transparent">'
                            f'<img src="{path.resolve().as_uri()}" width="{w}" height="{h}" style="display:block"></body></html>',
                            encoding="utf-8")
            subprocess.run([exe, "--disable-gpu", "--hide-scrollbars", f"--user-data-dir={profile}",
                            "--allow-file-access-from-files", "--default-background-color=00000000",
                            f"--force-device-scale-factor={SCALE}", f"--window-size={w},{h}",
                            f"--screenshot={target}", html.as_uri()],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
        print(f"{target}  {w}x{h} css px @{SCALE}x")


if __name__ == "__main__":
    main()
