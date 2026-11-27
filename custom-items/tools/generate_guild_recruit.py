"""Generate virtual guild recruitment and consent tiles. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_guilds'
NAMES=('heading','empty','open','closed','open_invited','closed_invited','terms','confirm','cancel','back','prev','next')


def art(name):
    ink, light = '#1c2732', '#fff1d2'
    image=Image.new('RGBA',(32,32)); d=ImageDraw.Draw(image)
    d.rounded_rectangle((2,2,29,29),radius=5,fill='#263039',outline='#151d24')
    d.rounded_rectangle((4,4,27,27),radius=4,fill='#465861',outline='#b99e6e',width=2)
    if name in ('open','closed','open_invited','closed_invited'):
        color='#92d3a2' if name.startswith('open') else '#aeb2bc'
        d.line((10,7,10,24),fill=light,width=2);d.polygon([(11,8),(23,8),(23,19),(17,17),(11,19)],fill=color,outline=ink)
        if name.startswith('open'):d.line((13,13,15,15,20,10),fill=light,width=2)
        else:d.rectangle((15,13,20,18),fill=ink);d.arc((15,10,20,16),180,360,fill=ink,width=2)
        if name.endswith('_invited'):d.rectangle((20,20,27,26),fill='#efcb7c',outline=ink);d.line((21,21,24,23,26,21),fill=ink)
    elif name in ('heading','empty','terms'):
        color={'heading':'#9bc7d2','empty':'#acb9bd','terms':'#e2c68b'}[name]
        d.polygon([(9,7),(20,7),(23,10),(23,24),(9,24)],fill=color,outline=ink)
        d.line((12,12,20,12),fill=light,width=2);d.line((12,16,20,16),fill=light);d.line((12,20,18,20),fill=light)
        if name=='empty':d.line((10,23,23,10),fill='#d6827b',width=2)
        if name=='terms':d.ellipse((19,19,27,27),fill='#e2aa75',outline=ink);d.line((22,21,24,21,24,24),fill=ink,width=2)
    elif name in ('confirm','cancel'):
        d.ellipse((8,8,24,24),fill='#8dd89f' if name=='confirm' else '#e08d83',outline=ink)
        if name=='confirm':d.line((11,16,15,20,22,11),fill=light,width=3)
        else:d.line((12,12,20,20),fill=light,width=3);d.line((20,12,12,20),fill=light,width=3)
    else:
        points=[(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)]
        if name=='next':points=[(32-x,y) for x,y in points]
        d.polygon(points,fill='#8bc5d4',outline=ink)
        if name=='back':d.ellipse((21,21,26,26),fill='#e1c789',outline=ink)
    return image


def save(name):
    path='menu/recruit/'+name;ref='chrdk_guilds:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES:save(name)
    print(f'Generated {len(NAMES)} Guilds recruitment models')
