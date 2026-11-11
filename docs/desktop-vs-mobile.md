# Desktop Firefox vs KiwiFox: theme mapping

Compared 2026-11-10 on stock Firefox, **Activist – Balanced** and **Dreamer – Bold** (AMO),
**Alpenglow** (Mozilla built-in, bundled in KiwiFox) and **Cascade** (userChrome.css), light and dark.
Desktop is the installed Firefox 159.0.1 (the release KiwiFox is built from), driven headless by
`tools/desktop_shots.py`. KiwiFox is rendered off-device by `screenshots.sh` from the real, patched
components: Fenix's FirefoxTheme, android-components' BrowserToolbar (display and edit mode), AwesomeBar
(search suggestions), Fenix's MenuDialogBottomSheet, and the same home / popup scopes Fenix's
HomeFragment, Homepage and AwesomeBarComposable use (the homepage cards are a stand-in using the roles
Fenix's homepage sections use). `tools/measure_parity.py` measures both.

Each sheet: desktop new tab, address bar dropdown and Settings on the left; KiwiFox on the right
(status bar, toolbar, homepage, address bar while typing with suggestions, main menu).

| Theme | Light | Dark |
|---|---|---|
| Firefox default | ![](compare/default-light.png) | ![](compare/default-dark.png) |
| Activist – Balanced | ![](compare/activist-light.png) | ![](compare/activist-dark.png) |
| Dreamer – Bold | ![](compare/dreamer-bold-light.png) | ![](compare/dreamer-bold-dark.png) |
| Alpenglow | ![](compare/alpenglow-light.png) | ![](compare/alpenglow-dark.png) |
| Cascade | ![](compare/cascade-light.png) | ![](compare/cascade-dark.png) |

## How each desktop surface maps

The rule throughout: a key the theme sets uses the theme's color; a key it leaves unset uses what
desktop Firefox 159 shows by default (measured, or read from its design tokens), not the toolbar color.
Whether that default is light or dark follows Firefox's own LightweightThemeConsumer logic: an opaque
background decides, else the text color, else the system's light / dark mode (KiwiFox's mode).

| Desktop | Theme keys | KiwiFox |
|---|---|---|
| New tab page | `ntp_background`, `ntp_text`, `ntp_card_background` | painted behind the homepage (`HomeFragment`), homepage text and cards re-themed (`Homepage` runs in `KiwiSurfaces.HomeScope`); a user wallpaper still draws on top, as on desktop. Defaults: #F7F6FB / #131215, cards #FFFFFF / #252428 |
| Address bar dropdown, menus | `popup`, `popup_text`, `popup_highlight`, `popup_highlight_text`, `popup_border` | search suggestions (`AwesomeBarComposable`) and the main menu sheet (`MenuDialogBottomSheet`) in the popup colors; `popup_highlight` colors grouped menu rows and the pressed / hovered / keyboard-focused item (desktop shows it on the hovered or selected row). Defaults: #FFFFFF / #171519 |
| Address field while typing | (desktop 159 draws field and results as one panel) | the edit-mode field takes the popup colors |
| Tab strip above the toolbar | `frame`, header images | the status bar: the header's color at that height (Alpenglow's gradient), else `frame`; a userChrome.css theme that leaves the tab strip alone gets desktop's default strip (#F6EEFA / #372A3E). Android 15+ ignores `statusBarColor`, so a view sized to the status bar inset paints it |
| Focus ring | `toolbar_field_border_focus` | a 3 dp ring (desktop's 2 px) while editing, composited over the typing field like desktop; an explicitly transparent value means no ring (Cascade). Default #764EDD / #B89CFF |
| Toolbar button icons | `icons`, `icons_attention` | the toolbar's browser actions (home, tabs, menu); icons inside the address field keep the field text color, as on desktop |
| Header images | `theme_frame`, `additional_backgrounds` (+ `_alignment`, `_tiling`, `_size`), `linear-gradient` entries, `backgrounds_area` | one band across the status bar and the toolbar, drawn from the top of the screen as desktop draws it from the top of the window (1 dp per desktop px: desktop's 85 px tab strip + toolbar is a phone's status bar + toolbar), behind the theme's translucent `toolbar` and `toolbar_field` colors, positioned and repeated per desktop CSS; `backgrounds_area: window` layers are sized to the screen. Animated APNG (KiwiFox's decoder), GIF and animated WebP play. SVG can't be decoded on Android: bundled themes ship pre-rendered PNGs, imported SVG layers are dropped with a note |
| Tabs | `tab_selected`, `tab_text`, `tab_background_text`, `tab_line` | the tab tray: background in the tab strip color, the selected tab in `tab_selected` / `tab_text`, other tabs as a light tint of their text, `tab_line` as the accent |

