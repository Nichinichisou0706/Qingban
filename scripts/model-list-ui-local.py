"""Model configuration checks with a synthetic server on the isolated emulator only."""
import json
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import device_driver as d

d.SERIAL = "emulator-5554"
d.OUTPUT = Path(__file__).resolve().parents[1] / "dist/ui-review"
assert d.shell("getprop", "ro.kernel.qemu").strip() == "1", "Only an emulator is allowed"
requests = []


class ModelsHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        assert self.headers.get("Authorization") == "Bearer synthetic-ui-key"
        requests.append(self.path)
        body = json.dumps({"data": [{"id": "test-chat-a"}, {"id": "test-chat-b"}, {"id": "test-reasoner"}]}).encode()
        self.send_response(200 if self.path == "/v1/models" else 404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def field(label, value):
    root, _ = d.nodes()
    fields = [n for n in root.iter("node") if n.get("package") == d.PACKAGE and n.get("class") == "android.widget.EditText"
              and any(label in (child.get("text") or "") for child in n.iter("node"))]
    assert len(fields) == 1, "Cannot find field: " + label
    n = fields[0]
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    d.tap((x1+x2)//2, (y1+y2)//2)
    d.text(value)
    d.back()


def open_settings():
    d.shell("am", "force-stop", d.PACKAGE)
    d.launch()
    d.click("打开仪表盘", exact=True)
    for _ in range(4):
        root, _ = d.nodes()
        if any(n.get("text") == "模型设置" for n in root.iter("node")):
            d.click("模型设置", exact=True)
            return
        d.swipe()
    raise AssertionError("Model settings entry was not visible on the dashboard")


def create(name, model):
    d.click("添加", exact=True)
    field("配置名称", name)
    field("接口地址", f"http://10.0.2.2:{server.server_port}/v1")
    field("API 密钥", "synthetic-ui-key")
    time.sleep(1.8)
    assert requests, "Automatic discovery did not request the model list"
    d.click("从 3 个模型中选择", exact=True)
    d.snapshot("model-list-picker")
    d.click(model, exact=True)
    d.swipe(y1=1850, y2=850)
    d.click("保存配置", exact=True)
    time.sleep(.6)
    root, raw = d.nodes()
    assert name in raw.decode(), "Saved profile did not appear"


server = ThreadingHTTPServer(("127.0.0.1", 0), ModelsHandler)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    open_settings()
    d.snapshot("model-list-empty")
    create("UI-Model-A", "test-chat-a")
    deletion_only = "delete" in sys.argv
    if not deletion_only:
        create("UI-Model-B", "test-chat-b")
        d.snapshot("model-list-two-providers")
        d.shell("am", "force-stop", d.PACKAGE)
        open_settings()
        _, raw = d.nodes()
        assert "UI-Model-B" in raw.decode() and "test-chat-b" in raw.decode(), "Selected profile did not survive restart"
        d.click("启用", exact=True)
        _, raw = d.nodes()
        assert "当前使用 · UI-Model-A" in raw.decode(), "Switch did not update the selected model"
        d.snapshot("model-list-active-switched")
        d.click("UI-Model-A更多操作", exact=True)
        d.click("编辑配置 / 查找模型", exact=True)
        time.sleep(1.8)
        d.snapshot("model-list-editor")
        d.back()
    for name in (["UI-Model-A"] if deletion_only else ["UI-Model-A", "UI-Model-B"]):
        d.click(name + "更多操作", exact=True)
        d.click("删除配置", exact=True)
        d.click("删除", exact=True)
    _, raw = d.nodes()
    assert "离线记录模式" in raw.decode() and "UI-Model" not in raw.decode(), "Temporary profiles were not removed"
    assert len(requests) >= (1 if deletion_only else 3)
    d.snapshot("model-list-deleted")
    print("PASS: " + ("automatic discovery, model selection, deletion message and offline state" if deletion_only else "automatic model discovery, picker, encrypted save/restart, two providers, switching and delete-to-offline"))
finally:
    server.shutdown()
    server.server_close()
