"""Draw virtual Generator GUI controls. Does not change inventory items. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_generator'
STATUSES = ('working', 'no_lava', 'no_water', 'no_cells', 'unloaded', 'carried')
MODULES = ('speed', 'capacity', 'luck')
CONTROLS = ('rebirth', 'take', 'close', 'fuel_help', 'fuel_total', 'container_refresh', 'fuel_open', 'fuel_back', 'container_settings', 'container_close')
NAMES = (tuple('status_' + s for s in STATUSES) + tuple('tier_' + str(n) for n in range(1,16))
         + tuple('module_' + m + '_' + s for m in MODULES for s in ('available', 'max'))
         + ('upgrade_tier_available', 'upgrade_tier_max', 'chunk_available', 'chunk_owned')
         + ('bunker_empty', 'bunker_partial', 'bunker_full') + CONTROLS)
DIGITS = {'0': ('111','101','101','101','111'), '1': ('010','110','010','010','111'),
          '2': ('111','001','111','100','111'), '3': ('111','001','111','001','111'),
          '4': ('101','101','111','001','001'), '5': ('111','100','111','001','111'),
          '6': ('111','100','111','101','111'), '7': ('111','001','010','010','010'),
          '8': ('111','101','111','101','111'), '9': ('111','101','111','001','111')}


def digits(draw, value, x, y, color, scale=1):
    for digit in str(value):
        for iy,row in enumerate(DIGITS[digit]):
            for ix, pixel in enumerate(row):
                if pixel == '1': draw.rectangle((x + ix*scale, y + iy*scale, x + (ix+1)*scale-1, y + (iy+1)*scale-1), fill=color)
        x += 4*scale


def art(name):
    colors = {'working':'#75d799','no_lava':'#eb9274','no_water':'#77bce7','no_cells':'#e4c277',
              'unloaded':'#a5acbb','carried':'#d3b6eb','speed':'#87c5ef','capacity':'#daa878','luck':'#ba9ce6'}
    ink, light = '#18212c', '#fff1d5'
    im = Image.new('RGBA', (32,32)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((2,2,29,29), radius=5, fill='#222a33', outline='#111921')
    d.rounded_rectangle((4,4,27,27), radius=4, fill='#46535a', outline='#c19b61', width=2)
    if name.startswith('tier_'):
        tier=int(name[5:]); color = ('#aab1b5','#b7a695','#a3bac6','#d7b36a','#7bd2df')[min(4,(tier-1)//3)]
        d.polygon([(16,7),(23,11),(23,20),(16,24),(9,20),(9,11)], fill=color, outline=ink)
        digits(d,tier,13 if tier<10 else 10,13,ink,1 if tier>=10 else 2)
    elif name.startswith('status_'):
        state=name[7:]; color=colors[state]; d.ellipse((7,7,24,24), fill=color, outline=ink)
        if state == 'working': d.line((10,16,14,20,21,11), fill=light, width=3)
        elif state in ('no_lava','no_water'):
            d.polygon([(16,8),(11,15),(11,19),(16,22),(21,19),(21,15)], fill=light, outline=ink); d.line((10,23,23,9), fill=ink, width=2)
        elif state == 'no_cells':
            d.rectangle((11,11,21,21), outline=light, width=2); d.line((12,20,20,12), fill=ink, width=2)
        elif state == 'unloaded': d.line((11,16,21,16), fill=light, width=3)
        else: d.rectangle((11,12,21,21), fill=light, outline=ink); d.arc((12,8,20,17),180,360,fill=light,width=2)
    elif name.startswith('module_'):
        _,module,state=name.split('_'); color=colors[module]; d.ellipse((8,8,23,23), fill=color, outline=ink)
        if module == 'speed': d.polygon([(17,8),(12,16),(16,16),(14,23),(21,14),(17,14)], fill=light, outline=ink)
        elif module == 'capacity': d.rectangle((11,11,20,21), fill=light, outline=ink); d.line((11,15,20,15), fill=ink)
        else: d.polygon([(16,9),(18,14),(23,15),(19,18),(20,22),(16,20),(12,22),(13,18),(9,15),(14,14)], fill=light, outline=ink)
        d.rectangle((22,21,26,26), fill='#e4c06b' if state=='max' else '#80c99a', outline=ink)
        d.line((23,23,25,23),fill=light); 
        if state=='available': d.line((24,22,24,24),fill=light)
    elif name.startswith('upgrade_tier_'):
        d.polygon([(16,7),(23,15),(19,15),(19,23),(13,23),(13,15),(9,15)], fill='#99d395', outline=ink)
        if name.endswith('_max'): d.line((10,25,22,25), fill=light, width=2)
    elif name.startswith('chunk_'):
        d.polygon([(9,12),(16,8),(23,12),(23,20),(16,24),(9,20)], fill='#b6a0e5', outline=ink)
        d.line((9,12,16,16,23,12), fill=light); d.line((16,16,16,24), fill=light)
        if name.endswith('_owned'): d.line((19,23,22,26,26,20), fill='#8ff2b1', width=2)
    elif name.startswith('bunker_'):
        d.rectangle((8,10,24,23), fill='#ba8852', outline=ink); d.rectangle((8,10,24,14), fill='#e1b66f', outline=ink); d.rectangle((14,14,18,18), fill=light, outline=ink)
        fill={'bunker_empty':0,'bunker_partial':7,'bunker_full':14}[name]
        d.rectangle((8,25,23,26), fill=ink)
        if fill: d.rectangle((9,25,8+fill,26), fill='#8ce2a6')
    else:
        color='#85bed4'
        if name in ('close','container_close','take'): color='#e18179'
        d.rounded_rectangle((8,8,23,23), radius=3, fill=color, outline=ink)
        if name in ('close','container_close'):
            d.line((11,11,20,20),fill=light,width=3); d.line((20,11,11,20),fill=light,width=3)
            if name=='container_close': d.point((25,25),fill=light)
        elif name == 'take': d.ellipse((11,12,20,20), outline=light, width=2); d.line((16,7,16,14), fill=light, width=2)
        elif name == 'rebirth': d.arc((10,10,21,21),45,330,fill=light,width=2); d.polygon([(20,9),(23,10),(21,14)],fill=light)
        elif name == 'fuel_total': d.ellipse((10,10,21,21),outline=light,width=2); d.line((16,11,16,16,20,18),fill=ink,width=2)
        elif name == 'fuel_help': d.text((12,9),'?',fill=light)
        elif name == 'container_settings': d.line((11,12,21,12),fill=light,width=2); d.line((11,16,21,16),fill=light,width=2); d.line((11,20,21,20),fill=light,width=2); d.rectangle((14,11,16,13),fill=ink); d.rectangle((18,15,20,17),fill=ink)
        elif name == 'container_refresh': d.arc((10,10,21,21),30,310,fill=light,width=2); d.polygon([(20,9),(23,11),(20,14)],fill=light)
        elif name == 'fuel_open': d.polygon([(16,8),(11,16),(13,22),(19,22),(21,16)],fill='#f5cb7f',outline=ink); d.polygon([(16,14),(14,18),(16,20),(18,18)],fill='#f28a63')
        elif name == 'fuel_back': d.polygon([(10,16),(17,10),(17,13),(22,13),(22,19),(17,19),(17,22)],fill=light,outline=ink)
    return im


def save(name):
    path = 'menu/' + name; ref = 'chrdk_generator:item/' + path
    target = ROOT / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True,exist_ok=True); art(name).save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}), ('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT / folder / (path+'.json'); target.parent.mkdir(parents=True,exist_ok=True); target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__ == '__main__':
    for name in NAMES: save(name)
    print(f'Generated {len(NAMES)} Generator GUI models')
