"""UI checks on the isolated Android emulator; never targets a physical device."""
import sys
import time
import re
from pathlib import Path

import device_driver as d

d.SERIAL = "emulator-5554"
d.OUTPUT = Path(__file__).resolve().parents[1] / "dist/ui-review"
d.OUTPUT.mkdir(parents=True, exist_ok=True)
assert d.shell("getprop", "ro.kernel.qemu").strip() == "1", "Only an emulator is allowed"

if sys.argv[-1] == "inspect":
    d.snapshot("latest")
elif sys.argv[-1] == "calendar":
    d.click("日历", exact=True)
    d.click("日", exact=True)
    d.snapshot("calendar-filled")
    d.click("今天", occurrence=1, exact=True)
    d.click("展开或收起助手快捷功能", exact=True)
    d.snapshot("assistant-expanded-v4")
elif sys.argv[-1] == "focus":
    d.shell("am", "start", "-n", d.PACKAGE+"/.MainActivity", "-a", "android.intent.action.SEND", "-t", "text/plain", "--es", "android.intent.extra.TEXT", "'开始专注做Study'")
    time.sleep(1)
    d.click("发送", exact=True)
    d.snapshot("agent-focus-command")
    d.back()
    d.click("专注", exact=True)
    d.snapshot("linked-focus")
    d.click("暂停", exact=True)
    d.shell("am", "force-stop", d.PACKAGE)
    d.launch()
    d.click("专注", exact=True)
    d.snapshot("focus-restored-after-stop")
    d.click("继续", exact=True)
    d.click("结束本轮", exact=True)
elif sys.argv[-1] == "notification":
    before = {re.search(r"\bid=(-?\d+)", part.splitlines()[0]).group(1) for part in d.shell("dumpsys", "notification").split("NotificationRecord(")[1:] if "pkg="+d.PACKAGE in part.splitlines()[0] and re.search(r"\bid=(-?\d+)", part.splitlines()[0])}
    d.shell("pm", "grant", d.PACKAGE, "android.permission.POST_NOTIFICATIONS")
    d.shell("appops", "set", d.PACKAGE, "SCHEDULE_EXACT_ALARM", "allow")
    d.shell("am", "start", "-n", d.PACKAGE+"/.MainActivity", "-a", "android.intent.action.SEND", "-t", "text/plain", "--es", "android.intent.extra.TEXT", "'开始一个1分钟番茄钟'")
    time.sleep(1)
    d.click("发送", exact=True)
    d.home()
    time.sleep(58)
    relevant = []
    for _ in range(5):
        records = d.shell("dumpsys", "notification")
        sections = records.split("NotificationRecord(")
        relevant = [part for part in sections[1:] if "pkg="+d.PACKAGE in part.splitlines()[0] and "qingban_focus" in part and re.search(r"\bid=(-?\d+)", part.splitlines()[0]).group(1) not in before]
        if relevant:
            break
        time.sleep(2)
    assert relevant, "No focus completion notification delivered in background"
    print("Background focus completion notification delivered")
    d.launch()
    d.back()
    d.click("专注", exact=True)
    d.snapshot("focus-background-completed")
