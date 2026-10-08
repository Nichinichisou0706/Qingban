"""Explicitly authorized physical-device check using synthetic credentials only."""
import json
import re
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import device_driver as d

d.SERIAL = sys.argv[1] if len(sys.argv) > 1 else d.SERIAL
assert d.shell("getprop", "ro.kernel.qemu").strip() != "1", "Physical phone required"
requests = []
output = Path(__file__).resolve().parents[1] / "dist/device-debug/ui-0.4.1"
output.mkdir(parents=True, exist_ok=True)
names = ["QB-Check-A-" + uuid.uuid4().hex[:6], "QB-Check-B-" + uuid.uuid4().hex[:6]]
created = []


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer synthetic-device-key":
            self.send_response(401); self.end_headers(); return
        requests.append(self.path)
        body = json.dumps({"data": [{"id": "synthetic-chat-a"}, {"id": "synthetic-chat-b"}, {"id": "synthetic-reasoner"}]}).encode()
        self.send_response(200 if self.path == "/v1/models" else 404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)

    def log_message(self, *args):
        pass


def labels():
    root, _ = d.nodes()
    return [n.get("text") or n.get("content-desc") or "" for n in root.iter("node") if n.get("package") == d.PACKAGE]


def field(label, value):
    root, _ = d.nodes()
    matches = [n for n in root.iter("node") if n.get("package") == d.PACKAGE and n.get("class") == "android.widget.EditText"
               and any(label in (child.get("text") or "") for child in n.iter("node"))]
    assert len(matches) == 1, "Cannot find requested input field"
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[0].get("bounds")))
    d.tap((x1+x2)//2, (y1+y2)//2)
    d.text(value); d.back()


def click_last(label):
    root, _ = d.nodes()
    matches = [n for n in root.iter("node") if n.get("package") == d.PACKAGE and n.get("class") != "android.widget.EditText" and (n.get("text") == label or n.get("content-desc") == label)]
    assert matches, "Requested dialog control not found"
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[-1].get("bounds")))
    d.tap((x1+x2)//2, (y1+y2)//2)


def shot(name):
    resumed = d.shell("dumpsys", "activity", "activities")
    assert any(d.PACKAGE in row for row in resumed.splitlines() if "topResumedActivity=" in row)
    (output / (name + ".png")).write_bytes(d.adb("exec-out", "screencap", "-p"))


def settings(restart=False):
    if restart:
        d.shell("am", "force-stop", d.PACKAGE)
    d.launch()
    current = labels()
    if "供应商配置 · 0" in current or any(x.startswith("供应商配置 ·") for x in current):
        return
    d.click("打开仪表盘", exact=True)
    for _ in range(5):
        if "模型设置" in labels():
            d.click("模型设置", exact=True); return
        d.swipe()
    raise AssertionError("Model settings entry not found")


def top():
    for _ in range(3):
        d.swipe(y1=600, y2=1950)


def find(label):
    for _ in range(6):
        if label in labels():
            return
        d.swipe()
    raise AssertionError("Requested model row not found")


def count():
    return int(next(x.split("·")[-1].strip() for x in labels() if x.startswith("供应商配置 ·")))


def activate(name):
    top()
    find(name + "更多操作")
    root, _ = d.nodes()
    candidates = []
    for node in root.iter("node"):
        children = list(node.iter("node"))
        if any(n.get("content-desc") == name + "更多操作" for n in children) and any(n.get("text") in ("启用", "已启用") for n in children):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            candidates.append(((x2-x1)*(y2-y1), node))
    assert candidates, "Activation control not found"
    row = min(candidates, key=lambda x: x[0])[1]
    target = next((n for n in row.iter("node") if n.get("text") == "启用"), None)
    if target is not None:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", target.get("bounds")))
        d.tap((x1+x2)//2, (y1+y2)//2)
    top()
    assert any(x == "当前使用 · " + name for x in labels()), "Activation failed"


def remove(name):
    top(); find(name + "更多操作")
    d.click(name + "更多操作", exact=True)
    d.click("删除配置", exact=True)
    d.click("删除", exact=True)
    created.remove(name)


def create(name, model, verify_preset=False):
    top(); d.click("添加", exact=True)
    if verify_preset:
        click_last("自定义兼容接口"); click_last("DeepSeek")
        assert "https://api.deepseek.com/v1" in labels(), "Provider preset failed"
        click_last("DeepSeek"); click_last("自定义兼容接口")
    field("配置名称", name)
    field("接口地址", f"http://127.0.0.1:{server.server_port}/v1")
    before = len(requests)
    field("API 密钥", "synthetic-device-key")
    for _ in range(8):
        if "从 3 个模型中选择" in labels():
            break
        time.sleep(.5)
    assert len(requests) > before, "Automatic discovery did not reach the local endpoint"
    d.click("从 3 个模型中选择", exact=True)
    field("搜索模型名称", model)
    assert "1 个匹配模型" in labels(), "Model search failed"
    shot("physical-model-picker")
    root, _ = d.nodes()
    choices = [n for n in root.iter("node") if n.get("package") == d.PACKAGE and n.get("text") == model and n.get("class") != "android.widget.EditText"]
    assert choices, "Model result row not found"
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", choices[-1].get("bounds")))
    d.tap((x1+x2)//2, (y1+y2)//2)
    assert "选择模型" not in labels(), "Model picker did not close"
    d.swipe(y1=1850, y2=900)
    d.click("保存配置", exact=True); created.append(name)
    top()
    assert count() == baseline_count + len(created), "Saved model count is incorrect"
    assert any(x == "当前使用 · " + name for x in labels()), "Save-and-activate failed"


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
reverse = "tcp:" + str(server.server_port)
previous_active = None
baseline_count = None
try:
    d.adb("reverse", reverse, reverse)
    settings(restart=True); top()
    current = labels()
    baseline_count = count()
    previous_active = next((x.removeprefix("当前使用 · ") for x in current if x.startswith("当前使用 · ")), None)
    create(names[0], "synthetic-chat-a", True)
    print("PASS: provider preset, automatic discovery, searchable model list, encrypted save", flush=True)
    create(names[1], "synthetic-chat-b")
    top(); field("搜索名称、模型或接口", names[0])
    assert names[0] + "更多操作" in labels() and names[1] + "更多操作" not in labels(), "Provider list search failed"
    d.click("清除搜索", exact=True)
    activate(names[0])
    print("PASS: two independent profiles and active-model switching", flush=True)
    settings(restart=True); top()
    assert any(x == "当前使用 · " + names[0] for x in labels()), "Active model was not restored after process restart"
    assert count() == baseline_count + 2, "Saved profiles were not restored"
    print("PASS: profile and selection survive process restart", flush=True)
    find(names[0] + "更多操作")
    d.click(names[0] + "更多操作", exact=True); d.click("编辑配置 / 查找模型", exact=True)
    for _ in range(8):
        if "从 3 个模型中选择" in labels():
            break
        time.sleep(.5)
    assert "API 密钥（留空保留）" in labels(), "Stored key was not retained"
    assert "已发现 3 个模型，点击下方选择" in labels(), "Retained encrypted key could not retrieve the model list"
    d.back()
    print("PASS: editing retains the encrypted credential and discovers models", flush=True)
finally:
    try:
        # Remove only newly created synthetic configurations, then restore the original selection.
        settings(restart=True)
        for name in created[:]:
            remove(name)
        top()
        if previous_active:
            activate(previous_active)
        elif any(x.startswith("当前使用 · ") for x in labels()):
            d.click("停用", exact=True)
        if baseline_count is not None:
            top(); assert count() == baseline_count, "Existing configuration count changed"
            if previous_active:
                assert "当前使用 · " + previous_active in labels(), "Original active configuration was not restored"
            print("PASS: temporary configurations removed; original model selection preserved", flush=True)
    finally:
        d.adb("reverse", "--remove", reverse)
        server.shutdown(); server.server_close()
