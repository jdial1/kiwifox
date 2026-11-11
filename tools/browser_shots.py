"""Capture real Chrome / Edge windows (new tab page and settings, light and dark) for theme inspiration.

Each browser runs with a throwaway profile in a 1100x720 window at a fixed position; the window is
brought to the front and captured from the screen (browser UI only renders in a real window). The
user's own profiles are untouched. Needs an unlocked interactive desktop.

Usage: python tools/browser_shots.py <out dir> [chrome] [edge]
"""
import ctypes
import json
import ctypes.wintypes as wt
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from PIL import ImageGrab

BROWSERS = {
    "chrome": (r"C:\Program Files\Google\Chrome\Application\chrome.exe", "chrome://newtab", "chrome://settings"),
    "edge": (r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe", "edge://newtab", "edge://settings"),
}
PAGE = Path(tempfile.gettempdir()) / "kiwifox-browser-page.html"
PAGE.write_text("<!doctype html><title>Theme comparison page</title><body style='font-family:sans-serif;margin:32px'>"
                "<h1>Sample article</h1><p>Web content.</p></body>", encoding="utf-8")
user32 = ctypes.windll.user32
ctypes.windll.shcore.SetProcessDpiAwareness(2)


def exe_of(pid):
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not handle:
        return ""
    try:
        buf = ctypes.create_unicode_buffer(1024)
        size = wt.DWORD(1024)
        return buf.value if kernel32.QueryFullProcessImageNameW(handle, 0, buf, ctypes.byref(size)) else ""
    finally:
        kernel32.CloseHandle(handle)


def command_line(pid):
    r = subprocess.run(["powershell", "-NoProfile", "-Command",
                        f"(Get-CimInstance Win32_Process -Filter 'ProcessId={pid}').CommandLine"],
                       capture_output=True, text=True)
    return r.stdout.strip()


def windows_of(exe):
    """Visible titled top-level windows whose process runs [exe] (launchers hand off to other processes)."""
    found = []

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def cb(hwnd, _):
        pid = wt.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if user32.IsWindowVisible(hwnd) and user32.GetWindowTextLengthW(hwnd) > 0 and                 exe_of(pid.value).lower() == exe.lower():
            found.append((hwnd, pid.value))
        return True

    user32.EnumWindows(cb, 0)
    return found


def capture(name, exe, url, dark, out):
    profile = Path(tempfile.mkdtemp(prefix=f"kiwifox-{name}-"))
    # Chromium's own light/dark choice (Settings > Appearance > Mode): 1 = light, 2 = dark. Without it the
    # browser follows the Windows mode.
    (profile / "Default").mkdir()
    (profile / "Default" / "Preferences").write_text(
        json.dumps({"browser": {"theme": {"color_scheme2": 2 if dark else 1}}}), encoding="utf-8")
    args = [exe, f"--user-data-dir={profile}", "--no-first-run", "--no-default-browser-check",
            "--disable-sync", "--window-position=60,60", "--window-size=1100,720",
            "--disable-features=msEdgeFRE,EdgeWelcomePage,ChromeWhatsNewUI", "--new-window", url]
    if dark:
        args.insert(1, "--force-dark-mode")
    before = {h for h, _ in windows_of(exe)}
    proc = subprocess.Popen(args)
    hwnd, owner = None, None
    try:
        for _ in range(60):
            time.sleep(0.5)
            new = [(h, p) for h, p in windows_of(exe) if h not in before]
            if new:
                hwnd, owner = new[0]
                break
        if hwnd is None:
            raise RuntimeError(f"{name}: no window")
        user32.ShowWindow(hwnd, 9)
        user32.SetWindowPos(hwnd, -1, 60, 60, 1100, 720, 0x0040)  # topmost while capturing
        user32.SetForegroundWindow(hwnd)
        time.sleep(3)
        time.sleep(3)
        rect = wt.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(rect))
        img = ImageGrab.grab(bbox=(rect.left, rect.top, rect.right, rect.bottom), all_screens=True)
        page = "newtab" if "newtab" in url else "page"
        target = out / f"{name}-{'dark' if dark else 'light'}-{page}.png"
        img.save(target)
        print(target, img.size)
    finally:
        if hwnd:
            user32.PostMessageW(hwnd, 0x0010, 0, 0)  # WM_CLOSE: closes only our window / temp profile instance
        time.sleep(1.5)
        subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], capture_output=True)
        # Only end the window's process if it is ours (runs our throwaway profile), never the user's browser.
        if owner and owner != proc.pid and str(profile).lower() in command_line(owner).lower():
            subprocess.run(["taskkill", "/PID", str(owner), "/T", "/F"], capture_output=True)
        time.sleep(1.5)
        shutil.rmtree(profile, ignore_errors=True)


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for name in sys.argv[2:] or list(BROWSERS):
        exe, newtab, settings = BROWSERS[name]
        for dark in (False, True):
            # New tab page only: the browsers ignore internal URLs given on the command line,
            # and typing into the window was removed: synthetic keystrokes can land in the user's window.
            capture(name, exe, newtab, dark, out)
            capture(name, exe, PAGE.as_uri(), dark, out)  # a web page: omnibox unfocused


if __name__ == "__main__":
    main()