## Measured (dominant pixel color per region)

| Theme | Surface | Desktop | KiwiFox | ΔE |
|---|---|---|---|---|
| activist light | status bar / tab strip | #14367B | #14367B | 0.0 |
| activist light | new tab background | #C9D6F3 | #C9D6F3 | 0.0 |
| activist light | dropdown | #FAFBFF | #FAFBFF | 0.0 |
| activist light | focus ring | #00B3F4 | #00B3F4 | 0.0 |
| activist light | field while typing | #FAFBFF | #FAFBFF | 0.0 |
| activist dark | status bar / tab strip | #14367B | #14367B | 0.0 |
| activist dark | new tab background | #C9D6F3 | #C9D6F3 | 0.0 |
| activist dark | dropdown | #FAFBFF | #FAFBFF | 0.0 |
| activist dark | focus ring | #00B3F4 | #00B3F4 | 0.0 |
| activist dark | field while typing | #FAFBFF | #FAFBFF | 0.0 |
| dreamer-bold light | status bar / tab strip | #2E1056 | #2E1056 | 0.0 |
| dreamer-bold light | new tab background | #050014 | #050014 | 0.0 |
| dreamer-bold light | dropdown | #24123B | #24123B | 0.0 |
| dreamer-bold light | focus ring | #0AA0C4 | #0BA0C4 | 0.0 |
| dreamer-bold light | field while typing | #24123B | #24123B | 0.0 |
| dreamer-bold dark | status bar / tab strip | #2E1056 | #2E1056 | 0.0 |
| dreamer-bold dark | new tab background | #050014 | #050014 | 0.0 |
| dreamer-bold dark | dropdown | #24123B | #24123B | 0.0 |
| dreamer-bold dark | focus ring | #0AA0C4 | #0BA0C4 | 0.0 |
| dreamer-bold dark | field while typing | #24123B | #24123B | 0.0 |
| alpenglow light | status bar / tab strip | #FF86B0 | #FC4CA0 | 25.1 |
| alpenglow light | new tab background | #F9F9FB | #F9F9FB | 0.0 |
| alpenglow light | dropdown | #291B43 | #281D4E | 6.1 |
| alpenglow light | focus ring | #AC70FF | #AC70FF | 0.0 |
| alpenglow light | field while typing | #291B42 | #281D4E | 6.7 |
| alpenglow dark | status bar / tab strip | #21133C | #321C64 | 20.1 |
| alpenglow dark | new tab background | #2A2A2E | #2A2A2E | 0.0 |
| alpenglow dark | dropdown | #31285E | #2D245B | 2.0 |
| alpenglow dark | focus ring | #AC70FF | #AC70FF | 0.0 |
| alpenglow dark | field while typing | #2C235A | #2D245B | 0.5 |
| cascade light | status bar / tab strip | #F8EEF5 | #F6EEFA | 2.7 |
| cascade light | new tab background | #F7F6FB | #F7F6FB | 0.0 |
| cascade light | dropdown | #FFFFFF | #FFFFFF | 0.0 |
| cascade light | focus ring | #FFFFFF | #FFFFFF | 0.0 |
| cascade light | field while typing | #FFFFFF | #FFFFFF | 0.0 |
| cascade dark | status bar / tab strip | #3B2A38 | #372A3E | 4.5 |
| cascade dark | new tab background | #131215 | #131215 | 0.0 |
| cascade dark | dropdown | #171519 | #171519 | 0.0 |
| cascade dark | focus ring | #171519 | #171519 | 0.0 |
| cascade dark | field while typing | #171519 | #171519 | 0.0 |

33 of 40 pairs are within ΔE 1 (identical to the eye). The rest:

