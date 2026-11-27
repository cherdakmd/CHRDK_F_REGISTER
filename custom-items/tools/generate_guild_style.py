"""Generate virtual guild style previews; requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image,ImageDraw

ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_guilds'
KINDS={'name':('gold','aqua','green','violet','white','blue','aurora','sunset','forest','frost','ember','pearl'),
       'emblem':('star','diamond','spark','crown','sun','snow','moon','comet'),
       'frame':('gold','amethyst','ocean','forest','ember','frost'),
       'animation':('aurora','shine','pulse','prism','flame')}
STATES=('normal','personal','active','both')
NAMES=('info','back','close','off')+tuple(f'tab_{k}_{s}' for k in KINDS for s in ('normal','selected'))+tuple(f'preset_{k}_{p}_{s}' for k,values in KINDS.items() for p in values for s in STATES)
COLORS={'gold':('#e6c45d','#f7e699'),'aqua':('#68d3da','#a4f3ea'),'green':('#70cc78','#b5eca6'),
        'violet':('#b58ce3','#ead0f9'),'white':('#edf2f6','#ffffff'),'blue':('#608de2','#adc9fa'),
        'aurora':('#67e8f9','#c084fc'),'sunset':('#ffd166','#ef476f'),'forest':('#86efac','#14b8a6'),
        'frost':('#e0f2fe','#38bdf8'),'ember':('#fbbf24','#f97316'),'pearl':('#f5d0fe','#e0e7ff'),
        'amethyst':('#ae77d7','#e0bcfa'),'ocean':('#39afbd','#9fe9eb')}


def art(name):
    ink,light='#1c2732','#fff1d2';im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im)
    d.rounded_rectangle((2,2,29,29),radius=5,fill='#263039',outline='#151d24')
    d.rounded_rectangle((4,4,27,27),radius=4,fill='#465861',outline='#b99e6e',width=2)
    if name.startswith('preset_'):
        _,kind,preset,state=name.split('_');a,b=COLORS.get(preset,('#d7b76d','#f7e8b4'))
        if kind=='name':
            d.polygon([(7,10),(21,10),(25,14),(18,22),(7,22)],fill=a,outline=ink);d.ellipse((9,13,11,15),fill=ink)
            d.line((13,14,21,14),fill=b,width=2);d.line((13,18,19,18),fill=b,width=2)
        elif kind=='frame':
            d.rectangle((7,8,24,23),fill=a,outline=ink,width=2);d.rectangle((11,12,20,19),fill='#314451',outline=b,width=1)
        elif kind=='emblem':
            color='#e8c978'
            if preset in ('star','spark','comet'):
                d.polygon([(16,7),(18,13),(24,14),(19,17),(21,23),(16,19),(11,23),(13,17),(8,14),(14,13)],fill=color,outline=ink)
                if preset=='spark':d.ellipse((13,13,19,19),fill=light,outline=ink)
                if preset=='comet':d.line((9,23,13,19),fill=light,width=2);d.line((7,20,11,18),fill=light)
            elif preset=='diamond':d.polygon([(16,7),(24,15),(16,24),(8,15)],fill=color,outline=ink);d.line((10,15,22,15),fill=light)
            elif preset=='crown':d.polygon([(8,11),(12,16),(16,8),(20,16),(24,11),(22,22),(10,22)],fill=color,outline=ink)
            elif preset=='sun':d.ellipse((10,10,22,22),fill=color,outline=ink);d.line((16,7,16,9),fill=light,width=2);d.line((16,23,16,25),fill=light,width=2);d.line((7,16,9,16),fill=light,width=2);d.line((23,16,25,16),fill=light,width=2)
            elif preset=='snow':d.line((16,7,16,24),fill=light,width=2);d.line((8,11,24,20),fill=light,width=2);d.line((8,20,24,11),fill=light,width=2)
            else:d.ellipse((9,8,23,23),fill=color,outline=ink);d.ellipse((14,6,25,19),fill='#465861')
        else:
            if preset=='aurora':d.arc((7,8,25,23),180,355,fill='#8ce7ed',width=3);d.arc((8,10,24,25),180,355,fill='#c19af0',width=2)
            elif preset=='shine':d.polygon([(16,7),(18,13),(24,16),(18,18),(16,24),(14,18),(8,16),(14,13)],fill='#efcd78',outline=ink)
            elif preset=='pulse':d.line((8,17,11,17,13,12,16,22,19,14,21,17,24,17),fill='#ee959c',width=2)
            elif preset=='prism':d.polygon([(16,7),(24,16),(16,24),(8,16)],fill='#82c9df',outline=ink);d.line((16,7,16,24),fill='#d3a7ef',width=2);d.line((8,16,24,16),fill=light)
            else:d.polygon([(16,7),(11,15),(12,22),(16,25),(21,22),(22,16),(19,13),(18,17)],fill='#f3a259',outline=ink);d.polygon([(16,15),(14,20),(16,23),(19,20)],fill='#f5d47c')
        if state in ('personal','both'):d.ellipse((5,22,11,28),fill='#93c8e7',outline=ink)
        if state in ('active','both'):d.ellipse((21,22,27,28),fill='#8edba2',outline=ink)
    elif name.startswith('tab_'):
        _,kind,state=name.split('_'); color={'name':'#8dc9d5','emblem':'#e5c377','frame':'#c29be0','animation':'#e89987'}[kind]
        d.rectangle((8,8,24,23),fill=color,outline=ink)
        if kind=='name':d.line((11,13,21,13),fill=light,width=2);d.line((11,18,19,18),fill=light,width=2)
        elif kind=='emblem':d.polygon([(16,10),(18,14),(22,15),(19,18),(20,21),(16,19),(12,21),(13,18),(10,15),(14,14)],fill=light,outline=ink)
        elif kind=='frame':d.rectangle((11,11,21,20),fill='#465861',outline=light,width=2)
        else:d.line((10,18,13,14,16,19,19,12,22,16),fill=light,width=2)
        if state=='selected':d.line((9,26,23,26),fill=light,width=2)
    elif name in ('close','off'):
        d.line((10,10,22,22),fill='#e98d83',width=4);d.line((22,10,10,22),fill='#e98d83',width=4)
        if name=='off':d.ellipse((20,20,27,27),fill='#aeb5bd',outline=ink)
    elif name=='back':d.polygon([(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)],fill='#8bc5d4',outline=ink)
    else:d.polygon([(9,9),(16,7),(23,9),(22,18),(16,24),(10,18)],fill='#e0c282',outline=ink);d.ellipse((13,12,19,18),fill=light,outline=ink)
    return im


def save(name):
    path='menu/style/'+name;ref='chrdk_guilds:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES:save(name)
    print(f'Generated {len(NAMES)} Guilds style models')
