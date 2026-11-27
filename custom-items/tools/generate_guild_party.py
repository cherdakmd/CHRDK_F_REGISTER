"""Generate virtual party navigation tiles. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_guilds'
NAMES = ('empty', 'group', 'invite', 'accept', 'decline', 'leave', 'close', 'prev', 'back', 'next')


def art(name):
    ink, light = '#1c2732', '#fff1d2'
    image = Image.new('RGBA', (32,32)); d = ImageDraw.Draw(image)
    d.rounded_rectangle((2,2,29,29), radius=5, fill='#263039', outline='#151d24')
    d.rounded_rectangle((4,4,27,27), radius=4, fill='#465861', outline='#b99e6e', width=2)
    if name in ('empty','group','invite'):
        color = '#aab8bd' if name=='empty' else '#88c9d2'
        d.ellipse((11,8,18,15),fill=color,outline=ink); d.rounded_rectangle((8,17,21,24),radius=3,fill=color,outline=ink)
        if name=='group': d.ellipse((18,10,23,15),fill='#d7c68b',outline=ink); d.rectangle((20,17,24,22),fill='#d7c68b',outline=ink)
        if name=='invite': d.ellipse((20,19,27,26),fill='#8fdb9f',outline=ink); d.line((22,23,25,23),fill=ink); d.line((23,21,23,25),fill=ink)
        if name=='empty': d.line((10,23,21,11),fill='#d99082',width=2)
    elif name in ('accept','decline','close'):
        color = '#8fdba4' if name=='accept' else '#e28a80'
        d.ellipse((8,8,24,24),fill=color,outline=ink)
        if name=='accept': d.line((11,16,15,20,22,11),fill=light,width=3)
        else:
            d.line((12,12,20,20),fill=light,width=3); d.line((20,12,12,20),fill=light,width=3)
            if name=='close': d.rectangle((22,22,26,26),fill='#aebac0',outline=ink)
    elif name=='leave':
        d.rectangle((10,8,22,24),fill='#bd8e61',outline=ink); d.rectangle((17,15,19,17),fill=light); d.polygon([(12,17),(17,12),(17,15),(24,15),(24,19),(17,19),(17,22)],fill='#e1c789',outline=ink)
    elif name in ('prev','back','next'):
        points=[(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)]
        if name=='next': points=[(32-x,y) for x,y in points]
        d.polygon(points,fill='#8bc5d4',outline=ink)
        if name=='back': d.ellipse((21,21,26,26),fill='#e1c789',outline=ink)
    return image


def save(name):
    path='menu/party/'+name; ref='chrdk_guilds:item/'+path
    texture=ROOT/'textures/item'/(path+'.png'); texture.parent.mkdir(parents=True,exist_ok=True); art(name).save(texture)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json'); target.parent.mkdir(parents=True,exist_ok=True); target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES: save(name)
    print(f'Generated {len(NAMES)} Guilds party models')
