"""Generate original 32px pixel art for virtual bait menu controls. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

PACK = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_fishing'
KINDS = ('quick', 'rare', 'large')
CONTROLS = ('confirm', 'back', 'close', 'help')


def art(name, selected=False):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    edge = '#f4c46c' if selected else '#8dd3df'
    d.rounded_rectangle((3, 3, 28, 28), radius=5, fill='#172d42', outline='#0c1a2b')
    d.rounded_rectangle((5, 5, 26, 26), radius=4, fill='#285770', outline=edge, width=2)
    ink, gold, dark, aqua = '#f8e9c7', '#f4c46c', '#294054', '#8ee0e4'
    if name in KINDS:
        d.line([(10, 20), (10, 16), (12, 14), (15, 15), (17, 18), (20, 18), (21, 15), (20, 12)], fill='#efa886', width=4, joint='curve')
        d.point((19, 12), fill=dark)
        if name == 'quick':
            d.line((7, 10, 12, 10), fill=aqua, width=2); d.line((6, 13, 9, 13), fill=aqua)
        elif name == 'rare':
            d.line((15, 8, 15, 12), fill=gold); d.line((13, 10, 17, 10), fill=gold)
            d.line((22, 8, 22, 11), fill=gold); d.point((24, 10), fill=gold)
        else:
            d.polygon([(7, 8), (9, 10), (11, 7), (13, 10), (15, 8), (14, 13), (8, 13)], fill=gold)
        if selected:
            d.ellipse((20, 20, 28, 28), fill='#55a67b', outline=ink)
            d.line([(22, 24), (24, 26), (27, 22)], fill=ink, width=2)
    elif name == 'confirm':
        d.ellipse((8, 8, 23, 23), fill='#6aad84', outline=ink, width=2)
        d.line([(11, 15), (15, 19), (21, 12)], fill=ink, width=3)
    elif name == 'back':
        d.line([(11, 15), (17, 15), (21, 17), (21, 21), (17, 23), (12, 23)], fill=ink, width=2)
        d.polygon([(7, 15), (13, 10), (13, 20)], fill=aqua)
    elif name == 'close':
        d.ellipse((8, 8, 23, 23), fill='#ac5964', outline=ink, width=2)
        d.line((12, 12, 19, 19), fill=ink, width=2); d.line((19, 12, 12, 19), fill=ink, width=2)
    elif name == 'help':
        d.polygon([(8, 9), (14, 8), (16, 10), (18, 8), (24, 9), (24, 23), (18, 22), (16, 24), (14, 22), (8, 23)], fill=ink)
        d.line((16, 10, 16, 22), fill=dark); d.line((10, 13, 14, 13), fill=dark); d.line((18, 13, 22, 13), fill=dark)
    return im


def save(name, image):
    path = 'menu/bait/' + name
    texture = PACK / 'textures/item' / (path + '.png'); texture.parent.mkdir(parents=True, exist_ok=True); image.save(texture)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_fishing:item/' + path}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_fishing:item/' + path}})]:
        target = PACK / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in KINDS:
        save(name, art(name)); save(name + '_selected', art(name, True))
    for name in CONTROLS: save(name, art(name))
    print('Generated ten bait-menu icons')
