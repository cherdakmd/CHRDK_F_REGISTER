"""Generate rank storefront icons. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_core'
NAMES=('my','top','rules','close')+tuple(f'level_{i}_{state}' for i in range(1,6) for state in ('current','lower','available'))


def art(name):
    im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im);ink='#1c2732';light='#fff1d2'
    d.rounded_rectangle((2,2,29,29),radius=5,fill='#25303d',outline='#17222c');d.rounded_rectangle((4,4,27,27),radius=4,fill='#506476',outline='#d5bd83',width=2)
    if name.startswith('level_'):
        _,raw,state=name.split('_');level=int(raw);colors=['#c7ccd2','#99d8ac','#8fd1dc','#e7c476','#c5a5e3'];d.polygon([(8,10),(16,7),(24,10),(22,19),(16,24),(10,19)],fill=colors[level-1],outline=ink)
        for n in range(level):d.point((12+n*2,14),fill=light)
        d.ellipse((20,20,27,27),fill={'current':'#8edba2','lower':'#adb5bd','available':'#e3c377'}[state],outline=ink)
        if state=='current':d.line((22,23,23,25,26,21),fill=ink)
        elif state=='lower':d.line((22,23,25,23),fill=ink)
        else:d.line((23,21,23,25),fill=ink);d.line((21,23,25,23),fill=ink)
    elif name=='close':d.line((10,10,22,22),fill='#e98d83',width=4);d.line((22,10,10,22),fill='#e98d83',width=4)
    elif name=='my':d.ellipse((11,8,21,18),fill='#e2c18a',outline=ink);d.polygon([(8,24),(10,19),(16,18),(22,19),(24,24)],fill='#8fc6d1',outline=ink)
    elif name=='top':d.polygon([(8,10),(12,15),(16,8),(20,15),(24,10),(22,22),(10,22)],fill='#e7c877',outline=ink)
    else:d.rectangle((8,8,23,24),fill='#dfcea1',outline=ink);d.line((11,12,20,12),fill=light,width=2);d.line((11,16,20,16),fill=light,width=2);d.line((11,20,18,20),fill=light,width=2)
    return im


def save(name):
    path='menu/rank/'+name;ref='chrdk_core:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES:save(name)
    print(f'Generated {len(NAMES)} rank menu models')
