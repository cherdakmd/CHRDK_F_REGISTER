"""Generate static password keypad controls (never keys). Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_core'
NAMES=('backspace','clear','page','cancel','confirm','progress')


def art(name):
    im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im);ink='#1c2732';light='#fff1d2'
    d.rounded_rectangle((2,2,29,29),radius=5,fill='#25303d',outline='#17222c');d.rounded_rectangle((4,4,27,27),radius=4,fill='#506476',outline='#d5bd83',width=2)
    if name=='backspace':d.polygon([(7,16),(14,10),(24,10),(24,22),(14,22)],fill='#b6d8d6',outline=ink);d.line((16,13,21,19),fill=ink);d.line((21,13,16,19),fill=ink)
    elif name in ('clear','cancel'):d.line((10,10,22,22),fill='#e98d83',width=4);d.line((22,10,10,22),fill='#e98d83',width=4);d.rectangle((20,20,27,27),fill='#e3c77f' if name=='clear' else '#a6b5bb',outline=ink)
    elif name=='page':d.rectangle((8,8,22,23),fill='#d7d0af',outline=ink);d.rectangle((11,10,24,25),fill='#e5d8af',outline=ink);d.line((14,14,21,14),fill=light,width=2);d.line((14,19,21,19),fill=light,width=2)
    elif name=='confirm':d.ellipse((8,8,24,24),fill='#93dca8',outline=ink);d.line((11,16,15,20,22,11),fill=light,width=3)
    else:d.rectangle((8,9,24,23),fill='#d8c9a1',outline=ink);d.ellipse((11,14,13,16),fill=ink);d.ellipse((15,14,17,16),fill=ink);d.ellipse((19,14,21,16),fill=ink)
    return im


def save(name):
    path='menu/keypad/'+name;ref='chrdk_core:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES:save(name)
    print(f'Generated {len(NAMES)} static keypad controls')
