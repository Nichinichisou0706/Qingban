"""Verify selection with uniquely named temporary data; never select real user items."""
import sys, time, re, uuid
from pathlib import Path
import device_driver as d
d.SERIAL=sys.argv[1] if len(sys.argv)>1 else 'emulator-5554'
tag='QB045-'+uuid.uuid4().hex[:6]
names=[tag+'-A',tag+'-B']
out=Path(__file__).resolve().parents[1]/'dist/device-debug'

def nodes(): return [n for n in d.nodes()[0].iter('node') if n.get('package')==d.PACKAGE]
def label(n): return n.get('text') or n.get('content-desc') or ''
def labels(): return [label(n) for n in nodes()]
def tap(n):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); d.tap((x1+x2)//2,(y1+y2)//2)
def click(text):
    matches=[n for n in nodes() if label(n)==text and n.get('class')!='android.widget.EditText']; assert matches,'Missing control: '+text; tap(matches[-1])
def find(text):
    for _ in range(9):
        if text in labels(): click(text); return
        d.swipe(x1=950,x2=950,y1=1960,y2=840)
    raise AssertionError('Missing scrolled control: '+text)
def route(page):
    d.shell('am','start','-n',d.PACKAGE+'/.MainActivity','--es','open_route',page); time.sleep(.8)
def search(text):
    field=next(n for n in nodes() if n.get('class')=='android.widget.EditText'); tap(field); d.text(text); d.back()
def all_items():
    route('memo'); click('打开仪表盘'); find('全部事项'); search(tag)
def assert_only_temporary(expected):
    texts=labels(); assert all(n in texts for n in expected),texts
    assert any(x.startswith(str(len(expected))+' 条记录') for x in texts), 'Refusing select-all outside temporary filtered list'
def delete_filtered(expected, cancel_first=False):
    assert_only_temporary(expected); click('多选'); click('全选'); assert '已选 %d / %d'%(len(expected),len(expected)) in labels()
    click('删除 (%d)'%len(expected))
    if cancel_first:
        click('保留'); assert all(n in labels() for n in expected); click('删除 (%d)'%len(expected))
    click('确认删除'); assert not any(n in labels() for n in expected)

created=[]
try:
    for name in names:
        route('memo'); find('新增'); field=next(n for n in nodes() if n.get('class')=='android.widget.EditText'); tap(field); d.text(name); d.back(); click('保存'); created.append(name)
    all_items(); delete_filtered(names,True); print('PASS: all-items filtered select-all, cancel confirmation, atomic deletion',flush=True)
    click('撤销'); assert_only_temporary(names); print('PASS: batch undo restores both selected notes',flush=True)
    # Memo selection has an independent list; filter before touching select-all.
    route('memo'); search(tag); assert all(n in labels() for n in names); click('多选'); click('全选'); assert '已选 2 / 2' in labels()
    click('全不选'); assert '已选 0 / 2' in labels(); click('全选'); click('删除 (2)'); click('确认删除'); assert not any(n in labels() for n in names)
    print('PASS: memo multi-select, deselect-all and delete',flush=True)
    d.shell('am','force-stop',d.PACKAGE); all_items(); assert not any(n in labels() for n in names)
    click('撤销'); assert_only_temporary(names); print('PASS: deleted state persists across restart and undo still restores batch',flush=True)
    # Remove only temporary records after validation.
    delete_filtered(names); created=[]; print('PASS: temporary notes removed',flush=True)
finally:
    if created:
        all_items(); visible=[n for n in created if n in labels()]
        if visible: delete_filtered(visible)
    route('today')
