"""Takes the README's phone screenshots on an emulator (see .github/workflows/screenshots.yml).

usage: phone.py APK DEMO_LIBRARY OUT_DIR DEBUG_DIR

Installs the app, gives it the demo library (a folder of M4B books, from :core:demoLibrary) through the system
folder picker as a person would, listens a little so the library has progress and listening starts, then
walks through the screens. Taps go by the texts and descriptions uiautomator sees; anything that can't be
found leaves a screenshot and the screen's layout in DEBUG_DIR.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from PIL import Image

APK, DEMO, OUT, DEBUG = sys.argv[1:5]
PACKAGE = "com.zyagodin.booksound"


def adb(*args, check=True, capture=False):
    result = subprocess.run(["adb", *args], check=check, capture_output=True)
    return result.stdout if capture else None


def shell(command, check=True):
    return adb("shell", command, check=check, capture=True).decode(errors="replace")


# ----------------------------------------------------------------------------------------------- screen

def nodes():
    for _ in range(6):
        out = shell("uiautomator dump /sdcard/ui.xml", check=False)
        if "dumped" in out:
            xml = adb("exec-out", "cat", "/sdcard/ui.xml", capture=True)
            try:
                return list(ET.fromstring(xml).iter("node"))
            except ET.ParseError:
                pass
        time.sleep(1)
    return []


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def matching(pattern, where=None):
    rx = re.compile(pattern, re.IGNORECASE)
    found = []
    for n in where if where is not None else nodes():
        label = n.get("text") or n.get("content-desc") or ""
        if label and rx.fullmatch(label.strip()):
            found.append(n)
    return found


def find(pattern, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        hits = matching(pattern)
        if hits:
            return hits[0]
        time.sleep(1)
    fail(f"not found: {pattern}")


def tap(pattern, timeout=20, settle=1.5):
    x, y = center(find(pattern, timeout))
    shell(f"input tap {x} {y}")
    time.sleep(settle)


def tap_if(pattern, settle=1.5):
    hits = matching(pattern)
    if hits:
        x, y = center(hits[0])
        shell(f"input tap {x} {y}")
        time.sleep(settle)
    return bool(hits)


def reveal(pattern, swipes=8):
    """Scrolls down until [pattern] is on screen."""
    for _ in range(swipes):
        hits = matching(pattern)
        if hits:
            return hits[0]
        swipe(1800, 1000)
    fail(f"not found while scrolling: {pattern}")


def long_press(pattern):
    x, y = center(reveal(pattern))
    shell(f"input swipe {x} {y} {x} {y} 900")
    time.sleep(1.5)


def back(settle=1.5):
    shell("input keyevent KEYCODE_BACK")
    time.sleep(settle)


def swipe(y1, y2, settle=1.5):
    shell(f"input swipe 540 {y1} 540 {y2} 400")
    time.sleep(settle)


def media(key):
    # The player has a media session: keys play and pause it without looking for buttons.
    shell(f"input keyevent {key}")
    time.sleep(1.5)


def capture(path):
    with open(path, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", capture=True))


def shot(name):
    time.sleep(1.5)
    path = os.path.join(OUT, f"{name}.png")
    capture(path)
    img = Image.open(path)
    img.resize((img.width // 2, img.height // 2), Image.LANCZOS).save(path, optimize=True)
    print("screenshot", name, flush=True)


def fail(message):
    os.makedirs(DEBUG, exist_ok=True)
    capture(os.path.join(DEBUG, "failure.png"))
    shell("uiautomator dump /sdcard/ui.xml", check=False)
    with open(os.path.join(DEBUG, "failure.xml"), "wb") as f:
        f.write(adb("exec-out", "cat", "/sdcard/ui.xml", capture=True, check=False) or b"")
    raise SystemExit(message)


def step(name):
    """Keeps what the screen looked like at each step, to tell where a run went wrong."""
    print("step", name, flush=True)
    os.makedirs(DEBUG, exist_ok=True)
    capture(os.path.join(DEBUG, f"{len(os.listdir(DEBUG)):02d}-{name}.png"))


# ------------------------------------------------------------------------------------------------ setup

def prepare():
    adb("install", "-r", "-g", APK)
    shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS", check=False)
    # The app opens the folder picker in Audiobooks: the library goes there.
    shell("mkdir -p /sdcard/Audiobooks")
    adb("push", f"{DEMO}/.", "/sdcard/Audiobooks/")
    # A clean status bar: the same time, full battery and signal, no notifications.
    shell("settings put global sysui_demo_allowed 1")
    for command in (
        "-e command enter",
        "-e command clock -e hhmm 0941",
        "-e command battery -e level 100 -e plugged false",
        "-e command network -e wifi show -e level 4",
        "-e command network -e mobile show -e datatype none -e level 4",
        "-e command notifications -e visible false",
    ):
        shell(f"am broadcast -a com.android.systemui.demo {command}")
    shell(f"am start -n {PACKAGE}/.MainActivity")
    time.sleep(4)


def pick_folder():
    """Chooses the Audiobooks folder in the system folder picker and allows the app to use it."""
    tap(r"Choose folder")
    time.sleep(2)
    step("picker")
    for _ in range(12):
        screen = nodes()
        if matching(r"allow", screen):
            tap_if(r"allow", settle=4)
            return
        if matching(r"Audiobooks", screen) and matching(r"use this folder", screen):
            tap_if(r"use this folder", settle=2)
            continue
        if matching(r"Audiobooks", screen):
            # Listed in another folder or in the roots: open it.
            target = max(matching(r"Audiobooks", screen), key=lambda n: center(n)[1])
            x, y = center(target)
            shell(f"input tap {x} {y}")
            time.sleep(2)
            continue
        # Elsewhere: the phone's storage from the roots (the drawer opens from the left edge).
        if not tap_if(r"Show roots"):
            shell("input swipe 2 1200 800 1200 300")
            time.sleep(1.5)
        tap_if(r"sdk_gphone.*|Internal storage|.*phone.*")
    fail("could not pick the folder")


def dismiss_prompts():
    for label in (r"not now", r"later", r"close"):
        tap_if(label)


def listen(title, chapter, resume=False):
    """Opens a book, plays it, jumps to a chapter and pauses: progress and a listening start."""
    tap(re.escape(title))
    step(f"detail-{title[:10]}")
    tap(r"Resume|Continue|Play" if resume else r"Play")
    time.sleep(3)
    media("KEYCODE_MEDIA_PAUSE")
    tap(r"Chapters")
    hits = []
    for _ in range(8):
        hits = matching(re.escape(chapter))
        if hits:
            break
        swipe(1900, 1300)
    if not hits:
        fail(f"no {chapter}")
    x, y = center(hits[0])
    shell(f"input tap {x} {y}")
    time.sleep(1.5)
    tap(r"Close player")
    back()


def main():
    os.makedirs(OUT, exist_ok=True)
    prepare()
    step("start")
    pick_folder()
    step("picked")
    find(r"Pride and Prejudice", timeout=90)
    dismiss_prompts()

    # Some listening: Pride and Prejudice three times (each after another book: new listening starts).
    listen("Pride and Prejudice", "Chapter 5")
    listen("Frankenstein", "Chapter 7")
    listen("Pride and Prejudice", "Chapter 12", resume=True)
    listen("The Sign of the Four", "Chapter 4")
    listen("Pride and Prejudice", "Chapter 26", resume=True)
    for title in ("A Study in Scarlet", "The Time Machine"):
        long_press(re.escape(title))
        tap(r"Mark as finished")
        for _ in range(4):
            swipe(500, 1900, settle=0.8)
    step("library")

    shot("library")
    x, y = center(reveal(r"Sherlock Holmes"))
    shell(f"input tap {x} {y}")
    time.sleep(2)
    shot("series")
    back()
    for _ in range(4):
        swipe(500, 1900, settle=0.8)

    tap(r"Pride and Prejudice")
    shot("detail")
    tap(r"Resume|Continue|Play")
    time.sleep(3)
    media("KEYCODE_MEDIA_PAUSE")
    shot("player")

    tap(r"Narrator's voice.*")
    tap(r"Deep male voice")
    tap(r"\+6 dB")
    swipe(1700, 700)
    shot("sound")
    back()

    tap(r"Sleep timer.*|Off")
    shot("sleep")
    back()

    tap(r"More")
    tap(r"Listening starts")
    shot("starts")
    back()


if __name__ == "__main__":
    main()
