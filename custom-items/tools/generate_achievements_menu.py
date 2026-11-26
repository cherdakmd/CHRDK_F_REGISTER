"""Generate original achievement category/state and navigation sprites. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_achievements'
CATEGORIES = ('path', 'combat', 'craft', 'social')
COLORS = {'path': '#8fc784', 'combat': '#e08679', 'craft': '#75b8d3', 'social': '#d3a1df', 'all': '#dec17b'}
CONTROLS = ('atlas', 'progress', 'prev', 'next', 'close')


def plaque(color, selected=False):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((2, 2, 29, 29), radius=6, fill='#292c3a', outline='#181925')
    d.rounded_rectangle((4, 4, 27, 27), radius=4, fill='#444455', outline=color, width=2 if selected else 1)
    if selected:
        d.line((8, 26, 23, 26), fill='#f9eabd', width=2)
        d.polygon([(16, 3), (13, 6), (19, 6)], fill='#f9eabd')
    return im, d


def emblem(d, name, color):
    dark, light, gold = '#252b38', '#f4e7c8', '#f3c769'
    if name == 'path':
        d.polygon([(8, 22), (11, 15), (15, 10), (19, 11), (17, 16), (22, 22)], fill=color, outline=dark)
        d.line((14, 22, 15, 19, 17, 17, 17, 13), fill=light, width=2)
    elif name == 'combat':
        d.polygon([(9, 23), (12, 17), (20, 8), (23, 7), (22, 11), (14, 20)], fill=light, outline=dark)
        d.line((10, 16, 16, 21), fill=color, width=2)
    elif name == 'craft':
        d.rectangle((9, 12, 22, 23), fill='#ad8058', outline=dark)
        d.polygon([(9, 12), (13, 8), (23, 8), (22, 12)], fill=color, outline=dark)
        d.line((12, 12, 12, 22), fill=dark); d.line((18, 12, 18, 22), fill=dark)
        d.line((10, 16, 21, 16), fill=light)
    elif name == 'social':
        d.ellipse((9, 8, 15, 14), fill=color, outline=dark); d.ellipse((17, 8, 23, 14), fill=light, outline=dark)
        d.rounded_rectangle((7, 15, 16, 23), radius=3, fill=color, outline=dark)
        d.rounded_rectangle((16, 15, 25, 23), radius=3, fill=light, outline=dark)
    else:
        d.polygon([(16, 7), (18, 13), (24, 11), (21, 16), (24, 20), (18, 19), (16, 24), (14, 19), (8, 20), (11, 16), (8, 11), (14, 13)], fill=gold, outline=dark)


def category_art(name, mode):
    selected = mode == 'selected'; im, d = plaque(COLORS[name], selected); emblem(d, name, COLORS[name])
    if mode in ('unlocked', 'locked'):
        d.ellipse((19, 19, 31, 31), fill='#252c3a')
        d.ellipse((20, 20, 30, 30), fill='#6bc581' if mode == 'unlocked' else '#9a91a5')
        if mode == 'unlocked': d.line((22, 25, 24, 27, 28, 22), fill='#fff5dc', width=2)
        else:
            d.arc((23, 21, 27, 27), 180, 360, fill='#fff5dc'); d.rectangle((22, 25, 28, 29), outline='#fff5dc')
    return im


def control_art(name):
    im, d = plaque('#b7bcd2'); light, dark = '#f2e4c6', '#292b3b'
    if name == 'atlas':
        d.polygon([(8, 8), (13, 7), (18, 9), (24, 7), (24, 22), (18, 24), (13, 22), (8, 24)], fill='#d7ca9b', outline=dark)
        d.line((13, 8, 13, 22), fill='#8d806e'); d.line((18, 10, 18, 23), fill='#8d806e'); d.ellipse((14, 13, 19, 18), fill='#6b9d98')
    elif name == 'progress':
        d.polygon([(10, 9), (22, 9), (21, 17), (16, 20), (11, 17)], fill='#e6bf6a', outline=dark)
        d.rectangle((13, 20, 19, 23), fill='#e6bf6a', outline=dark); d.ellipse((14, 12, 18, 16), fill=light)
    elif name in ('prev', 'next'):
        points = [(8, 16), (16, 9), (16, 13), (24, 13), (24, 19), (16, 19), (16, 23)]
        if name == 'next': points = [(32-x, y) for x, y in points]
        d.polygon(points, fill='#bad8dc', outline=dark)
    else:
        d.line((10, 10, 22, 22), fill='#ef8b80', width=4); d.line((22, 10, 10, 22), fill='#ef8b80', width=4)
    return im


def save(path, image):
    texture = ROOT / 'textures/item' / (path + '.png'); texture.parent.mkdir(parents=True, exist_ok=True); image.save(texture)
    ref = 'chrdk_achievements:item/' + path
    for directory, obj in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': ref}}),
                           ('items', {'model': {'type': 'minecraft:model', 'model': ref}})]:
        dest = ROOT / directory / (path + '.json'); dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(obj, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in CATEGORIES:
        for state in ('locked', 'unlocked'): save('menu/entry/' + name + '_' + state, category_art(name, state))
    for name in (*CATEGORIES, 'all'):
        for state in ('inactive', 'selected'): save('menu/tab/' + name + '_' + state, category_art(name, state))
    for name in CONTROLS: save('menu/' + name, control_art(name))
    print('Generated 23 achievement GUI icons')
