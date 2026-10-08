"""Approve and undo only a uniquely named temporary task; cancel it afterwards."""
import sys,time,re,uuid,datetime
from pathlib import Path
import device_driver as d
d.SERIAL=sys.argv[1] if len(sys.argv)>1 else 'emulator-5554'
name='QB-Review-'+uuid.uuid4().hex[:6]

def nodes():return [n for n in d.nodes()[0].iter('node') if n.get('package')==d.PACKAGE]
def labels():return [n.get('text') or n.get('content-desc') or '' for n in nodes()]
def tap(n):
 b=list(map(int,re.findall(r'\d+',n.get('bounds'))));d.tap((b[0]+b[2])//2,(b[1]+b[3])//2)
def click(label):
 c=[n for n in nodes() if n.get('class')!='android.widget.EditText' and (n.get('text')==label or n.get('content-desc')==label)];assert c,'Missing '+label;tap(c[-1])
def find(label):
 for _ in range(12):
  if label in labels():click(label);return
  d.swipe(x1=920,x2=920,y1=2050,y2=850)
 raise AssertionError('Cannot scroll to '+label)
def top():
 for _ in range(4):d.swipe(x1=920,x2=920,y1=600,y2=2000)
def route():d.shell('am','start','-n',d.PACKAGE+'/.MainActivity','--es','open_route','today');time.sleep(.5)
def search_history():
 d.click('打开仪表盘',exact=True);find('全部事项')
 f=next(n for n in nodes() if n.get('class')=='android.widget.EditText');tap(f);d.text(name);d.back()
def item_row():
 cs=[n for n in nodes() if any(c.get('text')==name for c in n.iter('node')) and any(c.get('text')=='编辑' for c in n.iter('node'))]
 return min(cs,key=lambda n:len(list(n.iter('node'))))

created=False
try:
 route();click('展开或收起助手快捷功能');click('＋ 事项')
 f=next(n for n in nodes() if n.get('class')=='android.widget.EditText');tap(f);d.text(name);d.back()
 ns=nodes();date=next(n for n in ns if re.fullmatch(r'\d{2}月\d{2}日',n.get('text','')));tap(date)
 ns=nodes();pickers=[n for n in ns if n.get('class')=='android.widget.NumberPicker'];assert len(pickers)==3
 today=datetime.date.fromisoformat(d.shell('date','+%F').strip());assert today.day>1,'Month boundary needs explicit date selection'
 p=pickers[-1];b=list(map(int,re.findall(r'\d+',p.get('bounds'))));mid=(b[1]+b[3])//2
 d.shell('input','swipe',str((b[0]+b[2])//2),str(mid-70),str((b[0]+b[2])//2),str(mid+100),'800');time.sleep(.6)
 ns=nodes();p=[n for n in ns if n.get('class')=='android.widget.NumberPicker'][-1]
 assert any(c.get('class')=='android.widget.EditText' and re.match(r'^'+str(today.day-1)+r'(日)?$',c.get('text','')) for c in p.iter('node')), 'Day wheel did not move to yesterday'
 click('确定');click('保存');created=True
 route();top()
 if '重新检查' in labels():click('重新检查')
 find('查看调整建议');click('全不选')
 for _ in range(12):
  ns=nodes();t=next((n for n in ns if n.get('text')==name),None)
  if t is not None:break
  d.swipe(x1=820,x2=820,y1=1780,y2=950)
 assert t is not None,'Temporary task not in review'
 tb=list(map(int,re.findall(r'\d+',t.get('bounds'))));ty=(tb[1]+tb[3])//2
 checks=[n for n in ns if n.get('checkable')=='true'];chosen=min(checks,key=lambda n:abs((int(re.findall(r'\d+',n.get('bounds'))[1])+int(re.findall(r'\d+',n.get('bounds'))[3]))//2-ty))
 tap(chosen);assert sum(n.get('checked')=='true' for n in nodes() if n.get('checkable')=='true')==1,'Only temporary task may be selected'
 click('同意并调整');find('撤销这次调整')
 print('PASS: daily review approval and undo of only temporary task',flush=True)
 route();search_history();assert any(n.get('text')==str(today-datetime.timedelta(days=1)) for n in item_row().iter('node'))
 print('PASS: original date restored after undo',flush=True)
finally:
 if created:
  d.back();route();search_history();tap(next(n for n in item_row().iter('node') if n.get('text')=='编辑'));click('取消事项')
  print('PASS: temporary task cancelled; no user tasks changed',flush=True)
 route()
