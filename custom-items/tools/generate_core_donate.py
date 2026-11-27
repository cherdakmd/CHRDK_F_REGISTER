"""Generate virtual donate menu icons. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw
ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_core'
NAMES=('pay','admin',*(f'tier_{i}' for i in range(1,8)),'empty','pending','granted','rejected')


def art(name):
    im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im);ink='#1c2732';light='#fff1d2'
    d.rounded_rectangle((2,2,29,29),radius=5,fill='#25303d',outline='#17222c');d.rounded_rectangle((4,4,27,27),radius=4,fill='#506476',outline='#d5bd83',width=2)
    if name.startswith('tier_'):
        i=int(name[-1]);colors=['#d7a174','#d4d9de','#edd07d','#9ed8e2','#92d8ab','#949eab','#d7b3e9'];d.polygon([(16,7),(24,13),(21,22),(16,25),(11,22),(8,13)],fill=colors[i-1],outline=ink);d.line((10,13,22,13),fill=light,width=2)
        for n in range(i): d.point((10+n*2,26),fill=light)
    elif name=='pay':d.ellipse((8,8,24,24),fill='#93dbac',outline=ink);d.line((16,10,16,22),fill=light,width=2);d.line((13,13,19,13,19,16,13,16,13,19,19,19),fill=light,width=2)
    elif name=='admin':d.rectangle((8,7,23,24),fill='#dfc68e',outline=ink);d.line((11,12,20,12),fill=light,width=2);d.line((11,16,20,16),fill=light,width=2);d.line((11,20,18,20),fill=light,width=2)
    else:
        d.rectangle((9,7,22,24),fill='#dcd4b3',outline=ink);d.line((12,12,19,12),fill=light);d.line((12,16,19,16),fill=light)
        color={'empty':'#aeb9bc','pending':'#e3c376','granted':'#91d9a3','rejected':'#df8a80'}[name];d.ellipse((19,19,27,27),fill=color,outline=ink)
        if name=='pending':d.line((23,21,23,23,25,24),fill=ink)
        elif name=='granted':d.line((21,23,23,25,26,21),fill=ink)
        elif name=='rejected':d.line((21,21,25,25),fill=ink);d.line((25,21,21,25),fill=ink)
        else:d.line((21,23,25,23),fill=ink)
    return im


def save(name):
    path='menu/donate/'+name;ref='chrdk_core:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES:save(name)
    print(f'Generated {len(NAMES)} donation menu models')
