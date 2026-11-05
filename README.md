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
* `inject_theme.py` is the old one-theme-per-APK injector, kept for reference.

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
