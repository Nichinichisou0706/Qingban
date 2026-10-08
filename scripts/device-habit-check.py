"""Exercise only a uniquely named temporary habit; remove it and preserve prior supervision state."""
import sys, time, re, uuid
from pathlib import Path
import device_driver as d
d.SERIAL=sys.argv[1] if len(sys.argv)>1 else 'emulator-5554'
name='QB-Habit-'+uuid.uuid4().hex[:6]

def nodes():
    return [n for n in d.nodes()[0].iter('node') if n.get('package')==d.PACKAGE]
def labels(): return [n.get('text') or n.get('content-desc') or '' for n in nodes()]
def tap(n):
    b=list(map(int,re.findall(r'\d+',n.get('bounds'))));d.tap((b[0]+b[2])//2,(b[1]+b[3])//2)
def click(label):
    candidates=[n for n in nodes() if n.get('class')!='android.widget.EditText' and (n.get('text')==label or n.get('content-desc')==label)]
    assert candidates,'Control missing: '+label
    tap(candidates[-1])
def find(label):
    for _ in range(8):
        if label in labels(): click(label);return
        d.swipe(x1=940,x2=940,y1=2050,y2=800)
    raise AssertionError('Scrolled control missing: '+label)
def route():
    d.shell('am','start','-n',d.PACKAGE+'/.MainActivity','--es','open_route','habits');time.sleep(.8)
def row():
    candidates=[n for n in nodes() if any(c.get('text')==name for c in n.iter('node')) and any(c.get('text')=='编辑习惯' for c in n.iter('node')) and any(c.get('checkable')=='true' for c in n.iter('node'))]
    return min(candidates,key=lambda n:len(list(n.iter('node'))))
def habit_checks(): return [n for n in row().iter('node') if n.get('checkable')=='true']
def supervision():
    for _ in range(4):d.swipe(x1=930,x2=930,y1=700,y2=2050)
    candidates=[n for n in nodes() if any(c.get('text')=='温和督促' for c in n.iter('node')) and any(c.get('checkable')=='true' for c in n.iter('node'))]
    parent=min(candidates,key=lambda n:len(list(n.iter('node'))))
    return next(n for n in parent.iter('node') if n.get('checkable')=='true')

created=False;prior=None
try:
    route(); prior=supervision().get('checked')=='true';tap(supervision());assert (supervision().get('checked')=='true')!=prior
    tap(supervision());assert (supervision().get('checked')=='true')==prior
    print('PASS: local supervision switch and original state restored',flush=True)
    find('新增习惯');field=next(n for n in nodes() if n.get('class')=='android.widget.EditText');tap(field);d.text(name);d.back();click('保存习惯');created=True
    for _ in range(8):
        if name in labels():break
        d.swipe(x1=940,x2=940,y1=2050,y2=800)
    assert name in labels();checks=habit_checks();today=next(n for n in reversed(checks) if n.get('enabled')=='true');tap(today)
    assert habit_checks()[-1].get('checked')=='true'
    print('PASS: daily habit creation and today check-in',flush=True)
    d.shell('am','force-stop',d.PACKAGE);route()
    for _ in range(8):
        if name in labels():break
        d.swipe(x1=940,x2=940,y1=2050,y2=800)
    assert habit_checks()[-1].get('checked')=='true';tap(habit_checks()[-1]);assert habit_checks()[-1].get('checked')=='false'
    print('PASS: process-restart persistence and undo check-in',flush=True)
    control=next(n for n in row().iter('node') if n.get('text')=='编辑习惯');tap(control)
    for _ in range(4):
        ns=nodes();candidates=[n for n in ns if any(c.get('text')=='继续养成' for c in n.iter('node')) and any(c.get('checkable')=='true' for c in n.iter('node'))]
        if candidates:break
        d.swipe(x1=830,x2=830,y1=1750,y2=950)
    label=next(n for n in ns if n.get('text')=='继续养成')
    label_bounds=list(map(int,re.findall(r'\d+',label.get('bounds'))))
    center_y=(label_bounds[1]+label_bounds[3])//2
    control=min((n for n in ns if n.get('checkable')=='true'),key=lambda n:abs((int(re.findall(r'\d+',n.get('bounds'))[1])+int(re.findall(r'\d+',n.get('bounds'))[3]))//2-center_y))
    assert control.get('checked')=='true';tap(control);click('保存习惯')
    if name not in labels():find('显示暂停的习惯')
    assert name in labels(); assert any('已暂停' in n.get('text','') for n in row().iter('node'))
    print('PASS: pause hides active card, records retained in paused list',flush=True)
finally:
    if created:
        route()
        for _ in range(10):
            if name in labels():break
            if '显示暂停的习惯' in labels():
                root=d.nodes()[0];chips=[n for n in root.iter('node') if any(c.get('text')=='显示暂停的习惯' for c in n.iter('node')) and n.get('checkable')=='true']
                if chips and chips[0].get('checked')!='true':click('显示暂停的习惯')
            d.swipe(x1=940,x2=940,y1=2050,y2=800)
        if name in labels():
            tap(next(n for n in row().iter('node') if n.get('text')=='编辑习惯'));find('删除习惯与打卡记录');click('删除');assert name not in labels();print('PASS: temporary habit and checks deleted',flush=True)
        else:raise AssertionError('Temporary habit must be cleaned up: '+name)
    if prior is not None:
        route()
        if (supervision().get('checked')=='true')!=prior:tap(supervision())
    d.shell('am','start','-n',d.PACKAGE+'/.MainActivity','--es','open_route','today')
