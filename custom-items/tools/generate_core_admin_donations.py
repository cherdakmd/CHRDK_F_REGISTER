"""Generate admin donation journal controls. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_core'
NAMES=('prev','next','refresh','empty','status_ok','status_flaky','status_down','filter_all','filter_pending','filter_granted','filter_rejected','record_pending_name','record_pending_amount','record_pending_review','record_granted','record_rejected')


def art(name):
    im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im);ink='#1c2732';light='#fff1d2'
    d.rounded_rectangle((2,2,29,29),radius=5,fill='#302e39',outline='#1a1e27');d.rounded_rectangle((4,4,27,27),radius=4,fill='#665c64',outline='#d6bc83',width=2)
    if name.startswith(('record_','filter_')):
        prefix,state=name.split('_',1)
        if prefix=='record':d.rectangle((8,8,23,24),fill='#e1d1ac',outline=ink);d.line((11,12,20,12),fill=light,width=2);d.line((11,16,20,16),fill=light)
        else:d.polygon([(8,9),(24,9),(19,16),(19,23),(13,23),(13,16)],fill='#b4d3d4',outline=ink)
        color={'pending_name':'#d9b5dc','pending_amount':'#e5b884','pending_review':'#e4ce8b','pending':'#e4ce8b','granted':'#91d8a2','rejected':'#db8b85','all':'#a9bfda'}[state]
        d.ellipse((19,19,28,28),fill=color,outline=ink)
        if state=='pending_name':d.ellipse((22,21,25,24),fill=ink);d.line((21,26,26,26),fill=ink)
        elif state=='pending_amount':d.line((23,21,23,26),fill=ink);d.line((21,22,25,22,25,24,21,24,21,25,25,25),fill=ink)
        elif state in ('pending_review','pending'):d.line((23,21,23,24,26,25),fill=ink)
        elif state=='granted':d.line((21,24,23,26,27,21),fill=ink)
        elif state=='rejected':d.line((22,22,26,26),fill=ink);d.line((26,22,22,26),fill=ink)
        else:d.line((21,23,26,23),fill=ink);d.line((21,25,26,25),fill=ink)
    elif name.startswith('status_'):
        state=name.split('_')[1];color={'ok':'#8ed8a5','flaky':'#e4c675','down':'#dc8b80'}[state]
        d.ellipse((8,8,24,24),fill=color,outline=ink)
        if state=='ok':d.line((11,16,15,20,21,12),fill=light,width=2)
        elif state=='flaky':d.line((16,11,16,18),fill=ink,width=2);d.ellipse((15,21,17,23),fill=ink)
        else:d.line((12,12,20,20),fill=light,width=2);d.line((20,12,12,20),fill=light,width=2)
    elif name in ('prev','next'):
        points=[(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)]
        if name=='next':points=[(32-x,y) for x,y in points]
        d.polygon(points,fill='#b2d5d7',outline=ink)
    elif name=='refresh':d.arc((8,8,24,24),40,310,fill='#e2c785',width=3);d.polygon([(19,7),(25,9),(21,13)],fill='#e2c785')
    else:d.rectangle((9,8,23,24),fill='#d7d1b4',outline=ink);d.line((12,13,20,13),fill=light,width=2);d.line((12,18,20,18),fill=light);d.line((10,23,22,10),fill='#d28d86',width=2)
    return im


def save(name):
    path='menu/admin_donations/'+name;ref='chrdk_core:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES:save(name)
    print(f'Generated {len(NAMES)} admin donation models')
