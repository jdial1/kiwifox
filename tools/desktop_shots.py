"""Screenshots of desktop Firefox with a theme applied, for comparing against KiwiFox on Android.

Runs the installed desktop Firefox headless with a throwaway profile and drives it over Marionette
(Firefox's built-in automation protocol); chrome-context screenshots include the toolbars and menus.
The user's own Firefox and profiles are not touched.

Theme kinds:
  amo:<path.xpi>        static theme, installed temporarily
  builtin:<addon id>    e.g. firefox-alpenglow@mozilla.org
  css:<repo dir>        userChrome.css theme: <repo>/chrome (or the repo itself) copied to <profile>/chrome,
                        plus <repo>/user.js if present
  default               stock Firefox

States captured, light and dark (ui.systemUsesDarkTheme): newtab, page, prompt (address bar dropdown
while typing), settings (about:preferences). Menus are popup layers that window screenshots miss.

Usage: python tools/desktop_shots.py <out dir> <label>=<theme> [<label>=<theme> ...]
"""
import base64
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

FIREFOX = r"C:\Program Files\Mozilla Firefox\firefox.exe"
PORT = 2829
PAGE = """<!doctype html><meta charset=utf-8><title>Theme comparison page</title>
<body style="font-family:sans-serif;margin:32px;max-width:640px">
<h1>Sample article</h1><p>Web content is not themed on either platform; this page only shows the
chrome around it.</p><p><a href="#">A link</a> &middot; <button>Button</button> <input placeholder="Input"></p></body>"""


class Marionette:
    def __init__(self, port):
        deadline = time.time() + 60
        while True:
            try:
                self.sock = socket.create_connection(("127.0.0.1", port), timeout=30)
                break
            except OSError:
                if time.time() > deadline:
                    raise
                time.sleep(0.5)
        self.buf = b""
        self.msg_id = 0
        self._read()  # server hello

    def _read(self):
        while b":" not in self.buf:
            self.buf += self.sock.recv(65536)
        length, rest = self.buf.split(b":", 1)
        length = int(length)
        while len(rest) < length:
            rest += self.sock.recv(1 << 20)
        self.buf = rest[length:]
        return json.loads(rest[:length])

    def cmd(self, name, params=None):
        self.msg_id += 1
        data = json.dumps([0, self.msg_id, name, params or {}]).encode()
        self.sock.sendall(str(len(data)).encode() + b":" + data)
        while True:
            msg = self._read()
            if msg[0] == 1 and msg[1] == self.msg_id:
                if msg[2]:
                    raise RuntimeError(f"{name}: {msg[2]}")
                return msg[3]

    def chrome(self, script, *args):
        self.cmd("Marionette:SetContext", {"value": "chrome"})
        return self.cmd("WebDriver:ExecuteAsyncScript" if "resolve" in script else "WebDriver:ExecuteScript",
                        {"script": script, "args": list(args)})

    def shot(self, path):
        self.cmd("Marionette:SetContext", {"value": "chrome"})
        png = self.cmd("WebDriver:TakeScreenshot", {"full": False})["value"]
        Path(path).write_bytes(base64.b64decode(png))


def prepare_profile(theme, dark):
    profile = Path(tempfile.mkdtemp(prefix="kiwifox-desktop-"))
    prefs = {
        "marionette.port": PORT,
        "ui.systemUsesDarkTheme": 1 if dark else 0,
        "toolkit.legacyUserProfileCustomizations.stylesheets": True,
        "browser.shell.checkDefaultBrowser": False,
        "browser.aboutwelcome.enabled": False,
        "browser.startup.homepage_override.mstone": "ignore",
        "startup.homepage_welcome_url": "",
        "datareporting.policy.dataSubmissionEnabled": False,
        "browser.newtabpage.activity-stream.feeds.section.topstories": False,
        "browser.newtabpage.activity-stream.feeds.topsites": False,
        "browser.newtabpage.activity-stream.showSponsored": False,
        "browser.urlbar.suggest.quicksuggest.sponsored": False,
        "browser.search.suggest.enabled": False,
        "browser.tabs.firefox-view": False,
        "sidebar.revamp": False,
        "extensions.autoDisableScopes": 0,
        "xpinstall.signatures.required": False,
    }
    lines = [f'user_pref("{k}", {json.dumps(v)});' for k, v in prefs.items()]
    if theme.startswith("css:"):
        repo = Path(theme[4:])
        src = repo / "chrome" if (repo / "chrome").is_dir() else repo
        shutil.copytree(src, profile / "chrome", ignore=shutil.ignore_patterns(".git"))
        user_js = repo / "user.js"
        if user_js.is_file():
            lines = user_js.read_text(encoding="utf-8", errors="replace").splitlines() + lines
    (profile / "user.js").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return profile


