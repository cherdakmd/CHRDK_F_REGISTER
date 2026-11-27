"""Generate atlas menu models; requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_core'
NAMES=('unknown','visited','complete','summary','task_progress','task_done','study','prev','next','back','close')


def art(name):
    im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im);ink='#1c2732';light='#fff1d2'
    d.rounded_rectangle((2,2,29,29),radius=5,fill='#263d3c',outline='#152420')
    d.rounded_rectangle((4,4,27,27),radius=4,fill='#52716a',outline='#d1bd83',width=2)
    if name in ('unknown','visited','complete'):
        d.polygon([(7,9),(13,7),(19,9),(25,7),(25,22),(19,24),(13,22),(7,24)],fill={'unknown':'#a9b4ad','visited':'#ddd6aa','complete':'#a6dc99'}[name],outline=ink)
        d.line((13,8,13,22),fill='#8d9b87');d.line((19,9,19,23),fill='#8d9b87')
        if name=='unknown':d.line((15,12,17,12,18,14,16,16,16,18),fill=light,width=2);d.point((16,20),fill=light)
        elif name=='visited':d.ellipse((14,13,19,18),fill='#d67765',outline=ink)
        else:d.line((13,16,16,19,22,12),fill=light,width=2)
    elif name=='summary':
        d.ellipse((8,8,24,24),fill='#b9d5c0',outline=ink);d.polygon([(16,9),(19,15),(16,22),(13,17)],fill='#d57c6c',outline=ink);d.ellipse((15,15,17,17),fill=light)
    elif name in ('task_progress','task_done'):
        d.rectangle((8,8,23,24),fill='#dfd3a6',outline=ink);d.line((11,12,20,12),fill=light,width=2);d.line((11,16,20,16),fill=light)
        d.ellipse((19,19,27,27),fill='#8ad9a7' if name=='task_done' else '#e5c778',outline=ink)
        if name=='task_done':d.line((21,23,23,25,26,21),fill=ink)
        else:d.line((23,21,23,23,25,24),fill=ink)
    elif name=='study':d.ellipse((8,7,19,18),fill='#9bd4db',outline=ink,width=2);d.line((18,18,24,24),fill='#dec187',width=4)
    elif name=='close':d.line((10,10,22,22),fill='#e98d83',width=4);d.line((22,10,10,22),fill='#e98d83',width=4)
    else:
        points=[(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)]
        if name=='next':points=[(32-x,y) for x,y in points]
        d.polygon(points,fill='#b1d8d5',outline=ink)
        if name=='back':d.ellipse((21,21,26,26),fill='#e2c788',outline=ink)
    return im


def save(name):
    path='menu/atlas/'+name;ref='chrdk_core:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES:save(name)
    print(f'Generated {len(NAMES)} atlas menu models')
