# KiwiFox

Firefox for Android built from each stable release tag with **runtime toolbar
themes**: a picker under Settings > Customize > Toolbar themes, bundled themes,
and an importer that takes links (addons.mozilla.org themes, r/FirefoxCSS Reddit
posts, GitHub repos) or files (.xpi/.zip/manifest.json/userChrome.css). No
LTS/ESR channel exists for Android, so stable is the target.

```
apply_kiwifox.py  Firefox tree + app.json  ->  KiwiFox sources, bundled theme assets, anchored edits
kiwifox-src/      the Kotlin added to Fenix (org.mozilla.fenix.kiwifox) + FirefoxTheme hook
theme/*.json      bundled themes (copied to assets/kiwifox/themes/)
import_theme.py   desktop theme -> theme JSON, for adding bundled themes (same rules as the in-app importer)
build.yml         daily: new FIREFOX_*_RELEASE tag -> apply -> build -> sign -> GitHub release
```

## Quick start (local test build)

```
./local_build.sh FIREFOX_159_0_1_RELEASE
adb install -r kiwifox-FIREFOX_159_0_1_RELEASE.apk
```

Bundled themes: `theme/kiwi.json`, `theme/cascade.json` (Rosé Pine),
`theme/materialfox.json` (light only), `theme/alpenglow.json`.
`themes-desktop/*.zip` are the same themes as desktop static themes.

## Screenshots

Each theme in light (left) and dark (right) mode: the address bar, the edit-mode address bar, a homepage card with chips and a button, and the main-menu sheet. They are rendered by `KiwiThemeScreenshotTest` with the real android-components toolbar and the patched Firefox/Acorn theme (Robolectric native graphics, 360 dp phone frame), not captured from a device. Regenerate with `run-mach.cmd /f/kiwifox/screenshots.sh` and `python tools/contact_sheets.py F:/kiwifox-dl/shots docs/screenshots`.

How these compare with the same themes on desktop Firefox, and which theme keys are still
not mapped: [docs/desktop-vs-mobile.md](docs/desktop-vs-mobile.md).

### Bundled themes

**Firefox default (no theme)**

![Firefox default (no theme)](docs/screenshots/firefox-default.png)

**Alpenglow**

![Alpenglow](docs/screenshots/bundled-alpenglow.png)

**Cascade**

![Cascade](docs/screenshots/bundled-cascade.png)

**Kiwi**

![Kiwi](docs/screenshots/bundled-kiwi.png)

**Kiwi (Material You green)**

![Kiwi (Material You green)](docs/screenshots/bundled-kiwi-green.png)

**Materialfox**

![Materialfox](docs/screenshots/bundled-materialfox.png)

### Popular image themes (addons.mozilla.org)

Listed in the app under *Popular image themes*. Most of desktop's most-used themes are artwork:
header images, animated APNG / GIF, or a window-wide gradient. KiwiFox draws the theme header as one
band across the status bar and toolbar (desktop: tab strip and toolbar), behind the theme's
translucent toolbar and address field, and plays animated images. Each image: desktop Firefox 159
on top, KiwiFox below, light and dark.

