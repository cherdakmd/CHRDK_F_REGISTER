"""Generate virtual Guilds main-menu tiles. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image,ImageDraw

ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_guilds'
NAMES=('recruit_list','recruit_open','recruit_closed','claims','mode_peaceful','mode_battle','personal','dues','style','back','close',
       'info_none','info_peaceful','info_battle','invite','treasury','upgrade_available','upgrade_max','war_none','war_cooldown','war_pending','war_active')


def art(name):
    ink,light='#1c2732','#fff1d2'
    im=Image.new('RGBA',(32,32));d=ImageDraw.Draw(im)
    d.rounded_rectangle((2,2,29,29),radius=5,fill='#263039',outline='#151d24')
    d.rounded_rectangle((4,4,27,27),radius=4,fill='#465861',outline='#b99e6e',width=2)
    if name.startswith('info_') or name.startswith('mode_'):
        color={'info_none':'#abb7be','info_peaceful':'#8fd7a7','info_battle':'#dc8e83','mode_peaceful':'#8fd7a7','mode_battle':'#dc8e83'}[name]
        d.polygon([(9,9),(16,7),(23,9),(22,18),(16,24),(10,18)],fill=color,outline=ink)
        if name.endswith('peaceful'):d.line((12,16,15,19,21,12),fill=light,width=2)
        elif name.endswith('battle'):d.line((12,11,20,20),fill=light,width=2);d.line((20,11,12,20),fill=light,width=2)
        else:d.line((12,16,20,16),fill=light,width=2)
        if name.startswith('mode_'):d.ellipse((20,20,26,26),fill='#e4c47b',outline=ink)
    elif name.startswith('war_'):
        d.line((10,10,22,22),fill='#c1d5dd',width=3);d.line((22,10,10,22),fill='#c1d5dd',width=3)
        color={'war_none':'#aeb7bc','war_cooldown':'#e0bc79','war_pending':'#a8b6df','war_active':'#e1867d'}[name]
        d.ellipse((19,19,27,27),fill=color,outline=ink)
        if name=='war_none':d.line((21,23,25,23),fill=ink,width=2)
        elif name=='war_cooldown':d.line((23,20,23,23,25,24),fill=ink)
        elif name=='war_pending':d.line((23,20,23,24),fill=ink);d.point((23,26),fill=ink)
        else:d.line((21,23,22,25,26,20),fill=ink)
    elif name.startswith('recruit_') or name=='invite':
        color='#9cd8a9' if name=='recruit_open' else '#abb5bc' if name=='recruit_closed' else '#9bc7d2'
        d.line((10,7,10,24),fill=light,width=2);d.polygon([(11,8),(23,8),(23,19),(17,17),(11,19)],fill=color,outline=ink)
        if name=='recruit_open':d.line((13,13,15,15,20,10),fill=light,width=2)
        if name=='recruit_closed':d.line((13,11,20,18),fill='#d87875',width=2)
        if name=='invite':d.rectangle((19,20,26,25),fill='#f0cd86',outline=ink);d.line((20,21,23,23,25,21),fill=ink)
    elif name in ('treasury','dues'):
        d.rectangle((8,12,24,23),fill='#bb9163',outline=ink);d.rectangle((8,9,24,14),fill='#e2c17c',outline=ink);d.rectangle((14,14,18,18),fill=light,outline=ink)
        if name=='dues':d.ellipse((20,20,27,27),fill='#8dcbd5',outline=ink)
    elif name.startswith('upgrade_'):
        d.polygon([(9,12),(13,12),(14,9),(20,9),(21,12),(24,12),(22,17),(19,18),(19,22),(22,24),(10,24),(13,22),(13,18),(11,17)],fill='#a8b8c6',outline=ink)
        d.ellipse((20,20,27,27),fill='#8ed9a4' if name.endswith('available') else '#d4b46f',outline=ink)
        if name.endswith('available'):d.line((22,24,25,24),fill=ink);d.line((23,22,23,26),fill=ink)
        else:d.line((22,24,25,24),fill=ink)
    elif name=='close':d.line((10,10,22,22),fill='#e98d83',width=4);d.line((22,10,10,22),fill='#e98d83',width=4)
    elif name=='back':d.polygon([(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)],fill='#8bc5d4',outline=ink)
    elif name=='claims':d.polygon([(8,14),(16,8),(24,14),(24,23),(8,23)],fill='#96ce9f',outline=ink);d.rectangle((14,18,18,23),fill=ink)
    elif name=='personal':d.polygon([(16,7),(23,14),(16,24),(9,14)],fill='#8bd5b5',outline=ink);d.line((11,14,21,14),fill=light)
    else:
        d.rectangle((8,8,24,23),fill='#d4b484',outline=ink);d.rectangle((11,11,21,20),fill='#8bc5d4',outline=ink);d.polygon([(12,19),(15,14),(18,17),(20,13),(21,19)],fill='#8bd09b')
    return im


def save(name):
    path='menu/main/'+name;ref='chrdk_guilds:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name in NAMES:save(name)
    print(f'Generated {len(NAMES)} Guilds main-menu models')
