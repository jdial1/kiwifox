#!/usr/bin/env python3
"""EXPERIMENTAL: pull toolbar colors out of a userChrome.css theme directory into
a desktop-style manifest.json, which import_theme.py then validates.

Usage: css_theme_colors.py <theme-dir> <out-manifest.json>

Resolves `:root` custom properties through var() chains, light-dark() and
@media (prefers-color-scheme). It fails loudly when a role cannot be resolved
to a literal color. It can still be WRONG when a value depends on Firefox's own
default stylesheets or :root attribute state (see README). Check the result
against the theme's screenshots before committing it.
Requires: pip install tinycss2
"""
import glob
import json
import re
import sys
from pathlib import Path

import tinycss2

ROLES = {
    "frame": ["--toolbar-bgcolor", "--toolbar-background-color", "--lwt-accent-color", "--toolbox-bgcolor"],
    "toolbar_field": ["--toolbar-field-background-color", "--urlbar-box-bgcolor", "--lwt-toolbar-field-background-color"],
    "toolbar_field_text": ["--toolbar-field-color", "--toolbar-field-text-color", "--urlbar-box-text-color",
                           "--toolbar-color", "--lwt-text-color"],
}
LITERAL = re.compile(r"^(#[0-9a-fA-F]{3,8}|rgba?\([^)]*\)|hsla?\([^)]*\)|[a-zA-Z]+)$")
# A root selector with no positive attribute test matches the default browser state. Attribute selectors like [privatebrowsingmode] or
# [style*="--lwt-accent-color..."] encode runtime browser state we cannot know.
ROOT_SELECTOR = re.compile(r"^(:root|\*|html|body)(:not\([^)]*\))*$")


class Unresolved(Exception):
    pass


def collect(css: str, scheme: str) -> dict[str, str]:
    """Root-scope custom properties for one color scheme. Later wins; !important beats normal."""
    found: dict[str, tuple[str, bool]] = {}

    def walk(rules, active: bool) -> None:
        for rule in rules:
            if rule.type == "at-rule" and rule.content is not None:
                nested_active = active
                if rule.lower_at_keyword == "media":
                    prelude = tinycss2.serialize(rule.prelude)
                    if "-moz-pref(" in prelude:  # depends on a user pref we cannot read
                        continue
                    m = re.search(r"prefers-color-scheme\s*:\s*(light|dark)", prelude)
                    nested_active = active and (m is None or m.group(1) == scheme)
                walk(tinycss2.parse_rule_list(rule.content), nested_active)
            elif rule.type == "qualified-rule" and active:
                selectors = [x.strip() for x in tinycss2.serialize(rule.prelude).split(",")]
                if not any(ROOT_SELECTOR.match(x) for x in selectors):  # a list applies if any selector matches the default state
                    continue
                for decl in tinycss2.parse_declaration_list(rule.content):
                    if decl.type != "declaration" or not decl.name.startswith("--"):
                        continue
                    previous = found.get(decl.name)
                    if previous and previous[1] and not decl.important:
                        continue
                    found[decl.name] = (tinycss2.serialize(decl.value).strip(), decl.important)

    walk(tinycss2.parse_stylesheet(css, skip_comments=True, skip_whitespace=True), True)
    return {name: value for name, (value, _) in found.items()}


def resolve(name: str, variables: dict[str, str], scheme: str, depth: int = 0) -> str:
    if depth > 12 or name not in variables:
        raise Unresolved(name)
    value = variables[name]
    if m := re.match(r"^light-dark\(\s*(.+?)\s*,\s*(.+)\)$", value):
        value = m.group(1 if scheme == "light" else 2).strip()
    if m := re.match(r"^var\(\s*(--[\w-]+)\s*(?:,\s*(.+))?\)$", value):
        try:
            return resolve(m.group(1), variables, scheme, depth + 1)
        except Unresolved:
            if m.group(2) and LITERAL.match(m.group(2).strip()):
                return m.group(2).strip()
            raise
    if not LITERAL.match(value):
        raise Unresolved(f"{name} = {value}")
    return value


def colors_for(css: str, scheme: str) -> dict[str, str]:
    variables = collect(css, scheme)
    out = {}
    for key, names in ROLES.items():
        for name in names:
            try:
                out[key] = resolve(name, variables, scheme)
                break
            except Unresolved:
                continue
        else:
            raise Unresolved(f"{scheme}: no literal value for {key} via {names}")
    return out


def main() -> None:
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    theme_dir, out = Path(sys.argv[1]), Path(sys.argv[2])
    css = "\n".join(Path(f).read_text(errors="ignore") for f in glob.glob(f"{theme_dir}/**/*.css", recursive=True))
    try:
        manifest = {
            "manifest_version": 2,
            "name": theme_dir.name,
            "version": "1.0",
            "theme": {"colors": colors_for(css, "light")},
        }
    except Unresolved as e:
        sys.exit(f"UNRESOLVED: {e}")
    try:
        dark = colors_for(css, "dark")
        if dark != manifest["theme"]["colors"]:
            manifest["dark_theme"] = {"colors": dark}
    except Unresolved as e:
        print(f"  warning: no separate dark palette resolved ({e}); light colors apply in both modes")
    out.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"wrote {out}: light={manifest['theme']['colors']} dark={manifest.get('dark_theme', {}).get('colors', 'same')}")


if __name__ == "__main__":
    main()