def capture(out, label, theme, dark):
    profile = prepare_profile(theme, dark)
    page = profile / "page.html"
    page.write_text(PAGE, encoding="utf-8")
    proc = subprocess.Popen([FIREFOX, "-headless", "-marionette", "-no-remote", "-profile", str(profile),
                             "-width", "1100", "-height", "720"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            env=dict(os.environ, MOZ_REMOTE_ALLOW_SYSTEM_ACCESS="1"))  # chrome-context access, this process only
    try:
        m = Marionette(PORT)
        m.cmd("WebDriver:NewSession", {"capabilities": {}})
        m.cmd("WebDriver:SetWindowRect", {"width": 1100, "height": 720})
        if theme.startswith("amo:"):
            m.cmd("Addon:Install", {"path": str(Path(theme[4:]).resolve()), "temporary": True})
        elif theme.startswith("builtin:"):
            m.chrome("""const [id, resolve] = arguments;
                const {AddonManager} = ChromeUtils.importESModule("resource://gre/modules/AddonManager.sys.mjs");
                AddonManager.getAddonByID(id).then(a => a.enable()).then(() => resolve(true), e => resolve(String(e)));""",
                     theme[8:])
        time.sleep(1.5)
        # Firefox stripes the address bar and shows a robot icon under automation; that is not part of any theme.
        hide_remote = "document.documentElement.removeAttribute('remotecontrol');"
        m.chrome(hide_remote)
        mode = "dark" if dark else "light"
        stem = f"{label}-{mode}"

        m.chrome("gBrowser.selectedBrowser.loadURI(Services.io.newURI('about:newtab'), "
                 "{triggeringPrincipal: Services.scriptSecurityManager.getSystemPrincipal()});")
        time.sleep(2.5)
        m.chrome(hide_remote)
        m.shot(out / f"{stem}-newtab.png")

        m.chrome("gBrowser.selectedBrowser.loadURI(Services.io.newURI(arguments[0]), "
                 "{triggeringPrincipal: Services.scriptSecurityManager.getSystemPrincipal()});", page.as_uri())
        time.sleep(1.5)
        m.shot(out / f"{stem}-page.png")

        m.chrome("""const resolve = arguments[0];
            gURLBar.focus(); gURLBar.value = 'firefox themes'; gURLBar.startQuery();
            setTimeout(() => resolve(gURLBar.view.isOpen), 1500);""")
        m.shot(out / f"{stem}-prompt.png")
        m.chrome("gURLBar.view.close(); gURLBar.blur(); gBrowser.selectedBrowser.focus();")

        # The app menu is a popup layer that window screenshots do not include; Settings is captured instead.
        m.chrome("gBrowser.selectedBrowser.loadURI(Services.io.newURI('about:preferences'), "
                 "{triggeringPrincipal: Services.scriptSecurityManager.getSystemPrincipal()});")
        time.sleep(2)
        m.chrome(hide_remote)
        m.shot(out / f"{stem}-settings.png")
        theme_id = m.chrome("""const resolve = arguments[0];
            const {AddonManager} = ChromeUtils.importESModule("resource://gre/modules/AddonManager.sys.mjs");
            AddonManager.getAddonsByTypes(["theme"]).then(t => resolve(t.filter(a => a.isActive).map(a => a.id).join(',')));""")
        print(f"{stem}: active theme {theme_id.get('value')}")
        try:
            m.cmd("Marionette:Quit", {"flags": ["eForceQuit"]})
        except Exception:
            pass
    finally:
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
        shutil.rmtree(profile, ignore_errors=True)


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for spec in sys.argv[2:]:
        label, theme = spec.split("=", 1)
        for dark in (False, True):
            capture(out, label, theme, dark)


if __name__ == "__main__":
    main()
