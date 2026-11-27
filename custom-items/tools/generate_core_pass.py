"""Generate virtual /pass menu controls. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw
ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_core'
NAMES=('status','payment','tiers','two_factor','password','sessions','stats')


def art(name):
    im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im);ink='#1c2732';light='#fff1d2'
    d.rounded_rectangle((2,2,29,29),radius=5,fill='#25303d',outline='#17222c')
    d.rounded_rectangle((4,4,27,27),radius=4,fill='#506476',outline='#d5bd83',width=2)
    if name=='status':d.ellipse((8,8,24,24),fill='#d5d3b8',outline=ink);d.line((16,10,16,16,20,18),fill=ink,width=2)
    elif name=='payment':d.polygon([(16,7),(24,13),(21,22),(16,25),(11,22),(8,13)],fill='#8ddcb5',outline=ink);d.line((10,13,22,13),fill=light,width=2)
    elif name=='tiers':
        d.rectangle((8,9,24,23),fill='#e3d3aa',outline=ink)
        for y in (12,16,20):d.ellipse((10,y,12,y+2),fill='#dbb66b');d.line((14,y+1,21,y+1),fill=light)
    elif name=='two_factor':d.polygon([(9,9),(16,7),(23,9),(22,18),(16,24),(10,18)],fill='#94c6d6',outline=ink);d.line((12,16,15,19,21,12),fill=light,width=2)
    elif name=='password':d.ellipse((8,8,16,16),outline='#eed17f',width=3);d.line((15,15,23,23),fill='#eed17f',width=3);d.line((20,20,20,23),fill='#eed17f',width=2)
    elif name=='sessions':d.ellipse((8,8,24,24),fill='#a9d5d2',outline=ink);d.polygon([(16,10),(19,16),(16,22),(13,16)],fill='#db887a',outline=ink)
    else:d.rectangle((8,8,23,24),fill='#e1c997',outline=ink);d.rectangle((11,17,13,21),fill='#7db5d2');d.rectangle((15,13,17,21),fill='#80c6a5');d.rectangle((19,10,21,21),fill='#dda777')
    return im


def save(name):
    path='menu/pass/'+name;ref='chrdk_core:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES:save(name)
    print(f'Generated {len(NAMES)} account menu models')
