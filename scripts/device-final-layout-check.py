"""Verify the centered date strip, then open the existing pet through app UI."""
import datetime, io, re, sys, time
from pathlib import Path
from PIL import Image

import device_driver as d
d.SERIAL = sys.argv[1] if len(sys.argv) > 1 else d.SERIAL

def nodes():
    return [n for n in d.nodes()[0].iter('node') if n.get('package') == d.PACKAGE]

def label(n):
    return n.get('text') or n.get('content-desc') or ''

def bounds(n):
    return tuple(map(int, re.findall(r'\d+', n.get('bounds'))))

def click(n):
    x1, y1, x2, y2 = bounds(n)
    d.tap((x1 + x2) // 2, (y1 + y2) // 2)

def find(text):
    for _ in range(10):
        matches = [n for n in nodes() if label(n) == text]
        if matches:
            return matches[0]
        d.swipe(x1=950, x2=950, y1=1960, y2=900)
    raise AssertionError('Missing control: ' + text)

d.shell('am', 'start', '-n', d.PACKAGE + '/.MainActivity', '--es', 'open_route', 'memo')
time.sleep(.8)
click(find('打开仪表盘'))
find('七天安排')
today = datetime.date.fromisoformat(d.shell('date', '+%F').strip())
expected = [str((today + datetime.timedelta(days=i)).day) for i in range(-3, 4)]
for _ in range(4):
    ns = nodes()
    groups = {}
    for n in ns:
        if label(n) in expected:
            groups.setdefault(bounds(n)[1], []).append(n)
    row = next((sorted(g, key=lambda n: bounds(n)[0]) for g in groups.values()
                if len(g) == 7 and [label(n) for n in sorted(g, key=lambda n: bounds(n)[0])] == expected), None)
    if row:
        break
    d.swipe(x1=950, x2=950, y1=1840, y2=1400)
assert row, 'The full seven-day strip must show three past dates and three future dates'
assert label(row[3]) == str(today.day)
crop = (max(0, bounds(row[0])[0] - 40), max(0, bounds(row[0])[1] - 110),
        min(1080, bounds(row[-1])[2] + 40), bounds(row[-1])[3] + 100)
Image.open(io.BytesIO(d.adb('exec-out', 'screencap', '-p'))).crop(crop).save(
    Path(__file__).resolve().parents[1] / 'dist/device-debug/045-final-centered-dates.png')
print('PASS: seven-day strip = ' + ', '.join(expected) + '; today is fourth', flush=True)
click(find('权限与桌面'))
click(find('开启悬浮 GPT 娘'))
d.home()
print('PASS: pet started from the app settings', flush=True)
