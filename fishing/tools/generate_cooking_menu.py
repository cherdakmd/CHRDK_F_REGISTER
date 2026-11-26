"""Generate original virtual cooking menu icons, without altering cooked items. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

PACK = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_fishing'
RECIPES = ('fried', 'smoked', 'soup')
CONTROLS = ('confirm', 'help', 'back', 'close')


def art(name, selected=False):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    edge = '#f4c46c' if selected else '#8dd3df'
    d.rounded_rectangle((3, 3, 28, 28), radius=5, fill='#172d42', outline='#0c1a2b')
    d.rounded_rectangle((5, 5, 26, 26), radius=4, fill='#285770', outline=edge, width=2)
    ink, gold, dark, aqua = '#f8e9c7', '#f4c46c', '#294054', '#8ee0e4'
    if name == 'fried':
        d.ellipse((7, 12, 22, 22), fill='#182d3c', outline=ink, width=2); d.line((21, 19, 26, 24), fill=gold, width=3)
        d.polygon([(9, 17), (12, 14), (17, 14), (20, 17), (17, 20), (12, 20)], fill='#ecaa63'); d.point((17, 16), fill=dark)
    elif name == 'smoked':
        d.polygon([(8, 18), (11, 14), (17, 13), (22, 16), (22, 19), (17, 22), (11, 22)], fill='#cb8560', outline=dark)
        d.polygon([(10, 18), (7, 15), (7, 21)], fill='#cb8560'); d.point((19, 17), fill=ink)
        d.line([(12, 11), (11, 9), (12, 7)], fill=ink); d.line([(17, 11), (16, 9), (17, 7)], fill=ink)
    elif name == 'soup':
        d.polygon([(7, 15), (24, 15), (22, 22), (19, 24), (12, 24), (9, 22)], fill='#a47055', outline=ink)
        d.ellipse((8, 12, 23, 18), fill='#ebbd75', outline=dark); d.rectangle((13, 14, 15, 15), fill='#7aa76a'); d.point((19, 16), fill='#d2785e')
        d.line((13, 8, 13, 11), fill=ink); d.line((19, 7, 19, 11), fill=ink)
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
    if selected:
        d.ellipse((20, 20, 28, 28), fill='#55a67b', outline=ink)
        d.line([(22, 24), (24, 26), (27, 22)], fill=ink, width=2)
    return im


def save(name, image):
    path = 'menu/cooking/' + name
    target = PACK / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); image.save(target)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_fishing:item/' + path}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_fishing:item/' + path}})]:
        target = PACK / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for recipe in RECIPES:
        save(recipe, art(recipe)); save(recipe + '_selected', art(recipe, True))
    for control in CONTROLS: save(control, art(control))
    print('Generated ten cooking menu icons')
