"""Repeat-rule UI regression on the isolated emulator. Never targets a phone."""
import re
import sys
import time
from pathlib import Path

import device_driver as d

d.SERIAL = "emulator-5554"
d.OUTPUT = Path(__file__).resolve().parents[1] / "dist/ui-review/alarm-repeat"
d.OUTPUT.mkdir(parents=True, exist_ok=True)
assert d.shell("getprop", "ro.kernel.qemu").strip() == "1"


def nodes():
    return [n for n in d.nodes()[0].iter("node") if n.get("package") == d.PACKAGE]


def labels():
    return [n.get("text") or n.get("content-desc") or "" for n in nodes()]


def click(label, prefix=False):
    matches = []
    for _ in range(3):
        matches = [n for n in nodes() if n.get("class") != "android.widget.EditText" and
                   ((n.get("text") or n.get("content-desc") or "").startswith(label) if prefix else (n.get("text") or n.get("content-desc") or "") == label)]
        if matches:
            break
        time.sleep(.8)
    assert matches, "Missing repeat control: " + label
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[-1].get("bounds")))
    d.tap((x1+x2)//2, (y1+y2)//2)


def find(label, prefix=False):
    for _ in range(8):
        if any(x.startswith(label) if prefix else x == label for x in labels()):
            click(label, prefix); return
        d.swipe(x1=920, x2=920, y1=1800, y2=850)
    raise AssertionError("Missing scrolled repeat control: " + label)


def shot(name):
    resumed = d.shell("dumpsys", "activity", "activities")
    assert any(d.PACKAGE in row for row in resumed.splitlines() if "topResumedActivity=" in row)
    (d.OUTPUT / (name+".png")).write_bytes(d.adb("exec-out", "screencap", "-p"))


def open_alarm():
    d.shell("am", "start", "-n", d.PACKAGE+"/.MainActivity", "--es", "open_route", "alarm")
    time.sleep(.8)
    click("新增")
    assert len([n for n in nodes() if n.get("class") == "android.widget.NumberPicker"]) == 2
    find("重复")
    current=labels()
    for label in ["只响一次", "每天", "法定工作日", "法定节假日", "周一至周五", "周末"]:
        assert label in current, "Main repeat option missing: " + label


def presets_and_custom():
    open_alarm(); shot("repeat-list")
    for label in ["每天", "法定工作日", "法定节假日", "周一至周五", "周末"]:
        click(label); click("完成")
        assert any(x.startswith(label) for x in labels()), "Repeat summary was not updated: "+label
        assert any(x.startswith("下次：") for x in labels()), "No actual next alarm preview: "+label
        assert not any(re.fullmatch(r"\d{2}月\d{2}日", x) for x in labels()), "Repeated alarm still requires chosen date"
        click("重复")
        print("PASS repeat selection: "+label, flush=True)
    find("自定义")
    assert all("星期"+day in labels() for day in "一二三四五六日")
    shot("custom-weekdays")
    for day in "六日":
        click("星期"+day)
    assert not any(n.get("checked") == "true" for n in nodes() if n.get("checkable") == "true")
    click("完成")
    assert any("至少选择一个" in x for x in labels()), "Empty custom weekdays were accepted"
    click("星期三"); click("完成")
    assert "周三  ›" in labels()
    shot("alarm-weekly-summary")
    click("重复"); click("每天"); d.back()
    assert "周三  ›" in labels(), "Dismissed repeat draft leaked into editor"
    click("重复")
    assert "生效及结束日期" not in labels(), "Alarm date settings were not removed"
    click("只响一次"); click("完成")
    assert not any(re.fullmatch(r"\d{2}月\d{2}日", x) for x in labels()), "One-time alarm still shows a date picker"
    assert any(x.startswith("下次：") for x in labels()), "One-time alarm has no automatic next trigger"
    d.back()
    print("PASS: independent repeat list, all presets, true next-trigger preview, custom weekdays, empty validation, cancel draft and one-time alarm without dates", flush=True)


def shift_persistence():
    open_alarm(); find("轮班制")
    assert len([n for n in nodes() if n.get("class") == "android.widget.NumberPicker"]) >= 2
    find("今天是本轮第几天？")
    assert not any(x.startswith("首个上班日：") for x in labels()), "Shift date picker was not removed"
    shot("shift-editor"); click("完成")
    assert "轮班制 · 上 2 天休 2 天  ›" in labels()
    # Save only a synthetic alarm, then cancel this whole test series after verifying persistence.
    fields = [n for n in nodes() if n.get("class") == "android.widget.EditText"]
    name = "QARepeat"+str(int(time.time()))
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", fields[0].get("bounds")))
    d.tap((x1+x2)//2, (y1+y2)//2); d.text(name); d.back()
    click("保存"); time.sleep(.8)
    assert name in labels(), "Shift alarm did not save"
    d.shell("am", "force-stop", d.PACKAGE)
    d.shell("am", "start", "-n", d.PACKAGE+"/.MainActivity", "--es", "open_route", "alarm")
    time.sleep(.8)
    assert "轮班制 · 上 2 天休 2 天" in labels()
    click(name); find("重复")
    find("轮班制")
    finish_shift_check()


def finish_shift_check():
    pickers = [n for n in nodes() if n.get("class") == "android.widget.NumberPicker"]
    values = [next((child.get("text") for child in picker.iter("node") if child.get("class") == "android.widget.EditText"), None) for picker in pickers]
    assert values[:2] == ["2", "2"], "Shift wheel values did not persist"
    click("返回重复列表"); d.back()
    assert any(x.startswith("QARepeat") for x in labels()), "Only a synthetic repeat alarm may be cancelled"
    click("取消事项"); time.sleep(.5)
    print("PASS: shift wheels, anchor date, saved rule and process-restart restoration; synthetic test alarm cancelled", flush=True)


def no_dates():
    open_alarm()
    click("每天")
    assert "生效及结束日期" not in labels()
    click("只响一次"); click("完成")
    current=labels()
    assert not any(re.fullmatch(r"\d{2}月\d{2}日",x) or x == "选择日期" for x in current)
    assert any(x.startswith("下次：今天") or x.startswith("下次：明天") for x in current)
    shot("alarm-no-date")
    name="QAClock"+str(int(time.time()))
    field=next(n for n in nodes() if n.get("class")=="android.widget.EditText")
    x1,y1,x2,y2=map(int,re.findall(r"\d+",field.get("bounds")))
    d.tap((x1+x2)//2,(y1+y2)//2); d.text(name); d.back(); click("保存")
    assert name in labels()
    d.shell("am","force-stop",d.PACKAGE)
    d.shell("am","start","-n",d.PACKAGE+"/.MainActivity","--es","open_route","alarm")
    time.sleep(.8); click(name)
    current=labels()
    assert not any(re.fullmatch(r"\d{2}月\d{2}日",x) or x == "选择日期" for x in current)
    assert any(x.startswith("下次：今天") or x.startswith("下次：明天") for x in current)
    assert any(x.startswith("QAClock") for x in current)
    click("取消事项")
    print("PASS: clock-only one-shot save/restart and automatic today/tomorrow; synthetic alarm cancelled",flush=True)
    open_alarm(); find("轮班制"); find("今天是本轮第几天？")
    assert not any(x.startswith("首个上班日：") for x in labels())
    shot("shift-without-date"); click("完成")
    assert any(x.startswith("轮班制 ·") for x in labels())
    assert any(x.startswith("下次：") for x in labels())
    d.back()
    print("PASS: repeat/shift without calendar date controls; cycle phase wheel and real next-alarm preview",flush=True)


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "all"
    if mode in ("all", "presets"): presets_and_custom()
    if mode in ("all", "shift"): shift_persistence()
    if mode == "resume_shift": finish_shift_check()
    if mode == "no_dates": no_dates()
