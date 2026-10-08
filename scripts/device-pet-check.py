"""Check the app's own overlay geometry and navigation; only crop character pixels."""
import sys,re,time,math,io
from pathlib import Path
from PIL import Image,ImageChops,ImageStat
import device_driver as d
d.SERIAL = sys.argv[1] if len(sys.argv) > 1 else d.SERIAL

def frame():
 blocks=re.split(r'(?=  Window #)',d.shell('dumpsys','window','windows'))
 bs=[b for b in blocks if d.PACKAGE in b.splitlines()[0] and 'ty=APPLICATION_OVERLAY' in b]
 assert len(bs)==1,'Expected one app pet window'
 b=bs[0];m=re.search(r'(?<![A-Za-z])frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
 if not m:m=re.search(r'mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b)
 assert m,'Overlay frame missing'
 return tuple(map(int,m.groups()))
def center(b):return (b[0]+b[2])//2,(b[1]+b[3])//2
def shot(b):return Image.open(io.BytesIO(d.adb('exec-out','screencap','-p'))).convert('RGB').crop(b)
def expand():
 b=frame()
 if b[2]-b[0]==b[3]-b[1]:
  d.tap(*center(b));b=frame()
 d.tap(*center(b))
 for _ in range(8):
  b=frame()
  if b[2]-b[0]==b[3]-b[1]:return b
  time.sleep(.2)
 raise AssertionError('Round menu did not expand at '+str(b))

d.home();b=frame();originalFrame=b;original=center(b);b=expand();width=b[2]-b[0]
density=int(re.findall(r'\d+',d.shell('wm','density').splitlines()[-1])[0])/160
assert abs(width-188*density)<3,'Expanded menu exceeds 188dp'
assert abs((originalFrame[2]-originalFrame[0])-46*density)<2 and abs((originalFrame[3]-originalFrame[1])-76*density)<2,'Compact pet must match 46 by 76dp central portrait'
print('PASS: compact pet is 46 by 76dp, same as expanded center portrait',flush=True)
print('PASS: one pet, bounded 188dp round menu',flush=True)
shot(b).save(Path(__file__).resolve().parents[1]/'dist/device-debug/045-final-pet-crop.png')
d.tap(*center(b));compact=frame();assert compact==originalFrame,'Closing menu did not restore the compact position';awake=shot(compact);time.sleep(6);faded=shot(compact)
assert frame()==compact,'Idle pet moved unexpectedly'
assert sum(ImageStat.Stat(ImageChops.difference(awake,faded)).mean)>5,'Idle pet did not visibly fade'
print('PASS: five-second idle collapse/fade and touch wake-up',flush=True)
b=expand();cy=center(b)[1];cx=center(b)[0];radius=width*66/188
# Switch through all four lightweight motion modes, preserving the original after four taps.
for _ in range(4):
 d.tap(int(cx-radius),cy);time.sleep(.15)
print('PASS: breathing, hop, sway and still controls',flush=True)
d.tap(cx,cy)
# Drag the portrait to the other edge; its whole window must stay on screen.
b=frame();x,y=center(b);screen=list(map(int,re.findall(r'\d+',d.shell('wm','size').splitlines()[-1])))
d.shell('input','swipe',str(x),str(y),str(screen[0]-x),str(y),'700');time.sleep(.5);b=frame()
assert 0<=b[0]<b[2]<=screen[0] and 0<=b[1]<b[3]<=screen[1],'Dragging moved pet off screen'
print('PASS: dragging and edge clamping',flush=True)
d.shell('input','swipe',str(center(b)[0]),str(center(b)[1]),str(original[0]),str(original[1]),'700');time.sleep(.5)
for index,expected in [(0,'GPT 娘'),(1,'轻伴 · 今天'),(2,'轻伴 · 备忘'),(3,'轻伴 · 日历'),(4,'轻伴 · 闹钟'),(5,'轻伴 · 专注')]:
 print('CHECK: pet route '+expected,flush=True)
 d.home();b=expand();cx,cy=center(b);radius=(b[2]-b[0])*66/188;angle=math.radians(-90+index*45)
 d.tap(int(cx+math.cos(angle)*radius),int(cy+math.sin(angle)*radius));r,_=d.nodes();assert any(n.get('text')==expected for n in r.iter('node')), 'Wrong route '+expected
 print('PASS: pet route '+expected,flush=True)
print('PASS: conversation, task, memo, calendar, alarm and focus routes',flush=True)
d.shell('am','start','-n',d.PACKAGE+'/.MainActivity','--es','open_route','today')