* **Alpenglow light dropdown / typing field, ΔE 6–7:** KiwiFox draws the theme's declared `popup`
  (hsla(254, 46%, 21%) = #281D4E) exactly; desktop renders its results panel slightly differently.
* **Status bar, ΔE 2–5:** desktop's default tab strip (Cascade) is a horizontal gradient; the Android
  status bar takes one color from it.
* **Alpenglow status bar, ΔE 20–25:** the status bar now draws Alpenglow's header itself; on a phone its
  corner artwork covers the measured middle (see Image and animated themes).

Toolbar icons: Alpenglow dark's `icons` violet (#C68AFF) appears on the KiwiFox toolbar buttons and
on desktop's, and in neither in light mode (Alpenglow sets no light `icons`).

Not themed (stock Firefox, no theme selected) rows are not listed: those compare Firefox for
Android's own design with desktop's, not a theme mapping.

## Image and animated themes

Most of desktop's most-used themes are artwork, not colors. Desktop Firefox 159 on top, KiwiFox
below (status bar, toolbar, top of the homepage), light and dark:

**Ash** (319,372 users): Firefox's Nova gradient, sized to the whole window (`backgrounds_area: window`)

![Ash](compare/image-nova-ash.png)

**Dark space** (98,209 users): animated GIF starfield

![Dark space](compare/image-dark-space.png)

**Spirited Away (Animated)** (28,373 users): animated APNG across the header

![Spirited Away (Animated)](compare/image-spirited-away.png)

**Praise the sun (animated)** (29,830 users): animated APNG knights at both ends, colored field border, green icons

![Praise the sun (animated)](compare/image-praisethesun.png)

**Nyan Cat (Animated)** (27,450 users): Nyan Cat and rainbow over a repeating starfield

![Nyan Cat (Animated)](compare/image-nyan-cat.png)

**Blue Nebula Space Theme** (16,351 users): nebula photo repeated across the header

![Blue Nebula Space Theme](compare/image-blue-nebula.png)

On a 360 dp phone the header is about a third as wide as a desktop window, so artwork anchored to both
corners (Alpenglow's noodles, the Praise the sun knights) covers more of the band; that is why Alpenglow's
status bar no longer measures as its gradient color in the table above. In the off-device renders GIF and
animated WebP layers (Android's AnimatedImageDrawable) do not draw; APNG does (KiwiFox's own decoder).

## Still different, and why

* **Layout-only CSS.** Cascade's one-line layout (address bar left of the tabs) and other tab strip,
  sidebar and button-placement rules have no Android counterpart: the phone toolbar is already a single
  row with a tab counter instead of tabs. What desktop layout CSS does to colors, corners, fonts, bar
  height and scale carries over (see README, Layout).
* **Sidebar and bookmarks bar keys** have no Android surface and are reported as dropped.
* **Settings** is not a gap: desktop's `about:preferences` ignores the theme and only follows light /
  dark mode, as KiwiFox's Settings do for imported themes.
* **Hover** does not exist on touch: `popup_highlight` shows on pressed, hovered (mouse) and
  keyboard-focused items, drawn as a ripple over the item rather than desktop's flat row fill.

## Bugs found by comparing

* Cascade's light mode rendered dark: the importer read every `.css` in the repository, including
  palettes `userChrome.css` never imports. `CssBundler` now follows `@import` from `userChrome.css`.
* The homepage had no theme background of its own (Fenix's homepage paints nothing; the window
  background showed), and the earlier screenshot harness hid that by painting the toolbar color.

## Limits

* Desktop menus are popup layers that window screenshots do not include; the app menu comparison uses
  the `popup*` values.
* KiwiFox images are rendered off-device with the real components, not from a phone: the status bar is
  a box in the color `KiwiThemeManager.applyToActivity` paints, and the homepage cards are a stand-in.
* Web content is not themed on either platform.

Regenerate: `python tools/desktop_shots.py <dir> default=default activist=amo:<xpi> ...`, `screenshots.sh`,
`python tools/compare_sheets.py <desktop dir> <mobile dir> <out> <desktop>=<mobile> ...`,
`python tools/measure_parity.py <desktop dir> <mobile dir> <desktop>=<mobile> ...`. Bundled desktop
themes: `python tools/rasterize_svg.py <raster dir> <svgs>`, then `gen-bundled.sh`.