**[Ash](https://addons.mozilla.org/en-US/firefox/addon/nova-ash/)** · 319,372 users · Firefox's Nova gradient, sized to the whole window (`backgrounds_area: window`)

![Ash](docs/compare/image-nova-ash.png)

**[Dark space](https://addons.mozilla.org/en-US/firefox/addon/nicothin-space/)** · 98,209 users · animated GIF starfield

![Dark space](docs/compare/image-dark-space.png)

**[Spirited Away (Animated)](https://addons.mozilla.org/en-US/firefox/addon/spirited-away-animated/)** · 28,373 users · animated APNG across the header

![Spirited Away (Animated)](docs/compare/image-spirited-away.png)

**[Praise the sun (animated)](https://addons.mozilla.org/en-US/firefox/addon/praisethesun/)** · 29,830 users · animated APNG knights at both ends, colored field border, green icons

![Praise the sun (animated)](docs/compare/image-praisethesun.png)

**[Nyan Cat (Animated)](https://addons.mozilla.org/en-US/firefox/addon/nyan-cat-animated/)** · 27,450 users · Nyan Cat and rainbow over a repeating starfield

![Nyan Cat (Animated)](docs/compare/image-nyan-cat.png)

**[Blue Nebula Space Theme](https://addons.mozilla.org/en-US/firefox/addon/blue-nebula-space-theme/)** · 16,351 users · nebula photo repeated across the header

![Blue Nebula Space Theme](docs/compare/image-blue-nebula.png)

### Popular Firefox themes (addons.mozilla.org)

Listed in the app under *Popular Firefox themes* with an Add button. Picked from the 100 most-used
AMO static themes (2026-11-10) by `tools/amo_survey.py` + `tools/pick_distinct.py`: no images (Android
drops them), URL text readable without correction, a visible address field, and at least CIE ΔE 25 from
every other theme here. `AmoCatalogTest` imports each `.xpi` through the app's importer. They have no
dark palette, so dark mode looks the same (as on desktop). Mozilla's Nova and Profiles families rank
higher but are image-based or near-stock tints.

**[Activist – Balanced](https://addons.mozilla.org/en-US/firefox/addon/activist-balanced_/)** · 316,320 users

![Activist – Balanced](docs/screenshots/amo-activist-balanced.png)

**[Visionary – Balanced](https://addons.mozilla.org/en-US/firefox/addon/visionary-balanced/)** · 196,904 users

![Visionary – Balanced](docs/screenshots/amo-visionary-balanced.png)

**[Expressionist – Balanced](https://addons.mozilla.org/en-US/firefox/addon/expressionist-balanced/)** · 103,127 users

![Expressionist – Balanced](docs/screenshots/amo-expressionist-balanced.png)

**[Innovator – Balanced](https://addons.mozilla.org/en-US/firefox/addon/innovator-balanced/)** · 86,019 users

![Innovator – Balanced](docs/screenshots/amo-innovator-balanced.png)

**[Playmaker – Balanced](https://addons.mozilla.org/en-US/firefox/addon/playmaker-balanced/)** · 81,906 users

![Playmaker – Balanced](docs/screenshots/amo-playmaker-balanced.png)

**[Dreamer – Bold](https://addons.mozilla.org/en-US/firefox/addon/dreamer-bold/)** · 54,865 users

![Dreamer – Bold](docs/screenshots/amo-dreamer-bold.png)

**[Cheers – Soft](https://addons.mozilla.org/en-US/firefox/addon/cheers-soft/)** · 45,848 users

![Cheers – Soft](docs/screenshots/amo-cheers-soft.png)

**[Visionary – Bold](https://addons.mozilla.org/en-US/firefox/addon/visionary-bold/)** · 37,011 users

![Visionary – Bold](docs/screenshots/amo-visionary-bold.png)

**[Playmaker – Bold](https://addons.mozilla.org/en-US/firefox/addon/playmaker-bold/)** · 27,452 users

![Playmaker – Bold](docs/screenshots/amo-playmaker-bold.png)

**[Cheers – Bold](https://addons.mozilla.org/en-US/firefox/addon/cheers-bold/)** · 21,395 users

![Cheers – Bold](docs/screenshots/amo-cheers-bold.png)

<details>
<summary><b>r/FirefoxCSS catalogue themes, as the importer converts them</b> (colors + layout)</summary>

**ArcWTF**

![ArcWTF](docs/screenshots/catalog-arcwtf.png)

**Cascade**

![Cascade](docs/screenshots/catalog-cascade.png)

**FF-ULTIMA**

![FF-ULTIMA](docs/screenshots/catalog-ff-ultima.png)

**Firefox GNOME theme**

![Firefox GNOME theme](docs/screenshots/catalog-firefox-gnome-theme.png)

**Firefox-Mod-Blur**

![Firefox-Mod-Blur](docs/screenshots/catalog-firefox-mod-blur.png)

**Firefox-UI-Fix (Lepton)**

![Firefox-UI-Fix (Lepton)](docs/screenshots/catalog-firefox-ui-fix.png)

**MaterialFox**

![MaterialFox](docs/screenshots/catalog-materialfox.png)

**ShyFox**

![ShyFox](docs/screenshots/catalog-shyfox.png)

**SimpleFox**

![SimpleFox](docs/screenshots/catalog-simplefox.png)

**WaveFox**

![WaveFox](docs/screenshots/catalog-wavefox.png)

</details>


## How the runtime theme works

* `KiwiThemeManager` loads bundled (assets) and imported (`filesDir/kiwifox/themes`)
  themes and keeps the selection in the `kiwifox` SharedPreferences as Compose state.
* `FirefoxTheme.kt` (Fenix's root theme) applies the selected theme's Material
  roles to the light and dark color schemes; the Compose toolbar reads those
  roles (`surface`, `surfaceContainerHighest`, `onSurface`, ...). Switching is
  instant. Private mode keeps Firefox's purple.
* `ThemeImporter` resolves a link to a manifest or CSS, `ThemeConverter` is a
  Kotlin port of `import_theme.py` (same desktop rules and contrast checks),
  `CssThemeExtractor` a port of `tools/css_theme_colors.py` (strict `:root`
  resolver). userChrome.css themes that depend on an AMO theme or browser state
  are refused with the reason shown in the app.
* Firefox Color share links are refused: their payload is LZMA-compressed; export
  the theme from Firefox Color and import the file.
* Besides colors, a theme has a `style` (radius, font, sizes, scale); see
  [Layout: radius, font, sizes, scale](#layout-radius-font-sizes-scale).
* `inject_theme.py` is the old one-theme-per-APK injector, kept for reference.

## Layout: radius, font, sizes, scale

Desktop themes change more than colors. Fenix hard-codes those things (the URL
pill is `CircleShape`, 48 dp, fixed `sp`; `AcornTheme` pins its shapes and a
static typography), so a theme could not reach them. Now a theme JSON may carry a
`style` object, every key optional (absent = stock Firefox for Android):

| Key | Range | Applied by |
|---|---|---|
| `fieldRadius` | dp, `-1` = pill | address field shape (display and edit toolbar) |
| `fieldHeight` | 36..48 dp | address field inside the toolbar's fixed 48 dp slot (web content offsets are unchanged) |
| `fieldBorderWidth` | 0..3 dp | field border; colors are the light/dark `fieldBorder` / `fieldBorderFocus` roles |
| `cornerScale` | 0..3 | Material shapes (menus, cards, dialogs, buttons that use `MaterialTheme.shapes`) |
| `fontFamily` | an Android family (`monospace`, `serif`, `sans-serif-condensed`, ...) | all Compose text (Acorn + Material typography, URL text) and XML text via a generated `KiwiFoxFont_*` overlay |
| `fontFile` | a `.ttf`/`.otf` imported in the app | Compose text only (XML has no runtime font hook) |
| `textScale` | 0.8..1.4 | activity `fontScale` (XML and Compose) |
| `uiScale` | 0.8..1.25 | activity `densityDpi`, like desktop `layout.css.devPixelsPerPx`; web content keeps its own zoom |

How it reaches the UI: `KiwiThemeManager` merges the theme's style with the
user's per-theme overrides and publishes it as `KiwiStyles.current` (snapshot
state in android-components `compose-base`, file `KiwiStyle.kt`). `apply_kiwifox.py`
patches `AcornTheme` (shapes, Material typography, `AcornTheme.typography`) and
the browser toolbar (`FullDisplayToolbar`, `BrowserEditToolbar`, `Origin`,
`InlineAutocompleteTextField`) to read it, and `HomeActivity.attachBaseContext`
to apply the scales. Radius, height, border and corner changes are instant;
text/interface scale and the XML font recreate the activity.

**From desktop themes** (`CssStyleExtractor`, Kotlin only): reads rules on
`.urlbar-background`/`#urlbar-background`/`#urlbar`/the input container, `.tab-background`,
panels and chrome root selectors, plus `--urlbar-height`, `--urlbar-border-radius`,
`--tab-border-radius`, `--toolbarbutton-border-radius`, `--arrowpanel-border-radius`,
`--border-radius-*`, and from the theme's `user.js` `browser.uidensity` and
`layout.css.devPixelsPerPx`. Cascade order is respected (`!important`, specificity,
source order), CSS nesting is walked, `:not(...)` counts as true and state
selectors (`[focused]`, `:hover`, ...) are skipped. Desktop px map to Android with
the 32 px desktop bar = 48 dp: heights scale by 1.5, a radius keeps its ratio to
the bar height (≥ half = pill). Fonts map by name (JetBrains Mono -> `monospace`,
Georgia -> `serif`, ...); fonts Android lacks fall back as desktop would, and
the note says to import the `.ttf`. AMO manifests contribute only the border
colors (`toolbar_field_border`, `_focus`), as on desktop.

**Style editor**: Settings > Customize > Toolbar themes > Style edits the
selected theme (or Firefox default): round/corner radius, field height, border,
corner scale, text size, interface scale, font (Android families or an imported
`.ttf`). Reset returns to the theme's values.

Catalogue results (unit test `catalogue layouts`, 2026-11-10):

| Theme | Style on Android |
|---|---|
| ArcWTF | pill field, 42 dp tall (28 px desktop bar) |
| FF-ULTIMA, Firefox-Mod-Blur | 15 dp field corners, corners ×2.5 |
| Firefox-UI-Fix | 12 dp field corners |
| MaterialFox | pill field, corners ×2 |
| ShyFox | 17 dp field corners, corners ×2.88 |
| Cascade | corners ×2 (its 8 px radius); field keeps desktop's default radius |
| GNOME theme | 13 dp field corners (9 px on a 34 px bar), corners ×2.25 |
| SimpleFox | square corners (×0) |
| WaveFox | nothing (all layout behind `-moz-pref()` toggles) |

None of the ten sets a chrome font. Still not mapped: tab strip/sidebar layout,
toolbar button placement, icon recolouring (the toolbar uses one `onSurface`
for text and icons), Fenix Compose code that hard-codes its own
`RoundedCornerShape(...)` instead of `MaterialTheme.shapes`, and Material
shapes in XML screens.

`tools/check_apply_pristine.sh` runs the full `apply_kiwifox.py` on `git HEAD`
copies of every patched upstream file and checks the result equals the build tree.

## What can be imported

| Source | Supported | Notes |
|---|---|---|
| AMO static themes (`manifest.json` `theme` key) | yes | `.xpi`, `.zip` or bare manifest |
| Firefox Color exports | yes | same format; its broken `.xpi` export is fine for us, only `manifest.json` is read |
| Mozilla bundled themes (Alpenglow) | yes | tested; uses `hsla()` and `theme_experiment` keys |
| Mozilla Light/Dark | n/a | they define no colors, only `color_scheme`; pick the mode in Firefox settings |
| r/FirefoxCSS `userChrome.css` themes (GitHub repo, `.css`, or a zipped `chrome` folder) | partly | colors from literal `:root` values (see below), layout from rules and `user.js` (see Layout) |

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

* Mapped desktop keys: the toolbar and address field (`frame`, `toolbar`,
  `toolbar_field*`), the new tab page (`ntp_*`), menus and the address bar
  dropdown (`popup*`), toolbar icons (`icons`, `icons_attention`) and header
  images; see [docs/desktop-vs-mobile.md](docs/desktop-vs-mobile.md) for where
  each lands and how close it measures to desktop. Tab, sidebar and bookmarks
  bar keys have no Android surface and are listed as `dropped:`; so are SVG
  header images in imported themes (Android cannot decode SVG).
* Android has one bar where desktop has the tab strip + toolbar. The bar is
  `toolbar` layered over `frame` (and the header images); the status bar takes
  the tab strip's color.
* Icons inside the address field use the URL text color (as on desktop). The
  importer replaces URL text below 4.5:1 on the field or 3:1 on the bar.
* Settings screens and other XML-based views keep Firefox's own colors for
  imported themes, as desktop's Settings page ignores themes.

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

## Windows (no WSL) local build

`local_build.sh` needs Linux/macOS. On Windows, Mozilla's plain bootstrapper
refuses Android, but the MozillaBuild one supports Android artifact mode:

1. Extract `MozillaBuildSetup-Latest.exe` with 7-Zip to `F:\mozilla-build`
   (no admin needed; the NSIS installer is just an archive), then create
   `F:\mozilla-build\msys2\tmp`: the installer normally makes it, and without
   it bash prints a warning that breaks mach's mozconfig parser.
2. Clone and inject as in `local_build.sh` (`git clone --depth 1 -b <TAG>`,
   `python inject_theme.py <src> theme/<name>.json app.json`).
3. `run-mach.cmd <script.sh>` runs a bash script inside MozillaBuild with
   `MOZCONFIG`, `MOZBUILD_STATE_PATH=F:\mozilla-state` and cwd set to the
   source tree. `bootstrap.sh` is the bootstrap step; see `build.sh` for the
   build step.

Verified 2026-11-04 on FIREFOX_159_0_1_RELEASE + cascade: bootstrap, `mach build`
(artifact mode) and `mach gradle fenix:assembleRelease` succeed (Gradle 21m48s,
12 threads, 24 GB RAM). The `--application-choice` string in `local_build.sh`
matches `python/mozboot/mozboot/bootstrap.py` exactly. Output is three per-ABI
APKs under `objdir-kiwifox/gradle/build/mobile/android/fenix/app/outputs/apk/release/`;
the arm64 one is copied to `kiwifox-<TAG>-<theme>.apk` here (package
`org.mozilla.kiwifox`, Android Debug signature, installs side by side with Firefox).

`inject_theme.py` reads and writes files with `newline=""` so Windows Python
does not turn the upstream LF files into CRLF (that made the diff 2600 lines
instead of 48).

Bootstrap on Windows leaves `build-tools` and `platforms` uninstalled: the
current Google command-line tools replace `sdkmanager` with a wrapper, and
cmd.exe splits package names at the `;`. Install them once by hand from
PowerShell (not cmd or bash):

```
$env:JAVA_HOME = "F:\mozilla-state\jdk\jdk-17.0.20.1+1"
F:\mozilla-state\android-sdk-windows\cmdline-tools\23.0\bin\android.exe sdk install --no-metrics "build-tools;37.0.0" "platforms;android-37.2"
```

(Versions come from `python/mozboot/mozboot/android-packages.txt` in the
Firefox tree; check them when the tag changes.)

## Fast iteration (measured 2026-11-04, 12 threads, 24 GB)

| Step | Time |
|---|---|
| Release build after a Kotlin change (`build.sh`: R8 + lintVital + package) | 3-6 min |
| First debug build (`dev.sh`, one-time: every module's debug variant) | 7 min 54 s |
| Debug build after a one-file change (`dev.sh`) | 33 s |

Loop for code changes: edit under `kiwifox-src/`, then

```
bash sync-src.sh                      # copy kiwifox-src + theme JSON over the applied tree
run-mach.cmd F:\kiwifox\dev.sh        # fenix:assembleDebug, installs + launches if adb sees a phone
```

What makes it fast: the Gradle daemon stays alive between runs, the configuration
cache is reused, `org.gradle.caching=true` in `~/.gradle/gradle.properties` caches task
outputs, the debug variant skips R8 and release lint, and `dev.sh` skips `mach build`
(only needed when the mozconfig or GeckoView version changes). The debug APK is a
separate app (`org.mozilla.fenix.debug`), so it sits next to the release KiwiFox.

Pair the phone once with Wireless debugging (Developer options), then
`adb connect <phone-ip>:<port>`; `dev.sh` then installs straight over Wi-Fi instead of
the WebDAV download. There is no true hot reload without Android Studio (Apply Changes /
Compose Live Edit), but theme JSON, imported CSS and catalogue fetches are all runtime
data and need no rebuild at all.

## Catalogue conversion results (FIREFOX_159_0_1_RELEASE, 2026-11-04)

`unit-test.sh` runs `CssThemeExtractorCatalogTest` over local clones of the ten
repositories (`KIWIFOX_CATALOG_DIR`). All ten convert. The extractor assumes
desktop Firefox on Windows with the default theme, falls back per variable to
Firefox's Proton defaults, and corrects unreadable URL text to black or white:

| Theme | Result |
|---|---|
| ArcWTF | own colors: white bar (light), #3F3F3F bar / #2B2B2B field (dark) |
| Cascade (cascadefox) | own colors: #1E2021 bar and field, both modes |
| MaterialFox | own light colors; dark palette unresolvable, light used, text corrected |
| SimpleFox | own dark bar #201E21; field and text from Firefox defaults, text corrected |
| Firefox-UI-Fix, GNOME theme, ShyFox, Mod-Blur, WaveFox, FF-ULTIMA | layout-only: Firefox default colors (what desktop shows with the default theme) |

## Kiwi Browser parity (from screenshots of Kiwi on the user's phone, 2026-11-04)

| Kiwi | KiwiFox | How |
|---|---|---|
| Toolbar at top: Home, address pill, +, tab count, menu | same | Firefox 159 defaults to top toolbar with the "+" shortcut; `kiwiHomeButton` adds Home at the start (`BrowserToolbarMiddleware`), switch under Customize > Toolbar |
| Toolbar, status bar and address field in the Material You green | `theme/kiwi-green.json` | sampled: bar #0C1F18, field #1B3429, text #D2E8DC, accent #92D5AB |
| Settings fully tinted (background, headers, values) | bundled themes only | `apply_kiwifox.py` generates `res/values{,-night}/kiwifox_overlays.xml`, one `KiwiFoxOverlay_<name>` style per bundled theme mapping Material/Fenix XML attrs to the theme roles; `KiwiThemeManager.applyOverlay` applies it in `HomeActivity.setupTheme`; changing the selection recreates the activity. Imported themes have no overlay: Compose UI (toolbar, menu, home) is themed, XML settings keep Firefox colors |
| Top app bar + system bars tinted | all themes | `KiwiThemeManager.applyToActivity` (runtime, after toolbar inflate / onResume / selection) |
| Settings sections Basics / Autofill / Appearance | same titles | `patch_settings_groups` regroups `preferences.xml`; leftovers under "More", then Privacy, Advanced, About |
| Three-dot menu: top-anchored flat list with icon row | not matched | Firefox's menu is a Compose bottom sheet with grouped items; it takes the theme colors but keeps its own layout |
| Section header text in accent green | not matched | Fenix's `PreferenceSectionHeader` uses `colorOnSurface`; changing it would also recolor stock Firefox |
