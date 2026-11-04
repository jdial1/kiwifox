# KiwiFox theme pipeline

Builds Firefox for Android from each stable release tag with an injected
toolbar theme. No LTS/ESR channel exists for Android, so stable is the target.

```
import_theme.py  desktop theme (.xpi/.zip/manifest.json)  ->  theme/<name>.json
inject_theme.py  theme JSON + app.json  ->  patched Firefox source tree
build.yml        daily: new FIREFOX_*_RELEASE tag -> inject -> build -> sign -> GitHub release
```

Run the importer locally, commit the JSON, set `THEME:` in `build.yml`.

## Quick start (local test build)

```
./local_build.sh FIREFOX_159_0_1_RELEASE theme/cascade.json
adb install -r kiwifox-FIREFOX_159_0_1_RELEASE-cascade.apk
```

One theme per APK. Bundled, verified themes: `theme/kiwi.json`,
`theme/cascade.json` (Rosé Pine), `theme/materialfox.json` (light only),
`theme/alpenglow.json`. `themes-desktop/*.zip` are the same themes as desktop
static themes, installable on desktop Firefox and re-importable here.

In-app switching is not built: it needs a runtime loader in
`FirefoxTheme.kt`, a settings entry, and the theme JSONs as assets. That is
Kotlin work that must be compiled to be trusted.

## What can be imported

| Source | Supported | Notes |
|---|---|---|
| AMO static themes (`manifest.json` `theme` key) | yes | `.xpi`, `.zip` or bare manifest |
| Firefox Color exports | yes | same format; its broken `.xpi` export is fine for us, only `manifest.json` is read |
| Mozilla bundled themes (Alpenglow) | yes | tested; uses `hsla()` and `theme_experiment` keys |
| Mozilla Light/Dark | n/a | they define no colors, only `color_scheme`; pick the mode in Firefox settings |
| r/FirefoxCSS `userChrome.css` themes | **no** | see below |

## Desktop rules mirrored (Firefox 159 source)

| Rule | Desktop source |
|---|---|
| Color syntax: hex, `rgb()`/`rgba()`, `hsl()`/`hsla()`, RGB arrays, CSS names | `LightweightThemeManager.sys.mjs` `loadColors()` |
| Missing/transparent `frame` -> white, alpha dropped | `LightweightThemeConsumer.sys.mjs` `--lwt-accent-color` |
| Missing `toolbar_field` -> `rgba(255,255,255,0.8)` layered over the bar | `toolbar_field` `fallbackColor` |
| Missing `toolbar_field_text` -> black | `toolbar_field_text` `fallbackColor` |
| `dark_theme` block -> dark scheme; absent -> same colors both modes | `ext-theme.js` `darkDetails: manifest.dark_theme` |
| Unknown keys: warn and ignore | `loadColors()` default branch |

## Android limits (reported by the importer, never hidden)

* Only `frame`, `toolbar`, `toolbar_field`, `toolbar_field_text`,
  `toolbar_field_border_focus` have a place on the Android toolbar. The other
  37 schema keys and all images are listed as `dropped:`.
* Android has one bar where desktop has frame + toolbar. The bar is drawn as
  `toolbar` layered over `frame` (design decision, not a desktop rule).
* The Android toolbar paints URL text and bar icons with one Material role
  (`onSurface`). The importer fails if the URL text color is below 4.5:1 on
  the field or 3:1 on the bar.
* Settings screens and other XML-based views keep Firefox's own colors.

## Why userChrome.css themes are rejected

Firefox for Android's interface is native Kotlin/Jetpack Compose
(`mobile/android/android-components/components/compose/browser-toolbar`).
Gecko renders only web content there, so there is no `#nav-bar`, `#urlbar`
or `.tabbrowser-tab` for CSS selectors to match, and no
`toolkit.legacyUserProfileCustomizations.stylesheets` support in the Android
prefs.

Extracting colors from the CSS instead was tested on 12 popular themes
(cascade, ShyFox, WaveFox, edge-frfox, firefox-gnome-theme, Firefox-UI-Fix,
MaterialFox, minimal-functional-fox, SimpleFox, Firefox-Mod-Blur,
firefox-one, FF-ULTIMA) with a `:root` variable resolver that understands
`var()` chains, `light-dark()` and `@media (prefers-color-scheme)`:

* 5 of 12 yield no toolbar colors at all: they consume Firefox's `--lwt-*`
  variables, meaning their colors come from whichever AMO theme the user
  installed alongside. For those, import the AMO theme instead.
* At least 1 (MaterialFox) resolved to the wrong (dark) palette for light
  mode, because the real value depends on Firefox's default stylesheets and
  `:root` attribute state that only the running browser has.
* 18% to 43% of each theme's declarations are layout (hiding tabs, compact
  bars), which has no Android equivalent.

`tools/css_theme_colors.py` ships as an explicitly experimental extractor
that only trusts unconditional `:root` rules and refuses anything else
(attribute-state selectors, `-moz-pref()` media, system colors like
`ActiveCaption`). On the 12 themes it resolves 2: cascade (both modes) and
MaterialFox (light only). Everything else fails loudly rather than guessing.
