"""Generate original virtual rod-upgrade controls; requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_fishing'
ICONS = ('help', 'empty', 'selected', 'maximum', 'prepare', 'confirm', 'back', 'close')

def art(name):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    ink, dark, aqua, gold = '#f7e9c5', '#20374a', '#8ee0e4', '#f4c46c'
    d.rounded_rectangle((3, 3, 28, 28), radius=5, fill='#172d42', outline='#0c1a2b')
    d.rounded_rectangle((5, 5, 26, 26), radius=4, fill='#285770', outline=gold if name in ('selected', 'confirm') else '#8dd3df', width=2)
    if name in ('empty', 'selected'):
        d.line((10, 22, 20, 9), fill=ink if name == 'selected' else '#7f9da8', width=2)
        d.line((20, 9, 23, 9, 24, 18), fill=aqua if name == 'selected' else '#7f9da8')
        d.ellipse((22, 17, 25, 20), fill='#d17d69' if name == 'selected' else '#7f9da8')
        if name == 'selected': d.ellipse((7, 20, 12, 25), fill=gold)
    elif name == 'maximum':
        d.polygon([(16, 6), (18, 12), (24, 12), (19, 16), (21, 23), (16, 19), (10, 23), (12, 16), (7, 12), (14, 12)], fill=gold, outline=ink)
    elif name == 'prepare':
        d.polygon([(8, 17), (12, 13), (22, 13), (24, 17), (20, 21), (9, 21)], fill=gold, outline=ink)
        d.line((11, 16, 22, 16), fill='#a77845')
    elif name == 'confirm':
        d.ellipse((8, 8, 23, 23), fill='#6aad84', outline=ink, width=2)
        d.line([(11, 15), (15, 19), (21, 12)], fill=ink, width=3)
    elif name == 'back':
        d.line([(11, 15), (17, 15), (21, 17), (21, 21), (17, 23), (12, 23)], fill=ink, width=2)
        d.polygon([(7, 15), (13, 10), (13, 20)], fill=aqua)
    elif name == 'close':
        d.ellipse((8, 8, 23, 23), fill='#ac5964', outline=ink, width=2)
        d.line((12, 12, 19, 19), fill=ink, width=2); d.line((19, 12, 12, 19), fill=ink, width=2)
    else:
        d.polygon([(8, 9), (14, 8), (16, 10), (18, 8), (24, 9), (24, 23), (18, 22), (16, 24), (14, 22), (8, 23)], fill=ink)
        d.line((16, 10, 16, 22), fill=dark); d.line((10, 13, 14, 13), fill=dark); d.line((18, 13, 22, 13), fill=dark)
    return im

def generate():
    for name in ICONS:
        path = 'menu/rod/' + name
        target = ROOT / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); art(name).save(target)
        for folder, payload in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_fishing:item/' + path}}),
                                ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_fishing:item/' + path}})]:
            target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')

if __name__ == '__main__':
    generate()
    print('Generated eight rod upgrade menu icons')
