"""Generate the virtual Honor editor icons. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_core'
NAMES = ('definitions', 'grants', 'filter', 'refresh', 'prev', 'next', 'back', 'close',
         'info', 'create', 'definition', 'grant_entry', 'name', 'format', 'badge', 'level',
         'description', 'join', 'delete', 'pages', 'empty_definitions', 'empty_grants')
INK = '#24253a'
CREAM = '#fff2cb'
GOLD = '#edcb7c'
TEAL = '#92d9c5'
RED = '#e18e8b'


def art(name):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((2, 2, 29, 29), radius=5, fill='#282a3a', outline='#191e29')
    d.rounded_rectangle((4, 4, 27, 27), radius=4, fill='#564b61', outline='#caa773', width=2)
    if name in ('definitions', 'definition', 'description', 'pages', 'empty_definitions'):
        d.rectangle((8, 7, 23, 25), fill='#e9d3a5', outline=INK)
        d.line((11, 11, 20, 11), fill='#997f72', width=2); d.line((11, 15, 20, 15), fill='#997f72')
        if name == 'definitions': d.polygon([(10, 22), (12, 19), (14, 22), (17, 18), (20, 22)], fill=GOLD, outline=INK)
        if name == 'definition': d.ellipse((17, 18, 25, 26), fill=TEAL, outline=INK)
        if name == 'description': d.polygon([(15, 23), (22, 16), (24, 18), (17, 25)], fill=TEAL, outline=INK)
        if name == 'pages': d.line((7, 9, 5, 9, 5, 26, 20, 26), fill=CREAM)
        if name == 'empty_definitions': d.line((8, 24, 24, 8), fill=RED, width=2)
    elif name in ('grants', 'grant_entry', 'empty_grants', 'name'):
        d.polygon([(7, 10), (11, 7), (23, 7), (25, 10), (25, 19), (18, 25), (7, 17)], fill='#d7bb89', outline=INK)
        d.ellipse((10, 10, 13, 13), fill=INK)
        if name == 'grants': d.line((14, 13, 22, 13), fill=CREAM, width=2); d.line((14, 17, 20, 17), fill=CREAM)
        if name == 'grant_entry': d.polygon([(17, 12), (19, 15), (23, 15), (20, 18), (21, 22), (17, 20), (14, 22), (15, 18), (12, 15), (16, 15)], fill=TEAL, outline=INK)
        if name == 'empty_grants': d.line((8, 23, 23, 8), fill=RED, width=2)
        if name == 'name': d.line((14, 12, 21, 12), fill=INK); d.line((14, 15, 20, 15), fill=INK); d.line((14, 18, 18, 18), fill=INK)
    elif name in ('prev', 'next', 'back'):
        pts = [(8, 16), (16, 9), (16, 13), (24, 13), (24, 19), (16, 19), (16, 23)]
        if name == 'next': pts = [(32-x, y) for x, y in pts]
        d.polygon(pts, fill=TEAL if name != 'back' else GOLD, outline=INK)
        if name == 'back': d.line((22, 9, 25, 9, 25, 23, 22, 23), fill=CREAM, width=2)
    elif name in ('close', 'delete'):
        d.ellipse((8, 8, 24, 24), fill=RED, outline=INK)
        if name == 'close': d.line((12, 12, 20, 20), fill=CREAM, width=2); d.line((20, 12, 12, 20), fill=CREAM, width=2)
        else: d.rectangle((12, 12, 20, 21), fill='#f0d4b6', outline=INK); d.line((11, 10, 21, 10), fill=INK, width=2); d.line((15, 14, 15, 19), fill=INK); d.line((18, 14, 18, 19), fill=INK)
    elif name == 'filter': d.polygon([(7, 8), (25, 8), (19, 16), (19, 23), (13, 25), (13, 16)], fill=TEAL, outline=INK)
    elif name == 'refresh': d.arc((8, 8, 24, 24), 40, 310, fill=TEAL, width=3); d.polygon([(19, 7), (25, 9), (21, 13)], fill=TEAL)
    elif name == 'info': d.ellipse((8, 8, 24, 24), fill=TEAL, outline=INK); d.ellipse((15, 11, 17, 13), fill=INK); d.line((16, 16, 16, 21), fill=INK, width=2)
    elif name == 'create': d.ellipse((8, 8, 24, 24), fill=TEAL, outline=INK); d.line((16, 11, 16, 21), fill=CREAM, width=3); d.line((11, 16, 21, 16), fill=CREAM, width=3)
    elif name == 'format':
        d.polygon([(8, 22), (14, 8), (18, 8), (24, 22), (20, 22), (19, 19), (13, 19), (12, 22)], fill=TEAL, outline=INK); d.line((14, 16, 18, 16), fill=INK)
    elif name == 'badge': d.polygon([(16, 7), (19, 12), (25, 13), (21, 17), (22, 24), (16, 21), (10, 24), (11, 17), (7, 13), (13, 12)], fill=GOLD, outline=INK)
    elif name == 'level': d.polygon([(7, 22), (10, 12), (14, 16), (16, 8), (19, 16), (23, 12), (25, 22)], fill=GOLD, outline=INK); d.line((9, 24, 23, 24), fill=CREAM, width=2)
    elif name == 'join': d.rectangle((9, 7, 22, 25), fill='#bd9074', outline=INK); d.rectangle((12, 10, 19, 22), fill='#7a6475', outline=INK); d.ellipse((17, 16, 19, 18), fill=GOLD)
    return im


def save(name):
    path = 'menu/honor/' + name; ref = 'chrdk_core:item/' + path
    target = ROOT / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); art(name).save(target)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': ref}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': ref}})]:
        target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in NAMES: save(name)
    print(f'Generated {len(NAMES)} honor menu models')
