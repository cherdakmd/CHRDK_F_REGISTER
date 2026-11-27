"""Generate original Cosmetics GUI control and stock-trail icons. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_cosmetics'
TRAILS = ('flame', 'hearts', 'notes', 'sparkles', 'magic', 'gold', 'aurora')
STATES = ('available', 'selected', 'locked', 'suspended', 'unavailable')
CONTROLS = ('summary', 'trails', 'join', 'colors', 'labels', 'slots', 'presets', 'hat', 'pets',
            'menu', 'close', 'empty', 'prev', 'next', 'back', 'clear', 'trails_info')
COLORS = {'flame': '#ef895b', 'hearts': '#e58db0', 'notes': '#80cbd3', 'sparkles': '#e9d291',
          'magic': '#bd8bd8', 'gold': '#e9bd59', 'aurora': '#93c8e3'}


def base(accent):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((2, 2, 29, 29), radius=6, fill='#2a2939', outline='#171923')
    d.rounded_rectangle((4, 4, 27, 27), radius=4, fill='#454254', outline=accent, width=2)
    return im, d


def star(d, x, y, color):
    d.polygon([(x,y-5),(x+2,y-2),(x+5,y),(x+2,y+2),(x,y+5),(x-2,y+2),(x-5,y),(x-2,y-2)], fill=color)


def trail_art(name, state):
    c = COLORS[name]; im, d = base(c); light = '#fff0d4'; dark = '#272a39'
    if name == 'flame':
        d.polygon([(16, 6), (19, 11), (21, 13), (20, 9), (24, 16), (23, 21), (19, 24), (13, 24), (9, 21), (8, 16), (11, 12), (12, 17)], fill=c, outline=dark)
        d.polygon([(16, 14), (19, 18), (18, 22), (14, 22), (13, 19)], fill=light)
    elif name == 'hearts':
        d.polygon([(16, 24), (8, 17), (8, 12), (10, 9), (13, 9), (16, 12), (19, 9), (22, 9), (24, 12), (24, 17)], fill=c, outline=dark)
        d.line((10, 13, 11, 11, 13, 11), fill=light)
    elif name == 'notes':
        d.line((15, 9, 15, 20), fill=c, width=3); d.line((21, 7, 21, 17), fill=c, width=3)
        d.line((15, 9, 21, 7), fill=light, width=2); d.ellipse((10, 18, 16, 22), fill=c); d.ellipse((16, 15, 22, 19), fill=c)
    elif name == 'sparkles':
        star(d, 15, 15, c); star(d, 22, 9, light); star(d, 9, 22, light)
    elif name == 'magic':
        d.polygon([(11, 23), (14, 16), (20, 8), (23, 10), (18, 18), (13, 24)], fill=c, outline=dark)
        star(d, 21, 7, light); d.ellipse((7, 10, 10, 13), fill=c)
    elif name == 'gold':
        d.polygon([(9, 16), (12, 10), (20, 10), (24, 15), (21, 22), (12, 23)], fill=c, outline=dark)
        d.line((12, 14, 17, 12), fill=light, width=2); star(d, 22, 8, light)
    else:
        d.arc((7, 9, 25, 25), 190, 350, fill=c, width=3); d.arc((10, 12, 22, 24), 190, 350, fill='#caa2eb', width=2)
        star(d, 15, 10, light); star(d, 21, 16, c)
    # Status glyphs remain distinguishable by shape, not just colour.
    d.ellipse((19, 19, 31, 31), fill=dark)
    shade = {'available': '#74c5d4', 'selected': '#76d48b', 'locked': '#a19aac', 'suspended': '#e2ad62', 'unavailable': '#de8c80'}[state]
    d.ellipse((20, 20, 30, 30), fill=shade)
    if state == 'available': d.line((25, 22, 25, 27), fill=light, width=2); d.point((25, 29), fill=light)
    elif state == 'selected': d.line((22, 25, 24, 27, 28, 22), fill=light, width=2)
    elif state == 'locked': d.arc((23, 21, 27, 27), 180, 360, fill=light); d.rectangle((22, 25, 28, 29), outline=light)
    elif state == 'suspended': d.line((23, 22, 23, 28), fill=light, width=2); d.line((27, 22, 27, 28), fill=light, width=2)
    else: d.line((22, 22, 28, 28), fill=light, width=2); d.line((28, 22, 22, 28), fill=light, width=2)
    return im


def control_art(name):
    im, d = base('#b4b0cf'); light, dark, pink = '#f5e8ce', '#2b2c3a', '#d69dc7'
    if name in ('prev', 'next', 'back', 'menu'):
        pts = [(7,16),(15,9),(15,13),(24,13),(24,19),(15,19),(15,23)]
        if name == 'next': pts = [(32-x, y) for x,y in pts]
        d.polygon(pts, fill=light, outline=dark)
        if name in ('back', 'menu'): d.rectangle((19,20,23,24), fill=pink if name == 'back' else '#e7c874')
    elif name == 'close':
        d.line((10,10,22,22), fill='#e48e87', width=4); d.line((22,10,10,22), fill='#e48e87', width=4)
    elif name in ('summary', 'trails_info', 'presets', 'empty'):
        d.rectangle((9,7,22,24), fill='#e8d1a6', outline=dark); d.line((12,12,19,12), fill='#8d715f', width=2)
        d.line((12,16,19,16), fill='#8d715f', width=2)
        if name == 'summary': d.ellipse((13,18,18,23), fill=pink)
        elif name == 'trails_info': star(d, 17, 20, '#b891d4')
        elif name == 'empty': d.line((13,20,18,20), fill='#a2969e')
        else: d.line((12,20,19,20), fill='#8d715f', width=2)
    elif name in ('trails', 'join', 'colors'):
        if name == 'trails': star(d, 16, 16, '#acdbe0'); star(d, 22, 9, pink)
        elif name == 'join': star(d, 16, 16, '#e8c26e'); d.line((16,8,16,24), fill=light)
        else:
            d.ellipse((8,8,20,20), fill='#d194bd'); d.ellipse((12,10,23,22), fill='#82c6c3'); d.ellipse((10,15,20,24), fill='#e7c979')
    elif name in ('labels', 'slots'):
        if name == 'labels': d.polygon([(8,10),(18,7),(24,13),(19,23),(8,21)], fill=light, outline=dark); d.ellipse((17,10,19,12), fill=dark)
        else:
            for y in (9,14,19): d.line((10,y,22,y), fill=light, width=2)
            d.ellipse((15,7,19,11), fill=pink); d.ellipse((10,12,14,16), fill=pink); d.ellipse((18,17,22,21), fill=pink)
    elif name == 'hat':
        d.polygon([(9,20),(11,10),(21,10),(23,20)], fill='#bd91ce', outline=dark); d.line((7,21,25,21), fill=light, width=2)
    elif name == 'pets':
        d.ellipse((11,15,21,23), fill=light); d.ellipse((8,10,12,15), fill=pink); d.ellipse((13,7,17,13), fill=pink); d.ellipse((19,9,23,15), fill=pink)
    else:  # clear selected effect
        d.ellipse((8,8,24,24), outline=light, width=2); d.line((10,22,22,10), fill='#e58e83', width=3)
    return im


def save(path, image):
    ref = 'chrdk_cosmetics:item/' + path
    dest = ROOT / 'textures/item' / (path + '.png'); dest.parent.mkdir(parents=True, exist_ok=True); image.save(dest)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': ref}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': ref}})]:
        dest = ROOT / folder / (path + '.json'); dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in TRAILS:
        for state in STATES: save(f'menu/trail/{name}/{state}', trail_art(name, state))
    for name in CONTROLS: save('menu/' + name, control_art(name))
    print('Generated 52 Cosmetics GUI icons')
