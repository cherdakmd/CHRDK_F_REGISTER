"""Generate daily calendar GUI icons; requires Pillow. No real rewards are changed."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_daily'
NAMES = ('header', 'claimed', 'claimed_today', 'today', 'locked')
COLORS = {'header': '#d6b66d', 'claimed': '#76bd83', 'claimed_today': '#56d39e', 'today': '#edc274', 'locked': '#9e99ae'}


def art(name):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((2, 2, 29, 29), radius=5, fill='#292a38', outline='#181b25')
    d.rounded_rectangle((4, 4, 27, 27), radius=4, fill='#454456', outline=COLORS[name], width=2)
    ink, light = '#2b2c3c', '#fff2d6'
    if name == 'header':
        d.polygon([(8, 9), (15, 10), (16, 12), (17, 10), (24, 9), (24, 22), (17, 22), (16, 24), (15, 22), (8, 22)], fill='#e9d5a8', outline=ink)
        d.line((16, 12, 16, 22), fill='#a37a53'); d.line((10, 14, 14, 15), fill='#8b6d54')
        d.line((18, 14, 22, 13), fill='#8b6d54')
    elif name == 'today':
        d.rectangle((8, 14, 24, 23), fill='#af7240', outline=ink)
        d.polygon([(8, 14), (10, 9), (22, 9), (24, 14)], fill='#d7a05c', outline=ink)
        d.rectangle((14, 14, 18, 18), fill='#f6d481', outline=ink)
        d.point((7, 8), fill=light); d.line((24, 7, 24, 10), fill=light); d.line((22, 8, 26, 8), fill=light)
    elif name in ('claimed', 'claimed_today'):
        d.ellipse((8, 8, 24, 24), fill=COLORS[name], outline=ink)
        d.line((11, 16, 15, 20, 22, 12), fill=light, width=3)
        if name == 'claimed_today': d.line((9, 26, 22, 26), fill=light, width=2)
    else:
        d.arc((11, 7, 21, 20), 180, 360, fill=light, width=2)
        d.rounded_rectangle((9, 14, 23, 24), radius=2, fill=COLORS[name], outline=ink)
        d.line((16, 18, 16, 21), fill=light, width=2)
    return im


def save(name):
    path = 'menu/' + name; ref = 'chrdk_daily:item/' + path
    dest = ROOT / 'textures/item' / (path + '.png'); dest.parent.mkdir(parents=True, exist_ok=True); art(name).save(dest)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': ref}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': ref}})]:
        dest = ROOT / folder / (path + '.json'); dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in NAMES: save(name)
    print('Generated five virtual daily calendar icons')
