"""Authorized UI and timer checks on the connected phone; existing items are not edited."""
import re
import sys
import time
from pathlib import Path

import device_driver as d
d.SERIAL = sys.argv[1] if len(sys.argv) > 1 else d.SERIAL
assert d.shell("getprop", "ro.kernel.qemu").strip() != "1"
output = Path(__file__).resolve().parents[1] / "dist/device-debug/ui-0.4.1"
output.mkdir(parents=True, exist_ok=True)


def root():
    return d.nodes()[0]


def labels():
    return [n.get("text") or n.get("content-desc") or "" for n in root().iter("node") if n.get("package") == d.PACKAGE]


def route(name):
    d.shell("am", "start", "-n", d.PACKAGE + "/.MainActivity", "--es", "open_route", name)
    time.sleep(.8)
    assert any(n.get("package") == d.PACKAGE for n in root().iter("node"))


def click(label, prefix=False):
    matches = [n for n in root().iter("node") if n.get("package") == d.PACKAGE and n.get("class") != "android.widget.EditText"
               and ((n.get("text") or n.get("content-desc") or "").startswith(label) if prefix else (n.get("text") or n.get("content-desc") or "") == label)]
    assert matches, "Requested control not found: " + label
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[-1].get("bounds")))
    d.tap((x1+x2)//2, (y1+y2)//2)


def find(label, prefix=False):
    for _ in range(6):
        if any(x.startswith(label) if prefix else x == label for x in labels()):
            click(label, prefix); return
        d.swipe(x1=910, x2=910, y1=1850, y2=850)
    raise AssertionError("Requested setting not found: " + label)


def wheels():
    return [n for n in root().iter("node") if n.get("package") == d.PACKAGE and n.get("class") == "android.widget.NumberPicker"]


def shot(name):
    resumed = d.shell("dumpsys", "activity", "activities")
    assert any(d.PACKAGE in row for row in resumed.splitlines() if "topResumedActivity=" in row)
    (output / (name + ".png")).write_bytes(d.adb("exec-out", "screencap", "-p"))


def calendar():
    route("calendar")
    selected = next((label for label in ["月", "周", "三日", "日"] if any((n.get("selected") == "true" or n.get("checked") == "true") and any(c.get("text") == label for c in n.iter("node")) for n in root().iter("node"))), "月")
    for view in ["月", "周", "三日", "日"]:
        d.click(view, exact=True)
        nodes = root()
        assert any((n.get("selected") == "true" or n.get("checked") == "true") and any(c.get("text") == view for c in n.iter("node")) for n in nodes.iter("node")), "Calendar selection did not update"
        if view != "月":
            assert any("横向滑动看日期" in (n.get("text") or "") for n in nodes.iter("node")), "Time grid did not render"
    d.click(selected, exact=True)
    print("PASS: month, week, three-day and day calendar views", flush=True)


def editors():
    route("today")
    assert sum(n.get("content-desc") == "GPT 娘" for n in root().iter("node") if n.get("package") == d.PACKAGE) == 1, "Single character display failed"
    click("展开或收起助手快捷功能")
    assert "＋ 事项" in labels() and " 番茄钟" in labels(), "Assistant quick menu did not expand"
    click("＋ 事项")
    date = next(x for x in labels() if re.fullmatch(r"\d{2}月\d{2}日", x))
    click(date); assert len(wheels()) == 3, "Date wheels missing"
    shot("physical-date-wheels"); d.back()
    find("开始时间：", prefix=True); assert len(wheels()) == 2, "Time wheels missing"
    shot("physical-time-wheels"); click("确定")
    find("结束：",prefix=True); assert len(wheels()) == 2, "End time wheels missing"
    click("结束日期：",prefix=True); assert len(wheels())==3, "End date wheels missing"
    d.back();d.back()
    find("预计时长：", prefix=True)
    assert len(wheels()) == 2, "Duration hour/minute wheels missing"
    d.back(); d.back()
    print("PASS: single character, assistant expansion, date/time/duration wheels (draft discarded)", flush=True)
    route("alarm"); click("展开或收起助手快捷功能"); click(" 闹钟")
    assert len(wheels()) == 2, "Alarm time wheels missing"
    assert not any(re.fullmatch(r"\d{2}月\d{2}日",x) for x in labels()), "Alarm date selector must stay removed"
    find("重复");find("周末");find("自定义")
    assert "星期六" in labels() and "星期日" in labels(), "Alarm repeat weekdays missing"
    click("完成")
    find("铃声 · 网易云选歌／已缓存音乐")
    click("打开网易云 · 选一首歌")
    resumed = d.shell("dumpsys", "activity", "activities")
    assert any("com.netease.cloudmusic" in row for row in resumed.splitlines() if "topResumedActivity=" in row), "NetEase did not launch"
    d.launch()
    assert "选择音乐" in labels(), "Music picker did not resume"
    # Exercise Android's share-return path without fetching or playing a remote song.
    sample = "https://music.163.com/song?id=123456"
    d.shell("am", "start", "-n", d.PACKAGE + "/.MainActivity", "-a", "android.intent.action.SEND", "-t", "text/plain", "--es", "android.intent.extra.TEXT", sample)
    assert sample in labels(), "Shared song link was not filled in"
    d.back(); d.back()
    print("PASS: alarm repeat, actual NetEase launch and Android share-link return (draft discarded)", flush=True)


def timer_text():
    return next(x for x in labels() if re.fullmatch(r"\d{1,3}:\d{2}", x))


def focus_notifications():
    dump = d.shell("dumpsys", "notification")
    sections = dump.split("NotificationRecord(")[1:]
    result = set()
    for section in sections:
        first = section.splitlines()[0]
        if "pkg=" + d.PACKAGE in first and re.search(r"channel=qingban_focus(?:\s|\))", first):
            match = re.search(r"\bid=(-?\d+)", first)
            if match:
                result.add(match.group(1))
    return result


def focus():
    route("focus")
    assert "专注中" not in labels() and "已暂停" not in labels(), "An existing timer is active; leave it unchanged"
    click("专注设置")
    original_minutes = int(next(x.split("：")[1].split()[0] for x in labels() if x.startswith("专注时长：")))
    assert original_minutes in [25, 45, 60], "Keep customized focus settings unchanged"
    click("专注时长：", prefix=True); click("5")
    for _ in range(6):
        picker = next((n for n in wheels() if (n.get("content-desc") or "").startswith("选择分钟")), wheels()[-1])
        if picker.get("content-desc") == "选择分钟，当前1" or any(c.get("class")=="android.widget.EditText" and c.get("text")=="1" for c in picker.iter("node")):
            break
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", picker.get("bounds")))
        middle = (y1+y2)//2
        d.shell("input", "swipe", str((x1+x2)//2), str(middle-100), str((x1+x2)//2), str(middle+180), "800")
    assert any(n.get("content-desc") == "选择分钟，当前1" or any(c.get("class")=="android.widget.EditText" and c.get("text")=="1" for c in n.iter("node")) for n in wheels()), "Wheel could not select one minute"
    click("确定"); click("保存")
    before = focus_notifications()
    click("开始" if "开始" in labels() else "开始新一轮")
    assert "专注中" in labels()
    click("暂停"); paused = timer_text()
    d.shell("am", "force-stop", d.PACKAGE); route("focus")
    assert "已暂停" in labels() and timer_text() == paused, "Paused timer did not restore"
    click("继续"); d.home()
    print("PASS: one-minute wheel selection, start/pause/resume and process-restart recovery; checking background completion", flush=True)
    deadline = time.monotonic() + 85
    while time.monotonic() < deadline:
        if focus_notifications() - before:
            break
        time.sleep(5)
    else:
        raise AssertionError("No new background focus completion notification")
    route("focus")
    assert "本轮已结束" in labels() and "开始休息" in labels(), "Completed timer state incorrect"
    shot("physical-focus-completed")
    click("重置"); click(str(original_minutes) + " 分钟")
    assert timer_text() == str(original_minutes) + ":00", "Original focus duration was not restored"
    print("PASS: actual phone background notification, completion state and restored focus settings; one test minute remains in statistics", flush=True)


mode = sys.argv[1] if len(sys.argv) > 1 else "all"
if mode in ("calendar", "all"): calendar()
if mode in ("editors", "all"): editors()
if mode in ("focus", "all"): focus()
route("today")
