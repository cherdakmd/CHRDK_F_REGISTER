"""Create original 32px pixel-art for virtual Fishing album controls. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

PACK = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_fishing'
IDS = ('unknown', 'previous', 'next', 'back', 'page', 'reward_available', 'reward_waiting')


def draw_icon(name):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((3, 3, 28, 28), radius=5, fill='#172d42', outline='#0c1a2b')
    d.rounded_rectangle((5, 5, 26, 26), radius=4, fill='#285770', outline='#8dd3df', width=2)
    ink, gold, dark, aqua = '#f8e9c7', '#f4c46c', '#294054', '#8ee0e4'
    if name == 'unknown':
        d.polygon([(8, 16), (11, 12), (17, 12), (22, 15), (22, 17), (17, 20), (11, 20)], fill='#7f9fa7')
        d.polygon([(10, 16), (7, 13), (7, 19)], fill='#7f9fa7')
        d.arc((13, 10, 19, 15), 180, 360, fill=ink, width=2)
        d.line([(18, 13), (16, 15), (16, 17)], fill=ink, width=2); d.point((16, 19), fill=ink)
    elif name in ('previous', 'next'):
        points = [(8, 16), (16, 9), (16, 13), (23, 13), (23, 19), (16, 19), (16, 23)]
        if name == 'next': points = [(31 - x, y) for x, y in points]
        d.polygon(points, fill=ink, outline=gold)
    elif name == 'back':
        d.line([(11, 15), (17, 15), (21, 17), (21, 21), (17, 23), (12, 23)], fill=ink, width=2)
        d.polygon([(7, 15), (13, 10), (13, 20)], fill=aqua)
    elif name == 'page':
        d.polygon([(8, 9), (14, 8), (16, 10), (18, 8), (24, 9), (24, 23), (18, 22), (16, 24), (14, 22), (8, 23)], fill=ink)
        d.line((16, 10, 16, 22), fill=dark); d.line((10, 12, 14, 12), fill=aqua, width=2)
        d.line((18, 13, 22, 13), fill=dark); d.line((18, 16, 22, 16), fill=dark)
    elif name in ('reward_available', 'reward_waiting'):
        color = gold if name == 'reward_available' else '#95a8a7'
        d.rectangle((9, 13, 22, 23), fill=color, outline=dark)
        d.rectangle((8, 10, 23, 14), fill=ink if name == 'reward_available' else '#c2ceca', outline=dark)
        d.line((15, 11, 15, 23), fill='#b67458', width=2)
        d.ellipse((10, 7, 15, 11), outline=ink, width=2); d.ellipse((16, 7, 21, 11), outline=ink, width=2)
        if name == 'reward_available':
            d.line((23, 7, 23, 11), fill=gold); d.line((21, 9, 25, 9), fill=gold)
        else:
            d.line((21, 21, 25, 25), fill=dark, width=2)
    return im


def save(name):
    path = 'menu/album/' + name
    texture = PACK / 'textures/item' / (path + '.png')
    texture.parent.mkdir(parents=True, exist_ok=True); draw_icon(name).save(texture)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_fishing:item/' + path}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_fishing:item/' + path}})]:
        target = PACK / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in IDS: save(name)
    print('Generated seven Fishing album icons')
