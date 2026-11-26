"""Generate opt-in Skills GUI navigation/state icons, not player items. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_skills'
TABS = ('skills', 'attributes', 'top', 'rebirth', 'potions', 'builds')
CONTROLS = ('guide', 'back', 'close', 'steps', 'summary', 'perk_active', 'perk_locked',
            'reset_attributes', 'confirm_attributes', 'reset_builds', 'confirm_builds')
PALETTE = {'skills': '#70b9d9', 'attributes': '#dd8573', 'top': '#e5c36d',
           'rebirth': '#be99df', 'potions': '#76d4bf', 'builds': '#daa56c'}


def plaque(accent, selected=False):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((2, 2, 29, 29), radius=6, fill='#282b3b', outline='#151926')
    d.rounded_rectangle((4, 4, 27, 27), radius=4, fill='#444458', outline=accent, width=2 if selected else 1)
    if selected:
        d.line((8, 26, 23, 26), fill='#fff4be', width=2)
        d.polygon([(16, 3), (13, 6), (19, 6)], fill='#fff4be')
    return im, d


def tab_art(name, selected):
    im, d = plaque(PALETTE[name], selected); c = PALETTE[name]; light = '#f7ebd0'; ink = '#293248'
    if name == 'skills':
        d.line((10, 22, 20, 9), fill='#cea174', width=3)
        d.line((8, 12, 11, 9, 19, 8, 22, 11), fill=c, width=3)
    elif name == 'attributes':
        d.polygon([(16, 8), (24, 12), (22, 21), (16, 24), (10, 21), (8, 12)], fill=c, outline=ink)
        d.line((16, 11, 16, 21), fill=light, width=2); d.line((12, 16, 20, 16), fill=light, width=2)
    elif name == 'top':
        d.rectangle((12, 17, 20, 22), fill=c); d.polygon([(9, 9), (23, 9), (21, 17), (16, 20), (11, 17)], fill=c, outline=ink)
        d.line((9, 11, 7, 11, 8, 16, 11, 17), fill=light); d.line((23, 11, 25, 11, 24, 16, 21, 17), fill=light)
    elif name == 'rebirth':
        d.polygon([(16, 7), (18, 13), (24, 11), (21, 16), (25, 19), (19, 19), (16, 25), (13, 19), (7, 19), (11, 16), (8, 11), (14, 13)], fill=c, outline=ink)
        d.ellipse((14, 14, 18, 18), fill=light)
    elif name == 'potions':
        d.polygon([(16, 7), (22, 13), (19, 23), (13, 23), (10, 13)], fill=c, outline=ink)
        d.line((12, 13, 16, 9, 17, 19), fill=light, width=1)
    else:
        d.polygon([(16, 7), (24, 11), (23, 20), (16, 24), (9, 20), (8, 11)], fill=c, outline=ink)
        d.line((16, 10, 16, 21), fill=light, width=2); d.line((16, 15, 11, 11), fill=light, width=2); d.line((16, 18, 21, 13), fill=light, width=2)
    return im


def control_art(name):
    accent = '#91bcd5'
    if name.startswith(('confirm_', 'reset_')): accent = '#ef8c77' if name.endswith('builds') else '#e5bd6d'
    if name == 'perk_active': accent = '#83d394'
    if name == 'perk_locked': accent = '#9993a8'
    im, d = plaque(accent, name.startswith('confirm_')); light = '#f8eed8'; ink = '#2d3140'
    if name in ('guide', 'steps', 'summary'):
        d.rectangle((9, 7, 22, 24), fill='#e9d4a3', outline=ink)
        d.line((12, 12, 19, 12), fill='#805b4b', width=2)
        d.line((12, 16, 19, 16), fill='#805b4b', width=2)
        if name == 'guide': d.ellipse((13, 18, 17, 22), fill=accent)
        elif name == 'steps': d.line((12, 21, 14, 19, 16, 21, 19, 18), fill=accent, width=2)
        else: d.line((12, 20, 19, 20), fill='#805b4b', width=2)
    elif name == 'back':
        d.polygon([(7, 16), (15, 9), (15, 13), (24, 13), (24, 19), (15, 19), (15, 23)], fill=accent, outline=ink)
    elif name == 'close':
        d.line((10, 10, 22, 22), fill='#ed8279', width=4); d.line((22, 10, 10, 22), fill='#ed8279', width=4)
    elif name.startswith('perk_'):
        d.polygon([(16, 7), (23, 11), (22, 20), (16, 24), (10, 20), (9, 11)], fill=accent, outline=ink)
        if name == 'perk_active': d.line((11, 16, 15, 20, 21, 12), fill=light, width=2)
        else:
            d.arc((13, 9, 19, 18), 180, 360, fill=light, width=2); d.rectangle((12, 15, 20, 21), outline=light)
    else:
        d.arc((8, 8, 23, 23), 45, 310, fill=accent, width=3)
        d.polygon([(19, 8), (25, 9), (22, 14)], fill=accent)
        if name.startswith('confirm_'):
            d.line((11, 17, 14, 20, 21, 12), fill=light, width=2)
        elif name.endswith('builds'):
            d.line((16, 11, 16, 21), fill=light, width=2); d.line((13, 14, 19, 14), fill=light, width=2)
        else: d.line((12, 16, 20, 16), fill=light, width=2)
    return im


def save(path, image):
    texture = ROOT / 'textures/item' / (path + '.png'); texture.parent.mkdir(parents=True, exist_ok=True); image.save(texture)
    ref = 'chrdk_skills:item/' + path
    for directory, obj in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': ref}}),
                           ('items', {'model': {'type': 'minecraft:model', 'model': ref}})]:
        file = ROOT / directory / (path + '.json'); file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(json.dumps(obj, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in TABS:
        for selected in (False, True): save('menu/tab/' + name + ('_selected' if selected else ''), tab_art(name, selected))
    for name in CONTROLS: save('menu/' + name, control_art(name))
    print('Generated 23 Skills menu navigation and state icons')
