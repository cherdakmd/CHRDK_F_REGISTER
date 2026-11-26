"""Generate original virtual fish sale controls; requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_fishing'
ICONS = ('help', 'clear', 'empty', 'sell', 'back', 'close')

def art(name):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    ink, dark, aqua, gold = '#f7e9c5', '#20374a', '#8ee0e4', '#f4c46c'
    d.rounded_rectangle((3, 3, 28, 28), radius=5, fill='#172d42', outline='#0c1a2b')
    d.rounded_rectangle((5, 5, 26, 26), radius=4, fill='#285770' if name != 'empty' else '#44515c', outline='#8dd3df', width=2)
    if name in ('empty', 'sell'):
        d.rectangle((8, 12, 23, 23), fill='#926b4b', outline=ink)
        d.rectangle((7, 10, 24, 15), fill='#c79b62', outline=dark)
        d.rectangle((14, 14, 17, 19), fill=gold)
        if name == 'sell':
            d.ellipse((20, 19, 28, 27), fill='#55a67b', outline=ink)
            d.line([(22, 23), (24, 25), (27, 21)], fill=ink, width=2)
        else: d.line((10, 20, 21, 20), fill='#d2b596')
    elif name == 'clear':
        d.polygon([(7, 10), (24, 10), (19, 16), (18, 22), (14, 24), (14, 16)], fill='#92aec0', outline=ink)
        d.line((20, 20, 25, 25), fill='#f08080', width=2); d.line((25, 20, 20, 25), fill='#f08080', width=2)
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
        path = 'menu/sale/' + name
        target = ROOT / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); art(name).save(target)
        for folder, payload in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_fishing:item/' + path}}),
                                ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_fishing:item/' + path}})]:
            target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')

if __name__ == '__main__':
    generate()
    print('Generated six virtual sale menu icons')
