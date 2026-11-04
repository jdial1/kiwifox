#!/usr/bin/env python3
"""Convert a desktop Firefox static theme (.xpi or manifest.json) into a
KiwiFox theme JSON that inject_theme.py can apply.

Usage: import_theme.py <theme.xpi | theme.zip | manifest.json> <out.json>

Accepts AMO static themes, Firefox Color exports (.zip/.xpi) and Mozilla's
bundled themes. userChrome.css themes are not themes in this sense and are
not accepted; see README.

Desktop rules mirrored here come from Firefox 159 source:
  - manifest key renames:  LightweightThemeManager.sys.mjs  loadColors()
  - missing-key defaults:  LightweightThemeConsumer.sys.mjs toolkitVariableMap
Requires: pip install webcolors
"""
import colorsys
import json
import re
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

import webcolors


class ThemeImportError(Exception):
    pass


@dataclass(frozen=True)
class RGBA:
    r: int
    g: int
    b: int
    a: float = 1.0

    def opaque(self) -> "RGBA":
        return RGBA(self.r, self.g, self.b, 1.0)

    def over(self, base: "RGBA") -> "RGBA":
        """Alpha-composite this color onto an opaque base, as the desktop browser does when layering."""
        mix = lambda top, bottom: round(top * self.a + bottom * (1 - self.a))
        return RGBA(mix(self.r, base.r), mix(self.g, base.g), mix(self.b, base.b), 1.0)

    def hex(self) -> str:
        if self.a != 1.0:
            raise ThemeImportError(f"internal: tried to emit non-opaque color {self}")
        return f"#{self.r:02X}{self.g:02X}{self.b:02X}"

    def luminance(self) -> float:
        def channel(c: int) -> float:
            c = c / 255
            return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
        return 0.2126 * channel(self.r) + 0.7152 * channel(self.g) + 0.0722 * channel(self.b)


WHITE, BLACK = RGBA(255, 255, 255), RGBA(0, 0, 0)
# LightweightThemeConsumer: toolbar_field fallbackColor "rgba(255, 255, 255, 0.8)"
DESKTOP_FIELD_FALLBACK = RGBA(255, 255, 255, 0.8)

# WCAG 2.x minimums: 4.5:1 for text, 3:1 for icons.
MIN_TEXT_CONTRAST = 4.5
MIN_ICON_CONTRAST = 3.0

# Every `theme.colors` key in desktop Firefox 159's schema (schemas/theme.json).
DESKTOP_KEYS = {
    "accentcolor", "bookmark_text", "button_background_active", "button_background_hover",
    "frame", "frame_inactive", "icons", "icons_attention", "ntp_background",
    "ntp_card_background", "ntp_text", "popup", "popup_border", "popup_highlight",
    "popup_highlight_text", "popup_text", "sidebar", "sidebar_border", "sidebar_highlight",
    "sidebar_highlight_text", "sidebar_text", "tab_background_separator",
    "tab_background_text", "tab_line", "tab_loading", "tab_selected", "tab_text", "textcolor",
    "toolbar", "toolbar_bottom_separator", "toolbar_field", "toolbar_field_border",
    "toolbar_field_border_focus", "toolbar_field_focus", "toolbar_field_highlight",
    "toolbar_field_highlight_text", "toolbar_field_separator", "toolbar_field_text",
    "toolbar_field_text_focus", "toolbar_text", "toolbar_top_separator",
    "toolbar_vertical_separator",
}
# The subset the Android toolbar can show (see scheme_from_colors).
SUPPORTED_KEYS = {"frame", "toolbar", "toolbar_field", "toolbar_field_text", "toolbar_field_border_focus"}


def parse_color(key: str, value) -> RGBA:
    """Parse the color forms desktop accepts: RGB(A) arrays and CSS color strings."""
    if isinstance(value, list):
        if len(value) not in (3, 4):
            raise ThemeImportError(f"{key}: color array must have 3 or 4 entries, got {value}")
        return RGBA(*value[:3], float(value[3]) if len(value) == 4 else 1.0)
    text = str(value).strip().lower()
    if text == "transparent":
        return RGBA(0, 0, 0, 0.0)
    if m := re.fullmatch(r"#([0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})", text):
        digits = m.group(1)
        if len(digits) in (3, 4):
            digits = "".join(c * 2 for c in digits)
        alpha = int(digits[6:8], 16) / 255 if len(digits) == 8 else 1.0
        return RGBA(int(digits[0:2], 16), int(digits[2:4], 16), int(digits[4:6], 16), alpha)
    if m := re.fullmatch(r"rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)(?:[\s,/]+([\d.]+%?))?\s*\)", text):
        r, g, b, a = m.groups()
        alpha = 1.0 if a is None else (float(a[:-1]) / 100 if a.endswith("%") else float(a))
        return RGBA(round(float(r)), round(float(g)), round(float(b)), alpha)
    if m := re.fullmatch(r"hsla?\(\s*([\d.]+)(?:deg)?[\s,]+([\d.]+)%[\s,]+([\d.]+)%(?:[\s,/]+([\d.]+%?))?\s*\)", text):
        h, sat, lum, a = m.groups()
        r, g, b = colorsys.hls_to_rgb(float(h) / 360, float(lum) / 100, float(sat) / 100)
        alpha = 1.0 if a is None else (float(a[:-1]) / 100 if a.endswith("%") else float(a))
        return RGBA(round(r * 255), round(g * 255), round(b * 255), alpha)
    try:
        named = webcolors.name_to_rgb(text)
    except ValueError:
        raise ThemeImportError(f"{key}: unsupported color syntax {value!r} (convert it to hex)") from None
    return RGBA(named.red, named.green, named.blue)


