"""Original transparent 32px icons for newly purchased Fishing bait. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

PACK = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_fishing'
KINDS = ('quick', 'rare', 'large')


def art(kind):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    dark, ink, gold = '#352d40', '#f4e6cb', '#f3bf66'
    if kind == 'quick':
        d.line([(7, 24), (9, 19), (12, 18), (16, 21), (20, 20), (22, 16), (19, 12)], fill=dark, width=8, joint='curve')
        d.line([(7, 24), (9, 19), (12, 18), (16, 21), (20, 20), (22, 16), (19, 12)], fill='#e99678', width=5, joint='curve')
        d.ellipse((17, 10, 22, 15), fill='#f8b798', outline=dark); d.point((19, 12), fill=dark)
        d.line((7, 12, 14, 12), fill='#8ed5e2', width=2); d.line((8, 9, 12, 9), fill='#8ed5e2')
    elif kind == 'rare':
        d.rectangle((10, 10, 22, 26), fill='#5b4884', outline=dark, width=2)
        d.polygon([(11, 10), (13, 7), (19, 7), (21, 10)], fill='#9ccbcf', outline=dark)
        d.rectangle((13, 5, 19, 7), fill=gold, outline=dark)
        d.rectangle((12, 15, 20, 22), fill='#c6addd'); d.line((16, 16, 16, 21), fill=ink); d.line((14, 19, 18, 19), fill=ink)
        d.line((24, 9, 24, 13), fill=gold); d.line((22, 11, 26, 11), fill=gold); d.point((7, 15), fill=gold)
    elif kind == 'large':
        d.ellipse((8, 9, 23, 23), fill='#d48d48', outline=dark, width=2)
        d.polygon([(11, 11), (16, 8), (21, 11), (21, 17), (16, 22), (11, 17)], fill=gold, outline=dark)
        d.line([(14, 15), (16, 13), (19, 15), (16, 17), (14, 15)], fill=ink, width=2)
        d.line([(16, 23), (17, 27), (20, 27), (21, 24)], fill='#c2d4dd', width=2)
        d.arc((20, 21, 25, 27), 0, 180, fill='#c2d4dd', width=2)
        d.line((15, 6, 18, 6), fill=dark, width=2)
    else: raise ValueError(kind)
    return im


def save(name):
    path = 'bait/' + name
    target = PACK / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); art(name).save(target)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_fishing:item/' + path}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_fishing:item/' + path}})]:
        target = PACK / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for kind in KINDS: save(kind)
    print('Generated three standalone bait item textures')
