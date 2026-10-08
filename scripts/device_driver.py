"""Shared ADB helpers; screenshots and dumps stay in the ignored local output."""
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def resolve_adb():
    candidates = [os.environ.get("ADB"), shutil.which("adb")]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates.append(str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")))
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(str(Path(os.environ["LOCALAPPDATA"]) / "MingAlarmToolchain/sdk/platform-tools/adb.exe"))
    return next((path for path in candidates if path and Path(path).is_file()), "adb")


ADB = resolve_adb()
SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")
PACKAGE = "io.github.ming.qingban"
OUTPUT = Path(__file__).resolve().parents[1] / "dist/device-debug"
OUTPUT.mkdir(parents=True, exist_ok=True)


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL, *args], check=True, capture_output=True, timeout=30).stdout

def shell(*args):
    return adb("shell", *args).decode("utf-8", errors="replace")

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/qingban-debug-ui.xml")
    raw = adb("shell", "cat", "/sdcard/qingban-debug-ui.xml")
    return ET.fromstring(raw), raw

def snapshot(name, screenshot=True):
    OUTPUT.mkdir(parents=True, exist_ok=True)
    root, raw = nodes()
    (OUTPUT / (name + ".xml")).write_bytes(raw)
    if screenshot:
        resumed = shell("dumpsys", "activity", "activities")
        if not any(PACKAGE in line for line in resumed.splitlines() if "topResumedActivity=" in line):
            raise RuntimeError("Screenshot skipped: Qingban is not the foreground activity")
        (OUTPUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
    for n in root.iter("node"):
        if n.get("package") == PACKAGE and n.get("password") != "true":
            label = n.get("text") or n.get("content-desc")
            if label:
                print(label, n.get("bounds"), "click=" + str(n.get("clickable")))
    return root

def tap(x, y):
    shell("input", "touchscreen", "swipe", str(x), str(y), str(x), str(y), "120")
    time.sleep(.5)

def click(label, occurrence=0, exact=False):
    root, _ = nodes()
    matches = [n for n in root.iter("node") if n.get("package") == PACKAGE and (label == (n.get("text") or n.get("content-desc") or "") if exact else label in (n.get("text") or n.get("content-desc") or ""))]
    if len(matches) <= occurrence:
        raise RuntimeError("Cannot find " + label)
    n = matches[occurrence]
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    tap((x1+x2)//2, (y1+y2)//2)

def back():
    shell("input", "keyevent", "4")
    time.sleep(.5)

def home():
    shell("input", "keyevent", "3")
    time.sleep(.8)

def launch():
    shell("am", "start", "-n", PACKAGE + "/.MainActivity")
    time.sleep(.8)

def swipe(x1=540, y1=1900, x2=540, y2=600):
    shell("input", "swipe", str(x1), str(y1), str(x2), str(y2), "400")
    time.sleep(.5)

def text(value):
    shell("input", "text", value)
    time.sleep(.3)

def overlay():
    raw = shell("dumpsys", "window", "windows")
    blocks = re.split(r"(?=  Window #)", raw)
    matches = [b for b in blocks if PACKAGE in b.splitlines()[0] and "ty=APPLICATION_OVERLAY" in b]
    print("overlay_count:", len(matches))
    for block in matches:
        for line in block.splitlines():
            if any(k in line for k in ("Window #", "mFrame=", "Frames:", "mViewVisibility=", "isOnScreen=", "isVisible=")):
                print(line.strip())
    return matches

if __name__ == "__main__":
    snapshot(sys.argv[1] if len(sys.argv)>1 else "current")