def contrast(a: RGBA, b: RGBA) -> float:
    hi, lo = sorted((a.luminance(), b.luminance()), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


def require_contrast(label: str, fg: RGBA, bg: RGBA, minimum: float) -> None:
    ratio = contrast(fg, bg)
    if ratio < minimum:
        raise ThemeImportError(
            f"{label}: contrast {ratio:.2f}:1 between {fg.hex()} and {bg.hex()} is below {minimum}:1")


def scheme_from_colors(colors: dict) -> dict[str, str]:
    """Map one desktop `colors` object onto the Material roles the Android toolbar reads."""
    get = lambda key: parse_color(key, colors[key]) if colors.get(key) else None

    # Desktop --lwt-accent-color processColor: missing or fully transparent frame -> white; alpha dropped.
    frame = get("frame")
    frame = WHITE if frame is None or frame.a == 0 else frame.opaque()

    # Android has one bar where desktop has frame + toolbar. Design decision:
    # the bar shows desktop's toolbar layered over the frame, as desktop draws it.
    bar = get("toolbar").over(frame) if colors.get("toolbar") else frame

    # Desktop: toolbar_field missing -> rgba(255,255,255,0.8) over what is behind it.
    field = (get("toolbar_field") or DESKTOP_FIELD_FALLBACK).over(bar)
    # Desktop: toolbar_field_text missing -> black.
    field_text = (get("toolbar_field_text") or BLACK).over(field)

    # The Android toolbar paints URL text AND bar icons with the same role
    # (onSurface). It cannot show two colors, so both must be readable.
    require_contrast("URL text on address field", field_text, field, MIN_TEXT_CONTRAST)
    require_contrast("toolbar icons on bar (use the URL text color)", field_text, bar, MIN_ICON_CONTRAST)

    roles = {
        "background": bar, "surface": bar, "surfaceBright": bar, "surfaceContainer": bar,
        "surfaceContainerLow": bar, "surfaceContainerLowest": bar,
        "surfaceContainerHigh": field, "surfaceContainerHighest": field,
        "onBackground": field_text, "onSurface": field_text, "onSurfaceVariant": field_text,
    }
    if colors.get("toolbar_field_border_focus"):
        accent = get("toolbar_field_border_focus").over(bar)
        roles["primary"] = roles["tertiary"] = accent
    return {role: color.hex() for role, color in roles.items()}


def read_manifest(path: Path) -> dict:
    if path.suffix in (".xpi", ".zip"):
        with zipfile.ZipFile(path) as z:
            return json.loads(z.read("manifest.json"))
    return json.loads(path.read_text())


def convert(manifest: dict) -> tuple[dict, list[str]]:
    if "theme" not in manifest:
        raise ThemeImportError("manifest has no `theme` key; this is not a static theme")
    colors = manifest["theme"].get("colors") or {}
    dark_colors = (manifest.get("dark_theme") or {}).get("colors")

    if not colors and not dark_colors:
        scheme = (manifest["theme"].get("properties") or {}).get("color_scheme")
        raise ThemeImportError(
            f"theme defines no colors (color_scheme={scheme!r}); on desktop it only picks the built-in "
            "light or dark look. Nothing to inject: choose that mode in Firefox settings instead.")
    experiment_keys = set(((manifest.get("theme_experiment") or {}).get("colors") or {}))
    all_keys = set(colors) | set(dark_colors or {})
    dropped = sorted(all_keys - SUPPORTED_KEYS)
    # Desktop logs "Unrecognized theme property" for keys outside the schema or theme_experiment, then ignores them.
    for key in sorted(all_keys - DESKTOP_KEYS - experiment_keys):
        print(f"  warning: unrecognized color key {key!r} (desktop ignores it too)")
    if manifest["theme"].get("images"):
        dropped.append("images (Android toolbar has no image layer)")

    light = scheme_from_colors(colors)
    # Desktop: a theme without dark_theme applies the same colors in dark mode.
    dark = scheme_from_colors(dark_colors) if dark_colors else light
    return {"_source": manifest.get("name", "imported theme"), "light": light, "dark": dark}, dropped


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    try:
        theme, dropped = convert(read_manifest(src))
    except ThemeImportError as e:
        sys.exit(f"IMPORT FAILED: {e}")
    out.write_text(json.dumps(theme, indent=2) + "\n")
    print(f"wrote {out}")
    for key in dropped:
        print(f"  dropped: {key}")


if __name__ == "__main__":
    main()
