"""Check recurring task scopes using one temporary habit, preserving real schedules."""
import sys,time,re,uuid
from pathlib import Path
import device_driver as d
d.SERIAL=sys.argv[1] if len(sys.argv)>1 else 'emulator-5554'
name='QB045-H-'+uuid.uuid4().hex[:6]

def nodes(): return [n for n in d.nodes()[0].iter('node') if n.get('package')==d.PACKAGE]
def label(n): return n.get('text') or n.get('content-desc') or ''
def labels(): return [label(n) for n in nodes() if n.get("class")!="android.widget.EditText"]
def bounds(n): return list(map(int,re.findall(r'\d+',n.get('bounds'))))
def tap(n):
    x1,y1,x2,y2=bounds(n);d.tap((x1+x2)//2,(y1+y2)//2)
def click(s, first=False):
    matches=[n for n in nodes() if label(n)==s and n.get('class')!='android.widget.EditText']; assert matches,'Missing control: '+s;tap(matches[0] if first else matches[-1])
def find(s):
    for _ in range(9):
        if s in labels():click(s);return
        d.swipe(x1=960,x2=960,y1=1960,y2=820)
    raise AssertionError('Missing scrolled control: '+s)
def route(s):d.shell('am','start','-n',d.PACKAGE+'/.MainActivity','--es','open_route',s);time.sleep(.8)
def top():
    for _ in range(4): d.swipe(x1=955,x2=955,y1=760,y2=2100)
def search(s):
    f=next(n for n in nodes() if n.get('class')=='android.widget.EditText');tap(f);time.sleep(.6);d.text(s);time.sleep(.8);d.back()
    assert any(n.get("class")=="android.widget.EditText" and n.get("text")==s for n in nodes()),"Search input was not entered"
def title_node():return next(n for n in nodes() if n.get('text')==name)
def nearest_check():
    ns=nodes();t=next(n for n in ns if n.get('text')==name); b=bounds(t);cy=(b[1]+b[3])//2
    checks=[n for n in ns if n.get('checkable')=='true' and bounds(n)[0]>500 and abs((bounds(n)[1]+bounds(n)[3])//2-cy)<130]
    assert checks,'Habit row checkbox missing';return min(checks,key=lambda n:abs((bounds(n)[1]+bounds(n)[3])//2-cy))
def board_visible():
    top()
    # A long fling can skip the small habit row, especially below a sticky bar.
    for _ in range(16):
        if name in labels():return
        d.swipe(x1=540,x2=540,y1=1760,y2=1330)
    d.snapshot('045-habit-board-failure-'+d.SERIAL)
    raise AssertionError('Temporary habit absent from board')
def select_only_habit():
    top();find('多选');board_visible();click("选择事项："+name);top()
    assert '已选 1 / ' in '\n'.join(labels()),'Only temporary habit must be selected'
    find('删除 (1)')
def all_items():
    route('memo');click('打开仪表盘');find('全部事项');search(name)

created=False
try:
    route('today');find('新增');f=next(n for n in nodes() if n.get('class')=='android.widget.EditText');tap(f);d.text(name);d.back()
    find('习惯');assert '每天' in labels();click('每天');click('完成');click('保存');created=True
    board_visible();assert '习惯 · 长期重复' in labels();assert nearest_check().get('checked')=='false';tap(nearest_check())
    top();find('已完成');board_visible();assert nearest_check().get('checked')=='true'
    print('PASS: daily recurring habit in right column; completing today only',flush=True)
    route('calendar');top();click('日',True);click('今天',True);click('›');assert name in labels()
    print('PASS: completed habit remains scheduled for tomorrow',flush=True)
    d.shell('am','force-stop',d.PACKAGE);route('today');top();find('已完成');board_visible();assert nearest_check().get('checked')=='true'
    tap(nearest_check());top();find('待办');board_visible();assert nearest_check().get('checked')=='false'
    print('PASS: habit completion persists after restart, then restored to pending',flush=True)
    select_only_habit();assert '仅本次' in labels();click('确认删除')
    # Whole-series record remains visible in history after deleting today's occurrence.
    all_items();assert name in labels();assert any(s.startswith('1 条记录') for s in labels());click('撤销')
    route('today');board_visible();assert name in labels()
    print('PASS: occurrence deletion leaves series intact; undo restores today',flush=True)
    select_only_habit();click('整个系列');click('确认删除');created=False
    all_items();assert name not in labels();print('PASS: whole-series deletion hides recurring task',flush=True)
finally:
    if created:
        all_items()
        if name in labels():
            assert any(s.startswith('1 条记录') for s in labels()),'Refusing cleanup of unrelated records'
            click('多选');click('全选');click('删除 (1)');click('确认删除')
    route('today')
