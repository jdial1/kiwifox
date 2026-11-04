#!/usr/bin/env python3
"""Inject a theme into a Firefox for Android source tree.

Usage: inject_theme.py <firefox-src-root> <theme.json> <app.json>

Every edit is anchored to text that exists in the upstream source. If an
anchor is missing (upstream renamed or moved something), this script exits
with an error instead of guessing. Fix the anchor, then rerun.
"""
import json
import re
import sys
from pathlib import Path

ACORN_COLORS = "mobile/android/android-components/components/compose/base/src/main/java/mozilla/components/compose/base/theme/AcornColors.kt"
FENIX_GRADLE = "mobile/android/fenix/app/build.gradle"

SCHEME_FUNCTIONS = {
    "light": ("fun acornLightColorScheme(): ColorScheme =", "lightColorScheme("),
    "dark": ("fun acornDarkColorScheme(): ColorScheme =", "darkColorScheme("),
}


class AnchorMissing(Exception):
    pass


def extract_block(text: str, start_anchor: str, end_anchor: str) -> tuple[int, int]:
    """Return (start, end) offsets of text between two anchors. Each must occur exactly once."""
    for anchor in (start_anchor, end_anchor):
        count = text.count(anchor)
        if count != 1:
            raise AnchorMissing(f"expected 1 occurrence of {anchor!r}, found {count}")
    start = text.index(start_anchor)
    end = text.index(end_anchor, start)
    return start, end


def hex_to_compose(hex_color: str) -> str:
    if not re.fullmatch(r"#[0-9A-Fa-f]{6}", hex_color):
        raise ValueError(f"color must be #RRGGBB, got {hex_color!r}")
    return f"Color(0xFF{hex_color[1:].upper()})"


def replace_scheme_role(block: str, role: str, value: str) -> str:
    pattern = re.compile(rf"^(\s*){re.escape(role)} = [^,\n]+,$", re.MULTILINE)
    matches = pattern.findall(block)
    if len(matches) != 1:
        raise AnchorMissing(f"role {role!r}: expected 1 assignment, found {len(matches)}")
    return pattern.sub(lambda m: f"{m.group(1)}{role} = {value},", block)


def inject_color_scheme(text: str, scheme: str, roles: dict[str, str]) -> str:
    fn_anchor, call_anchor = SCHEME_FUNCTIONS[scheme]
    fn_start, _ = extract_block(text, fn_anchor, fn_anchor)
    call_start = text.index(call_anchor, fn_start)
    call_end = text.index("\n    )\n", call_start)
    block = text[call_start:call_end]
    for role, hex_color in roles.items():
        block = replace_scheme_role(block, role, hex_to_compose(hex_color))
    return text[:call_start] + block + text[call_end:]


def inject_app_identity(text: str, app: dict[str, str]) -> str:
    start, end = extract_block(text, "release releaseTemplate >> {", "benchmark releaseTemplate >> {")
    block = text[start:end]
    for old, new in (
        ('applicationIdSuffix ".firefox"', f'applicationIdSuffix "{app["applicationIdSuffix"]}"'),
        ('"sharedUserId": "org.mozilla.firefox.sharedID"', f'"sharedUserId": "{app["sharedUserId"]}"'),
    ):
        if block.count(old) != 1:
            raise AnchorMissing(f"release block: expected 1 occurrence of {old!r}, found {block.count(old)}")
        block = block.replace(old, new)
    return text[:start] + block + text[end:]


def patch_file(path: Path, transform) -> None:
    # newline="" keeps upstream LF endings intact on Windows (write_text would emit CRLF)
    with open(path, encoding="utf-8", newline="") as f:
        original = f.read()
    patched = transform(original)
    if patched == original:
        raise AnchorMissing(f"{path}: transform made no changes (already patched?)")
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(patched)
    print(f"patched {path}")


def main() -> None:
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    root = Path(sys.argv[1])
    theme = json.loads(Path(sys.argv[2]).read_text())
    app = json.loads(Path(sys.argv[3]).read_text())
    try:
        patch_file(root / ACORN_COLORS, lambda t: inject_color_scheme(
            inject_color_scheme(t, "light", theme["light"]), "dark", theme["dark"]))
        patch_file(root / FENIX_GRADLE, lambda t: inject_app_identity(t, app))
    except AnchorMissing as e:
        sys.exit(f"INJECTION FAILED: {e}")


if __name__ == "__main__":
    main()
