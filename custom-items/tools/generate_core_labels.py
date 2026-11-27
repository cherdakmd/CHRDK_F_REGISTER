"""Generate label settings icons. Custom palette previews remain vanilla. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image,ImageDraw

ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_core'
LABELS=('rank','classes','guilds','quests','achievements','vk','header','expeditions')
STATES=('on','off','unavailable','waiting','unassigned')
PICKS=('available','selected','unavailable')
CONTROLS=('preview','color','order','presets','enable','disable','profile','reset','cosmetics','close','back','info')
NAMES=CONTROLS+tuple(f'label_{label}_{state}' for label in LABELS for state in STATES)+tuple(f'pick_{label}_{state}' for label in LABELS if label!='header' for state in PICKS)+('place_locked','place_empty','place_assigned','preset_empty','preset_filled')
INK='#1c2732';LIGHT='#fff1d2'


def symbol(d,label):
    if label=='rank':d.polygon([(9,10),(12,15),(16,8),(20,15),(23,10),(22,21),(10,21)],fill='#e7c877',outline=INK)
    elif label=='classes':d.line((10,21,21,10),fill='#d5e1e2',width=3);d.line((10,15,16,21),fill='#e7c877',width=2)
    elif label=='guilds':d.line((10,8,10,23),fill=LIGHT,width=2);d.polygon([(11,9),(23,9),(23,19),(17,17),(11,19)],fill='#97d0a7',outline=INK)
    elif label=='quests':d.rectangle((9,8,22,23),fill='#e4d1a4',outline=INK);d.line((12,12,19,12),fill=LIGHT,width=2);d.line((12,16,19,16),fill=LIGHT)
    elif label=='achievements':d.ellipse((10,8,22,20),fill='#e6c77b',outline=INK);d.polygon([(12,18),(12,24),(16,22),(20,24),(20,18)],fill='#a4c9dc',outline=INK)
    elif label=='vk':d.polygon([(8,10),(11,20),(15,20),(17,15),(20,20),(24,20),(20,14),(23,10),(19,10),(16,14),(14,10)],fill='#9bc8e9',outline=INK)
    elif label=='header':d.rectangle((8,9,24,22),fill='#d6d9c5',outline=INK);d.rectangle((10,11,22,13),fill='#83c5d1');d.line((11,16,21,16),fill=LIGHT);d.line((11,19,21,19),fill=LIGHT)
    else:d.ellipse((8,8,24,24),fill='#c5d8c7',outline=INK);d.polygon([(16,9),(19,15),(16,22),(13,17)],fill='#d57c6c',outline=INK)


def art(name):
    image=Image.new('RGBA',(32,32));d=ImageDraw.Draw(image)
    d.rounded_rectangle((2,2,29,29),radius=5,fill='#26323f',outline='#17222c');d.rounded_rectangle((4,4,27,27),radius=4,fill='#536a73',outline='#d3bc83',width=2)
    if name.startswith(('label_','pick_')):
        prefix,label,state=name.split('_');symbol(d,label)
        color={'on':'#90d7a5','off':'#b4babd','unavailable':'#d28a83','waiting':'#e4c578','unassigned':'#a5b6e0','available':'#d6c88b','selected':'#90d7a5'}[state]
        d.ellipse((20,20,28,28),fill=color,outline=INK)
        if state in ('on','selected'):d.line((22,24,24,26,27,21),fill=INK)
        elif state=='off':d.line((22,24,26,24),fill=INK)
        elif state=='unavailable':d.line((22,22,26,26),fill=INK);d.line((26,22,22,26),fill=INK)
        elif state=='waiting':d.line((24,21,24,24,26,25),fill=INK)
        elif state=='unassigned':d.ellipse((22,22,26,26),outline=INK)
        else:d.line((24,22,24,26),fill=INK);d.line((22,24,26,24),fill=INK)
        if prefix=='pick':d.point((7,7),fill=LIGHT)
    elif name.startswith('place_'):
        d.polygon([(7,10),(21,10),(25,14),(18,22),(7,22)],fill='#d9c995',outline=INK);d.ellipse((9,13,11,15),fill=INK)
        if name=='place_locked':d.rectangle((17,15,22,20),fill=INK);d.arc((17,12,22,18),180,360,fill=INK,width=2)
        elif name=='place_assigned':d.line((14,16,16,18,21,13),fill='#86c991',width=2)
        else:d.line((14,16,20,16),fill=LIGHT,width=2)
    elif name.startswith('preset_'):
        d.rectangle((8,8,23,24),fill='#e3d3ab',outline=INK)
        if name=='preset_filled':d.line((11,12,20,12),fill=LIGHT,width=2);d.line((11,16,20,16),fill=LIGHT,width=2);d.line((11,20,17,20),fill=LIGHT)
        else:d.line((14,16,19,16),fill=INK);d.line((16,14,16,19),fill=INK)
    elif name in ('close','disable'):
        d.line((10,10,22,22),fill='#e98d83',width=4);d.line((22,10,10,22),fill='#e98d83',width=4)
        if name=='disable':d.ellipse((21,21,27,27),fill='#b0bdc4',outline=INK)
    elif name in ('back','order'):
        d.polygon([(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)],fill='#b1d8d5',outline=INK)
        if name=='order':d.line((11,24,21,24),fill=LIGHT,width=2)
    elif name in ('preview','profile'):
        d.ellipse((11,8,21,18),fill='#e2c08d',outline=INK);d.polygon([(8,24),(10,19),(16,18),(22,19),(24,24)],fill='#a8d2d5',outline=INK)
        if name=='preview':d.ellipse((20,20,27,26),fill='#e9dec0',outline=INK);d.point((24,23),fill=INK)
    elif name=='enable':d.ellipse((8,8,24,24),fill='#93dca8',outline=INK);d.line((11,16,15,20,22,11),fill=LIGHT,width=3)
    elif name=='color':
        d.ellipse((8,8,24,24),fill='#dbc492',outline=INK)
        for x,y,c in ((11,13,'#e18b80'),(15,10,'#eacb7b'),(20,12,'#8bcbd5'),(11,18,'#8dd4a3'),(18,19,'#be9ada')):d.ellipse((x,y,x+2,y+2),fill=c)
    elif name=='presets':d.rectangle((8,8,23,24),fill='#e4d1a4',outline=INK);d.line((11,12,20,12),fill=LIGHT,width=2);d.line((11,16,20,16),fill=LIGHT,width=2)
    elif name=='reset':d.arc((8,8,24,24),40,310,fill='#e1cb88',width=3);d.polygon([(19,7),(25,9),(21,13)],fill='#e1cb88')
    elif name=='cosmetics':d.polygon([(16,7),(18,13),(24,15),(18,18),(16,24),(14,18),(8,15),(14,13)],fill='#d2ade6',outline=INK)
    else:d.ellipse((8,8,24,24),fill='#9bcbd9',outline=INK);d.line((16,14,16,21),fill=LIGHT,width=2);d.ellipse((15,10,17,12),fill=LIGHT)
    return image


def save(name):
    path='menu/labels/'+name;ref='chrdk_core:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES:save(name)
    print(f'Generated {len(NAMES)} label menu models')
