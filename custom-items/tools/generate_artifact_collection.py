"""Generate original collection GUI icons; requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

PACK = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_artifacts'
IDS = ('unknown', 'previous', 'next', 'rewards', 'progress', 'close')


def art(name):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((3, 3, 28, 28), radius=5, fill='#302c43', outline='#171622')
    d.rounded_rectangle((5, 5, 26, 26), radius=4, fill='#534765', outline='#d8ba7f', width=2)
    ink, gold, dark = '#f2e4ca', '#f3c776', '#352d3b'
    if name == 'unknown':
        d.polygon([(15, 8), (22, 12), (22, 20), (15, 24), (8, 20), (8, 12)], fill='#766f82', outline=ink)
        d.arc((12, 10, 19, 17), 180, 360, fill=ink, width=2); d.line((18, 14, 15, 18), fill=ink, width=2); d.point((15, 21), fill=ink)
    elif name in ('previous', 'next'):
        points = [(9, 15), (17, 8), (17, 12), (23, 12), (23, 19), (17, 19), (17, 23)]
        if name == 'next': points = [(31-x, y) for x, y in points]
        d.polygon(points, fill=ink, outline=gold)
    elif name == 'rewards':
        d.rectangle((8, 14, 23, 23), fill=gold, outline=dark); d.rectangle((7, 11, 24, 15), fill=ink, outline=dark)
        d.line((15, 11, 15, 23), fill='#bf795d', width=2); d.ellipse((10, 7, 15, 11), outline=ink, width=2); d.ellipse((16, 7, 21, 11), outline=ink, width=2)
    elif name == 'progress':
        d.polygon([(8, 9), (14, 8), (16, 10), (18, 8), (24, 9), (24, 23), (18, 22), (16, 24), (14, 22), (8, 23)], fill=ink)
        d.line((16, 10, 16, 23), fill=dark); d.line((10, 18, 14, 18), fill=gold, width=2); d.line((18, 13, 22, 13), fill=dark); d.line((18, 16, 22, 16), fill=dark)
    elif name == 'close':
        d.ellipse((8, 8, 23, 23), fill='#a34e5b', outline=ink, width=2)
        d.line((12, 12, 19, 19), fill=ink, width=2); d.line((19, 12, 12, 19), fill=ink, width=2)
    return im


def save(name):
    path = 'collection/' + name
    texture = PACK / 'textures/item' / (path + '.png'); texture.parent.mkdir(parents=True, exist_ok=True); art(name).save(texture)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_artifacts:item/' + path}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_artifacts:item/' + path}})]:
        target = PACK / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in IDS: save(name)
    print('Generated six artifact collection icons')
