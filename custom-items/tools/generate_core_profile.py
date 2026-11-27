"""Generate built-in profile controls. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_core'
NAMES=('chat','labels','friend','group','refresh','close')


def art(name):
    im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im);ink='#1c2732';light='#fff1d2'
    d.rounded_rectangle((2,2,29,29),radius=5,fill='#25303d',outline='#17222c');d.rounded_rectangle((4,4,27,27),radius=4,fill='#506476',outline='#d5bd83',width=2)
    if name=='chat':d.polygon([(8,9),(24,9),(24,20),(16,20),(12,24),(12,20),(8,20)],fill='#b6ded7',outline=ink);d.line((11,13,21,13),fill=light,width=2);d.line((11,17,19,17),fill=light)
    elif name=='labels':d.polygon([(7,10),(21,10),(25,14),(18,22),(7,22)],fill='#e4c786',outline=ink);d.ellipse((9,13,11,15),fill=ink);d.line((14,14,21,14),fill=light,width=2);d.line((14,18,19,18),fill=light)
    elif name=='friend':d.ellipse((9,8,19,18),fill='#e2c08d',outline=ink);d.polygon([(7,24),(9,19),(14,18),(19,19),(21,24)],fill='#a8d2d5',outline=ink);d.line((23,16,23,23),fill='#9fe1a8',width=2);d.line((20,19,26,19),fill='#9fe1a8',width=2)
    elif name=='group':d.line((9,7,9,24),fill=light,width=2);d.polygon([(10,8),(23,8),(23,19),(17,17),(10,19)],fill='#9ad4a6',outline=ink);d.ellipse((13,10,18,15),fill=light)
    elif name=='refresh':d.arc((8,8,24,24),40,310,fill='#e1cb88',width=3);d.polygon([(19,7),(25,9),(21,13)],fill='#e1cb88')
    else:d.line((10,10,22,22),fill='#e98d83',width=4);d.line((22,10,10,22),fill='#e98d83',width=4)
    return im


def save(name):
    path='menu/profile/'+name;ref='chrdk_core:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES:save(name)
    print(f'Generated {len(NAMES)} profile menu models')
