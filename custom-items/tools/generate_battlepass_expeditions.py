"""Generate virtual expedition menu tiles. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_battlepass'
CONTROLS = ('mark_current', 'marks', 'routes', 'journal', 'labels', 'claim', 'back', 'journal_back',
            'close', 'route_create', 'waypoint', 'saved_route')
NAMES = (tuple(f'license_{level}_{state}' for level in ('free', 'premium') for state in ('missing', 'owned'))
         + ('effect_on', 'effect_off', 'title_off', 'title_free', 'title_premium') + CONTROLS)


def art(name):
    ink, light = '#1b2330', '#fff1d4'
    image = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(image)
    d.rounded_rectangle((2, 2, 29, 29), radius=5, fill='#282f3a', outline='#111a24')
    d.rounded_rectangle((4, 4, 27, 27), radius=4, fill='#495461', outline='#caa66c', width=2)
    if name.startswith('license_'):
        _, level, state = name.split('_'); color = '#87d5c0' if level == 'free' else '#ebc96e'
        d.polygon([(16,7),(20,10),(24,11),(22,18),(16,24),(10,18),(8,11),(12,10)],fill=color,outline=ink)
        d.polygon([(16,10),(18,15),(16,19),(14,15)],fill=light,outline=ink)
        d.ellipse((20,20,28,28),fill='#89dca3' if state=='owned' else '#8997a0',outline=ink)
        if state=='owned': d.line((22,24,23,26,27,21),fill=ink,width=1)
        else: d.line((22,24,26,24),fill=light,width=2)
    elif name.startswith('title_'):
        state=name[6:]; color={'off':'#a3abb1','free':'#87d5c0','premium':'#ebc96e'}[state]
        d.polygon([(8,10),(20,10),(24,14),(17,23),(8,23)],fill=color,outline=ink); d.ellipse((10,13,12,15),fill=ink)
        if state=='off': d.line((12,21,22,11),fill='#e17f79',width=2)
        if state=='free': d.line((13,16,16,19,21,12),fill=light,width=2)
        if state=='premium': d.polygon([(13,15),(15,17),(17,12),(19,17),(21,15),(20,20),(14,20)],fill=light,outline=ink)
    elif name.startswith('effect_'):
        color='#f2d176' if name=='effect_on' else '#9ba5ae'
        d.polygon([(16,7),(18,13),(24,15),(18,17),(16,24),(14,17),(8,15),(14,13)],fill=color,outline=ink)
        d.ellipse((21,21,27,27),fill='#8ddba2' if name=='effect_on' else '#d5857e',outline=ink)
        if name=='effect_on': d.line((22,24,24,26,26,22),fill=ink)
        else: d.line((22,22,26,26),fill=light)
    elif name in ('back','journal_back'):
        d.polygon([(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)],fill='#90c8d9',outline=ink)
        if name=='journal_back': d.ellipse((21,21,26,26),fill='#e5c77d',outline=ink)
    elif name=='close':
        d.line((10,10,22,22),fill='#e98d83',width=4); d.line((22,10,10,22),fill='#e98d83',width=4)
    elif name in ('waypoint','marks','mark_current'):
        d.ellipse((10,7,22,19),fill='#e58f80',outline=ink); d.polygon([(11,15),(21,15),(16,24)],fill='#e58f80',outline=ink); d.ellipse((14,11,18,15),fill=light,outline=ink)
        if name=='marks': d.ellipse((21,20,27,26),fill='#90c8d9',outline=ink)
        if name=='mark_current': d.line((20,23,26,23),fill=light,width=2); d.line((23,20,23,26),fill=light,width=2)
    elif name in ('routes','saved_route','route_create'):
        d.polygon([(8,9),(13,8),(19,10),(24,8),(24,23),(19,24),(13,22),(8,24)],fill='#d9c997',outline=ink)
        d.line((10,19,13,16,16,18,21,12),fill='#b17265',width=2); d.ellipse((9,18,12,21),fill='#90c8d9',outline=ink); d.ellipse((20,11,23,14),fill='#e68d80',outline=ink)
        if name=='routes': d.rectangle((21,21,26,26),fill='#90c8d9',outline=ink)
        if name=='route_create': d.line((20,23,26,23),fill=light,width=2); d.line((23,20,23,26),fill=light,width=2)
    elif name=='journal':
        d.polygon([(8,9),(15,11),(16,23),(8,21)],fill='#a1c6d1',outline=ink); d.polygon([(16,11),(23,9),(23,21),(16,23)],fill=light,outline=ink); d.line((10,13,13,14),fill=ink); d.line((18,13,21,12),fill=ink)
    elif name=='labels':
        d.polygon([(8,10),(20,10),(24,14),(17,23),(8,23)],fill='#d0b5db',outline=ink); d.ellipse((10,13,12,15),fill=ink); d.line((14,16,20,16),fill=light,width=2)
    elif name=='claim':
        d.rectangle((8,12,24,23),fill='#bf9262',outline=ink); d.rectangle((8,9,24,14),fill='#e6c887',outline=ink); d.rectangle((14,14,18,18),fill=light,outline=ink); d.ellipse((20,20,27,27),fill='#8cdda2',outline=ink)
    return image


def save(name):
    path = 'menu/expedition/' + name; ref = 'chrdk_battlepass:item/' + path
    texture = ROOT / 'textures/item' / (path + '.png'); texture.parent.mkdir(parents=True, exist_ok=True); art(name).save(texture)
    for folder, data in [('models/item', {'parent':'minecraft:item/generated','textures':{'layer0':ref}}),
                         ('items', {'model':{'type':'minecraft:model','model':ref}})]:
        target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in NAMES: save(name)
    print(f'Generated {len(NAMES)} BattlePass expedition models')
